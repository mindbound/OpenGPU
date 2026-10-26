package io.github.mindbound.opengpu.card;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.ArgRows;
import io.github.mindbound.opengpu.core.fixtures.device.ArgRows.NumberKind;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.M0Replay;
import io.github.mindbound.opengpu.core.fixtures.device.MappingRows;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import io.github.mindbound.opengpu.render.Installation;

/**
 * Callback parity with {@code :ocelot-harness} on the root's side (S11 plan P15.4; amendments B1, F-1, F-2): each call
 * through OC's own dispatch and its real {@code ArgumentsImpl} on the card (OcCalls), with OC's retry of a call over
 * budget, gives what {@code :core}'s M0Replay, the model the harness's CallReplay holds ocelot's entity to, gives on a
 * {@code DeviceRig} with the same arguments: the values Lua gets, of the same Java classes, or OC's argument error. The
 * card runs on direct folds and a ManualClock, as the rig does, so nothing depends on timing. The rows are
 * {@code :core}'s:
 * <ul>
 * <li>ArgRows' integer rows in every integer argument, for both number kinds, and its byte and string rows, on T2
 * under NoRenderHost, as the harness's ArgumentRowsTest runs them;</li>
 * <li>MappingRows on T2 on a FakeRenderHost running the tier-E oracle, whose frame is not uniform, so swapped
 * arguments read other bytes (F-2), and whose 16 x 4 texture sees createTexture's and updateTexture's order;</li>
 * <li>a charges script whose consume, limit and pause events on the card's context equal RecordingBudget's on the rig
 * (P9; 28:1077).</li>
 * </ul>
 * {@code getCaps} is compared on its parity part, Device's 15 keys plus {@code renderer} (F-1); the root's other keys
 * are CardEnvironmentTest's. {@code bind} is the card's alone (M0Callbacks).
 */
@Timeout(60)
class CallbackDispatchParityTest {

    // 28:920's rows in every integer argument of readPixels, createTexture, updateTexture and free (28:1129)
    @ParameterizedTest
    @EnumSource(NumberKind.class)
    void everyIntegerRowInEveryIntegerArgumentIsTheReplays(NumberKind kind) throws Exception {
        Side side = Side.noRenderer();
        Map<String, Integer> handles = side.setUp(kind);
        int calls = 0;
        for (ArgRows.Site site : ArgRows.integerSites()) {
            for (ArgRows.Value row : ArgRows.integerRows()) {
                side.assertSame(site.with(row), kind, handles);
                calls++;
            }
        }
        Assertions.assertEquals(84, calls, "12 integer arguments, 7 rows each");
    }

    // A byte string with bytes of 0x80 and above, a String, a number, nil and no value, in the three string arguments
    @Test
    void everyByteAndStringRowIsTheReplays() throws Exception {
        for (NumberKind kind : NumberKind.values()) {
            Side side = Side.noRenderer();
            Map<String, Integer> handles = side.setUp(kind);
            for (ArgRows.Site site : ArgRows.byteSites()) {
                for (ArgRows.Value row : ArgRows.byteRows()) {
                    side.assertSame(site.with(row), kind, handles);
                }
            }
            // Of the rows, submit accepted only the byte string's segment: the String's bytes are no segment
            Assertions.assertEquals(
                1,
                side.card.device()
                    .snapshot().openSegments.size(),
                kind.toString());
        }
    }

    // F-2: a corner of frame 1 and NoRendererTest's asymmetric rows see readPixels' argument order
    @Test
    void mappingRowsAreTheReplaysAndReachWhatTheyName() throws Exception {
        Side side = Side.fake();
        Map<String, Integer> handles = new HashMap<>();
        for (MappingRows.Row row : MappingRows.rows()) {
            Object[] values = side.assertSame(row.call, NumberKind.LONG, handles);
            if (row.message == null) {
                Assertions.assertNotNull(values[0], row + " is valid");
            } else {
                Assertions.assertEquals(2, values.length, row.toString());
                Assertions.assertNull(values[0], row.toString());
                Assertions.assertEquals(row.message, values[1], row.toString());
            }
            if (row.call.keep != null) {
                handles.put(row.call.keep, (Integer) values[0]);
            }
            if (row.step) {
                side.step();
            }
        }
        Assertions.assertEquals(
            3L,
            side.card.device()
                .snapshot().lastFrameId,
            "frames 1 to 3");
    }

    // P9: the card's charges through OC's Context are the rig's through RecordingBudget, OC's retry and Device's
    // two-phase pause included (28:710-713, :1077)
    @Test
    void theChargesScriptChargesAsTheReplay() throws Exception {
        Side side = Side.fake();
        side.assertSame("submit", TestEncoder.segment(TestEncoder.fill(1, 1, 8, 8, 3)));
        side.assertSame("present");
        // Confirmed, so the tail is empty and a 1 MiB texture is not "busy" (H2)
        side.step();
        side.assertSame("readPixels", 0L, 0L, 64L, 32L);
        side.assertSame("getCaps");
        Object[] big = side.assertSame("createTexture", 1024L, 1024L);
        side.assertSame("free", ((Integer) big[0]).longValue());
        Object[] small = side.assertSame("createTexture", 64L, 64L);
        side.assertSame("updateTexture", ((Integer) small[0]).longValue(), 0L, 0L, 64L, 64L, new byte[4096]);
        List<String> expected = side.rig.budget.events();
        Assertions.assertTrue(expected.contains("limit"), expected::toString);
        Assertions.assertTrue(expected.contains("pause 0.100000000"), expected::toString);
        Assertions.assertEquals(expected, side.context.charges());
    }

    /** A card and a rig of one tier and host kind, called alike. */
    private static final class Side {

        final CardRig cards;
        final CardEnvironment card;
        final DeviceRig rig;
        final RecordingContext context = new RecordingContext();

        private Side(CardRig cards, DeviceRig rig) {
            this.cards = cards;
            this.card = cards.card(2);
            this.rig = rig;
        }

        /** T2 under NoRenderHost, as the harness's ArgumentRowsTest. */
        static Side noRenderer() {
            return new Side(new CardRig(Installation.dedicated(), Runnable::run), DeviceRig.noRenderer(2));
        }

        /** T2 on a FakeRenderHost running the tier-E oracle, with direct folds and a ManualClock, on both. */
        static Side fake() {
            return new Side(new CardRig(), DeviceRig.tier(2));
        }

        /** ArgRows.SETUP on both: the handle the rows name. */
        Map<String, Integer> setUp(NumberKind kind) throws Exception {
            Map<String, Integer> handles = new HashMap<>();
            Object[] made = assertSame(ArgRows.SETUP, kind, handles);
            handles.put(ArgRows.SETUP.keep, (Integer) made[0]);
            return handles;
        }

        void step() {
            cards.host.step();
            rig.step();
        }

        /** The call with the arguments OC delivers on {@code kind}, a fresh array for each side. */
        Object[] assertSame(ArgRows.Call call, NumberKind kind, Map<String, Integer> handles) throws Exception {
            return assertSame(call.callback, call.java(kind, handles), call.java(kind, handles), call + " on " + kind);
        }

        /** The call with these values, as 5.3 delivers them; a byte array is copied for the rig. */
        Object[] assertSame(String callback, Object... args) throws Exception {
            Object[] copy = args.clone();
            for (int i = 0; i < copy.length; i++) {
                if (copy[i] instanceof byte[]) {
                    copy[i] = ((byte[]) copy[i]).clone();
                }
            }
            return assertSame(callback, copy, args, callback + describe(args));
        }

        /**
         * Fails unless the card's values for {@code forCard} equal the replay's for {@code forRig}, getCaps on its
         * parity part, or both raise the same argument error. Returns the card's values, empty after an error.
         */
        private Object[] assertSame(String callback, Object[] forRig, Object[] forCard, String what) throws Exception {
            Object[] replayed = null;
            String replay;
            try {
                replayed = M0Replay.run(rig, callback, forRig);
                replay = describe(replayed);
            } catch (IllegalArgumentException e) {
                replay = "error " + e.getMessage();
            }
            Object[] values;
            String dispatched;
            try {
                values = OcCalls.callWithRetry(card, callback, context, forCard);
                dispatched = describe(parityPart(values, replayed));
            } catch (IllegalArgumentException e) {
                values = new Object[0];
                dispatched = "error " + e.getMessage();
            }
            Assertions.assertEquals(replay, dispatched, what);
            return values;
        }

        /** getCaps' parity part (F-1): the card's table cut to the replay's keys, in the card's order. */
        private static Object[] parityPart(Object[] values, Object[] replayed) {
            if (replayed == null || replayed.length != 1
                || !(replayed[0] instanceof Map)
                || values.length != 1
                || !(values[0] instanceof Map)) {
                return values;
            }
            Map<?, ?> keys = (Map<?, ?>) replayed[0];
            Map<Object, Object> part = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) values[0]).entrySet()) {
                if (keys.containsKey(e.getKey())) {
                    part.put(e.getKey(), e.getValue());
                }
            }
            return new Object[] { part };
        }
    }

    /** Values as Lua sees them, with their Java classes: equal descriptions are equal values of equal types. */
    static String describe(Object[] values) {
        List<String> out = new ArrayList<>();
        for (Object v : values) {
            out.add(describe(v));
        }
        return out.toString();
    }

    private static String describe(Object v) {
        if (v == null) {
            return "nil";
        }
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            return "byte[" + b.length + "] " + sha(b);
        }
        if (v instanceof String) {
            return "String \"" + v + "\"";
        }
        if (v instanceof Map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                entries.add(e.getKey() + "=" + describe(e.getValue()));
            }
            return "table " + entries;
        }
        return v.getClass()
            .getSimpleName() + " "
            + v;
    }

    /** The first 8 bytes of the SHA-256, in hex. */
    private static String sha(byte[] b) {
        byte[] d;
        try {
            d = MessageDigest.getInstance("SHA-256")
                .digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            s.append(String.format(Locale.ROOT, "%02x", d[i] & 0xFF));
        }
        return s.toString();
    }
}
