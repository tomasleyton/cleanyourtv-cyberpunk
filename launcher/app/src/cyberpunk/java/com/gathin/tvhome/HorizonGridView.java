package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Synthwave floor for the bottom of the screen: vertical lines converging on a vanishing point at
 * the top edge, horizontal lines bunching up towards it, and a glowing cyan horizon. Static: it is
 * drawn into the display list once and never invalidated.
 */
public class HorizonGridView extends View {

    private static final int VERTICALS_PER_SIDE = 11;
    private static final int HORIZONTALS = 12;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint horizon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int magenta;
    private final int cyan;

    public HorizonGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        magenta = context.getColor(R.color.cyber_magenta) & 0x00FFFFFF;
        cyan = context.getColor(R.color.cyber_cyan) & 0x00FFFFFF;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(density);
        horizon.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Fade the grid towards the horizon so it reads as distance, not as a pattern.
        line.setShader(new LinearGradient(0, 0, 0, h,
                0x0A000000 | magenta, 0x66000000 | magenta, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float vx = w / 2f;
        float spacing = w / 9f;
        for (int i = -VERTICALS_PER_SIDE; i <= VERTICALS_PER_SIDE; i++) {
            float xb = vx + i * spacing;
            canvas.drawLine(xb, h, vx + (xb - vx) * 0.02f, 0, line);
        }
        for (int k = 1; k <= HORIZONTALS; k++) {
            float t = k / (float) HORIZONTALS;
            float y = h * t * t;
            canvas.drawLine(0, y, w, y, line);
        }
        // Horizon: three fading strokes under a crisp line.
        for (int i = 3; i >= 1; i--) {
            horizon.setStrokeWidth(i * 2f * density);
            horizon.setColor(((0x30 / i) << 24) | cyan);
            canvas.drawLine(0, 1, w, 1, horizon);
        }
        horizon.setStrokeWidth(density);
        horizon.setColor(0x99000000 | cyan);
        canvas.drawLine(0, 1, w, 1, horizon);
    }
}
