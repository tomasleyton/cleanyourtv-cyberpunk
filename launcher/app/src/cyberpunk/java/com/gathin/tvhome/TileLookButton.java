package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * Top-bar button that switches the app tiles between text and banner artwork ({@link TileLook}).
 * It shows the current look ("TXT" or "IMG"); a press flips it and recreates the activity, which
 * rebuilds every tile with the other look (the dock keeps its focus: MainActivity remembers it).
 *
 * It sits between the Wi-Fi and Settings buttons: MainActivity's key rules swallow Left on the first
 * and Right on the last top button, so a button at either end would be unreachable with the D-pad.
 * Up is swallowed here like on the other buttons; Down goes to the grid through nextFocusDown.
 */
public class TileLookButton extends TextView {

    public TileLookButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        setText(TileLook.isText(context) ? R.string.tile_look_text : R.string.tile_look_image);
        setOnClickListener(v -> {
            TileLook.toggle(context);
            if (context instanceof Activity) ((Activity) context).recreate();
        });
        setOnKeyListener((v, keyCode, event) ->
                event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP);
    }
}
