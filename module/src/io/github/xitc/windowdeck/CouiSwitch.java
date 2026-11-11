package io.github.xitc.windowdeck;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/** Draws the switch in a settings row. The row owns input and persistence. */
final class CouiSwitch extends View {
    private static final int SLIDE_MS = 383;
    private static final int STRETCH_MS = 133;
    private static final int COLOR_MS = 450;
    private static final float PEAK_TAIL = 0.3f;
    private static final PathInterpolator EASING = new PathInterpolator(0.3f, 0f, 0.1f, 1f);
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int trackWidth, trackHeight, thumbSize, thumbInset;
    private final int offColor, onColor;
    private int trackColor;
    private boolean checked;
    private float position;
    private float tail;
    private ValueAnimator animator;

    CouiSwitch(Context context) {
        super(context);
        trackWidth = Ui.dp(context, 38);
        trackHeight = Ui.dp(context, 24);
        thumbSize = Ui.dp(context, 18);
        thumbInset = Ui.dp(context, 3);
        offColor = SettingsTheme.trackOff(context);
        onColor = SettingsTheme.accent(context);
        trackColor = offColor;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setChecked(boolean checked, boolean animate) {
        if (this.checked == checked) return;
        this.checked = checked;
        if (!animate || !isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            settle();
            return;
        }
        if (animator != null) animator.cancel();

        float startPosition = position;
        float startTail = tail;
        int startColor = trackColor;
        float targetPosition = checked ? 1f : 0f;
        float peakTail = checked ? -PEAK_TAIL : PEAK_TAIL;
        int targetColor = checked ? onColor : offColor;
        animator = ValueAnimator.ofFloat(0f, COLOR_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.setDuration(COLOR_MS);
        animator.addUpdateListener(animation -> {
            float elapsed = (float) animation.getAnimatedValue();
            position = interpolate(startPosition, targetPosition, elapsed / SLIDE_MS);
            tail = elapsed < STRETCH_MS
                    ? interpolate(startTail, peakTail, elapsed / STRETCH_MS)
                    : interpolate(peakTail, 0f, (elapsed - STRETCH_MS) / (SLIDE_MS - STRETCH_MS));
            trackColor = (int) COLORS.evaluate(ease(elapsed / COLOR_MS), startColor, targetColor);
            invalidate();
        });
        animator.start();
    }

    boolean isChecked() {
        return checked;
    }

    @Override
    protected void onDetachedFromWindow() {
        settle();
        super.onDetachedFromWindow();
    }

    private void settle() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        position = checked ? 1f : 0f;
        tail = 0f;
        trackColor = checked ? onColor : offColor;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(trackWidth, widthSpec), resolveSize(trackHeight, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width < thumbSize || height < thumbSize) return;

        paint.setColor(trackColor);
        float radius = height / 2f;
        canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);

        float inset = Math.min(thumbInset, (width - thumbSize) / 2f);
        float travel = width - thumbSize - 2f * inset;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float left = inset + travel * (rtl ? 1f - position : position);
        float extension = thumbSize * (rtl ? -tail : tail);
        float top = (height - thumbSize) / 2f;
        paint.setColor(0xffffffff);
        float thumbRadius = thumbSize / 2f;
        canvas.drawRoundRect(
                Math.max(0, left + Math.min(0, extension)), top,
                Math.min(width, left + thumbSize + Math.max(0, extension)), top + thumbSize,
                thumbRadius, thumbRadius, paint);
    }

    private static float interpolate(float start, float end, float progress) {
        return start + (end - start) * ease(progress);
    }

    private static float ease(float progress) {
        return EASING.getInterpolation(Math.min(1f, progress));
    }
}
