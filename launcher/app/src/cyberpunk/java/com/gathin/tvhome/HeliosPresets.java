package com.gathin.tvhome;

/**
 * What the Helios panel's keys cycle through: the four colours (Left/Right), the three whites of the
 * warm/neutral/cold cycle (hold OK) and the brightness levels (Down). Plain data, no Android types, so the
 * matching is unit tested on the JVM.
 */
final class HeliosPresets {

    static final class Colour {
        /** Name shown on the panel (string resource). */
        final int name;
        /** 0xRRGGBB. */
        final int rgb;

        Colour(int name, int rgb) {
            this.name = name;
            this.rgb = rgb;
        }
    }

    static final Colour[] COLOURS = {
            new Colour(R.string.helios_red, 0xFF0000),
            new Colour(R.string.helios_blue, 0x0000FF),
            new Colour(R.string.helios_purple, 0x9B30FF),
            new Colour(R.string.helios_colour_green, 0x00FF00),
    };

    /** The whites hold OK cycles through, in order (kelvin): the user's warm, neutral and cold. */
    static final int WARM_CT = 3000, NEUTRAL_CT = 4100, COOL_CT = 6000;
    static final int[] WHITES = {WARM_CT, NEUTRAL_CT, COOL_CT};
    private static final int[] WHITE_NAMES = {R.string.helios_white_warm, R.string.helios_white_neutral, R.string.helios_white_cool};
    /** Brightness levels Down steps through, in percent. */
    static final int[] BRIGHT_LEVELS = {25, 50, 75, 100};

    /** Yeelight color_mode values. */
    static final int MODE_RGB = 1, MODE_CT = 2;
    /** A reported temperature this close to a white counts as that white (the lamp rounds a little). */
    private static final int CT_TOLERANCE = 100;

    /** Index into {@link #COLOURS} of the colour the lamp shows, or -1 (a white, or a colour of its own). */
    static int colourIndex(int mode, int rgb) {
        if (mode != MODE_RGB) return -1;
        for (int i = 0; i < COLOURS.length; i++) {
            if (COLOURS[i].rgb == (rgb & 0xFFFFFF)) return i;
        }
        return -1;
    }

    /** The string resource naming the white the lamp is on, or 0 for a colour or an in-between temperature. */
    static int whiteName(int mode, int ct) {
        if (mode != MODE_CT) return 0;
        for (int i = 0; i < WHITES.length; i++) {
            if (Math.abs(ct - WHITES[i]) <= CT_TOLERANCE) return WHITE_NAMES[i];
        }
        return 0;
    }

    /**
     * Where hold OK goes: warm from a colour; from a white, the next one up (warm, neutral, cold, then
     * warm again); from an in-between temperature, the next white above it.
     */
    static int nextWhite(int mode, int ct) {
        if (mode != MODE_CT) return WARM_CT;
        for (int white : WHITES) {
            if (white > ct + CT_TOLERANCE) return white;
        }
        return WARM_CT;
    }

    /** The next brightness level above the current one, wrapping to the lowest. */
    static int nextBright(int bright) {
        for (int level : BRIGHT_LEVELS) {
            if (level > bright) return level;
        }
        return BRIGHT_LEVELS[0];
    }

    private HeliosPresets() {
    }
}
