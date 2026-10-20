package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;

/**
 * The focus highlight of the cyberpunk flavour: a cyan neon frame with cut corners, a faint fill
 * and a soft outer glow. MainActivity moves and sizes it exactly like the stock GlassView (it is
 * one), so the 6dp glow inset must match {@code MainActivity.onTileFocused}.
 */
public class NeonGlassView extends GlassView {

    private static final int GLOW_STEPS = 3;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path frame = new Path();
    private final Path[] halos = new Path[GLOW_STEPS];
    private final float density;

    public NeonGlassView(Context context, AttributeSet attrs) {
        super(context, attrs);   // keeps the hardware layer: the spring only moves a cached texture
        density = getResources().getDisplayMetrics().density;
        int cyan = context.getColor(R.color.cyber_cyan);
        fill.setColor((cyan & 0x00FFFFFF) | 0x22000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2f * density);
        rim.setColor(cyan);
        glow.setStyle(Paint.Style.STROKE);
        glow.setColor(cyan);
        for (int i = 0; i < GLOW_STEPS; i++) halos[i] = new Path();
    }

    /**
     * Corners are cut, not rounded, so the radius does not apply; the call still matters because
     * MainActivity makes it right after resizing the view, and the hardware layer needs the redraw.
     */
    @Override
    void setRadius(float px) {
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = 6f * density;
        float cut = 12f * density;
        cutRect(frame, inset, inset, w - inset, h - inset, cut);
        for (int i = 0; i < GLOW_STEPS; i++) {
            float o = (i + 1) * 1.5f * density;
            cutRect(halos[i], inset - o, inset - o, w - inset + o, h - inset + o, cut + o);
        }
    }

    private static void cutRect(Path p, float l, float t, float r, float b, float c) {
        p.reset();
        p.moveTo(l + c, t);
        p.lineTo(r, t);
        p.lineTo(r, b - c);
        p.lineTo(r - c, b);
        p.lineTo(l, b);
        p.lineTo(l, t + c);
        p.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Outer glow: widening, fading strokes, drawn outside in so the brightest sits on the rim.
        for (int i = GLOW_STEPS - 1; i >= 0; i--) {
            glow.setStrokeWidth((i + 1) * 2f * density);
            glow.setAlpha(0x38 / (i + 1));
            canvas.drawPath(halos[i], glow);
        }
        canvas.drawPath(frame, fill);
        canvas.drawPath(frame, rim);
    }
}
