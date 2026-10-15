package io.github.mindbound.opengpu.core.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.OcArgs;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;

/**
 * 28:920 as a table: every row of 28 §3.6's callback table (28:338-345) with saturated and fractional Lua arguments
 * (10^10, −10^10, 16.7, 0.9; NaN raises OC's argument error and never reaches the device), {@code x + w} at the
 * {@code int} limit, and {@code createTexture(1e9, 1e9)}, which returns {@code "invalid texture size"} uncharged
 * (28:719). Each row's Lua arguments go through {@link OcArgs#checkInteger}, OC's own conversion (28:84; H7), and then
 * to {@link Device} through OC's direct call with its retry; the row gives the expected {@code toLua()} and the budget
 * events (28 §8.2; plan §3.3). A failing row also asserts that nothing was offered and the snapshot did not change;
 * where the charge follows the checks, that no budget event happened (28:86-91). Plan §4.2 with amendment A7's rows and
 * amendment A's H23 rows.
 */
class DeviceCallbackTableTest {

    private static final int T = 0x08000000;
    private static final double NAN = Double.NaN;
    private static final String NO_INTEGER = " (number has no integer representation)";

    /** The callback as the root's {@code @Callback} makes it: the arguments through checkInteger, then the device. */
    interface Call {

        CallResult apply(Device device, CallBudget budget, boolean[] called);
    }

    interface Setup {

        void apply(DeviceRig rig);
    }

    interface After {

        void check(DeviceRig rig);
    }

    static final class Row {

        final String name;
        final int tier;
        final Setup setup;
        final Call call;
        final Object[] lua;
        final List<String> events;
        final String argError;
        final After after;

        Row(String name, int tier, Setup setup, Call call, Object[] lua, List<String> events, String argError,
            After after) {
            this.name = name;
            this.tier = tier;
            this.setup = setup;
            this.call = call;
            this.lua = lua;
            this.events = events;
            this.argError = argError;
            this.after = after;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // ---- Setups

    private static final Setup NONE = rig -> {};
    /** A live 16×16 texture 0x08000000 (28:538). */
    private static final Setup TEX16 = rig -> Assertions.assertEquals(T, rig.create(16, 16));
    /** 256 live 1×1 textures: the count cap (28:687). */
    private static final Setup FULL_COUNT = rig -> {
        for (int i = 0; i < 256; i++) {
            rig.create(1, 1);
        }
    };
    /** T2's whole VRAM in one 1 024 × 1 024 texture, folded at once on an empty tail (H2). */
    private static final Setup FULL_VRAM = rig -> rig.create(1024, 1024);
    /** Plan §4.3's busy boundary: FRAME 1 in the tail, then 1 024 × 976 queued behind it (999 488 queued bytes). */
    private static final Setup BEHIND_FRAME = rig -> {
        rig.present();
        rig.create(1024, 976);
    };
    /** H23: the whole VRAM freed while a frame is open, so it is credited but still pending. */
    private static final Setup PENDING_VRAM = rig -> {
        int t = rig.create(1024, 1024);
        rig.submit(TestEncoder.segment());
        rig.free(t);
    };
    /** H23: 256 textures, one of them freed while a frame is open. */
    private static final Setup PENDING_COUNT = rig -> {
        FULL_COUNT.apply(rig);
        rig.submit(TestEncoder.segment());
        rig.free(T);
    };

    // ---- The rows

    static Stream<Row> rows() {
        List<Row> rows = new ArrayList<>();
        createRows(rows);
        updateRows(rows);
        freeRows(rows);
        submitPresentRows(rows);
        readPixelsRows(rows);
        return rows.stream();
    }

    private static void createRows(List<Row> rows) {
        rows.add(ok("createTexture(16, 16)", 2, NONE, create(16.0, 16.0, null), handle(T), "consume 0.000976563"));
        rows.add(ok("createTexture(16, 16) T3", 3, NONE, create(16.0, 16.0, null), handle(T), "consume 0.001953125"));
        rows.add(
            new Row(
                "createTexture(16.7, 16.7)",
                2,
                NONE,
                create(16.7, 16.7, null),
                handle(T),
                events("consume 0.000976563"),
                null,
                rig -> {
                    DeviceSnapshot s = rig.snapshot();
                    Assertions.assertEquals(16, s.live.get(0).width);
                    Assertions.assertEquals(16, s.live.get(0).height);
                    Assertions.assertEquals(256, s.vramUsed);
                }));
        rows.add(ok("createTexture(1024.9, 1)", 2, NONE, create(1024.9, 1.0, null), handle(T), "consume 0.003906250"));
        rows.add(ok("createTexture(1, 1024)", 2, NONE, create(1.0, 1024.0, null), handle(T), "consume 0.003906250"));
        // 28:719: uncharged at both tiers, no pause computed from unchecked sizes
        rows.add(fails("createTexture(1e9, 1e9)", 2, NONE, create(1e9, 1e9, null), "invalid texture size"));
        rows.add(fails("createTexture(1e9, 1e9) T3", 3, NONE, create(1e9, 1e9, null), "invalid texture size"));
        Object[][] sizes = { { 1e10, 1.0 }, { -1e10, 1.0 }, { 0.0, 1.0 }, { 1.0, 0.0 }, { 1025.0, 1.0 },
            { 1.0, 1025.0 }, { 1.0, 1e10 }, { 1.0, -1e10 }, { 1.0, 0.9 }, { -0.9, 1.0 }, { -1.0, -1.0 } };
        for (Object[] s : sizes) {
            rows.add(
                fails(
                    "createTexture(" + s[0] + ", " + s[1] + ")",
                    2,
                    NONE,
                    create(s[0], s[1], null),
                    "invalid texture size"));
        }
        rows.add(argError("createTexture(NaN, 1)", 2, NONE, create(NAN, 1.0, null), "bad argument #1" + NO_INTEGER));
        rows.add(argError("createTexture(1, NaN)", 2, NONE, create(1.0, NAN, null), "bad argument #2" + NO_INTEGER));
        // format: nil or the surface format's exact name (28:343, §6.2)
        rows.add(
            ok(
                "createTexture(16, 16, \"index8\")",
                2,
                NONE,
                create(16.0, 16.0, "index8"),
                handle(T),
                "consume 0.000976563"));
        rows.add(
            ok(
                "createTexture(16, 16, \"rgb565\") T3",
                3,
                NONE,
                create(16.0, 16.0, "rgb565"),
                handle(T),
                "consume 0.001953125"));
        for (String f : new String[] { "rgb565", "", "INDEX8", "index8 ", "index16" }) {
            rows.add(
                fails("createTexture(16, 16, \"" + f + "\")", 2, NONE, create(16.0, 16.0, f), "unsupported format"));
        }
        rows.add(
            fails("createTexture(16, 16, \"index8\") T3", 3, NONE, create(16.0, 16.0, "index8"), "unsupported format"));
        rows.add(
            fails("createTexture(1, 1) with 256 live", 2, FULL_COUNT, create(1.0, 1.0, null), "too many textures"));
        rows.add(
            fails(
                "createTexture(1, 1) after 1024x1024",
                2,
                FULL_VRAM,
                create(1.0, 1.0, null),
                "not enough video memory"));
        // the busy pair behind FRAME 1 (H2; plan §4.3): queued 999 488 + 49 024 + 64 = the cap; one byte more is busy
        rows.add(
            ok(
                "createTexture(128, 383) behind a frame",
                2,
                BEHIND_FRAME,
                create(128.0, 383.0, null),
                handle(0x08010000),
                "consume 0.187011719"));
        rows.add(fails("createTexture(185, 265) behind a frame", 2, BEHIND_FRAME, create(185.0, 265.0, null), "busy"));
        // H23: pending frees still count against the caps until they are appended
        rows.add(fails("createTexture(1, 1) with 1 MB pending", 2, PENDING_VRAM, create(1.0, 1.0, null), "busy"));
        rows.add(
            fails("createTexture(1, 1) with 255 live and 1 pending", 2, PENDING_COUNT, create(1.0, 1.0, null), "busy"));
    }

    private static void updateRows(List<Row> rows) {
        byte[] full = new byte[256];
        // the handle messages of rows 15-18 (28:336)
        Object[][] handles = { { 0.0, "invalid handle 0x00000000" }, { 16.7, "invalid handle 0x00000010" },
            { -1e10, "invalid handle 0x80000000" }, { 1e10, "handle 0x7FFFFFFF has an unknown type" },
            { (double) 0x48000000, "handle 0x48000000 has an unknown type" },
            { (double) 0x10000000, "handle 0x10000000 is a buffer, expected a texture" },
            { (double) 0x18000000, "handle 0x18000000 is a render target, expected a texture" },
            { (double) 0x38000000, "handle 0x38000000 is a compute list, expected a texture" },
            { (double) 0x08000001, "handle 0x08000001 is not live" },
            { (double) 0x08010000, "handle 0x08010000 is not live" } };
        for (Object[] h : handles) {
            rows.add(
                fails(
                    "updateTexture(" + hexName(h[0]) + ", 0, 0, 16, 16)",
                    2,
                    TEX16,
                    update(h[0], 0.0, 0.0, 16.0, 16.0, full),
                    (String) h[1]));
            rows.add(fails("free(" + hexName(h[0]) + ")", 2, TEX16, free(h[0]), (String) h[1]));
        }
        // regions on the 16×16 texture (28:344): (x, w) with y = 0, h = 16, then the (y, h) mirrors
        Object[][] bad = { { 16.0, 1.0 }, { 0.0, 17.0 }, { 1.0, 16.0 }, { 1e10, 1.0 }, { 1.0, 1e10 }, { -1e10, 1.0 },
            { -1.0, 1.0 }, { 0.0, 0.0 }, { 16.7, 1.0 }, { 0.0, 0.9 }, { 1e10, 1e10 }, { 8.0, 2147483647.0 } };
        for (Object[] r : bad) {
            byte[] data = new byte[16];
            rows.add(
                fails(
                    "updateTexture x, w = " + r[0] + ", " + r[1],
                    2,
                    TEX16,
                    update((double) T, r[0], 0.0, r[1], 16.0, data),
                    "invalid texture region"));
            rows.add(
                fails(
                    "updateTexture y, h = " + r[0] + ", " + r[1],
                    2,
                    TEX16,
                    update((double) T, 0.0, r[0], 16.0, r[1], data),
                    "invalid texture region"));
        }
        for (Object[] r : new Object[][] { { 15.0, 1.0 }, { 15.9, 1.9 } }) {
            rows.add(
                ok(
                    "updateTexture x, w = " + r[0] + ", " + r[1],
                    2,
                    TEX16,
                    update((double) T, r[0], 0.0, r[1], 16.0, new byte[16]),
                    lua(Boolean.TRUE),
                    "consume 0.000061035"));
            rows.add(
                ok(
                    "updateTexture y, h = " + r[0] + ", " + r[1],
                    2,
                    TEX16,
                    update((double) T, 0.0, r[0], 16.0, r[1], new byte[16]),
                    lua(Boolean.TRUE),
                    "consume 0.000061035"));
        }
        rows.add(
            ok(
                "updateTexture(t, 0, 0, 16, 16, 256 B)",
                2,
                TEX16,
                update((double) T, 0.0, 0.0, 16.0, 16.0, full),
                lua(Boolean.TRUE),
                "consume 0.000976563"));
        for (int n : new int[] { 0, 255, 257, 512 }) {
            rows.add(
                fails(
                    "updateTexture(t, 0, 0, 16, 16, " + n + " B)",
                    2,
                    TEX16,
                    update((double) T, 0.0, 0.0, 16.0, 16.0, new byte[n]),
                    "data must be 256 bytes"));
        }
        rows.add(
            fails(
                "updateTexture(t, 0, 0, 16, 16, 256 B) T3",
                3,
                TEX16,
                update((double) T, 0.0, 0.0, 16.0, 16.0, full),
                "data must be 512 bytes"));
        rows.add(
            ok(
                "updateTexture(t, 0, 0, 16, 16, 512 B) T3",
                3,
                TEX16,
                update((double) T, 0.0, 0.0, 16.0, 16.0, new byte[512]),
                lua(Boolean.TRUE),
                "consume 0.001953125"));
        rows.add(
            argError(
                "updateTexture(NaN, ...)",
                2,
                TEX16,
                update(NAN, 0.0, 0.0, 16.0, 16.0, full),
                "bad argument #1" + NO_INTEGER));
        rows.add(
            argError(
                "updateTexture(t, NaN, ...)",
                2,
                TEX16,
                update((double) T, NAN, 0.0, 16.0, 16.0, full),
                "bad argument #2" + NO_INTEGER));
        rows.add(
            argError(
                "updateTexture(t, 0, 0, 16, NaN)",
                2,
                TEX16,
                update((double) T, 0.0, 0.0, 16.0, NAN, full),
                "bad argument #5" + NO_INTEGER));
    }

    private static void freeRows(List<Row> rows) {
        rows.add(ok("free(t)", 2, TEX16, free((double) T), lua(Boolean.TRUE)));
        rows.add(ok("free(t) T3", 3, TEX16, free((double) T), lua(Boolean.TRUE)));
        rows.add(fails("free(t) twice", 2, rig -> {
            TEX16.apply(rig);
            rig.free(T);
        }, free((double) T), "handle 0x08000000 is not live"));
        rows.add(fails("free(t) on a new device", 2, NONE, free((double) T), "handle 0x08000000 is not live"));
        rows.add(argError("free(NaN)", 2, TEX16, free(NAN), "bad argument #1" + NO_INTEGER));
    }

    private static void submitPresentRows(List<Row> rows) {
        rows.add(
            fails(
                "submit(\"\")",
                2,
                NONE,
                submit(new byte[0]),
                "0: header: segment of 0 bytes is shorter than the 8-byte header",
                "consume 0.015625000"));
        rows.add(
            fails(
                "submit(\"\") T3",
                3,
                NONE,
                submit(new byte[0]),
                "0: header: segment of 0 bytes is shorter than the 8-byte header",
                "consume 0.007812500"));
        rows.add(
            fails(
                "submit(70000 B)",
                2,
                NONE,
                submit(new byte[70000]),
                "0: submit: 70000 bytes exceed the 65536-byte cap",
                "consume 0.078125000"));
        rows.add(
            ok(
                "submit(header, END)",
                2,
                NONE,
                submit(TestEncoder.segment()),
                lua(Boolean.TRUE),
                "consume 0.015633583"));
        rows.add(ok("present()", 2, NONE, present(), lua(Long.valueOf(1)), "consume 0.062500000"));
        rows.add(ok("present() T3", 3, NONE, present(), lua(Long.valueOf(1)), "consume 0.031250000"));
    }

    private static void readPixelsRows(List<Row> rows) {
        rows.add(ok("readPixels(0, 0, 1, 1)", 2, NONE, read(0.0, 0.0, 1.0, 1.0), pixels(1), "consume 0.000003815"));
        rows.add(
            ok("readPixels(319, 199, 1, 1)", 2, NONE, read(319.0, 199.0, 1.0, 1.0), pixels(1), "consume 0.000003815"));
        rows.add(
            ok(
                "readPixels(0, 0, 256, 128)",
                2,
                NONE,
                read(0.0, 0.0, 256.0, 128.0),
                pixels(32768),
                "consume 0.125000000"));
        rows.add(
            ok(
                "readPixels(0, 0, 320, 102)",
                2,
                NONE,
                read(0.0, 0.0, 320.0, 102.0),
                pixels(32640),
                "consume 0.124511719"));
        rows.add(
            fails(
                "readPixels(0, 0, 257, 128)",
                2,
                NONE,
                read(0.0, 0.0, 257.0, 128.0),
                "region exceeds 32768 bytes per call"));
        // the region before the cap: an invalid region over the cap is "invalid region"
        rows.add(fails("readPixels(0, 0, 321, 200)", 2, NONE, read(0.0, 0.0, 321.0, 200.0), "invalid region"));
        Object[][] bad = { { 320.0, 0.0, 1.0, 1.0 }, { 0.0, 200.0, 1.0, 1.0 }, { 0.0, 0.0, 0.0, 1.0 },
            { 1e10, 0.0, 1.0, 1.0 }, { 1.0, 0.0, 1e10, 1.0 }, { -1.0, 0.0, 1.0, 1.0 }, { -1e10, 0.0, 1.0, 1.0 },
            { 319.0, 0.0, 2.0, 1.0 }, { 300.0, 0.0, 2147483647.0, 1.0 },
            // A7: the y and h mirrors
            { 0.0, 1e10, 1.0, 1.0 }, { 0.0, 0.0, 1.0, 1e10 }, { 0.0, -1.0, 1.0, 1.0 }, { 0.0, 0.0, 1.0, 0.0 },
            { 0.0, 199.0, 1.0, 2.0 }, { 0.0, 150.0, 1.0, 2147483647.0 }, { 0.0, 0.0, 0.9, 1.0 } };
        for (Object[] r : bad) {
            rows.add(
                fails(
                    "readPixels(" + r[0] + ", " + r[1] + ", " + r[2] + ", " + r[3] + ")",
                    2,
                    NONE,
                    read(r[0], r[1], r[2], r[3]),
                    "invalid region"));
        }
        rows.add(
            ok("readPixels(0.5, 0.5, 1.9, 1.9)", 2, NONE, read(0.5, 0.5, 1.9, 1.9), pixels(1), "consume 0.000003815"));
        rows.add(ok("readPixels(0, 0.5, 1, 1.9)", 2, NONE, read(0.0, 0.5, 1.0, 1.9), pixels(1), "consume 0.000003815"));
        rows.add(
            ok(
                "readPixels(0, 0, 256, 128) T3",
                3,
                NONE,
                read(0.0, 0.0, 256.0, 128.0),
                pixels(65536),
                "consume 0.250000000"));
        rows.add(
            ok(
                "readPixels(639, 399, 1, 1) T3",
                3,
                NONE,
                read(639.0, 399.0, 1.0, 1.0),
                pixels(2),
                "consume 0.000007629"));
        rows.add(
            fails(
                "readPixels(0, 0, 257, 128) T3",
                3,
                NONE,
                read(0.0, 0.0, 257.0, 128.0),
                "region exceeds 65536 bytes per call"));
        rows.add(fails("readPixels(640, 0, 1, 1) T3", 3, NONE, read(640.0, 0.0, 1.0, 1.0), "invalid region"));
        rows.add(
            argError("readPixels(NaN, 0, 1, 1)", 2, NONE, read(NAN, 0.0, 1.0, 1.0), "bad argument #1" + NO_INTEGER));
        rows.add(
            argError("readPixels(0, 0, 1, NaN)", 2, NONE, read(0.0, 0.0, 1.0, NAN), "bad argument #4" + NO_INTEGER));
    }

    // ---- The runner

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void row(Row row) {
        DeviceRig rig = DeviceRig.tier(row.tier);
        row.setup.apply(rig);
        rig.budget.clearEvents();
        String before = state(rig.snapshot());
        List<String> offeredBefore = rig.offeredText();
        boolean[] called = { false };
        if (row.argError != null) {
            IllegalArgumentException e = Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> rig.budget.call(() -> row.call.apply(rig.device, rig.budget, called)));
            Assertions.assertEquals(row.argError, e.getMessage());
            Assertions.assertFalse(called[0], "the device was called");
            Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
            Assertions.assertEquals(offeredBefore, rig.offeredText());
            Assertions.assertEquals(before, state(rig.snapshot()));
            return;
        }
        CallResult r = rig.budget.call(() -> row.call.apply(rig.device, rig.budget, called));
        Assertions.assertTrue(called[0]);
        Object[] lua = r.toLua();
        Assertions
            .assertTrue(Arrays.deepEquals(row.lua, lua), () -> describe(row.lua) + " expected, got " + describe(lua));
        Assertions.assertEquals(row.events, rig.budget.events());
        if (!r.isOk()) {
            Assertions.assertEquals(offeredBefore, rig.offeredText(), "a failing call offered a record");
            Assertions.assertEquals(before, state(rig.snapshot()), "a failing call changed the device");
        }
        if (row.after != null) {
            row.after.check(rig);
        }
    }

    @Test
    void getCapsIsTheCoreTableUncharged() {
        // 28:707: no charge; getCaps takes no budget at all. H6: the 15 :core keys
        for (int tier = 2; tier <= 3; tier++) {
            DeviceRig rig = DeviceRig.tier(tier);
            Assertions.assertEquals(
                DeviceCaps.forTier(tier)
                    .luaTable(),
                rig.device.getCaps());
            Assertions.assertEquals(
                DeviceCapsTest.KEYS,
                new ArrayList<>(
                    rig.device.getCaps()
                        .keySet()));
            Assertions.assertNotSame(rig.device.getCaps(), rig.device.getCaps());
            Assertions.assertEquals(Collections.emptyList(), rig.offeredText());
        }
        Assertions.assertEquals(
            "rgb565",
            DeviceRig.tier(3).device.getCaps()
                .get("format"));
        Assertions.assertEquals(
            32768,
            DeviceRig.tier(2).device.getCaps()
                .get("maxReadbackBytes"));
    }

    @Test
    void theTableCoversEveryCallbackAndHasUniqueRows() {
        List<String> names = new ArrayList<>();
        rows().forEach(r -> names.add(r.name));
        Assertions.assertEquals(
            names.size(),
            names.stream()
                .distinct()
                .count(),
            "duplicate row names");
        for (String callback : new String[] { "createTexture(", "updateTexture", "free(", "submit(", "present(",
            "readPixels(" }) {
            Assertions.assertTrue(
                names.stream()
                    .anyMatch(n -> n.startsWith(callback)),
                callback);
        }
        Assertions.assertTrue(names.size() >= 70, names.size() + " rows");
    }

    // ---- Calls through OC's argument conversion

    static Call create(Object w, Object h, String format) {
        return (device, budget, called) -> {
            int iw = OcArgs.checkInteger(0, w);
            int ih = OcArgs.checkInteger(1, h);
            called[0] = true;
            return device.createTexture(budget, iw, ih, format);
        };
    }

    static Call update(Object tex, Object x, Object y, Object w, Object h, byte[] data) {
        return (device, budget, called) -> {
            int it = OcArgs.checkInteger(0, tex);
            int ix = OcArgs.checkInteger(1, x);
            int iy = OcArgs.checkInteger(2, y);
            int iw = OcArgs.checkInteger(3, w);
            int ih = OcArgs.checkInteger(4, h);
            called[0] = true;
            return device.updateTexture(budget, it, ix, iy, iw, ih, data);
        };
    }

    static Call free(Object tex) {
        return (device, budget, called) -> {
            int it = OcArgs.checkInteger(0, tex);
            called[0] = true;
            return device.free(it);
        };
    }

    static Call read(Object x, Object y, Object w, Object h) {
        return (device, budget, called) -> {
            int ix = OcArgs.checkInteger(0, x);
            int iy = OcArgs.checkInteger(1, y);
            int iw = OcArgs.checkInteger(2, w);
            int ih = OcArgs.checkInteger(3, h);
            called[0] = true;
            return device.readPixels(budget, ix, iy, iw, ih);
        };
    }

    static Call submit(byte[] stream) {
        return (device, budget, called) -> {
            called[0] = true;
            return device.submit(budget, stream);
        };
    }

    static Call present() {
        return (device, budget, called) -> {
            called[0] = true;
            return device.present(budget);
        };
    }

    // ---- Row builders

    private static Row ok(String name, int tier, Setup setup, Call call, Object[] lua, String... events) {
        return new Row(name, tier, setup, call, lua, events(events), null, null);
    }

    private static Row fails(String name, int tier, Setup setup, Call call, String message, String... events) {
        return new Row(name, tier, setup, call, new Object[] { null, message }, events(events), null, null);
    }

    private static Row argError(String name, int tier, Setup setup, Call call, String message) {
        return new Row(name, tier, setup, call, null, events(), message, null);
    }

    private static List<String> events(String... events) {
        return Arrays.asList(events);
    }

    private static Object[] lua(Object... values) {
        return values;
    }

    private static Object[] handle(int h) {
        return new Object[] { Integer.valueOf(h) };
    }

    private static Object[] pixels(int n) {
        return new Object[] { new byte[n], Long.valueOf(0) };
    }

    private static String hexName(Object value) {
        double d = (Double) value;
        if (d == Math.rint(d) && d >= 0 && d <= Integer.MAX_VALUE) {
            return String.format(Locale.ROOT, "0x%08X", (int) d);
        }
        return String.valueOf(value);
    }

    private static String describe(Object[] lua) {
        if (lua == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < lua.length; i++) {
            Object v = lua[i];
            b.append(i == 0 ? "" : ", ")
                .append(
                    v instanceof byte[] ? ((byte[]) v).length + " B"
                        : v + (v == null ? ""
                            : " " + v.getClass()
                                .getSimpleName()));
        }
        return b.append('}')
            .toString();
    }

    /**
     * A device's whole observable state as one line: what a failing call must leave unchanged. The tests of this
     * package share it.
     */
    static String state(DeviceSnapshot s) {
        StringBuilder b = new StringBuilder();
        b.append("e")
            .append(s.epoch)
            .append(s.released ? " released" : "")
            .append(" last ")
            .append(s.lastFrameId)
            .append(" K ")
            .append(s.confirmedFrameId)
            .append(" pixels ")
            .append(String.format(Locale.ROOT, "%08X", RecordText.crc32(s.confirmedPixels())))
            .append(" tail ")
            .append(RecordText.of(s.tail))
            .append(" frames ")
            .append(s.unconfirmedFrames)
            .append(" queued ")
            .append(s.queuedBytes)
            .append(" vram ")
            .append(s.vramUsed)
            .append(" live ")
            .append(s.live)
            .append(" masters [");
        for (MasterTexture m : s.masters.values()) {
            b.append(m)
                .append(String.format(Locale.ROOT, " %08X; ", RecordText.crc32(m.texels())));
        }
        b.append("] free ")
            .append(s.freeSlots().length)
            .append('/')
            .append(Arrays.hashCode(s.freeSlots()))
            .append(" generations ")
            .append(Arrays.hashCode(s.generations()))
            .append(" open ")
            .append(s.openSegments.size())
            .append(' ')
            .append(s.openTotals)
            .append(" deferred ")
            .append(s.deferredFrees)
            .append(' ')
            .append(s.deferredCount)
            .append(' ')
            .append(s.deferredBytes)
            .append(" exhausted ")
            .append(s.budgetExhausted)
            .append(" readbacks ")
            .append(s.staleReadbacks)
            .append('/')
            .append(s.rejectedReadbacks);
        return b.toString();
    }
}
