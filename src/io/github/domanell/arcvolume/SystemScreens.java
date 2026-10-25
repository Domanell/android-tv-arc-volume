package io.github.domanell.arcvolume;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** System and vendor settings screens the app sends the user to. */
final class SystemScreens {
    /** No known auto-start settings screen on this TV. */
    static final int AUTO_START_NONE = 0;
    /** TCL Guard is there and its Auto Launch tab is shown. */
    static final int AUTO_START_AVAILABLE = 1;
    /** TCL Guard is there, but this firmware hides its Auto Launch tab. */
    static final int AUTO_START_HIDDEN = 2;
    /** TCL Guard is there; whether it shows the Auto Launch tab could not be read. */
    static final int AUTO_START_UNCONFIRMED = 3;

    /**
     * TCL Guard 2.10 opens its Permission Shield on the "Auto Launch Permission" tab for
     * this action.
     */
    private static final String ACTION_TCL_AUTO_LAUNCH = "com.tcl.guard.action.PermissionShield";

    /*
     * TCL Guard 2.10 (PermissionShieldActivity.initViewAndFragment) shows the Auto Launch tab only
     * when both firmware flags are "true". Other Guard versions are not verified.
     */
    private static final String[] TCL_AUTO_LAUNCH_FLAGS = {
            "ro.feature.tguard_app_datasource", "ro.feature.tguard_fileclear"};

    private SystemScreens() {
    }

    static boolean openOverlaySettings(Activity activity) {
        return open(activity, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + activity.getPackageName())));
    }

    /** One of the {@code AUTO_START_*} values for this TV. */
    static int autoStartScreen(Context context) {
        if (new Intent(ACTION_TCL_AUTO_LAUNCH).resolveActivity(context.getPackageManager()) == null) {
            return AUTO_START_NONE;
        }
        boolean allTrue = true;
        for (String flag : TCL_AUTO_LAUNCH_FLAGS) {
            String value = systemProperty(flag);
            if ("false".equals(value)) {
                return AUTO_START_HIDDEN;
            }
            allTrue &= "true".equals(value);
        }
        return allTrue ? AUTO_START_AVAILABLE : AUTO_START_UNCONFIRMED;
    }

    /**
     * Opens the TCL Guard Auto Launch tab unless this firmware is known to hide it; returns
     * false when there is no screen to open.
     */
    static boolean openAutoStartSettings(Activity activity) {
        int screen = autoStartScreen(activity);
        if (screen != AUTO_START_AVAILABLE && screen != AUTO_START_UNCONFIRMED) {
            return false;
        }
        return open(activity, new Intent(ACTION_TCL_AUTO_LAUNCH));
    }

    /** The value of a system property, or "" when it is missing or cannot be read. */
    private static String systemProperty(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
            return value == null ? "" : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    private static boolean open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
