package io.github.domanell.arcvolume;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Guided step measurement. Only observes the volume the user changes with the remote; it never
 * changes the volume itself. Returns the accepted step as {@link #EXTRA_STEP}.
 */
public class CalibrationActivity extends Activity {
    static final String EXTRA_STEP = "io.github.domanell.arcvolume.STEP";

    /* Soundbar reports land up to about 300 ms after a press; the user pauses about a second. */
    private static final long SETTLE_MS = 700;

    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable settleRunnable = this::settle;
    private final VolumeReceiver receiver = new VolumeReceiver(this);

    private AudioManager audio;
    private StepCalibration calibration;
    private TextView progress;
    private TextView detail;
    private Ui.Row useRow;
    private Ui.Row retryRow;
    private Ui.Row cancelRow;

    /* Static nested class: see VolumeService.VolumeReceiver. */
    private static final class VolumeReceiver extends BroadcastReceiver {
        private final CalibrationActivity activity;

        VolumeReceiver(CalibrationActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) {
                activity.onVolumeChange();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        audio = getSystemService(AudioManager.class);

        LinearLayout column = Ui.screen(this);
        Ui.add(column, Ui.title(this, "Measure step"), 0);
        Ui.add(column, Ui.body(this, "Press Volume up on the remote " + StepCalibration.MATCHES_NEEDED
                + " times, pausing about one second after each press. ARC Volume only watches "
                + "the volume and never changes it. Lower the volume afterwards if needed."), 16);
        progress = Ui.add(column, Ui.heading(this, ""), 24);
        detail = Ui.add(column, Ui.body(this, ""), 6);

        useRow = Ui.row(this, "", v -> accept());
        Ui.add(column, useRow.view, 24);
        retryRow = Ui.row(this, "Try again", v -> restart());
        Ui.add(column, retryRow.view, 8);
        cancelRow = Ui.row(this, "Cancel", v -> finish());
        cancelRow.setSummary("Keep the current step");
        Ui.add(column, cancelRow.view, 8);
        restart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        VolumeService.setLearningPaused(true);
        registerReceiver(receiver, new IntentFilter(ACTION_VOLUME_CHANGED), Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onPause() {
        unregisterReceiver(receiver);
        handler.removeCallbacks(settleRunnable);
        VolumeService.setLearningPaused(false);
        super.onPause();
    }

    /**
     * Counts volume key presses and notes held keys when the TV passes them to the app; never
     * consumes them.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if ((key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN)
                && event.getAction() == KeyEvent.ACTION_DOWN
                && calibration.result() == VolumePredictor.UNKNOWN && !calibration.failed()) {
            if (event.getRepeatCount() == 0) {
                calibration.onKeyPress();
            } else {
                calibration.onKeyRepeat();
            }
            settleLater();
        }
        return super.dispatchKeyEvent(event);
    }

    private void onVolumeChange() {
        calibration.onVolumeChange();
        settleLater();
    }

    private void settleLater() {
        handler.removeCallbacks(settleRunnable);
        handler.postDelayed(settleRunnable, SETTLE_MS);
    }

    private void settle() {
        if (calibration.result() != VolumePredictor.UNKNOWN || calibration.failed()) {
            return;
        }
        calibration.onSettled(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        update();
    }

    private void restart() {
        handler.removeCallbacks(settleRunnable);
        int min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        calibration = new StepCalibration(audio.getStreamVolume(AudioManager.STREAM_MUSIC), max, max - min);
        update();
        cancelRow.view.requestFocus();
    }

    private void update() {
        int result = calibration.result();
        boolean failed = calibration.failed();
        if (result != VolumePredictor.UNKNOWN) {
            progress.setText("Step " + result);
            detail.setText("The last " + StepCalibration.MATCHES_NEEDED
                    + " presses each moved the volume by " + result + ".");
            useRow.title.setText("Use step " + result);
        } else if (failed) {
            progress.setText("Could not determine the step");
            detail.setText("The presses did not agree. Try again with longer pauses, "
                    + "one press at a time.");
        } else if (calibration.atLimit()) {
            progress.setText("The volume is at its limit");
            detail.setText("Lower the volume a little, then continue pressing Volume up.");
        } else if (calibration.presses() == 0) {
            progress.setText("Waiting for the first press");
            // The soundbar may top out below the TV's maximum, and many TVs do not pass volume
            // keys to the app, so a press at the soundbar's limit leaves no trace here.
            detail.setText("If nothing happens after a press, the soundbar may be at its maximum. "
                    + "Lower the volume a little, then press Volume up again.");
        } else {
            int last = calibration.lastStep();
            progress.setText("Presses that agree: " + calibration.matches() + " of "
                    + StepCalibration.MATCHES_NEEDED);
            detail.setText(last == VolumePredictor.UNKNOWN
                    ? "The last press could not be measured. Press briefly without holding, "
                            + "and pause between presses."
                    : "The last press moved the volume by " + last + ".");
        }
        Ui.show(useRow.view, result != VolumePredictor.UNKNOWN);
        Ui.show(retryRow.view, failed);
        if (result != VolumePredictor.UNKNOWN) {
            useRow.view.requestFocus();
        } else if (failed) {
            retryRow.view.requestFocus();
        }
    }

    private void accept() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_STEP, calibration.result()));
        finish();
    }
}
