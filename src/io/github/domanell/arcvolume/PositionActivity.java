package io.github.domanell.arcvolume;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Position and size editor. A sample badge showing 42 sits at its real overlay coordinates.
 * Like TV settings, every change is saved at once and Back just leaves the screen.
 *
 * <p>The panel is one vertical list, so Up/Down is the only focus movement. Left/Right on the
 * Scale row change the scale. OK on Position enters movement mode: the panel hides, the arrows
 * move the badge, OK saves the new position and Back puts the badge back where the move
 * started. The panel sits on the side of the screen away from the badge.
 */
public class PositionActivity extends Activity {
    private static final int SAMPLE_VOLUME = 42;

    private FrameLayout root;
    private VolumeBadgeView badge;
    private LinearLayout panel;
    private TextView moveHint;
    private Ui.Row positionRow;
    private Ui.Row scaleRow;

    private int rightDp;
    private int topDp;
    private int scale;
    private boolean moving;
    private int moveStartRightDp;
    private int moveStartTopDp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rightDp = AppSettings.rightDp(this);
        topDp = AppSettings.topDp(this);
        scale = AppSettings.scale(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BACKGROUND);
        setContentView(root);

        badge = new VolumeBadgeView(this);
        badge.setState(SAMPLE_VOLUME, false);
        root.addView(badge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.LEFT));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(Ui.PANEL);
        Ui.add(panel, Ui.heading(this, "Position and size"), 0);

        positionRow = Ui.row(this, "Position", v -> startMoving());
        Ui.add(panel, positionRow.view, 16);
        scaleRow = Ui.stepperRow(this, "Scale", this::changeScale);
        Ui.add(panel, scaleRow.view, 8);
        Ui.Row resetRow = Ui.row(this, "Reset to default", v -> reset());
        resetRow.setSummary("Under the TCL volume panel, 100%");
        Ui.add(panel, resetRow.view, 8);

        Ui.add(panel, Ui.hint(this, "Press Volume to compare with the TV's own volume panel."), 16);

        root.addView(panel, new FrameLayout.LayoutParams(
                Ui.dp(this, 400), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL));

        moveHint = Ui.body(this, "Arrows: Move     OK: Done     Back: Cancel movement");
        moveHint.setBackgroundColor(Ui.PANEL);
        moveHint.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        root.addView(moveHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        Ui.show(moveHint, false);

        positionRow.view.requestFocus();
        root.post(this::update);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VolumeService.setOverlayPaused(true);
    }

    @Override
    protected void onPause() {
        VolumeService.setOverlayPaused(false);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            update();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!moving) {
            return super.dispatchKeyEvent(event);
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(moveDistance(event), 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(-moveDistance(event), 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -moveDistance(event));
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, moveDistance(event));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) {
                    stopMoving();
                    save();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (!down) {
                    rightDp = moveStartRightDp;
                    topDp = moveStartTopDp;
                    stopMoving();
                }
                return true;
            default:
                // Volume and other keys keep working, so the TV panel can be shown for comparison.
                return super.dispatchKeyEvent(event);
        }
    }

    /** Distance in dp for one arrow event; holding the key speeds up. */
    private static int moveDistance(KeyEvent event) {
        int repeats = event.getRepeatCount();
        if (repeats < 3) return 1;
        if (repeats < 10) return 2;
        if (repeats < 20) return 4;
        return 8;
    }

    private void changeScale(int direction) {
        scale = VolumeBadgeView.clamp(scale + direction * AppSettings.SCALE_STEP,
                AppSettings.MIN_SCALE, AppSettings.MAX_SCALE);
        update();
        save();
    }

    private void startMoving() {
        moving = true;
        moveStartRightDp = rightDp;
        moveStartTopDp = topDp;
        Ui.show(panel, false);
        Ui.show(moveHint, true);
        update();
    }

    private void stopMoving() {
        moving = false;
        Ui.show(moveHint, false);
        Ui.show(panel, true);
        update();
        positionRow.view.requestFocus();
    }

    /** Moves the badge; {@code dRight} is toward the left edge, {@code dTop} is down. */
    private void move(int dRight, int dTop) {
        rightDp += dRight;
        topDp += dTop;
        update();
    }

    private void reset() {
        rightDp = AppSettings.DEFAULT_RIGHT_DP;
        topDp = AppSettings.DEFAULT_TOP_DP;
        scale = AppSettings.DEFAULT_SCALE;
        update();
        save();
    }

    private void save() {
        AppSettings.prefs(this).edit()
                .putInt(AppSettings.KEY_RIGHT_DP, rightDp)
                .putInt(AppSettings.KEY_TOP_DP, topDp)
                .putInt(AppSettings.KEY_SCALE, scale)
                .apply();
    }

    /** Keeps the badge fully on screen, moves the sample badge there and updates the panel. */
    private void update() {
        Rect screen = getWindowManager().getMaximumWindowMetrics().getBounds();
        float density = getResources().getDisplayMetrics().density;
        int width = VolumeBadgeView.sizePx(VolumeBadgeView.WIDTH_DP, scale, density);
        int height = VolumeBadgeView.sizePx(VolumeBadgeView.HEIGHT_DP, scale, density);
        rightDp = VolumeBadgeView.clamp(rightDp, 0, (int) ((screen.width() - width) / density));
        int halfHeightDp = (int) (screen.height() / 2 / density);
        topDp = VolumeBadgeView.clamp(topDp, -halfHeightDp,
                (int) ((screen.height() / 2 - height) / density));

        Rect frame = VolumeBadgeView.frame(screen.width(), screen.height(), density,
                rightDp, topDp, scale);
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        FrameLayout.LayoutParams badgeParams = (FrameLayout.LayoutParams) badge.getLayoutParams();
        badgeParams.leftMargin = frame.left - origin[0];
        badgeParams.topMargin = frame.top - origin[1];
        badge.setLayoutParams(badgeParams);
        badge.setScale(scale);

        boolean badgeOnLeft = frame.centerX() < screen.width() / 2;
        int margin = Ui.dp(this, 48);
        FrameLayout.LayoutParams panelParams = (FrameLayout.LayoutParams) panel.getLayoutParams();
        panelParams.gravity = (badgeOnLeft ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL;
        panelParams.leftMargin = margin;
        panelParams.rightMargin = margin;
        panel.setLayoutParams(panelParams);

        boolean badgeLow = frame.centerY() > screen.height() * 2 / 3;
        FrameLayout.LayoutParams hintParams = (FrameLayout.LayoutParams) moveHint.getLayoutParams();
        hintParams.gravity = Gravity.CENTER_HORIZONTAL | (badgeLow ? Gravity.TOP : Gravity.BOTTOM);
        hintParams.topMargin = Ui.dp(this, 36);
        hintParams.bottomMargin = Ui.dp(this, 36);
        moveHint.setLayoutParams(hintParams);

        boolean defaultPosition = rightDp == AppSettings.DEFAULT_RIGHT_DP
                && topDp == AppSettings.DEFAULT_TOP_DP;
        positionRow.setSummary((defaultPosition ? "Default" : "Custom") + ". Press OK to move.");
        scaleRow.setValue(Ui.stepperValue(scale + "%",
                scale > AppSettings.MIN_SCALE, scale < AppSettings.MAX_SCALE));
    }
}
