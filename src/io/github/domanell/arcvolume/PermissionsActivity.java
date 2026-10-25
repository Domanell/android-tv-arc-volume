package io.github.domanell.arcvolume;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Overlay permission and auto-start status. The overlay row opens its settings screen; the
 * auto-start row opens the vendor screen only where one is known to have the setting.
 * Auto-start counts as verified only after a boot started the service; opening the vendor
 * screen proves nothing, so it never changes the status.
 */
public class PermissionsActivity extends Activity {
    private Ui.Row overlayRow;
    private TextView overlayManual;
    private Ui.Row autoStartRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int autoStartScreen = SystemScreens.autoStartScreen(this);
        boolean canOpen = autoStartScreen == SystemScreens.AUTO_START_AVAILABLE
                || autoStartScreen == SystemScreens.AUTO_START_UNCONFIRMED;

        LinearLayout column = Ui.screen(this);
        Ui.add(column, Ui.title(this, "Permissions and auto-start"), 0);

        overlayRow = Ui.row(this, "Display over other apps", v -> {
            if (!SystemScreens.openOverlaySettings(this)) {
                Ui.show(overlayManual, true);
            }
        });
        Ui.add(column, overlayRow.view, 20);
        overlayManual = Ui.add(column, Ui.body(this, "This TV has no direct link to the setting. "
                + "Open Settings > Apps > Special app access > Display over other apps and turn on "
                + "ARC Volume."), 8);
        Ui.show(overlayManual, false);

        autoStartRow = Ui.row(this, "Auto-start",
                canOpen ? v -> SystemScreens.openAutoStartSettings(this) : null);
        Ui.add(column, autoStartRow.view, 8);

        Ui.add(column, Ui.hint(this, autoStartHint(autoStartScreen)), 20);
        Ui.add(column, Ui.hint(this, "Auto-start is verified after ARC Volume starts with the TV. "
                + "Opening a settings screen does not confirm it."), 8);

        (Settings.canDrawOverlays(this) ? autoStartRow : overlayRow).view.requestFocus();
    }

    /** Runs on start and again on return from a settings screen. */
    @Override
    protected void onResume() {
        super.onResume();
        boolean overlay = Settings.canDrawOverlays(this);
        overlayRow.setSummary(overlay ? "Allowed" : "Not allowed. Needed to show the volume.");
        if (overlay) {
            Ui.show(overlayManual, false);
        }

        if (AppSettings.autoStartVerified(this)) {
            autoStartRow.setSummary("Verified: started by itself after the last TV boot");
        } else if (AppSettings.bootBroadcastThisBoot(this)) {
            autoStartRow.setSummary("Not verified: the TV notified the app after the last boot, "
                    + "but the service did not start");
        } else {
            autoStartRow.setSummary("Not verified: has not started with the TV since the last boot");
        }
    }

    private static String autoStartHint(int screen) {
        switch (screen) {
            case SystemScreens.AUTO_START_AVAILABLE:
                return "TCL TVs start an app with the TV only if TCL Guard allows it. Select "
                        + "Auto-start, turn on ARC Volume under Auto Launch, then press Back.";
            case SystemScreens.AUTO_START_UNCONFIRMED:
                return "Select Auto-start to open TCL Guard. If it has an Auto Launch tab, turn on "
                        + "ARC Volume there. If it does not, open ARC Volume after each TV restart.";
            case SystemScreens.AUTO_START_HIDDEN:
                return "TCL Guard on this TV has no Auto Launch setting, so the TV may not start "
                        + "ARC Volume by itself. If the volume number is missing after a restart, "
                        + "open ARC Volume.";
            default:
                return "ARC Volume found no auto-start settings screen on this TV. Some TVs limit "
                        + "starting apps after a restart. If the volume number is missing after a "
                        + "restart, open ARC Volume.";
        }
    }
}
