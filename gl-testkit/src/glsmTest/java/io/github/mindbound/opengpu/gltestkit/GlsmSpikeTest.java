package io.github.mindbound.opengpu.gltestkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.lwjgl.opengl.GL11;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;

import io.github.mindbound.opengpu.gltestkit.glsmprobe.ProbeTarget;
import io.github.mindbound.opengpu.gltestkit.glsmprobe.R8uiClearProbe;
import io.github.mindbound.opengpu.gltestkit.glsmprobe.Rgba8ClearProbe;
import io.github.mindbound.opengpu.gltestkit.glsmprobe.StateProbe;

/**
 * The GLSM spike (roadmap M0, after S4; docs/research/23-gl-testing-strategy.md §2.3): Angelica's GLSM outside FML, on
 * glCoreTest's context, with OpenGPU's own GL-calling classes, the probes, loaded through GLSMRedirector by GlsmLoader.
 * It checks that the transform moves the probes' GL calls to GLStateManager, folding the scissor test's enable and
 * disable as in game; that the transformed probes read back byte-exact and equal to their raw copies on the same
 * context (23 §2.4); that GLSM's cache follows the redirected calls and is no pass-through; and what becomes of
 * glClearBufferu, which GLSM leaves unmapped: under unmappedGL=WARN, the in-game default, it stays a direct driver call
 * and clears exactly, and under FAIL the transform rejects its class. GLStateManager runs on the GL thread only
 * (GlsmExtension).
 */
@GlsmTest
class GlsmSpikeTest {

    private static final byte[] RGBA8_OUTSIDE = bytes(
        ProbeTarget.CLEAR_R,
        ProbeTarget.CLEAR_G,
        ProbeTarget.CLEAR_B,
        ProbeTarget.CLEAR_A);
    private static final byte[] RGBA8_INSIDE = bytes(255, 0, 0, 255);
    /** cacheFollowsTheRedirectedCalls' raw glClearColor, which GLSM does not see. */
    private static final byte[] RAW_GREEN = bytes(0, 255, 0, 255);
    private static final String CLEAR_BUFFERU = "org/lwjgl/opengl/GL30.glClearBufferu(IILjava/nio/IntBuffer;)V";
    private static final String[] PROBES = { ProbeTarget.class.getName(), Rgba8ClearProbe.class.getName(),
        R8uiClearProbe.class.getName(), StateProbe.class.getName() };

    @Test
    void reportsGlsm(GlsmInfo glsm, GlInfo gl, TestReporter reporter) {
        reporter.publishEntry(gl.asMap());
        reporter.publishEntry(glsm.asMap());
        Assertions.assertEquals("opengpu-gl", glsm.mainThread);
        Assertions.assertTrue(glsm.coreProfile, "GLSM saw no core profile");
        Assertions.assertEquals("WARN", glsm.unmappedGL, "the GLSM legs set angelica.unmappedGL=WARN");
    }

    @Test
    void redirectorMovesTheProbesCallsToGlsm() throws Exception {
        GlsmLoader loader = new GlsmLoader();
        String glsm = GLSMRedirector.getTargetClassName();
        Assertions.assertSame(
            GLStateManager.class,
            Class.forName(glsm.replace('/', '.'), false, loader),
            "the redirected calls must reach the GLStateManager GlsmExtension initialized");
        for (String probe : PROBES) {
            GlsmLoader.Transform transform = loader.transform(probe);
            Assertions.assertSame(
                loader,
                loader.loadClass(probe)
                    .getClassLoader(),
                probe);
            Assertions.assertFalse(transform.excluded, probe + ": a core exclusion matched");
            Assertions.assertTrue(transform.filtered, probe + ": the constant-pool filter held it back");
            List<String> before = glCalls(transform.original);
            Assertions.assertFalse(before.isEmpty(), probe + " makes no GL call");
            // A class whose GL calls are all unmapped, R8uiClearProbe's, comes through unchanged, byte for byte
            boolean onlyUnmapped = Collections.frequency(before, CLEAR_BUFFERU) == before.size();
            Assertions.assertEquals(!onlyUnmapped, transform.changed, probe + ": transformClassNode's result");
            if (onlyUnmapped) {
                Assertions.assertArrayEquals(transform.original, transform.defined, probe);
            }
            List<String> expected = new ArrayList<>();
            for (String call : before) {
                expected.add(redirected(call, glsm));
            }
            List<String> after = glCalls(transform.defined);
            Assertions.assertEquals(expected, after, probe);
            for (String call : after) {
                if (call.startsWith(glsm + ".")) {
                    assertLinks(call);
                }
            }
        }
        List<String> target = glCalls(loader.transform(ProbeTarget.class.getName()).defined);
        Assertions.assertTrue(target.contains(glsm + ".enableScissorTest()V"), target.toString());
        Assertions.assertTrue(target.contains(glsm + ".disableScissorTest()V"), target.toString());
        Assertions.assertTrue(
            target.contains(glsm + ".glDisable(I)V [" + GL11.GL_DITHER + "]"),
            "dither is not a folded capability: " + target);
    }

    @Test
    void transformedClearIsExactAndMatchesTheRawClear(GlThread gl) throws Exception {
        Callable<byte[][]> probe = probe(new GlsmLoader(), Rgba8ClearProbe.class.getName());
        byte[][] viaGlsm = gl.call(probe);
        byte[][] raw = gl.call(new Rgba8ClearProbe());
        assertImage("GLSM", viaGlsm, 4, RGBA8_OUTSIDE, RGBA8_INSIDE);
        assertImage("raw", raw, 4, RGBA8_OUTSIDE, RGBA8_INSIDE);
        Assertions.assertArrayEquals(raw[0], viaGlsm[0], "glReadPixels, GLSM against raw");
        Assertions.assertArrayEquals(raw[1], viaGlsm[1], "glGetTexImage, GLSM against raw");
    }

    @Test
    void cacheFollowsTheRedirectedCalls(GlThread gl, TestReporter reporter) throws Exception {
        GlsmLoader loader = new GlsmLoader();
        Runnable leaveState = loader.instance(StateProbe.class.getName(), Runnable.class);
        Callable<byte[][]> clear = probe(loader, Rgba8ClearProbe.class.getName());
        String scissorBoxFrom = gl.call(() -> {
            leaveState.run();
            int[] viewport = { StateProbe.VX, StateProbe.VY, StateProbe.VW, StateProbe.VH };
            int[] box = { ProbeTarget.SX, ProbeTarget.SY, ProbeTarget.SW, ProbeTarget.SH };
            float[] colour = { ProbeTarget.CLEAR_R / 255f, ProbeTarget.CLEAR_G / 255f, ProbeTarget.CLEAR_B / 255f,
                ProbeTarget.CLEAR_A / 255f };
            Assertions.assertArrayEquals(viewport, GlsmExtension.glsmInts(GL11.GL_VIEWPORT), "GLSM: viewport");
            Assertions.assertArrayEquals(viewport, GlsmExtension.driverInts(GL11.GL_VIEWPORT), "driver: viewport");
            Assertions.assertArrayEquals(box, GlsmExtension.glsmInts(GL11.GL_SCISSOR_BOX), "GLSM: scissor box");
            Assertions.assertArrayEquals(box, GlsmExtension.driverInts(GL11.GL_SCISSOR_BOX), "driver: scissor box");
            float[] glsmColour = GlsmExtension.glsmFloats(GL11.GL_COLOR_CLEAR_VALUE);
            float[] driverColour = GlsmExtension.driverFloats(GL11.GL_COLOR_CLEAR_VALUE);
            Assertions.assertArrayEquals(colour, glsmColour, "GLSM: clear colour");
            Assertions.assertArrayEquals(colour, driverColour, "driver: clear colour");
            Assertions.assertTrue(GLStateManager.glIsEnabled(GL11.GL_SCISSOR_TEST), "GLSM: scissor test");
            Assertions.assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), "driver: scissor test");
            Assertions.assertEquals(Collections.emptyList(), GlsmExtension.outOfSync());
            // Where GLSM answers a scissor-box query from shows behind its back: the cache from 2.2.21, the driver
            // before (25 R-1). Recorded, not asserted.
            GL11.glScissor(0, 0, 1, 1);
            String from = Arrays.equals(box, GlsmExtension.glsmInts(GL11.GL_SCISSOR_BOX)) ? "cache" : "driver";
            GL11.glScissor(ProbeTarget.SX, ProbeTarget.SY, ProbeTarget.SW, ProbeTarget.SH);
            // The defaults again through GLSM, all but the clear colour, and then a glClearColor GLSM does not see
            GLStateManager.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
            GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
            GLStateManager.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
            GL11.glClearColor(0f, 1f, 0f, 1f);
            List<String> drift = GlsmExtension.outOfSync();
            Assertions.assertEquals(1, drift.size(), drift.toString());
            Assertions.assertTrue(
                drift.get(0)
                    .startsWith("clear colour:"),
                drift.toString());
            return from;
        });
        // The probe's first glClearColor matches GLSM's cache, so GLSM skips it and the full clear shows the raw green;
        // its second differs and reaches the driver. Only calls that go through GLSM come out this way.
        byte[][] reads = gl.call(clear);
        assertImage("GLSM after a raw glClearColor", reads, 4, RAW_GREEN, RGBA8_INSIDE);
        reporter.publishEntry("glsm.scissorBoxFrom", scissorBoxFrom);
        System.out.println("[opengpu-glsm] glsm.scissorBoxFrom=" + scissorBoxFrom);
    }

    @Test
    void glClearBufferuStaysADirectCallAndClearsExactly(GlThread gl) throws Exception {
        Map<String, String> mapped = GLSMRedirector.getGLPrefixMethodRedirects();
        for (String name : new String[] { "glClearBuffer", "glClearBufferu", "glClearBufferfi" }) {
            Assertions.assertFalse(mapped.containsKey(name), name + " is mapped");
        }
        // The other ClearBuffer names are GL43's buffer-object clears, which GLSM maps
        Assertions.assertTrue(mapped.containsKey("glClearBufferData"), "glClearBufferData");
        Assertions.assertTrue(mapped.containsKey("glClearBufferSubData"), "glClearBufferSubData");
        GlsmLoader loader = new GlsmLoader();
        Callable<byte[][]> probe = probe(loader, R8uiClearProbe.class.getName());
        List<String> calls = glCalls(loader.transform(R8uiClearProbe.class.getName()).defined);
        Assertions.assertEquals(2, Collections.frequency(calls, CLEAR_BUFFERU), calls.toString());
        byte[][] viaGlsm = gl.call(probe);
        byte[][] raw = gl.call(new R8uiClearProbe());
        byte[] outside = bytes(R8uiClearProbe.OUTSIDE);
        byte[] inside = bytes(R8uiClearProbe.INSIDE);
        assertImage("GLSM", viaGlsm, 1, outside, inside);
        assertImage("raw", raw, 1, outside, inside);
        Assertions.assertArrayEquals(raw[0], viaGlsm[0], "glReadPixels, GLSM against raw");
        Assertions.assertArrayEquals(raw[1], viaGlsm[1], "glGetTexImage, GLSM against raw");
    }

    @Test
    void unmappedGlFailRejectsTheGlClearBufferuProbe() throws Exception {
        URL angelica = GLSMRedirector.class.getProtectionDomain()
            .getCodeSource()
            .getLocation();
        try (FailModeLoader fail = new FailModeLoader(angelica)) {
            Class<?> properties = fail.systemProperties();
            Assertions.assertNotSame(SystemProperties.class, properties);
            Assertions.assertEquals(
                "FAIL",
                String.valueOf(
                    properties.getField("UNMAPPED_GL")
                        .get(null)));
            Class<?> type = Class.forName(GLSMRedirector.class.getName(), true, fail);
            Assertions.assertNotSame(GLSMRedirector.class, type);
            Object redirector = type.getConstructor()
                .newInstance();
            Method transform = type.getMethod("transformClassNode", String.class, ClassNode.class);
            String mapped = Rgba8ClearProbe.class.getName();
            Assertions.assertEquals(Boolean.TRUE, transform.invoke(redirector, mapped, node(mapped)), mapped);
            String unmapped = R8uiClearProbe.class.getName();
            ClassNode unmappedNode = node(unmapped);
            InvocationTargetException thrown = Assertions.assertThrows(
                InvocationTargetException.class,
                () -> transform.invoke(redirector, unmapped, unmappedNode));
            Assertions.assertInstanceOf(IllegalStateException.class, thrown.getCause());
            Assertions.assertEquals(
                "Unmapped GL call " + CLEAR_BUFFERU + " in " + unmapped,
                thrown.getCause()
                    .getMessage());
        }
    }

    /**
     * A second GLSMRedirector and SystemProperties, from the same Angelica jar, set up under angelica.unmappedGL=FAIL:
     * GLSM reads the mode once, into a static final (SystemProperties.java:26 at 2.2.8 and 2.2.28), so the leg's WARN
     * copy cannot change. Loading a jar's own redirector apart is also how 25 §4's linkage test can check each jar.
     */
    private static final class FailModeLoader extends URLClassLoader {

        private static final String MODE = "angelica.unmappedGL";
        private static final String REDIRECT = "com.gtnewhorizons.angelica.glsm.redirect.";
        private static final String CONFIG = "com.gtnewhorizons.angelica.config.";

        FailModeLoader(URL angelica) {
            super(new URL[] { angelica }, GlsmSpikeTest.class.getClassLoader());
        }

        /** This loader's SystemProperties, initialized under FAIL before its GLSMRedirector reads it. */
        Class<?> systemProperties() throws ClassNotFoundException {
            String previous = System.getProperty(MODE);
            System.setProperty(MODE, "FAIL");
            try {
                return Class.forName(SystemProperties.class.getName(), true, this);
            } finally {
                if (previous == null) {
                    System.clearProperty(MODE);
                } else {
                    System.setProperty(MODE, previous);
                }
            }
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(REDIRECT) && !name.startsWith(CONFIG)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Callable<byte[][]> probe(GlsmLoader loader, String name) throws ReflectiveOperationException {
        Callable<byte[][]> probe = loader.instance(name, Callable.class);
        Assertions.assertSame(
            loader,
            probe.getClass()
                .getClassLoader(),
            name);
        return probe;
    }

    /**
     * A class's calls to LWJGL's GL classes and to GLStateManager, in order, as owner.name+descriptor; a glEnable or
     * glDisable gets its int constant appended in brackets when one comes right before the call, where GLSM looks.
     */
    private static List<String> glCalls(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        String glsm = GLSMRedirector.getTargetClassName();
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!call.owner.startsWith("org/lwjgl/opengl/") && !call.owner.equals(glsm)) {
                    continue;
                }
                String text = call.owner + "." + call.name + call.desc;
                Integer constant = constantBefore(call);
                if ((call.name.equals("glEnable") || call.name.equals("glDisable")) && constant != null) {
                    text += " [" + constant + "]";
                }
                calls.add(text);
            }
        }
        return calls;
    }

    private static Integer constantBefore(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        if (previous instanceof IntInsnNode) {
            return ((IntInsnNode) previous).operand;
        }
        if (previous instanceof LdcInsnNode && ((LdcInsnNode) previous).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) previous).cst;
        }
        return null;
    }

    /**
     * What GLSMRedirector should make of one of the probes' calls: the scissor test's enable and disable fold to GLSM's
     * no-argument methods (GLSMRedirector.java:185-193 and :850-879 at 2.2.8), glClearBufferu stays where it is
     * (unmapped, 24 §3.7), and every other call keeps its name and descriptor on GLStateManager.
     */
    private static String redirected(String call, String glsm) {
        if (call.equals(CLEAR_BUFFERU)) {
            return call;
        }
        String member = call.substring(call.indexOf('.') + 1);
        if (member.equals("glEnable(I)V [" + GL11.GL_SCISSOR_TEST + "]")) {
            return glsm + ".enableScissorTest()V";
        }
        if (member.equals("glDisable(I)V [" + GL11.GL_SCISSOR_TEST + "]")) {
            return glsm + ".disableScissorTest()V";
        }
        return glsm + "." + member;
    }

    /**
     * A redirected call must link: a public static GLStateManager method of that name and descriptor (25 §4, step 3).
     */
    private static void assertLinks(String call) throws ClassNotFoundException {
        String member = call.substring(call.indexOf('.') + 1)
            .replaceAll(" \\[.*]$", "");
        String name = member.substring(0, member.indexOf('('));
        String descriptor = member.substring(member.indexOf('('));
        Type[] arguments = Type.getArgumentTypes(descriptor);
        Class<?>[] parameters = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = classOf(arguments[i]);
        }
        Method method;
        try {
            method = GLStateManager.class.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(call + " has no GLStateManager method to link to", e);
        }
        Assertions.assertTrue(Modifier.isStatic(method.getModifiers()), call + " is not static");
        Assertions.assertEquals(classOf(Type.getReturnType(descriptor)), method.getReturnType(), call);
    }

    private static Class<?> classOf(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.VOID:
                return void.class;
            case Type.BOOLEAN:
                return boolean.class;
            case Type.BYTE:
                return byte.class;
            case Type.CHAR:
                return char.class;
            case Type.SHORT:
                return short.class;
            case Type.INT:
                return int.class;
            case Type.FLOAT:
                return float.class;
            case Type.LONG:
                return long.class;
            case Type.DOUBLE:
                return double.class;
            case Type.ARRAY:
                return Class.forName(
                    type.getDescriptor()
                        .replace('/', '.'),
                    false,
                    GlsmSpikeTest.class.getClassLoader());
            default:
                return Class.forName(type.getClassName(), false, GlsmSpikeTest.class.getClassLoader());
        }
    }

    private static ClassNode node(String name) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(GlsmLoader.bytes(GlsmSpikeTest.class.getClassLoader(), name)).accept(node, 0);
        return node;
    }

    /** Every byte of both readbacks: {@code inside} in the scissor box, {@code outside} elsewhere; rows bottom-up. */
    private static void assertImage(String what, byte[][] reads, int bytesPerPixel, byte[] outside, byte[] inside) {
        String[] via = { "glReadPixels", "glGetTexImage" };
        Assertions.assertEquals(via.length, reads.length, what);
        for (int r = 0; r < reads.length; r++) {
            for (int y = 0; y < ProbeTarget.H; y++) {
                for (int x = 0; x < ProbeTarget.W; x++) {
                    boolean in = x >= ProbeTarget.SX && x < ProbeTarget.SX + ProbeTarget.SW
                        && y >= ProbeTarget.SY
                        && y < ProbeTarget.SY + ProbeTarget.SH;
                    byte[] want = in ? inside : outside;
                    for (int c = 0; c < bytesPerPixel; c++) {
                        int got = reads[r][(y * ProbeTarget.W + x) * bytesPerPixel + c] & 0xFF;
                        if (got != (want[c] & 0xFF)) {
                            Assertions.fail(
                                what + ", "
                                    + via[r]
                                    + ": pixel ("
                                    + x
                                    + ", "
                                    + y
                                    + ") byte "
                                    + c
                                    + " is "
                                    + got
                                    + ", expected "
                                    + (want[c] & 0xFF));
                        }
                    }
                }
            }
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
