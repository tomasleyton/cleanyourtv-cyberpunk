package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Locale;

/**
 * A frame with two cut corners (top-left and bottom-right) and a hairline edge, cyberpunk style.
 * Used for the app tiles (magenta edge, cyan while focused) and, with {@code app:panel="true"}, for
 * the info panels of the bottom module (cyan edge).
 *
 * The corners are really cut: background and children are drawn inside a clip path, so whatever is
 * behind the tile (the ambient glow, the floor grid) shows through them. Android 11 renders path
 * clips on the GPU with Skia; the clip is one op per tile and the focus zoom runs in a layer, so
 * it stays cheap (measured on the TCL: about 1 % janky frames while moving focus).
 *
 * As an app tile (item_app.xml) with {@code cyber_text_tiles} on (values/config.xml) it turns the
 * tile into a text tile at inflate time: banner, icon and label are shrunk to nothing (AppAdapter
 * keeps binding them, unchanged) and the two texts of the tile are shown and written here, so
 * AppAdapter in main needs no change for this look:
 * <ul>
 * <li>{@code tile_name}: the app name. AppAdapter binds it into the item's {@code name} view (the
 * one that fades in under a stock tile) on every bind, so a TextWatcher on that sibling copies it
 * here. A card's caption after " · " is dropped; the tile shows the label alone.</li>
 * <li>{@code tile_index}: the tile's position in the grid plus one ("01" is the first dock slot),
 * "NN // ACTIVE" while focused. The position comes from the RecyclerView at measure time: a bind
 * can happen while the item is detached (scrap or prefetch), but the item is always attached and
 * bound by the time the layout manager measures it, and the watcher requests a layout so that
 * measure does happen after each bind.</li>
 * </ul>
 */
public class CyberTileFrame extends FrameLayout {

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorStateList edgeColors;
    private final Path clip = new Path();
    private final Path edge = new Path();
    private final float cut;
    private final boolean textTiles;

    /** Tile texts; null for a panel or for banner tiles. */
    private TextView indexView, nameView;
    /** The item's {@code name} view once watched; null until the tile is first attached. */
    private TextView boundName;
    private int position = RecyclerView.NO_POSITION;

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
        textTiles = !panel && getResources().getBoolean(R.bool.cyber_text_tiles);
        // Banner tiles keep the magenta edge under the neon frame; a text tile's edge turns cyan with it.
        edgeColors = context.getColorStateList(panel ? R.color.cyber_cyan_edge
                : textTiles ? R.color.tile_edge : R.color.cyber_magenta_dim);
        edgePaint.setColor(edgeColors.getDefaultColor());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (!textTiles) return;
        indexView = findViewById(R.id.tile_index);
        nameView = findViewById(R.id.tile_name);
        if (indexView == null || nameView == null) return;
        indexView.setVisibility(VISIBLE);
        nameView.setVisibility(VISIBLE);
        for (int id : new int[] {R.id.banner, R.id.icon, R.id.label}) {
            View v = findViewById(id);
            if (v == null) continue;
            LayoutParams lp = (LayoutParams) v.getLayoutParams();
            lp.width = 0;
            lp.height = 0;
            v.setLayoutParams(lp);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (nameView == null || boundName != null) return;
        ViewParent item = getParent();
        TextView name = item instanceof View ? ((View) item).findViewById(R.id.name) : null;
        if (name == null) return;
        boundName = name;
        // The first bind happened before this attach: take the text now, then follow every rebind.
        nameView.setText(tileName(name.getText()));
        name.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                nameView.setText(tileName(s));
                requestLayout();   // a rebind: the position is read again in onMeasure
            }
        });
    }

    /** The label alone: AppAdapter joins a card's caption to it with " · ". */
    private static CharSequence tileName(CharSequence bound) {
        if (bound == null) return "";
        String s = bound.toString();
        int sep = s.indexOf(" · ");
        return sep > 0 ? s.substring(0, sep) : s;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (indexView != null) {
            ViewParent item = getParent();
            ViewParent grid = item == null ? null : item.getParent();
            if (grid instanceof RecyclerView) {
                RecyclerView rv = (RecyclerView) grid;
                int pos = rv.getChildAdapterPosition((View) item);
                if (pos == RecyclerView.NO_POSITION) pos = rv.getChildLayoutPosition((View) item);
                if (pos != RecyclerView.NO_POSITION && pos != position) {
                    position = pos;
                    updateIndex();
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void updateIndex() {
        if (indexView == null || position == RecyclerView.NO_POSITION) return;
        String n = String.format(Locale.ROOT, "%02d", position + 1);
        indexView.setText(isFocused() ? getContext().getString(R.string.tile_focused, n) : n);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        updateIndex();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        int c = edgeColors.getColorForState(getDrawableState(), edgeColors.getDefaultColor());
        if (c != edgePaint.getColor()) {
            edgePaint.setColor(c);
            invalidate();
        }
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
