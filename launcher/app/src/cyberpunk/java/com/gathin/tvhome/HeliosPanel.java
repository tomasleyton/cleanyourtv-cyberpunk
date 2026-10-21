package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The MODULE // HELIOS IOT panel of the bottom module: a remote control for the owner's Xiaomi lamp
 * (Yeelight commands over miIO, see {@link MiioClient}). It is the focusable view under the grid
 * ({@code @id/belowGrid}: Down on the grid's last row lands here, MainActivity.onTileVerticalKey).
 * <ul>
 * <li>OK toggles the light. Holding OK switches to white and cycles warm, neutral, cold. Left and Right step
 * through the colours of {@link HeliosPresets}. Down steps the brightness through 25/50/75/100 %.
 * Up and Back return to the tile focus came from. A colour or brightness key switches the lamp on
 * when it was off. Keys apply after a short pause, so a run of presses sends one request.</li>
 * <li>The body line shows what the lamp last said: "LIGHT ON · 57% · WARM WHITE", "LIGHT OFF",
 * "NO CONFIG" when {@code helios.json} is missing, "NO RESPONSE" when it does not answer. The title
 * gives way to the key hint while the panel is focused.</li>
 * <li>Credentials come from {@code helios.json} in the launcher's external files directory (the
 * folder Config reads config.json from): {@code {"bulb_ip":"...","bulb_token":"32 hex"}}. They are
 * read again on every refresh, so pushing the file needs no restart; nothing is baked into the APK.</li>
 * <li>Network only on one worker thread owned by this view (created on attach, shut down on detach),
 * never on the main thread, and never while the home is behind another app: the state is refreshed
 * when the window gains focus, once a minute while it keeps it, when the panel is focused after a
 * while, and after each command.</li>
 * </ul>
 */
public class HeliosPanel extends CyberTileFrame {

    static final String FILE_NAME = "helios.json";
    private static final long PERIOD_MS = 60_000L;
    /** A focus or window event refreshes only when the last reading is older than this. */
    private static final long FRESH_MS = 20_000L;
    /** A failed refresh keeps the last good reading up this long: the bulb drops a packet now and then. */
    private static final long KEEP_MS = 10 * 60_000L;
    /** ... and reads again this soon after a failure, instead of waiting for the next minute. */
    private static final long RETRY_MS = 2_500L;
    /** Failures in a row before the panel admits the lamp is not answering. */
    private static final int FAILURES_SHOWN = 3;
    /** Keys apply after this pause, so a run through colours or levels sends one request. */
    private static final long APPLY_DELAY_MS = 350L;

    /** What the lamp last said. Immutable; built on the worker, read on the main thread. */
    private static final class State {
        static final int UNKNOWN = 0, NO_CONFIG = 1, NO_REPLY = 2, OK = 3;
        final int kind;
        final boolean on;
        final int bright, ct, rgb, mode;

        State(int kind, boolean on, int bright, int ct, int rgb, int mode) {
            this.kind = kind;
            this.on = on;
            this.bright = bright;
            this.ct = ct;
            this.rgb = rgb;
            this.mode = mode;
        }

        static State of(int kind) {
            return new State(kind, false, 0, 0, 0, 0);
        }

        State withPower(boolean on) {
            return new State(kind, on, bright, ct, rgb, mode);
        }

        State withBright(int bright) {
            return new State(kind, true, bright, ct, rgb, mode);
        }

        State withCt(int ct) {
            return new State(kind, true, bright, ct, rgb, HeliosPresets.MODE_CT);
        }

        State withRgb(int rgb) {
            return new State(kind, true, bright, ct, rgb, HeliosPresets.MODE_RGB);
        }
    }

    private interface Command {
        void run(MiioClient c) throws IOException;
    }

    private TextView title, line, hint;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    /** Bumped on each attach: a result from a worker of an earlier attach is dropped. */
    private int generation;
    private State state = State.of(State.UNKNOWN);
    /** The last reading that did come back, so a key still acts on the lamp while a refresh has failed. */
    private State lastOk;
    private long lastOkAt;
    /** Refreshes that failed since the last good reading. */
    private int failures;
    private boolean busy;
    private long lastRefresh = Long.MIN_VALUE / 4;
    /** The grid tile that had focus before this panel, for Up and Back. */
    private WeakReference<View> lastCard;

    // What the keys asked for since the last send; sent together by applyPending after the pause.
    private boolean applyPending;
    private boolean pendPower;
    private int pendBright, pendCt, pendRgb;   // 0 = nothing asked

    // Worker thread only.
    private MiioClient client;
    private String clientSpec;

    /** A quick second read after a lost packet (see run). */
    private final Runnable retry = this::refresh;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, PERIOD_MS);
        }
    };

    private final Runnable apply = () -> {
        applyPending = false;
        final boolean power = pendPower;
        final int bright = pendBright, ct = pendCt, rgb = pendRgb;
        pendPower = false;
        pendBright = pendCt = pendRgb = 0;
        if (!power && bright == 0 && ct == 0 && rgb == 0) return;
        run(c -> {
            if (power) c.setPower(true);
            if (bright > 0) c.setBright(bright);
            if (ct > 0) c.setCt(ct);
            else if (rgb > 0) c.setRgb(rgb);
        });
    };

    private final ViewTreeObserver.OnGlobalFocusChangeListener focusWatch = (oldFocus, newFocus) -> {
        if (newFocus != null && newFocus.getId() == R.id.card) lastCard = new WeakReference<>(newFocus);
    };

    public HeliosPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);   // the background selector does the highlighting
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        title = findViewById(R.id.helios_title);
        line = findViewById(R.id.helios_line);
        hint = findViewById(R.id.helios_hint);
        render();
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        generation++;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "helios-miio");
            t.setDaemon(true);
            return t;
        });
        getViewTreeObserver().addOnGlobalFocusChangeListener(focusWatch);
        if (hasWindowFocus()) startTicking();
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(focusWatch);
        handler.removeCallbacksAndMessages(null);
        applyPending = false;
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (worker == null) return;
        if (hasWindowFocus) startTicking();
        else {
            handler.removeCallbacks(tick);
            handler.removeCallbacks(retry);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        render();
        if (gainFocus && stale()) refresh();
    }

    private void startTicking() {
        handler.removeCallbacks(tick);
        if (stale()) refresh();
        handler.postDelayed(tick, PERIOD_MS);
    }

    private boolean stale() {
        return SystemClock.uptimeMillis() - lastRefresh > FRESH_MS;
    }

    // ---- remote keys -------------------------------------------------------------------------

    private static boolean isOk(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            // Short press toggles on key up; a hold becomes onKeyLongPress and cancels that up.
            if (event.getRepeatCount() == 0) event.startTracking();
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BACK:
                leave();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                stepBright();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cycleColour(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cycleColour(1);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (!isOk(keyCode)) return super.onKeyLongPress(keyCode, event);
        toggleWhite();
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOk(keyCode)) {
            if (!event.isCanceled()) toggle();   // canceled: the hold already acted
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    /** Back to the tile focus came from; the grid itself when that tile is gone. */
    private void leave() {
        View card = lastCard == null ? null : lastCard.get();
        if (card != null && card.isAttachedToWindow() && card.isShown() && card.requestFocus()) return;
        View grid = getRootView().findViewById(R.id.grid);
        if (grid != null) grid.requestFocus();
    }

    /**
     * The lamp state a key acts on: the current one, or the last one read when a refresh has just
     * failed (the bulb drops a packet now and then; the key must still work). Null before any
     * reading or without credentials, when a key only tries to read again.
     */
    private State known() {
        if (state.kind == State.OK) return state;
        return state.kind == State.NO_REPLY ? lastOk : null;
    }

    private void toggle() {
        State s = known();
        if (s == null) {
            refresh();   // unconfigured or never answered: OK tries again
            return;
        }
        final boolean on = !s.on;
        state = s.withPower(on);
        run(c -> c.setPower(on));
    }

    /** Hold OK: to white, cycling warm, neutral, cold (HeliosPresets.nextWhite). */
    private void toggleWhite() {
        State s = known();
        if (s == null) {
            refresh();
            return;
        }
        int ct = HeliosPresets.nextWhite(s.mode, s.ct);
        if (!s.on) pendPower = true;
        pendCt = ct;
        pendRgb = 0;
        state = s.withCt(ct);
        schedule();
    }

    private void cycleColour(int dir) {
        State s = known();
        if (s == null) {
            refresh();
            return;
        }
        int n = HeliosPresets.COLOURS.length;
        int i = HeliosPresets.colourIndex(s.mode, s.rgb);
        i = i < 0 ? (dir > 0 ? 0 : n - 1) : (i + dir + n) % n;
        int rgb = HeliosPresets.COLOURS[i].rgb;
        if (!s.on) pendPower = true;
        pendRgb = rgb;
        pendCt = 0;
        state = s.withRgb(rgb);
        schedule();
    }

    private void stepBright() {
        State s = known();
        if (s == null) {
            refresh();
            return;
        }
        int bright = HeliosPresets.nextBright(s.bright);
        if (!s.on) pendPower = true;
        pendBright = bright;
        state = s.withBright(bright);
        schedule();
    }

    private void schedule() {
        busy = true;
        applyPending = true;
        handler.removeCallbacks(apply);
        handler.postDelayed(apply, APPLY_DELAY_MS);
        render();
    }

    // ---- lamp I/O (worker thread) ------------------------------------------------------------

    private void refresh() {
        run(null);
    }

    /** Runs the command (if any) and then reads the lamp's state, on the worker; shows the result. */
    private void run(Command command) {
        if (worker == null) return;
        lastRefresh = SystemClock.uptimeMillis();
        if (command != null) busy = true;
        render();
        final int gen = generation;
        worker.execute(() -> {
            State s;
            MiioClient c = client();
            if (c == null) {
                s = State.of(State.NO_CONFIG);
            } else {
                try {
                    if (command != null) command.run(c);
                    s = query(c);
                } catch (IOException | RuntimeException e) {
                    s = State.of(State.NO_REPLY);
                }
            }
            final State result = s;
            final boolean afterCommand = command != null;
            post(() -> {
                if (gen != generation || worker == null) return;
                if (afterCommand) busy = false;
                // A result that crosses a pending key must not undo what the key chose.
                if (applyPending) return;
                if (result.kind == State.OK) {
                    lastOk = result;
                    lastOkAt = SystemClock.uptimeMillis();
                    failures = 0;
                    state = result;
                } else if (result.kind == State.NO_REPLY && lastOk != null && ++failures < FAILURES_SHOWN
                        && SystemClock.uptimeMillis() - lastOkAt < KEEP_MS) {
                    // One lost packet must not flash "no response": keep the last reading and read again soon.
                    state = lastOk;
                    handler.removeCallbacks(retry);
                    handler.postDelayed(retry, RETRY_MS);
                } else {
                    state = result;
                }
                render();
            });
        });
    }

    private static State query(MiioClient c) throws IOException {
        String[] p = c.getProp("power", "bright", "ct", "rgb", "color_mode");
        if (p.length < 5) throw new IOException("short get_prop reply");
        return new State(State.OK, "on".equals(p[0]), num(p[1]), num(p[2]), num(p[3]), num(p[4]));
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The client for the credentials in helios.json, rebuilt when the file changes; null without them. */
    private MiioClient client() {
        String ip = null, token = null;
        File dir = Config.dir(getContext());
        File file = dir == null ? null : new File(dir, FILE_NAME);
        if (file != null && file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[4096];
                int n = 0, r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);   // Windows editors add a BOM
                JSONObject o = new JSONObject(text);
                ip = o.optString("bulb_ip", o.optString("ip", "")).trim();
                token = o.optString("bulb_token", o.optString("token", "")).trim();
            } catch (IOException | JSONException e) {
                ip = null;
            }
        }
        if (ip == null || ip.isEmpty() || token == null || token.isEmpty()) {
            client = null;
            clientSpec = null;
            return null;
        }
        String spec = ip + "/" + token;
        if (client == null || !spec.equals(clientSpec)) {
            try {
                client = new MiioClient(ip, MiioClient.hexToken(token));
                clientSpec = spec;
            } catch (IOException | IllegalArgumentException e) {
                client = null;
                clientSpec = null;
            }
        }
        return client;
    }

    // ---- text ----------------------------------------------------------------------------------

    private void render() {
        if (line == null) return;
        Context ctx = getContext();
        String text;
        switch (state.kind) {
            case State.NO_CONFIG:
                text = ctx.getString(R.string.helios_unconfigured);
                break;
            case State.NO_REPLY:
                text = ctx.getString(R.string.helios_no_reply);
                break;
            case State.OK:
                text = state.on ? ctx.getString(R.string.helios_on, state.bright, colourLabel(ctx))
                        : ctx.getString(R.string.helios_off);
                break;
            default:
                text = ctx.getString(R.string.helios_connecting);
        }
        if (busy) text += " …";
        line.setText(text);
        boolean focused = isFocused();
        if (hint != null) hint.setVisibility(focused ? VISIBLE : GONE);
        if (title != null) title.setVisibility(focused ? GONE : VISIBLE);
    }

    private String colourLabel(Context ctx) {
        int colour = HeliosPresets.colourIndex(state.mode, state.rgb);
        if (colour >= 0) return ctx.getString(HeliosPresets.COLOURS[colour].name);
        int white = HeliosPresets.whiteName(state.mode, state.ct);
        if (white != 0) return ctx.getString(white);
        if (state.mode == HeliosPresets.MODE_RGB) return String.format(Locale.ROOT, "#%06X", state.rgb & 0xFFFFFF);
        return state.ct + "K";
    }
}
