package com.cleanhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The shimmer around the highlighted tile.
 *
 * This view fills the whole screen and sits behind everything else, so the
 * light spreads out from the tile in every direction but is hidden wherever
 * another tile is. The light is six soft blobs of blue, purple and pink that
 * drift around the tile's edge, each swelling and fading at its own pace.
 */
class GlowView extends View {

    /** Blue, purple, pink, then a second, slightly shifted set. */
    private static final int[] COLORS = {
            0x3D7BFF, 0x9B4DFF, 0xFF4FB8, 0x5B5BFF, 0xC23DFF, 0xFF5FA2};

    private final Paint[] paints = new Paint[COLORS.length];
    private final float radius;

    private View target;
    private long since;

    GlowView(Context context) {
        super(context);
        radius = 68 * getResources().getDisplayMetrics().density;
        for (int i = 0; i < COLORS.length; i++) {
            int rgb = COLORS[i];
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new RadialGradient(0, 0, radius,
                    new int[]{0xB0000000 | rgb, 0x38000000 | rgb, rgb},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            paints[i] = p;
        }
    }

    /** The tile to glow around, or null for no glow. */
    void setTarget(View tile) {
        if (tile == target) return;
        target = tile;
        since = SystemClock.uptimeMillis();
        invalidate();
    }

    View target() {
        return target;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        View tile = target;
        if (tile == null) return;
        if (tile.getWidth() == 0 || !tile.isAttachedToWindow()) {
            postInvalidateOnAnimation();    // not laid out yet; try next frame
            return;
        }

        // Where the tile's centre is, measured in this view's own space.
        float cx = tile.getWidth() / 2f, cy = tile.getHeight() / 2f;
        Object stop = getParent();
        for (View v = tile; v != stop; ) {
            cx += v.getLeft();
            cy += v.getTop();
            if (!(v.getParent() instanceof View)) break;
            v = (View) v.getParent();
        }
        float halfW = tile.getWidth() * 0.54f, halfH = tile.getHeight() * 0.54f;

        long now = SystemClock.uptimeMillis();
        double t = now / 1000.0;
        float fade = Math.min(1f, (now - since) / 300f);

        for (int i = 0; i < paints.length; i++) {
            // Each blob circles the tile (alternate ones the other way) ...
            double angle = t * (0.35 + 0.07 * i) * (i % 2 == 0 ? 1 : -1) + i * Math.PI / 3;
            // ... drifting in and out as it goes, which makes the edge wavy ...
            double wave = 1 + 0.18 * Math.sin(t * (1.1 + 0.23 * i) + i * 1.7);
            float x = cx + (float) (Math.cos(angle) * halfW * 0.9 * wave);
            float y = cy + (float) (Math.sin(angle) * halfH * 0.9 * wave);
            // ... while swelling and dimming at its own speed.
            float size = (float) (0.85 + 0.2 * Math.sin(t * (0.8 + 0.17 * i) + i));
            double bright = 0.75 + 0.25 * Math.sin(t * (1.6 + 0.3 * i) + i * 2.1);
            paints[i].setAlpha((int) (255 * fade * bright));

            canvas.save();
            canvas.translate(x, y);
            canvas.scale(size, size);
            canvas.drawCircle(0, 0, radius, paints[i]);
            canvas.restore();
        }
        postInvalidateOnAnimation();        // keep moving
    }
}
