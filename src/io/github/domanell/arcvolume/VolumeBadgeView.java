package io.github.domanell.arcvolume;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/**
 * Volume level (or a crossed-out speaker when muted) in a box styled like the TCL volume
 * panel it sits under: same width, near-square corners, translucent black, no border.
 * The whole badge scales with one percentage.
 *
 * <p>Optionally a vertical volume bar sits above the box, in a panel the size and place of the
 * TCL one, for TVs that hide their own volume panel while a soundbar is connected. The badge
 * position always refers to the number box; the bar extends the view upward.
 */
final class VolumeBadgeView extends View {
    static final int WIDTH_DP = 44;
    static final int HEIGHT_DP = 32;
    /* The bar panel takes the place of the 44x200 dp TCL panel, 3 dp above the box. */
    static final int BAR_HEIGHT_DP = 200;
    static final int BAR_GAP_DP = 3;

    /*
     * The TCL panel is black at about 60% opacity. The system caps this overlay window
     * at alpha 0.8, so 0xBF (75%) here ends up at the same 60% on screen.
     */
    private static final int BACKGROUND = 0xBF000000;
    private static final int MUTED_ICON = 0xFFFF6B6B;
    private static final float ICON_UNITS = 24f;
    private static final float TEXT_SP = 16;
    private static final float CORNER_DP = 1;
    private static final float ICON_DP = 20;
    private static final float TRACK_WIDTH_DP = 4;
    private static final float TRACK_PADDING_DP = 16;
    private static final int TRACK = 0x40FFFFFF;
    private static final int FILL = 0xFFFFFFFF;
    private static final int FILL_MUTED = 0x80FFFFFF;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speaker = new Path();
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private int scale = AppSettings.DEFAULT_SCALE;
    private String label = "";
    private int volume;
    private boolean muted;
    private boolean bar;
    private int barMax = 100;

    VolumeBadgeView(Context context) {
        super(context);
        backgroundPaint.setColor(BACKGROUND);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFontFeatureSettings("tnum");
        applyTextSize();
        trackPaint.setColor(TRACK);

        iconFill.setColor(MUTED_ICON);
        iconStroke.setColor(MUTED_ICON);
        iconStroke.setStyle(Paint.Style.STROKE);
        iconStroke.setStrokeWidth(2f);
        iconStroke.setStrokeCap(Paint.Cap.ROUND);

        speaker.moveTo(3, 9);
        speaker.lineTo(7, 9);
        speaker.lineTo(12, 4);
        speaker.lineTo(12, 20);
        speaker.lineTo(7, 15);
        speaker.lineTo(3, 15);
        speaker.close();
    }

    void setState(int volume, boolean muted) {
        this.volume = volume;
        this.label = String.valueOf(volume);
        this.muted = muted;
        invalidate();
    }

    /** Scales the box, number and icon together; {@code percent} 100 is the TCL size. */
    void setScale(int percent) {
        if (percent == scale) {
            return;
        }
        scale = percent;
        applyTextSize();
        requestLayout();
        invalidate();
    }

    /** Shows or hides the volume bar; {@code max} is the level that fills it. */
    void setBar(boolean show, int max) {
        if (show == bar && max == barMax) {
            return;
        }
        boolean resize = show != bar;
        bar = show;
        barMax = Math.max(1, max);
        if (resize) {
            requestLayout();
        }
        invalidate();
    }

    /** Height above the number box taken by the bar, in dp before scaling. */
    static int barOffsetDp(boolean bar) {
        return bar ? BAR_HEIGHT_DP + BAR_GAP_DP : 0;
    }

    /** Badge size in pixels for a size in dp at the given scale. */
    static int sizePx(int sizeDp, int scalePercent, float density) {
        return Math.round(sizeDp * density * scalePercent / 100f);
    }

    /**
     * Screen frame of the whole view, bar included, for a number box anchored by its top-right
     * corner (see {@link AppSettings#KEY_RIGHT_DP} and {@link AppSettings#KEY_TOP_DP}), moved
     * back inside the screen if any part of it would be outside.
     */
    static Rect frame(int screenWidth, int screenHeight, float density,
            int rightDp, int topDp, int scalePercent, boolean bar) {
        int width = sizePx(WIDTH_DP, scalePercent, density);
        int offset = sizePx(barOffsetDp(bar), scalePercent, density);
        int height = sizePx(HEIGHT_DP, scalePercent, density) + offset;
        int left = clamp(screenWidth - Math.round(rightDp * density) - width, 0, screenWidth - width);
        int top = clamp(screenHeight / 2 + Math.round(topDp * density) - offset,
                0, screenHeight - height);
        return new Rect(left, top, left + width, top + height);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        setMeasuredDimension(sizePx(WIDTH_DP, scale, density),
                sizePx(HEIGHT_DP, scale, density) + sizePx(barOffsetDp(bar), scale, density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cornerRadius = dp(CORNER_DP);
        float boxTop = 0;
        if (bar) {
            drawBar(canvas, cornerRadius);
            boxTop = getHeight() - sizePx(HEIGHT_DP, scale, getResources().getDisplayMetrics().density);
        }
        box.set(0, boxTop, getWidth(), getHeight());
        canvas.drawRoundRect(box, cornerRadius, cornerRadius, backgroundPaint);

        float centerY = box.centerY();
        if (muted) {
            drawMutedIcon(canvas, centerY);
            return;
        }
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = centerY - (metrics.ascent + metrics.descent) / 2;
        canvas.drawText(label, getWidth() / 2f, baseline, textPaint);
    }

    /** Panel with a thin rounded track filled from the bottom; dimmed while muted. */
    private void drawBar(Canvas canvas, float cornerRadius) {
        box.set(0, 0, getWidth(), dp(BAR_HEIGHT_DP));
        canvas.drawRoundRect(box, cornerRadius, cornerRadius, backgroundPaint);

        float halfWidth = dp(TRACK_WIDTH_DP) / 2;
        float centerX = getWidth() / 2f;
        float top = dp(TRACK_PADDING_DP);
        float bottom = dp(BAR_HEIGHT_DP - TRACK_PADDING_DP);
        box.set(centerX - halfWidth, top, centerX + halfWidth, bottom);
        canvas.drawRoundRect(box, halfWidth, halfWidth, trackPaint);

        float level = Math.max(0f, Math.min(1f, volume / (float) barMax));
        if (level > 0) {
            box.top = bottom - (bottom - top) * level;
            fillPaint.setColor(muted ? FILL_MUTED : FILL);
            canvas.drawRoundRect(box, halfWidth, halfWidth, fillPaint);
        }
    }

    /** Draws a crossed-out speaker from a 24x24 unit box, centered and scaled to the icon size. */
    private void drawMutedIcon(Canvas canvas, float centerY) {
        float iconSize = dp(ICON_DP);
        canvas.save();
        canvas.translate((getWidth() - iconSize) / 2, centerY - iconSize / 2);
        canvas.scale(iconSize / ICON_UNITS, iconSize / ICON_UNITS);
        canvas.drawPath(speaker, iconFill);
        canvas.drawLine(16, 9, 22, 15, iconStroke);
        canvas.drawLine(22, 9, 16, 15, iconStroke);
        canvas.restore();
    }

    private void applyTextSize() {
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                TEXT_SP * scale / 100f, getResources().getDisplayMetrics()));
    }

    /** Scaled dp. */
    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density * scale / 100f;
    }
}
