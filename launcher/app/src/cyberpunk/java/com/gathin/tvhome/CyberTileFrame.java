package com.gathin.tvhome;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

/**
 * A frame with two cut corners (top-left and bottom-right) and a hairline edge, cyberpunk style.
 * Used for the app tiles (magenta edge) and, with {@code app:panel="true"}, for the info panels of
 * the bottom module (cyan edge).
 *
 * The corners are really cut: background and children are drawn inside a clip path, so whatever is
 * behind the tile (the ambient glow, the floor grid) shows through them. Android 11 renders path
 * clips on the GPU with Skia; the clip is one op per tile and the focus zoom runs in a layer, so
 * it stays cheap (measured on the TCL: about 1 % janky frames while moving focus).
 */
public class CyberTileFrame extends FrameLayout {

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final Path edge = new Path();
    private final float cut;

    public CyberTileFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        cut = 10f * density;
        boolean panel = false;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CyberTileFrame);
            panel = a.getBoolean(R.styleable.CyberTileFrame_panel, false);
            a.recycle();
        }
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(density);
        edgePaint.setColor(context.getColor(panel ? R.color.cyber_cyan_edge : R.color.cyber_magenta_dim));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cutRect(clip, 0, 0, w, h, cut);
        float i = edgePaint.getStrokeWidth() / 2f;   // keep the stroke inside the view
        cutRect(edge, i, i, w - i, h - i, cut);
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
    public void draw(Canvas canvas) {
        int saved = canvas.save();
        canvas.clipPath(clip);
        super.draw(canvas);   // background, then children
        canvas.restoreToCount(saved);
        canvas.drawPath(edge, edgePaint);
    }

    /** AppAdapter rounds every card through its outline; this shape is cut, not rounded, so that is ignored. */
    @Override
    public void setOutlineProvider(ViewOutlineProvider provider) {
    }

    @Override
    public void setClipToOutline(boolean clipToOutline) {
    }
}
