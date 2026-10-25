package io.github.domanell.arcvolume;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * Shows the STREAM_MUSIC index (the soundbar's own volume level reported over CEC)
 * in a short-lived overlay badge whenever it changes.
 */
public class VolumeService extends Service {
    private static final String TAG = "ArcVolume";
    private static final String CHANNEL_ID = "arc_volume";
    private static final int NOTIFICATION_ID = 1;

    /*
     * The soundbar report lands about 200-300 ms after the local index change of a key press.
     * After SETTLE_MS without changes the actual index is shown and confirmed, which corrects
     * any wrong prediction (see VolumePredictor).
     */
    private static final long SETTLE_MS = 400;

    /*
     * The TCL panel hides 3000 ms after the last key press; soundbar reports do not extend it.
     * Without a recent key press (mute, soundbar remote) the event itself already lags the
     * key by about REPORT_LATENCY_MS.
     */
    private static final long PANEL_TIMEOUT_MS = 3000;
    private static final long REPORT_LATENCY_MS = 300;
    private static final long FADE_IN_MS = 100;
    private static final long FADE_OUT_MS = 150;

    /** Start intent extra naming who started the service, for the log. */
    static final String EXTRA_REASON = "io.github.domanell.arcvolume.REASON";
    /** Start intent extra asking to show the badge once with the current level. */
    private static final String EXTRA_PREVIEW = "io.github.domanell.arcvolume.PREVIEW";

    static final int STOPPED = 0;
    /** Running, but the system did not accept the foreground start, so it may stop the service. */
    static final int BACKGROUND = 1;
    static final int FOREGROUND = 2;

    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    /*
     * Pauses set by the app screens, which run in this process on the main thread: the position
     * editor shows its own sample badge, and the step measurement must not race the learner.
     */
    private static boolean overlayPaused;
    private static boolean learningPaused;
    /** The running instance, so a pause can take the badge down at once. */
    private static VolumeService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable settleRunnable = this::settle;
    private final Runnable hideRunnable = this::hideOverlay;
    private final VolumeReceiver volumeReceiver = new VolumeReceiver(this);
    /* SharedPreferences keeps listeners weakly, so the field holds it. */
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener =
            (prefs, key) -> onSettingChanged(key);

    private AudioManager audio;
    private WindowManager windows;
    private VolumeBadgeView badge;
    private WindowManager.LayoutParams layoutParams;
    private boolean overlayAdded;
    private VolumePredictor predictor;
    private long lastKeyPressAt = Long.MIN_VALUE / 2;
    private long hideAt;
    /** The current burst saw a learning pause, so it must not count as evidence. */
    private boolean burstPaused;

    /*
     * Static nested class instead of an anonymous one: d8 8.2.2 (build-tools 34.0.0) crashes on
     * the MethodParameters attribute that javac 21 emits for an inner class's implicit outer parameter.
     */
    private static final class VolumeReceiver extends BroadcastReceiver {
        private final VolumeService service;

        VolumeReceiver(VolumeService service) {
            this.service = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) {
                return;
            }
            service.onVolumeEvent(intent.getAction());
        }
    }

    /** Hides the badge at once and keeps it hidden until unpaused. Main thread only. */
    static void setOverlayPaused(boolean paused) {
        overlayPaused = paused;
        if (paused && running != null) {
            running.handler.removeCallbacks(running.hideRunnable);
            running.removeOverlay();
        }
    }

    /**
     * Stops step learning while the user measures the step. A burst that is still open when the
     * pause starts or ends is excluded too. Main thread only.
     */
    static void setLearningPaused(boolean paused) {
        learningPaused = paused;
        if (running != null) {
            running.burstPaused = true;
        }
    }

    /** Starts the service; returns false if the system refuses a start from the background. */
    static boolean start(Context context, String reason) {
        return start(context, new Intent(context, VolumeService.class).putExtra(EXTRA_REASON, reason));
    }

    /** Shows the badge briefly at its saved position and scale, with the current level. */
    static boolean preview(Context context) {
        return start(context, new Intent(context, VolumeService.class)
                .putExtra(EXTRA_REASON, "preview")
                .putExtra(EXTRA_PREVIEW, true));
    }

    private static boolean start(Context context, Intent intent) {
        try {
            context.startForegroundService(intent);
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "cannot start service (" + intent.getStringExtra(EXTRA_REASON) + ")", e);
            return false;
        }
    }

    /** {@link #STOPPED}, {@link #BACKGROUND} or {@link #FOREGROUND}, as the system sees it. */
    @SuppressWarnings("deprecation")
    static int state(Context context) {
        ActivityManager activities = context.getSystemService(ActivityManager.class);
        // getRunningServices still returns the caller's own services.
        for (ActivityManager.RunningServiceInfo info : activities.getRunningServices(Integer.MAX_VALUE)) {
            if (info.service.getPackageName().equals(context.getPackageName())
                    && info.service.getClassName().equals(VolumeService.class.getName())) {
                return info.foreground ? FOREGROUND : BACKGROUND;
            }
        }
        return STOPPED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        audio = getSystemService(AudioManager.class);
        windows = getSystemService(WindowManager.class);
        int step = AppSettings.step(this);
        predictor = new VolumePredictor(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                step, step != VolumePredictor.UNKNOWN, !AppSettings.stepManual(this),
                audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        createOverlayView();
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener);
        RestartJobService.schedule(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_VOLUME_CHANGED);
        filter.addAction(ACTION_MUTE_CHANGED);
        registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = intent == null ? "restarted" : intent.getStringExtra(EXTRA_REASON);
        try {
            startForeground(NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException e) {
            Log.w(TAG, "foreground start refused (" + reason + "): " + e.getMessage());
        }
        boolean foreground = state(this) == FOREGROUND;
        if (Intent.ACTION_BOOT_COMPLETED.equals(reason)) {
            AppSettings.prefs(this).edit()
                    .putInt(AppSettings.KEY_BOOT_VERIFIED, AppSettings.bootCount(this))
                    .apply();
        }
        Log.i(TAG, "service started, volume=" + audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                + " step=" + predictor.step() + " learning=" + predictor.learning()
                + " foreground=" + foreground + " reason=" + reason
                + " autoStartScreen=" + SystemScreens.autoStartScreen(this));
        if (intent != null && intent.getBooleanExtra(EXTRA_PREVIEW, false)) {
            showPreview();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = null;
        unregisterReceiver(volumeReceiver);
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void onVolumeEvent(String action) {
        int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        long now = SystemClock.uptimeMillis();
        int shown = volume;
        boolean keyPress = false;
        if (ACTION_VOLUME_CHANGED.equals(action)) {
            shown = predictor.onIndex(volume);
            keyPress = predictor.wasKeyPress();
        }
        if (keyPress) {
            lastKeyPressAt = now;
            hideAt = now + PANEL_TIMEOUT_MS;
        } else if (now - lastKeyPressAt >= PANEL_TIMEOUT_MS) {
            hideAt = now + PANEL_TIMEOUT_MS - REPORT_LATENCY_MS;
        }
        if (learningPaused) {
            burstPaused = true;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, action + ": stream=" + volume + " muted=" + muted + " shown=" + shown
                    + " key=" + keyPress + " hideIn=" + (hideAt - now));
        }
        badge.setState(shown, muted);
        showOverlay();
        handler.removeCallbacks(settleRunnable);
        handler.postDelayed(settleRunnable, SETTLE_MS);
    }

    private void settle() {
        int volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        boolean muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        boolean learn = !learningPaused && !burstPaused;
        burstPaused = false;
        if (predictor.settle(volume, learn)) {
            AppSettings.prefs(this).edit()
                    .putInt(AppSettings.KEY_STEP, predictor.step()).apply();
            Log.i(TAG, "learned soundbar step " + predictor.step());
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "settled: volume=" + volume + " muted=" + muted + " learn=" + learn);
        }
        badge.setState(volume, muted);
        // The badge is down if the position editor paused it meanwhile; bring it back in time.
        if (!overlayAdded && hideAt > SystemClock.uptimeMillis()) {
            showOverlay();
        }
    }

    private void showPreview() {
        badge.setState(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.isStreamMute(AudioManager.STREAM_MUSIC));
        hideAt = SystemClock.uptimeMillis() + PANEL_TIMEOUT_MS;
        showOverlay();
    }

    private void onSettingChanged(String key) {
        if (AppSettings.KEY_STEP.equals(key) || AppSettings.KEY_STEP_MANUAL.equals(key)) {
            int step = AppSettings.step(this);
            boolean determined = step != VolumePredictor.UNKNOWN;
            boolean learning = !AppSettings.stepManual(this);
            if ((determined && step != predictor.step()) || determined != predictor.determined()
                    || learning != predictor.learning()) {
                predictor.configure(step, determined, learning);
                Log.i(TAG, "step set to " + predictor.step() + " determined=" + determined
                        + " learning=" + learning);
            }
        } else if (AppSettings.KEY_RIGHT_DP.equals(key) || AppSettings.KEY_TOP_DP.equals(key)
                || AppSettings.KEY_SCALE.equals(key)) {
            applyPlacement();
        }
    }

    private void createOverlayView() {
        badge = new VolumeBadgeView(this);

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        layoutParams.setTitle("ArcVolumeOverlay");
        applyPlacement();
    }

    /** Moves and scales the badge to the saved settings, keeping it fully on screen. */
    private void applyPlacement() {
        int scale = AppSettings.scale(this);
        Rect screen = windows.getMaximumWindowMetrics().getBounds();
        Rect frame = VolumeBadgeView.frame(screen.width(), screen.height(),
                getResources().getDisplayMetrics().density,
                AppSettings.rightDp(this), AppSettings.topDp(this), scale);
        badge.setScale(scale);
        layoutParams.x = frame.left;
        layoutParams.y = frame.top;
        if (overlayAdded) {
            windows.updateViewLayout(badge, layoutParams);
        }
    }

    private void showOverlay() {
        if (overlayPaused) {
            return;
        }
        handler.removeCallbacks(hideRunnable);
        if (!overlayAdded) {
            badge.setAlpha(0f);
            try {
                windows.addView(badge, layoutParams);
                overlayAdded = true;
            } catch (RuntimeException e) {
                Log.e(TAG, "cannot add overlay (SYSTEM_ALERT_WINDOW not allowed?)", e);
                return;
            }
        }
        badge.animate().cancel();
        badge.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        handler.postAtTime(hideRunnable, hideAt);
    }

    private void hideOverlay() {
        if (!overlayAdded) {
            return;
        }
        // A cancelled animation skips its end action, so a new show keeps the view attached.
        badge.animate().cancel();
        badge.animate().alpha(0f).setDuration(FADE_OUT_MS)
                .withEndAction(this::removeOverlay).start();
    }

    private void removeOverlay() {
        if (overlayAdded) {
            badge.animate().cancel();
            windows.removeView(badge);
            overlayAdded = false;
        }
    }

    private Notification buildNotification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Volume overlay", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("ARC Volume")
                .setOngoing(true)
                .build();
    }
}
