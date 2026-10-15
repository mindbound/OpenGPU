package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;

/**
 * 28 §11.2's colour, handle, flags and font cases (28 §3.4, §5.3-§5.6; §3.6 rows 14-20 and the per-opcode field order
 * of 28:324), through both validators. Empty ops get every field check (28 §3.5).
 */
class ValidatorFieldTest {

    private static final int TEX = Expectations.TEXTURE;

    // Colours (28 §3.4; row 14)

    @ParameterizedTest(name = "index8 0x{0}")
    @ValueSource(strings = { "00000100", "FF000000", "FFFFFFFF", "80000000", "0000FF00", "000001FF" })
    void index8TakesOnlyAnIndex(String hex) {
        int c = (int) Long.parseLong(hex, 16);
        assertBadColourEverywhere(Expectations.t2(), c, 0);
    }

    @ParameterizedTest(name = "rgb565 0x{0}")
    @ValueSource(strings = { "00FF8000", "FEFFFFFF", "00000000", "000000FF", "7FFFFFFF", "01FFFFFF" })
    void rgb565TakesOnlyAlpha0xFF(String hex) {
        int c = (int) Long.parseLong(hex, 16);
        assertBadColourEverywhere(Expectations.t3(), c, 0xFF000000);
    }

    @Test
    void theEdgesOfEachFormatAreValid() {
        for (int c : new int[] { 0, 1, 254, 255 }) {
            Expectations.assertAccepted(segment(clear(c)), Expectations.t2(), new FrameTotals(14, 1, 64000, 0, 0, 0));
        }
        for (int c : new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFF7F7F7F }) {
            Expectations.assertAccepted(segment(clear(c)), Expectations.t3(), new FrameTotals(14, 1, 256000, 0, 0, 0));
        }
    }

    @Test
    void textChecksFgThenBgEvenWhenBgIsUnused() {
        MapLiveView t2 = Expectations.t2();
        // Both bad: fg decides, and the detail is fg
        StreamError both = Expectations
            .assertRejected(segment(text(0, 0, 0x100, 0x200, 0, 0, "a")), t2, ErrorCode.BAD_COLOUR, 8, "TEXT");
        Assertions.assertEquals(0x100, both.detail);
        // A bad bg with TRANSPARENT_BG still fails (28 §5.6)
        StreamError bg = Expectations
            .assertRejected(segment(text(0, 0, 1, 0x200, 1, 0, "a")), t2, ErrorCode.BAD_COLOUR, 8, "TEXT");
        Assertions.assertEquals(0x200, bg.detail);
        // And with an empty string
        Expectations.assertRejected(segment(text(0, 0, 1, 0x200, 1, 0, "")), t2, ErrorCode.BAD_COLOUR, 8, "TEXT");
    }

    @Test
    void emptyOpsStillCheckTheirColour() {
        MapLiveView t2 = Expectations.t2();
        Expectations.assertRejected(segment(fill(0, 0, 0, 10, 0x100)), t2, ErrorCode.BAD_COLOUR, 8, "FILL_RECT");
        Expectations.assertRejected(segment(fill(0, 0, 10, -1, 0x100)), t2, ErrorCode.BAD_COLOUR, 8, "FILL_RECT");
        Expectations.assertRejected(segment(fill(320, 0, 10, 10, 0x100)), t2, ErrorCode.BAD_COLOUR, 8, "FILL_RECT");
        Expectations
            .assertRejected(segment(text(-32768, -32768, 0x100, 0, 0, 0, "")), t2, ErrorCode.BAD_COLOUR, 8, "TEXT");
    }

    @Test
    void theFirstFailingRecordDecides() {
        MapLiveView t2 = Expectations.t2();
        // Records at 8 (CLEAR), 13 (FILL_RECT), 26 (BLIT), 44 (TEXT)
        Expectations.assertRejected(
            segment(clear(1), fill(0, 0, 1, 1, 0x100), blit(0, 0, 0, 1, 1, 0, 0, 0), text(0, 0, 0x100, 0, 0, 0, "")),
            t2,
            ErrorCode.BAD_COLOUR,
            13,
            "FILL_RECT");
        Expectations.assertRejected(
            segment(clear(1), fill(0, 0, 1, 1, 2), blit(0, 0, 0, 1, 1, 0, 0, 0), text(0, 0, 0x100, 0, 0, 0, "")),
            t2,
            ErrorCode.BAD_HANDLE,
            26,
            "BLIT");
        Expectations.assertRejected(
            segment(clear(1), fill(0, 0, 1, 1, 2), blit(TEX, 0, 0, 1, 1, 0, 0, 0), text(0, 0, 1, 0, 0, 3, "")),
            t2,
            ErrorCode.UNKNOWN_FONT,
            44,
            "TEXT");
    }

    // Handles (28 §6.1; rows 15-19)

    @ParameterizedTest(name = "0x{0}")
    @ValueSource(strings = { "00000000", "80000000", "FFFFFFFF", "07FFFFFF", "00000001", "00010000", "88000000" })
    void malformedHandlesAreBadHandle(String hex) {
        // 0, at least 2^31, or type 0 with other bits set (Handles.java:49-51)
        int handle = (int) Long.parseLong(hex, 16);
        StreamError e = assertBlitRejected(Expectations.t2(), handle, ErrorCode.BAD_HANDLE);
        Assertions.assertEquals(Integer.toUnsignedLong(handle), e.detail);
        Assertions.assertEquals(String.format(Locale.ROOT, "invalid handle 0x%s", hex), e.reason);
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(ints = { 8, 9, 12, 15 })
    void unassignedTypesAreUnknownType(int type) {
        assertBlitRejected(Expectations.t2(), Handles.pack(type, 0, 0), ErrorCode.UNKNOWN_TYPE);
        assertBlitRejected(Expectations.t2(), Handles.pack(type, 2047, 65535), ErrorCode.UNKNOWN_TYPE);
        Assertions.assertEquals(0x7FFFFFFF, Handles.pack(15, 2047, 65535));
        Assertions.assertEquals(0x48000000, Handles.pack(9, 0, 0));
    }

    @Test
    void reservedTypesAreWrongTypeAndNamed() {
        String[] kinds = { "buffer", "render target", "program", "pipeline", "kernel", "compute list" };
        for (int type = 2; type <= 7; type++) {
            int handle = Handles.pack(type, 0, 0);
            Assertions.assertEquals(type << 27, handle);
            StreamError e = assertBlitRejected(Expectations.t2(), handle, ErrorCode.WRONG_TYPE);
            Assertions.assertEquals(
                String.format(Locale.ROOT, "handle 0x%08X is a %s, expected a texture", handle, kinds[type - 2]),
                e.reason);
        }
    }

    @Test
    void textureHandlesThatAreNotLiveAreStale() {
        // Another generation of a live slot
        assertBlitRejected(Expectations.t2(), 0x08000001, ErrorCode.STALE_HANDLE);
        assertBlitRejected(Expectations.t2(), 0x0800FFFF, ErrorCode.STALE_HANDLE);
        // A never-allocated slot: generation 0 matches, the slot is not live (28 §6.1)
        assertBlitRejected(Expectations.t2(), 0x08010000, ErrorCode.STALE_HANDLE);
        assertBlitRejected(Expectations.t2(), Handles.pack(1, 2047, 0), ErrorCode.STALE_HANDLE);
        // Freed: create, free, use
        MapLiveView view = Expectations.t2();
        int second = view.create(4, 4);
        Assertions.assertEquals(0x08010000, second);
        Expectations
            .assertAccepted(segment(blit(second, 0, 0, 4, 4, 0, 0, 0)), view, new FrameTotals(27, 1, 0, 0, 16, 0));
        view.free(second);
        assertBlitRejected(view, second, ErrorCode.STALE_HANDLE);
        // The slot comes back at the queue's tail with its generation bumped (28 §6.1), and the old handle stays
        // stale whatever the allocator hands out next
        int third = view.create(4, 4);
        Assertions.assertEquals(0x08020000, third);
        assertBlitRejected(view, second, ErrorCode.STALE_HANDLE);
        // No texture at all
        assertBlitRejected(Expectations.withLimits(view.surface(), view.limits()), TEX, ErrorCode.STALE_HANDLE);
    }

    @Test
    void aLiveTextureOfTheOtherFormatIsWrongFormat() {
        // Unreachable in M0 through createTexture (28 §6.2), reachable through a view
        MapLiveView t2 = Expectations.t2();
        t2.put(0x08010000, 4, 4, Formats.RGB565);
        assertBlitRejected(t2, 0x08010000, ErrorCode.WRONG_FORMAT);
        MapLiveView t3 = Expectations.t3();
        t3.put(0x08010000, 4, 4, Formats.INDEX8);
        StreamError e = assertBlitRejected(t3, 0x08010000, ErrorCode.WRONG_FORMAT);
        Assertions.assertEquals("texture 0x08010000 is index8, the surface is rgb565", e.reason);
    }

    @Test
    void theHandleDecidesBeforeTheFlags() {
        MapLiveView t2 = Expectations.t2();
        t2.put(0x08010000, 4, 4, Formats.RGB565);
        // Stale with bad flags: the handle (28 §11.2 [M])
        Expectations
            .assertRejected(segment(blit(0x08000001, 0, 0, 1, 1, 0, 0, 0x02)), t2, ErrorCode.STALE_HANDLE, 8, "BLIT");
        // Wrong format with bad flags: the format
        Expectations
            .assertRejected(segment(blit(0x08010000, 0, 0, 1, 1, 0, 0, 0x80)), t2, ErrorCode.WRONG_FORMAT, 8, "BLIT");
        // An empty BLIT with a stale handle still fails
        Expectations
            .assertRejected(segment(blit(0x08000001, 0, 0, 0, 0, 0, 0, 0)), t2, ErrorCode.STALE_HANDLE, 8, "BLIT");
        Expectations
            .assertRejected(segment(blit(0x08000001, 0, 0, -5, 5, 0, 0, 0)), t2, ErrorCode.STALE_HANDLE, 8, "BLIT");
    }

    @Test
    void whateverTheViewReportsLiveIsAccepted() {
        // 28 §6.1: handles are device-local, so the validator cannot tell another device's handle; it accepts what
        // the view reports live, whoever created it (S7 tests the two-device case)
        MapLiveView t2 = Expectations.t2();
        int foreign = Handles.pack(1, 2047, 65535);
        t2.put(foreign, 2, 2, Formats.INDEX8);
        Expectations
            .assertAccepted(segment(blit(foreign, 0, 0, 2, 2, 0, 0, 1)), t2, new FrameTotals(27, 1, 0, 0, 4, 0));
    }

    // Flags and the font (28 §5.5, §5.6; row 20)

    @ParameterizedTest(name = "flags 0x{0}")
    @ValueSource(strings = { "02", "04", "06", "08", "80", "FE", "FF", "03" })
    void undefinedFlagBitsAreBadFlagsField(String hex) {
        // Bits 1-2 are M1's integer scale and stay undefined in v1 (29 F8)
        int flags = Integer.parseInt(hex, 16);
        MapLiveView t2 = Expectations.t2();
        StreamError b = Expectations
            .assertRejected(segment(blit(TEX, 0, 0, 1, 1, 0, 0, flags)), t2, ErrorCode.BAD_FLAGS_FIELD, 8, "BLIT");
        Assertions.assertEquals(flags, b.detail);
        StreamError t = Expectations
            .assertRejected(segment(text(0, 0, 1, 0, flags, 0, "a")), t2, ErrorCode.BAD_FLAGS_FIELD, 8, "TEXT");
        Assertions.assertEquals(flags, t.detail);
    }

    @Test
    void bit0IsKey0AndTransparentBg() {
        MapLiveView t2 = Expectations.t2();
        Expectations
            .assertAccepted(segment(blit(TEX, 0, 0, 16, 16, 0, 0, 1)), t2, new FrameTotals(27, 1, 0, 0, 256, 0));
        Expectations.assertAccepted(segment(text(0, 0, 1, 0, 1, 0, "a")), t2, new FrameTotals(27, 1, 0, 0, 0, 78));
    }

    @ParameterizedTest(name = "font {0}")
    @ValueSource(ints = { 1, 2, 6, 127, 255 })
    void m0DefinesFont0Only(int font) {
        StreamError e = Expectations.assertRejected(
            segment(text(0, 0, 1, 0, 0, font, "a")),
            Expectations.t2(),
            ErrorCode.UNKNOWN_FONT,
            8,
            "TEXT");
        Assertions.assertEquals(font, e.detail);
    }

    @Test
    void textsFieldsAreCheckedInLayoutOrder() {
        // fg, bg, flags, font, string (28:324, §5.6)
        MapLiveView t2 = Expectations.t2();
        Expectations.assertRejected(segment(text(0, 0, 1, 0, 0x02, 1, "a")), t2, ErrorCode.BAD_FLAGS_FIELD, 8, "TEXT");
        Expectations
            .assertRejected(segment(text(0, 0, 1, 0, 0, 1, bytes(0xC0, 0x80))), t2, ErrorCode.UNKNOWN_FONT, 8, "TEXT");
        Expectations.assertRejected(segment(text(0, 0, 1, 0x100, 0x02, 1, "a")), t2, ErrorCode.BAD_COLOUR, 8, "TEXT");
        Expectations.assertRejected(segment(text(0, 0, 0x100, 0, 0x02, 1, "a")), t2, ErrorCode.BAD_COLOUR, 8, "TEXT");
    }

    private static void assertBadColourEverywhere(MapLiveView view, int bad, int good) {
        String[] fields = { "colour", "colour", "fg", "bg" };
        byte[][] segments = { segment(clear(bad)), segment(fill(1, 1, 2, 2, bad)),
            segment(text(0, 0, bad, good, 0, 0, "a")), segment(text(0, 0, good, bad, 0, 0, "a")) };
        String[] ops = { "CLEAR", "FILL_RECT", "TEXT", "TEXT" };
        for (int i = 0; i < segments.length; i++) {
            StreamError e = Expectations.assertRejected(segments[i], view, ErrorCode.BAD_COLOUR, 8, ops[i]);
            Assertions.assertEquals(Integer.toUnsignedLong(bad), e.detail);
            Assertions
                .assertTrue(e.reason.startsWith(fields[i] + String.format(Locale.ROOT, " 0x%08X", bad)), e.reason);
        }
    }

    private static StreamError assertBlitRejected(MapLiveView view, int handle, ErrorCode code) {
        return Expectations.assertRejected(segment(blit(handle, 0, 0, 1, 1, 0, 0, 0)), view, code, 8, "BLIT");
    }
}
