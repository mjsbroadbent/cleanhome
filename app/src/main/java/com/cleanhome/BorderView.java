package com.cleanhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The outline of the highlighted tile: a plain white rounded border that
 * gently brightens and dims, slowly enough not to pull the eye.
 */
class BorderView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private boolean active;

    BorderView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3 * density);
        line.setColor(Color.WHITE);
    }

    void setActive(boolean on) {
        active = on;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        float inset = 1.5f * density;
        rect.set(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!active) return;

        // 0..1 and back, once every three seconds.
        double t = SystemClock.uptimeMillis() / 1000.0;
        float pulse = (float) (0.5 + 0.5 * Math.sin(t * 2 * Math.PI / 3));

        line.setAlpha((int) (255 * (0.65 + 0.35 * pulse)));
        float corner = 16 * density;
        canvas.drawRoundRect(rect, corner, corner, line);

        postInvalidateOnAnimation();        // keep pulsing
    }
}
