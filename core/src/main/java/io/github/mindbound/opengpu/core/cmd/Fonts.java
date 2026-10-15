package io.github.mindbound.opengpu.core.cmd;

/**
 * {@code TEXT}'s font ids (28 §5.6). M0 defines font 0 only, whose 6×13 cell is part of the format because it sets the
 * pixel result and the fill charge (28 §7.3); any other id is {@code UNKNOWN_FONT}, and M1's ids are a relaxed check
 * (28 §4.3). The glyph bitmaps are not part of the format (28 §7.1).
 */
public final class Fonts {

    public static final int FONT_0 = 0;
    public static final int FONT_0_CELL_WIDTH = 6;
    public static final int FONT_0_CELL_HEIGHT = 13;

    /** One unclipped cell per codepoint, 6·13 px (28 §3.5); M0 advances one cell per codepoint (28:650). */
    public static final int FONT_0_FILL_PER_CODEPOINT = FONT_0_CELL_WIDTH * FONT_0_CELL_HEIGHT;

    private Fonts() {}

    /** font == 0 in M0. */
    public static boolean isDefined(int font) {
        return font == FONT_0;
    }
}
