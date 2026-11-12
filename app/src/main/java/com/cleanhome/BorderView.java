package com.cleanhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * The outline of the highlighted tile: a rounded border in the same blue,
 * purple and pink as the glow. The colours travel slowly around the edge
 * while the whole border pulses brighter and thicker, then back.
 */
class BorderView extends View {

    private static final int[] COLORS = {
            0xFF3D7BFF, 0xFF9B4DFF, 0xFFFF4FB8, 0xFF9B4DFF, 0xFF3D7BFF};

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix turn = new Matrix();
    private final float density;
    private SweepGradient colors;
    private boolean active;

    BorderView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        halo.setStyle(Paint.Style.STROKE);
    }

    void setActive(boolean on) {
        active = on;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        colors = new SweepGradient(w / 2f, h / 2f, COLORS, null);
        line.setShader(colors);
        halo.setShader(colors);
        float inset = 2f * density;
        rect.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!active || colors == null) return;

        double t = SystemClock.uptimeMillis() / 1000.0;
        // 0..1 and back, about every two seconds.
        float pulse = (float) (0.5 + 0.5 * Math.sin(t * Math.PI));

        // Send the colours round the edge, one lap every nine seconds.
        turn.setRotate((float) (t * 40 % 360), getWidth() / 2f, getHeight() / 2f);
        colors.setLocalMatrix(turn);

        float corner = 15 * density;

        // A wide, faint stroke underneath gives the line a soft edge.
        halo.setStrokeWidth((10 + 4 * pulse) * density);
        halo.setAlpha((int) (255 * (0.20 + 0.25 * pulse)));
        canvas.drawRoundRect(rect, corner, corner, halo);

        line.setStrokeWidth((4.5f + 2f * pulse) * density);
        line.setAlpha((int) (255 * (0.65 + 0.35 * pulse)));
        canvas.drawRoundRect(rect, corner, corner, line);

        postInvalidateOnAnimation();        // keep moving
    }
}
