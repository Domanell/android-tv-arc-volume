package io.github.domanell.arcvolume;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Saved app settings: badge position and scale, the soundbar step and its mode, and what the
 * app has seen of auto-start. Shared by the service and the app screens, which run in one
 * process, so the service picks up changes through a preference listener.
 */
final class AppSettings {
    static final String PREFS = "arc_volume";

    static final String KEY_STEP = "soundbar_step";
    /** True when the user fixed the step; observations then never replace it. */
    static final String KEY_STEP_MANUAL = "step_manual";

    /** Badge right edge, in dp from the right screen edge. */
    static final String KEY_RIGHT_DP = "badge_right_dp";
    /** Badge top edge, in dp below the vertical screen center (negative is above). */
    static final String KEY_TOP_DP = "badge_top_dp";
    static final String KEY_SCALE = "badge_scale_percent";

    /** Boot count of the last boot whose BOOT_COMPLETED reached the app. */
    static final String KEY_BOOT_BROADCAST = "started_at_boot";
    /**
     * Boot count of the last boot whose BOOT_COMPLETED started the service. TCL refuses the
     * foreground start at boot even when auto-start is allowed, so a background start counts.
     */
    static final String KEY_BOOT_VERIFIED = "verified_at_boot";

    /* The TCL panel is 44x200 dp, vertically centered, 41 dp from the right edge; 3 dp below it. */
    static final int DEFAULT_RIGHT_DP = 41;
    static final int DEFAULT_TOP_DP = 100 + 3;
    static final int DEFAULT_SCALE = 100;
    static final int MIN_SCALE = 75;
    static final int MAX_SCALE = 150;
    static final int SCALE_STEP = 5;

    private AppSettings() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The saved step, or {@link VolumePredictor#UNKNOWN} while none is determined; the
     * predictor then uses {@link VolumePredictor#DEFAULT_STEP}.
     */
    static int step(Context context) {
        return prefs(context).getInt(KEY_STEP, VolumePredictor.UNKNOWN);
    }

    static boolean stepManual(Context context) {
        return prefs(context).getBoolean(KEY_STEP_MANUAL, false);
    }

    static int rightDp(Context context) {
        return prefs(context).getInt(KEY_RIGHT_DP, DEFAULT_RIGHT_DP);
    }

    static int topDp(Context context) {
        return prefs(context).getInt(KEY_TOP_DP, DEFAULT_TOP_DP);
    }

    static int scale(Context context) {
        int scale = prefs(context).getInt(KEY_SCALE, DEFAULT_SCALE);
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    static boolean defaultPosition(Context context) {
        return rightDp(context) == DEFAULT_RIGHT_DP && topDp(context) == DEFAULT_TOP_DP;
    }

    static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    static boolean bootBroadcastThisBoot(Context context) {
        return prefs(context).getInt(KEY_BOOT_BROADCAST, -1) == bootCount(context);
    }

    /**
     * True when BOOT_COMPLETED of the current boot started the service, in the foreground or
     * not. A broadcast alone, or a start by the restart job, does not count.
     */
    static boolean autoStartVerified(Context context) {
        return prefs(context).getInt(KEY_BOOT_VERIFIED, -1) == bootCount(context);
    }
}
