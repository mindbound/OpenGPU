package io.github.mindbound.opengpu.card;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import io.github.mindbound.opengpu.render.Installation;
import li.cil.oc.api.API;
import li.cil.oc.api.detail.Builder;
import li.cil.oc.api.detail.NetworkAPI;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.Visibility;

/**
 * The card's callbacks, charges, getCaps keys, tick, NBT and node (S11 plan P7-P10, P15.3; amendments B1, B4, B5),
 * called through OC's own scan, dispatch and argument reader (OcCalls) on a RecordingContext. Each mapping is checked
 * against the same call on a {@code DeviceRig}, the device's own answer, on a FakeRenderHost running the tier-E oracle
 * or on NoRenderHost. Only {@link #ocsNodeBuilderGetsNeighborsAndOpengpuWithNoConnector} sets an OC API static, and
 * restores it.
 */
@Timeout(60)
class CardEnvironmentTest {

    /** The shortest legal segment (28 §3.1): the header and END. */
    private static final byte[] NO_OP = TestEncoder.segment();

    /** Lua's view of a CallResult, for comparing the card's answer with the device's. */
    private static String lua(Object[] values) {
        return Arrays.deepToString(values);
    }

    private static String lua(CallResult result) {
        return lua(result.toLua());
    }

    private static final CallBudget FREE = new CallBudget() {

        @Override
        public void consume(double cost) {}

        @Override
        public void pause(double seconds) {}
    };

    // 28:84: every M0 callback is direct but bind, which reads OC's network
    @Test
    void ocFindsTheEightCallbacksAllDirectButBindWithTheDefaultLimit() throws Exception {
        Map<String, Callback> callbacks = OcCalls.callbacks(new CardRig().card(2));
        Assertions.assertEquals(
            Arrays
                .asList("bind", "createTexture", "free", "getCaps", "present", "readPixels", "submit", "updateTexture"),
            new ArrayList<>(callbacks.keySet()));
        for (Map.Entry<String, Callback> e : callbacks.entrySet()) {
            Assertions.assertEquals(
                !"bind".equals(e.getKey()),
                e.getValue()
                    .direct(),
                e.getKey());
            Assertions.assertEquals(
                Integer.MAX_VALUE,
                e.getValue()
                    .limit(),
                e.getKey());
        }
    }

    // 28:84: checkByteArray hands over the per-call array itself, which Device owns
    @Test
    void submitHandsTheDeviceOcsArrayAndAnswersAsTheDevice() throws Exception {
        CardEnvironment card = new CardRig().card(2);
        DeviceRig rig = DeviceRig.tier(2);
        byte[] segment = TestEncoder.segment(TestEncoder.clear(5));
        Object[] accepted = OcCalls.call(card, "submit", new RecordingContext(), (Object) segment);
        Assertions.assertEquals(lua(rig.device.submit(FREE, segment.clone())), lua(accepted));
        Assertions.assertSame(
            segment,
            card.device()
                .snapshot().openSegments.get(0),
            "the array OC handed over");
        byte[] shortSegment = { 1, 2, 3 };
        Object[] rejected = OcCalls.call(card, "submit", new RecordingContext(), (Object) shortSegment);
        Assertions.assertNull(rejected[0]);
        Assertions.assertEquals(lua(rig.device.submit(FREE, shortSegment)), lua(rejected));
    }

    @Test
    void presentGivesALongFrameIdOrNoRenderer() throws Exception {
        CardEnvironment fake = new CardRig().card(3);
        Assertions.assertEquals(
            Collections.singletonList(1L),
            Arrays.asList(OcCalls.call(fake, "present", new RecordingContext())));
        Object[] second = OcCalls.call(fake, "present", new RecordingContext());
        Assertions.assertEquals(Long.valueOf(2), second[0]);
        Assertions.assertSame(Long.class, second[0].getClass());
        CardEnvironment none = new CardRig(Installation.dedicated(), Runnable::run).card(3);
        Assertions.assertEquals("[null, no renderer]", lua(OcCalls.call(none, "present", new RecordingContext())));
    }

    // A corner filled on T2, so swapped arguments read other bytes; and NoRendererTest's asymmetric rows (F-2)
    @Test
    void readPixelsPassesXYWHInThatOrder() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.card(2);
        DeviceRig rig = DeviceRig.tier(2);
        byte[] corner = TestEncoder.segment(TestEncoder.fill(0, 0, 3, 2, 9));
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "submit", context, (Object) corner);
        OcCalls.call(card, "present", context);
        cards.host.step();
        rig.submit(corner.clone());
        rig.present();
        rig.step();
        Object[] region = OcCalls.call(card, "readPixels", context, 1.0, 0.0, 4.0, 3.0);
        Assertions.assertEquals(lua(rig.device.readPixels(FREE, 1, 0, 4, 3)), lua(region));
        Assertions.assertNotEquals(lua(rig.device.readPixels(FREE, 0, 1, 3, 4)), lua(region), "the swapped region");
        Assertions.assertEquals("[null, invalid region]", lua(OcCalls.call(card, "readPixels", context, 0, 200, 1, 1)));
        Assertions.assertEquals(Long.valueOf(1), OcCalls.call(card, "readPixels", context, 200, 0, 1, 1)[1]);
        Assertions.assertEquals(Long.valueOf(1), OcCalls.call(card, "readPixels", context, 319, 199, 1, 1)[1]);
    }

    @Test
    void texturesAnswerAsTheDevice() throws Exception {
        CardEnvironment card = new CardRig().card(2);
        DeviceRig rig = DeviceRig.tier(2);
        RecordingContext context = new RecordingContext();
        Object[] made = OcCalls.call(card, "createTexture", context, 4, 4);
        int handle = rig.device.createTexture(FREE, 4, 4, null)
            .handle();
        Assertions.assertEquals(Collections.singletonList(handle), Arrays.asList(made));
        Assertions.assertSame(Integer.class, made[0].getClass(), "a handle crosses as an Integer (24:30)");
        Assertions.assertEquals(
            lua(rig.device.createTexture(FREE, 4, 4, "rgb565")),
            lua(OcCalls.call(card, "createTexture", context, 4, 4, "rgb565")));
        Assertions.assertEquals(
            lua(rig.device.createTexture(FREE, 2, 2, "index8")),
            lua(OcCalls.call(card, "createTexture", context, 2, 2, "index8")));
        byte[] data = new byte[16];
        Assertions.assertEquals(
            lua(rig.device.updateTexture(FREE, handle, 0, 0, 4, 4, data.clone())),
            lua(OcCalls.call(card, "updateTexture", context, handle, 0, 0, 4, 4, data)));
        Assertions.assertEquals(
            lua(rig.device.updateTexture(FREE, handle, 0, 0, 4, 4, new byte[15])),
            lua(OcCalls.call(card, "updateTexture", context, handle, 0, 0, 4, 4, new byte[15])));
        Assertions.assertEquals(lua(rig.device.free(handle)), lua(OcCalls.call(card, "free", context, handle)));
        Assertions.assertEquals(lua(rig.device.free(handle)), lua(OcCalls.call(card, "free", context, handle)));
        // A 16 x 4 texture, so swapped arguments give another texture or region: createTexture's (w, h) and
        // updateTexture's (x, y, w, h) order, as readPixelsPassesXYWHInThatOrder sees readPixels' (S11 review)
        Object[] wide = OcCalls.call(card, "createTexture", context, 16, 4);
        int wideHandle = rig.device.createTexture(FREE, 16, 4, null)
            .handle();
        Assertions.assertEquals(Collections.singletonList(wideHandle), Arrays.asList(wide));
        Object[][] regions = { { 10, 0, 6, 1, new byte[6] }, { 0, 10, 1, 6, new byte[6] },
            { 0, 0, 16, 4, new byte[64] } };
        List<String> answers = new ArrayList<>();
        for (Object[] r : regions) {
            Object[] args = { wideHandle, r[0], r[1], r[2], r[3], ((byte[]) r[4]).clone() };
            String answer = lua(OcCalls.call(card, "updateTexture", context, args));
            Assertions.assertEquals(
                lua(
                    rig.device.updateTexture(
                        FREE,
                        wideHandle,
                        (Integer) r[0],
                        (Integer) r[1],
                        (Integer) r[2],
                        (Integer) r[3],
                        (byte[]) r[4])),
                answer);
            answers.add(answer);
        }
        Assertions.assertEquals(Arrays.asList("[true]", "[null, invalid texture region]", "[true]"), answers);
    }

    // 28:291: OC raises its own argument errors in Lua; nothing is charged or changed
    @Test
    void argumentErrorsAreOcsAndChargeNothing() throws Exception {
        CardEnvironment card = new CardRig().card(3);
        RecordingContext context = new RecordingContext();
        IllegalArgumentException word = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> OcCalls.call(card, "readPixels", context, "x", 0, 1, 1));
        Assertions.assertEquals("bad argument #1 (integer expected, got string)", word.getMessage());
        IllegalArgumentException none = Assertions
            .assertThrows(IllegalArgumentException.class, () -> OcCalls.call(card, "submit", context));
        Assertions.assertEquals("bad arguments #1 (string expected, got no value)", none.getMessage());
        IllegalArgumentException format = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> OcCalls.call(card, "createTexture", context, 4, 4, 5.0));
        Assertions.assertEquals("bad argument #3 (string expected, got number)", format.getMessage());
        Assertions.assertEquals(Collections.emptyList(), context.charges());
        Assertions.assertTrue(
            card.device()
                .snapshot().live.isEmpty());
    }

    // 28:1077: Device's first two-phase attempt changes nothing and becomes OC's exception, so OC retries
    @Test
    void coresLimitReachedBecomesOcsLimitReachedException() throws Exception {
        CardEnvironment card = new CardRig().card(2);
        RecordingContext context = new RecordingContext();
        Assertions
            .assertThrows(LimitReachedException.class, () -> OcCalls.call(card, "createTexture", context, 1024, 1024));
        Assertions.assertTrue(
            card.device()
                .snapshot().live.isEmpty(),
            "nothing changed");
        Assertions.assertEquals(Collections.emptyList(), context.charges(), "nothing charged");
        // The machine repeats it synchronized on the next tick, where Device pauses for the rest (28:710-713)
        context.setSynchronized(true);
        Object[] made = OcCalls.call(card, "createTexture", context, 1024, 1024);
        context.setSynchronized(false);
        Assertions.assertEquals(Integer.valueOf(0x08000000), made[0]);
        Assertions.assertEquals(Collections.singletonList("pause 0.100000000"), context.charges());
    }

    // S11 spike P1: OC's own exception from consumeCallBudget passes out of the callback untouched
    @Test
    void ocsOwnLimitReachedExceptionPassesOutUntouched() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.card(3);
        RecordingContext empty = new RecordingContext(null, 0.0);
        Assertions.assertThrows(LimitReachedException.class, () -> OcCalls.call(card, "submit", empty, NO_OP));
        Assertions.assertThrows(LimitReachedException.class, () -> OcCalls.call(card, "present", empty));
        Assertions.assertThrows(LimitReachedException.class, () -> OcCalls.call(card, "readPixels", empty, 0, 0, 1, 1));
        DeviceSnapshot after = card.device()
            .snapshot();
        Assertions.assertTrue(after.openSegments.isEmpty());
        Assertions.assertEquals(0, after.lastFrameId);
        Assertions.assertEquals(Collections.emptyList(), cards.host.offered(card.deviceKey()), "nothing offered");
    }

    // P9: the charges OC sees are :core's model, the harness's ChargesTest pins through real OC (28:1130)
    @Test
    void theChargesFollowCoresModel() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.card(2);
        RecordingContext context = new RecordingContext();
        DeviceRig rig = DeviceRig.tier(2);
        byte[] segment = TestEncoder.segment(TestEncoder.fill(1, 1, 8, 8, 3));
        OcCalls.callWithRetry(card, "submit", context, (Object) segment);
        rig.call(b -> rig.device.submit(b, segment.clone()));
        OcCalls.callWithRetry(card, "present", context);
        rig.call(b -> rig.device.present(b));
        // Confirmed, so the tail is empty and a 1 MiB texture is not "busy" (H2)
        cards.host.step();
        rig.step();
        OcCalls.callWithRetry(card, "readPixels", context, 0, 0, 64, 32);
        rig.call(b -> rig.device.readPixels(b, 0, 0, 64, 32));
        int handle = (Integer) OcCalls.callWithRetry(card, "createTexture", context, 1024, 1024)[0];
        int rigHandle = rig.call(b -> rig.device.createTexture(b, 1024, 1024, null))
            .handle();
        Assertions.assertEquals(rigHandle, handle);
        OcCalls.callWithRetry(card, "free", context, handle);
        rig.device.free(rigHandle);
        int small = (Integer) OcCalls.callWithRetry(card, "createTexture", context, 64, 64)[0];
        rig.call(b -> rig.device.createTexture(b, 64, 64, null));
        OcCalls.callWithRetry(card, "updateTexture", context, small, 0, 0, 64, 64, new byte[4096]);
        rig.call(b -> rig.device.updateTexture(b, small, 0, 0, 64, 64, new byte[4096]));
        OcCalls.callWithRetry(card, "getCaps", context);
        List<String> expected = rig.budget.events();
        Assertions.assertTrue(expected.contains("limit"), expected::toString);
        Assertions.assertTrue(expected.contains("pause 0.100000000"), expected::toString);
        Assertions.assertEquals(expected, context.charges());
    }

    @Test
    void everyCallbackSetsTheContext() throws Exception {
        CardEnvironment card = new CardRig().card(3);
        Object[][] calls = { { "getCaps" }, { "submit", NO_OP }, { "present" }, { "readPixels", 0, 0, 1, 1 },
            { "createTexture", 2, 2 }, { "updateTexture", 0x08000000, 0, 0, 1, 1, new byte[2] },
            { "free", 0x08000000 } };
        for (Object[] call : calls) {
            RecordingContext context = new RecordingContext();
            OcCalls.call(card, (String) call[0], context, Arrays.copyOfRange(call, 1, call.length));
            Assertions.assertSame(context, card.context(), (String) call[0]);
        }
    }

    // 24:125 and P10 on an available host: the 15 keys, renderer "host-gl", readback; no graphics, no hostRenderer
    @Test
    void getCapsOnAnAvailableHost() throws Exception {
        CardEnvironment card = new CardRig().card(3);
        Map<?, ?> caps = caps(card);
        Assertions.assertEquals(keys(card, "renderer", "readback"), order(caps));
        Assertions.assertEquals("host-gl", caps.get("renderer"));
        Assertions.assertEquals(Boolean.TRUE, caps.get("readback"));
        Map<String, Object> device = card.device()
            .getCaps();
        for (Map.Entry<String, Object> e : device.entrySet()) {
            Assertions.assertEquals(e.getValue(), caps.get(e.getKey()), e.getKey());
        }
    }

    @Test
    void getCapsGivesTheCachedHostRenderer() throws Exception {
        CardEnvironment card = new CardRig(Installation.of(new FakeRenderHost(), "off", "Test GL 3.3"), Runnable::run)
            .card(2);
        Map<?, ?> caps = caps(card);
        Assertions.assertEquals(keys(card, "renderer", "readback", "hostRenderer"), order(caps));
        Assertions.assertEquals("Test GL 3.3", caps.get("hostRenderer"));
    }

    // 24:125: a dedicated server has no renderer, with its reason (24:228)
    @Test
    void getCapsOnADedicatedServer() throws Exception {
        CardEnvironment card = new CardRig(Installation.dedicated(), Runnable::run).card(3);
        Map<?, ?> caps = caps(card);
        Assertions.assertEquals(keys(card, "renderer", "readback", "graphics"), order(caps));
        Assertions.assertEquals("none", caps.get("renderer"));
        Assertions.assertEquals(Boolean.TRUE, caps.get("readback"));
        Assertions.assertEquals("dedicated server", caps.get("graphics"));
    }

    // 24:125, :230: an integrated server whose client has no graphics gives that client's GL reason, and its probed
    // GL_RENDERER, so 19 keys (Q-S12-7 (a), a departure from S11 plan P10's 18 for NoRenderHost)
    @Test
    void getCapsOnAnIntegratedServerWhoseClientHasNoGraphicsGivesItsReasonAndRenderer() throws Exception {
        CardEnvironment card = new CardRig(
            Installation.of(NoRenderHost.INSTANCE, "host GPU below GL 3.3", "Test GL 2.1"),
            Runnable::run).card(2);
        Map<?, ?> caps = caps(card);
        Assertions.assertSame(NoRenderHost.INSTANCE, card.renderHost());
        Assertions.assertEquals(keys(card, "renderer", "readback", "graphics", "hostRenderer"), order(caps));
        Assertions.assertEquals("none", caps.get("renderer"));
        Assertions.assertEquals("host GPU below GL 3.3", caps.get("graphics"));
        Assertions.assertEquals("Test GL 2.1", caps.get("hostRenderer"));
    }

    // P10: a GL host that has become unavailable shows all 19 names (28:692)
    @Test
    void getCapsOnAHostThatIsNoLongerAvailableShowsNineteenKeys() throws Exception {
        FakeRenderHost host = new FakeRenderHost();
        CardEnvironment card = new CardRig(
            Installation.of(host, "disabled after GPU timeout", "Test GL"),
            Runnable::run).card(3);
        host.setAvailable(false);
        Map<?, ?> caps = caps(card);
        Assertions.assertEquals(19, caps.size(), caps::toString);
        Assertions.assertEquals("none", caps.get("renderer"));
        Assertions.assertEquals("disabled after GPU timeout", caps.get("graphics"));
        Assertions.assertEquals("Test GL", caps.get("hostRenderer"));
    }

    // 28:707: getCaps and free charge nothing beyond OC's 1/limit, which OC's machine adds
    @Test
    void getCapsAndFreeChargeNothing() throws Exception {
        CardEnvironment card = new CardRig().card(2);
        RecordingContext context = new RecordingContext();
        int handle = (Integer) OcCalls.call(card, "createTexture", new RecordingContext(), 2, 2)[0];
        OcCalls.call(card, "getCaps", context);
        OcCalls.call(card, "free", context, handle);
        Assertions.assertEquals(Collections.emptyList(), context.charges());
    }

    // 28:1076; H12: update() is the device's tick, which reports "dropped" 1 s after a present; D16: the status is
    // signalled at the next tick's start, the tick queue's drain
    @Test
    void updateTicksTheDevice() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "present", context);
        Assertions.assertTrue(card.canUpdate());
        card.update();
        Assertions.assertEquals(0, cards.drain());
        Assertions.assertEquals(Collections.emptyList(), context.signals());
        cards.clock.advanceMillis(1000);
        card.update();
        Assertions.assertEquals(Collections.emptyList(), context.signals(), "before the drain");
        Assertions.assertEquals(1, cards.drain());
        Assertions.assertEquals(
            1,
            context.signals()
                .size());
        Assertions.assertEquals(
            Arrays.asList(
                CardEnvironment.SIGNAL,
                card.node()
                    .address(),
                1L,
                "dropped")
                .toString(),
            signalText(
                context.signals()
                    .get(0)));
    }

    // Q-S11-6: the card saves the bound display's address; no device state is saved (24:179)
    @Test
    void saveAndLoadKeepTheDisplaysAddressAndNoDeviceState() {
        CardEnvironment card = new CardRig().card(3);
        NBTTagCompound saved = new NBTTagCompound();
        saved.setString(CardEnvironment.DISPLAY, "opengpu_screen-9");
        card.load(saved);
        Assertions.assertEquals("opengpu_screen-9", card.boundAddress());
        NBTTagCompound written = new NBTTagCompound();
        card.save(written);
        Assertions.assertEquals(new LinkedHashSet<>(Arrays.asList("node", "opengpu:display")), keySet(written));
        Assertions.assertEquals("opengpu_screen-9", written.getString("opengpu:display"));

        CardEnvironment fresh = new CardRig().card(3);
        fresh.load(new NBTTagCompound());
        Assertions.assertNull(fresh.boundAddress());
        NBTTagCompound none = new NBTTagCompound();
        fresh.save(none);
        Assertions.assertEquals(Collections.singleton("node"), keySet(none));
    }

    // 24:29: a Neighbors component named opengpu, so only its own machine sees it
    @Test
    void theCardAsksItsFactoryForANeighborsComponentNamedOpengpu() {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.card(2);
        Assertions.assertEquals(Collections.singletonList("CardEnvironment Neighbors opengpu"), cards.nodes.requests());
        Assertions.assertSame(
            card,
            card.node()
                .host());
    }

    // Seam (a) of spike §6 item 2, once: NodeFactory.OC's builder calls under a stub OC network API (amendments B4)
    @Test
    void ocsNodeBuilderGetsNeighborsAndOpengpuWithNoConnector() {
        List<String> calls = new ArrayList<>();
        FakeNodes nodes = new FakeNodes();
        NetworkAPI saved = API.network;
        try {
            Assertions.assertNull(
                NodeFactory.OC.component(null, Visibility.Neighbors, "opengpu"),
                "no node while OC's network API is null");
            API.network = proxy(NetworkAPI.class, (self, method, args) -> {
                if (!method.getName()
                    .equals("newNode")) {
                    throw new UnsupportedOperationException(method.getName());
                }
                Environment host = (Environment) args[0];
                calls.add(
                    "newNode " + host.getClass()
                        .getSimpleName() + " " + args[1]);
                return proxy(Builder.NodeBuilder.class, (b, m, a) -> {
                    calls.add(m.getName() + (a == null ? "" : " " + Arrays.toString(a)));
                    if (!m.getName()
                        .equals("withComponent")) {
                        throw new UnsupportedOperationException(m.getName());
                    }
                    return proxy(Builder.ComponentBuilder.class, (c, n, x) -> {
                        calls.add(n.getName());
                        if (!n.getName()
                            .equals("create")) {
                            throw new UnsupportedOperationException(n.getName());
                        }
                        return nodes.component(host, (Visibility) args[1], (String) a[0]);
                    });
                });
            });
            CardRig cards = new CardRig();
            CardEnvironment card = new CardEnvironment(
                2,
                new CardDeps(cards.installation, Runnable::run, cards.clock, () -> 1, 1, NodeFactory.OC, cards.ticks));
            Assertions.assertEquals(
                Arrays.asList("newNode CardEnvironment Neighbors", "withComponent [opengpu]", "create"),
                calls);
            Assertions.assertTrue(card.node() instanceof Component);
            Assertions.assertSame(
                card,
                card.node()
                    .host());
        } finally {
            API.network = saved;
        }
        Assertions.assertNull(API.network);
    }

    private static Map<?, ?> caps(CardEnvironment card) throws Exception {
        return (Map<?, ?>) OcCalls.call(card, "getCaps", new RecordingContext())[0];
    }

    /** The device's 15 keys in order, then the given ones. */
    private static List<String> keys(CardEnvironment card, String... more) {
        List<String> keys = new ArrayList<>(
            card.device()
                .getCaps()
                .keySet());
        Assertions.assertEquals(15, keys.size());
        keys.addAll(Arrays.asList(more));
        return keys;
    }

    /** The table's keys in its order. */
    private static List<Object> order(Map<?, ?> caps) {
        return new ArrayList<>(caps.keySet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> keySet(NBTTagCompound tag) {
        return new LinkedHashSet<>((Set<String>) tag.func_150296_c());
    }

    /** "[name, arg, ...]" with the arguments as given. */
    static String signalText(RecordingContext.Signal signal) {
        List<Object> parts = new ArrayList<>();
        parts.add(signal.name);
        parts.addAll(Arrays.asList(signal.args));
        return parts.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(CardEnvironmentTest.class.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
