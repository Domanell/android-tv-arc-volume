package io.github.domanell.arcvolume;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Views for the app screens, built in code in the style of Android TV settings: a vertical
 * list of rows with a title and a summary, a light fill on the focused row, and changes that
 * apply at once. Up/Down is the only focus movement inside a list.
 */
final class Ui {
    static final int BACKGROUND = 0xFF121418;
    static final int PANEL = 0xF2202329;
    private static final int TEXT = 0xFFECEDEF;
    private static final int TEXT_DIM = 0xFFA4A9B1;
    private static final int TEXT_DISABLED = 0xFF5F646B;
    private static final int FILL_IDLE = 0x1FFFFFFF;
    private static final int FILL_DISABLED = 0x0AFFFFFF;
    private static final int FILL_FOCUSED = 0xFFECEDEF;
    private static final int TEXT_ON_FOCUS = 0xFF121418;
    private static final int SUMMARY_ON_FOCUS = 0xFF3C4043;
    private static final int TRACK_ON = 0xFF8AB4F8;
    private static final int TRACK_ON_FOCUSED = 0xFF1A73E8;
    private static final int TRACK_OFF = 0xFF5F646B;
    private static final int TRACK_OFF_FOCUSED = 0xFF9AA0A6;

    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] FOCUSED_CHECKED =
            {android.R.attr.state_focused, android.R.attr.state_checked};
    private static final int[] CHECKED = {android.R.attr.state_checked};

    /**
     * A full-width focusable row: title, optional summary under it, and an optional value or
     * switch on the right.
     */
    static final class Row {
        final LinearLayout view;
        final TextView title;
        final TextView summary;
        final TextView value;
        Switch toggle;

        Row(LinearLayout view, TextView title, TextView summary, TextView value) {
            this.view = view;
            this.title = title;
            this.summary = summary;
            this.value = value;
        }

        /** Sets the summary; an empty one is hidden. */
        void setSummary(CharSequence text) {
            summary.setText(text);
            show(summary, text.length() > 0);
        }

        void setValue(CharSequence text) {
            value.setText(text);
            show(value, text.length() > 0);
        }

        /** A disabled row is dimmed and skipped by focus. */
        void setEnabled(boolean enabled) {
            view.setEnabled(enabled);
            view.setFocusable(enabled);
        }
    }

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Sets a scrolling content column, padded to the TV safe area, and returns the column. */
    static LinearLayout screen(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(activity, 120), dp(activity, 36), dp(activity, 120), dp(activity, 36));
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        activity.setContentView(scroll);
        return column;
    }

    static TextView title(Context context, String text) {
        TextView view = text(context, text, 30, TEXT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView heading(Context context, String text) {
        TextView view = text(context, text, 22, TEXT);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView body(Context context, String text) {
        return text(context, text, 17, TEXT);
    }

    static TextView hint(Context context, String text) {
        return text(context, text, 15, TEXT_DIM);
    }

    static TextView text(Context context, String text, float sp, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    /** A row that runs {@code onClick} on OK; with a null listener OK does nothing. */
    static Row row(Context context, String title, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 56));
        row.setPadding(dp(context, 20), dp(context, 10), dp(context, 20), dp(context, 10));
        row.setBackground(fill(context));
        row.setOnClickListener(onClick);
        row.setFocusable(true);
        row.setClickable(onClick != null);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setDuplicateParentStateEnabled(true);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView name = text(context, title, 19, TEXT);
        name.setTextColor(textColors(TEXT_ON_FOCUS, TEXT));
        name.setDuplicateParentStateEnabled(true);
        texts.addView(name);

        TextView summary = text(context, "", 15, TEXT_DIM);
        summary.setTextColor(textColors(SUMMARY_ON_FOCUS, TEXT_DIM));
        summary.setDuplicateParentStateEnabled(true);
        texts.addView(summary);
        show(summary, false);

        TextView value = text(context, "", 18, TEXT);
        value.setTextColor(textColors(TEXT_ON_FOCUS, TEXT));
        value.setDuplicateParentStateEnabled(true);
        value.setGravity(Gravity.END);
        value.setSingleLine(true);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.leftMargin = dp(context, 16);
        row.addView(value, valueParams);
        show(value, false);
        return new Row(row, name, summary, value);
    }

    /**
     * Row with a switch on the right; OK on the row runs {@code onClick}, which sets the switch.
     * The switch only mirrors the row's focus, so its colors stay readable on the focus fill.
     */
    static Row switchRow(Context context, String title, View.OnClickListener onClick) {
        Row row = row(context, title, onClick);
        Switch toggle = new Switch(context);
        toggle.setFocusable(false);
        toggle.setClickable(false);
        toggle.setDuplicateParentStateEnabled(true);
        toggle.setThumbTintList(new ColorStateList(
                new int[][] {FOCUSED, CHECKED, StateSet.WILD_CARD},
                new int[] {TEXT_ON_FOCUS, TEXT, TEXT_DIM}));
        toggle.setThumbTintMode(PorterDuff.Mode.SRC_IN);
        toggle.setTrackTintList(new ColorStateList(
                new int[][] {FOCUSED_CHECKED, FOCUSED, CHECKED, StateSet.WILD_CARD},
                new int[] {TRACK_ON_FOCUSED, TRACK_OFF_FOCUSED, TRACK_ON, TRACK_OFF}));
        // SRC replaces the theme's translucent track color with an opaque one.
        toggle.setTrackTintMode(PorterDuff.Mode.SRC);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(context, 16);
        row.view.addView(toggle, params);
        row.toggle = toggle;
        return row;
    }

    /**
     * Row whose value changes with Left/Right, like a TV settings slider; {@code onStep} gets
     * -1 or +1. Left/Right never move focus out of the row.
     */
    static Row stepperRow(Context context, String title, IntConsumer onStep) {
        Row row = row(context, title, null);
        row.view.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                onStep.accept(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            }
            return true;
        });
        return row;
    }

    /** Stepper value with arrows that show which directions are still possible. */
    static String stepperValue(String value, boolean canDecrease, boolean canIncrease) {
        return (canDecrease ? "‹   " : "    ") + value + (canIncrease ? "   ›" : "    ");
    }

    /** Adds a full-width child with a top margin. */
    static <T extends View> T add(LinearLayout parent, T child, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(parent.getContext(), topMarginDp);
        parent.addView(child, params);
        return child;
    }

    static void show(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static ColorStateList textColors(int focused, int idle) {
        return new ColorStateList(
                new int[][] {DISABLED, FOCUSED, StateSet.WILD_CARD},
                new int[] {TEXT_DISABLED, focused, idle});
    }

    private static Drawable fill(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(DISABLED, shape(context, FILL_DISABLED));
        states.addState(FOCUSED, shape(context, FILL_FOCUSED));
        states.addState(StateSet.WILD_CARD, shape(context, FILL_IDLE));
        return states;
    }

    private static Drawable shape(Context context, int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, 8));
        return shape;
    }
}
