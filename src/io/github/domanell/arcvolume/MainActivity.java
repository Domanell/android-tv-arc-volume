package io.github.domanell.arcvolume;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Main screen: service and permission status, and the list of settings screens. Showing it
 * starts the service if it is not running. Back closes the screen; the service keeps running.
 */
public class MainActivity extends Activity {
    /* The system shows the service as foreground shortly after the start request. */
    private static final long STATUS_DELAY_MS = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refresh;

    private TextView status;
    private TextView statusDetail;
    private Ui.Row allowRow;
    private Ui.Row positionRow;
    private Ui.Row stepRow;
    private Ui.Row permissionsRow;
    private Ui.Row previewRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RestartJobService.schedule(this);

        LinearLayout column = Ui.screen(this);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.title(this, "ARC Volume"),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        header.addView(Ui.hint(this, "v" + versionName()));
        Ui.add(column, header, 0);

        status = Ui.add(column, Ui.heading(this, ""), 20);
        statusDetail = Ui.add(column, Ui.body(this, ""), 6);

        allowRow = Ui.row(this, "Allow display over other apps",
                v -> SystemScreens.openOverlaySettings(this));
        allowRow.setSummary("Turn on ARC Volume, then press Back to return here");
        Ui.add(column, allowRow.view, 20);

        positionRow = Ui.row(this, "Position and size",
                v -> startActivity(new Intent(this, PositionActivity.class)));
        Ui.add(column, positionRow.view, 20);
        stepRow = Ui.row(this, "Volume step",
                v -> startActivity(new Intent(this, StepActivity.class)));
        Ui.add(column, stepRow.view, 8);
        permissionsRow = Ui.row(this, "Permissions and auto-start",
                v -> startActivity(new Intent(this, PermissionsActivity.class)));
        Ui.add(column, permissionsRow.view, 8);
        previewRow = Ui.row(this, "Show preview", v -> showPreview());
        Ui.add(column, previewRow.view, 8);

        (Settings.canDrawOverlays(this) ? positionRow : allowRow).view.requestFocus();
    }

    /**
     * Runs on start and on return from another screen: starts the service if the system has
     * stopped it, then updates text in place so focus stays put.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (VolumeService.state(this) == VolumeService.STOPPED) {
            VolumeService.start(this, "launcher");
        }
        refresh();
        refreshSoon();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        int state = VolumeService.state(this);
        if (!overlay) {
            status.setText("Display over other apps is not allowed");
            statusDetail.setText("ARC Volume needs this permission to show the volume.");
        } else if (state == VolumeService.STOPPED) {
            status.setText("Starting");
            statusDetail.setText("If this stays, close ARC Volume and open it again.");
        } else if (state == VolumeService.BACKGROUND) {
            status.setText("Running");
            statusDetail.setText("The TV runs it as a background service and may stop it when "
                    + "memory is low. If the volume number disappears, open ARC Volume.");
        } else {
            status.setText("Running");
            statusDetail.setText("You can close this screen. "
                    + "The volume overlay will keep working.");
        }
        if (showRow(allowRow, !overlay)) {
            positionRow.view.requestFocus();
        }

        positionRow.setSummary((AppSettings.defaultPosition(this)
                ? "Default position" : "Custom position") + ", " + AppSettings.scale(this) + "%");
        int step = AppSettings.step(this);
        stepRow.setSummary((AppSettings.stepManual(this) ? "Manual" : "Automatic") + ", "
                + (step == VolumePredictor.UNKNOWN
                        ? VolumePredictor.DEFAULT_STEP + " (default)" : String.valueOf(step)));
        permissionsRow.setSummary(!overlay ? "Display over other apps is not allowed"
                : AppSettings.autoStartVerified(this)
                        ? "Auto-start verified after TV boot" : "Auto-start not verified yet");
        previewRow.setSummary(overlay ? "Shows the volume box for a moment"
                : "Needs display over other apps");
        previewRow.setEnabled(overlay);
    }

    /** Shows or hides a row; returns true if it was hidden while it had focus. */
    private static boolean showRow(Ui.Row row, boolean visible) {
        boolean hadFocus = !visible && row.view.hasFocus();
        Ui.show(row.view, visible);
        return hadFocus;
    }

    private void refreshSoon() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, STATUS_DELAY_MS);
    }

    private void showPreview() {
        VolumeService.preview(this);
        refreshSoon();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }
}
