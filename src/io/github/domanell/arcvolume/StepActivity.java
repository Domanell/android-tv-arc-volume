package io.github.domanell.arcvolume;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.widget.LinearLayout;

/**
 * Volume step settings: automatic learning or a fixed step, plus guided measurement. Like TV
 * settings, every change is saved at once and Back just leaves the screen. The screen also
 * follows a step the service learns while it is open.
 */
public class StepActivity extends Activity {
    private static final int REQUEST_CALIBRATION = 1;

    /* SharedPreferences keeps listeners weakly, so the field holds it. */
    private final SharedPreferences.OnSharedPreferenceChangeListener stepListener =
            (prefs, key) -> update();

    private int maxStep;
    private Ui.Row autoRow;
    private Ui.Row stepRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AudioManager audio = getSystemService(AudioManager.class);
        maxStep = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                - audio.getStreamMinVolume(AudioManager.STREAM_MUSIC);

        LinearLayout column = Ui.screen(this);
        Ui.add(column, Ui.title(this, "Volume step"), 0);
        Ui.add(column, Ui.hint(this, "How many volume levels the soundbar moves per remote "
                + "press. ARC Volume uses it to show the new number right away, before the "
                + "soundbar confirms it."), 8);

        autoRow = Ui.switchRow(this, "Automatic step", v -> toggleAuto());
        Ui.add(column, autoRow.view, 20);
        stepRow = Ui.stepperRow(this, "Step per press", this::changeStep);
        Ui.add(column, stepRow.view, 8);
        Ui.Row calibrateRow = Ui.row(this, "Measure step now", v -> startActivityForResult(
                new Intent(this, CalibrationActivity.class), REQUEST_CALIBRATION));
        calibrateRow.setSummary("Instead of waiting: press Volume up "
                + StepCalibration.MATCHES_NEEDED + " times with short pauses.");
        Ui.add(column, calibrateRow.view, 8);

        update();
        autoRow.view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(stepListener);
        update();
    }

    @Override
    protected void onPause() {
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(stepListener);
        super.onPause();
    }

    /** A measured step replaces the current one and keeps the mode. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CALIBRATION || resultCode != RESULT_OK || data == null) {
            return;
        }
        int step = data.getIntExtra(CalibrationActivity.EXTRA_STEP, VolumePredictor.UNKNOWN);
        if (step != VolumePredictor.UNKNOWN) {
            AppSettings.prefs(this).edit().putInt(AppSettings.KEY_STEP, step).apply();
        }
    }

    /**
     * Switching to manual fixes the current step, so the shown value stays in use; switching
     * back to automatic starts learning from it.
     */
    private void toggleAuto() {
        boolean manual = !AppSettings.stepManual(this);
        SharedPreferences.Editor editor = AppSettings.prefs(this).edit()
                .putBoolean(AppSettings.KEY_STEP_MANUAL, manual);
        if (manual) {
            editor.putInt(AppSettings.KEY_STEP, currentStep());
        }
        editor.apply();
    }

    private void changeStep(int direction) {
        int step = VolumeBadgeView.clamp(currentStep() + direction, 1, maxStep);
        AppSettings.prefs(this).edit().putInt(AppSettings.KEY_STEP, step).apply();
    }

    private int currentStep() {
        int step = AppSettings.step(this);
        return step == VolumePredictor.UNKNOWN ? VolumePredictor.DEFAULT_STEP : step;
    }

    private void update() {
        boolean manual = AppSettings.stepManual(this);
        boolean determined = AppSettings.step(this) != VolumePredictor.UNKNOWN;
        int step = currentStep();
        autoRow.toggle.setChecked(!manual);
        autoRow.setSummary(manual ? "Off: ARC Volume always uses the step below."
                : "On: ARC Volume watches your volume presses in the background and corrects "
                        + "the step by itself. Nothing to do.");
        stepRow.setEnabled(manual);
        if (manual) {
            stepRow.setValue(Ui.stepperValue(String.valueOf(step), step > 1, step < maxStep));
            stepRow.setSummary("Left/Right to change");
        } else {
            stepRow.setValue(String.valueOf(step));
            stepRow.setSummary(determined ? "Determined"
                    : "Not found yet, using " + VolumePredictor.DEFAULT_STEP);
        }
    }
}
