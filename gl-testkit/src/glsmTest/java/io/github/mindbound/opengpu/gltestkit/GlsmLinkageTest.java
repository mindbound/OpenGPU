package io.github.mindbound.opengpu.gltestkit;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 25 §4's per-jar linkage test (M0 step S9; 24:248's unmappedGL=FAIL redirector run), on the legs gl-testkit's
 * glsmLinkageTest passes: Angelica's floor 2.2.8, 25 §6's CI-only 2.2.21, the pack's 2.2.28 and the latest release,
 * Nexus's {@code <release>} at build time. Its JVM holds no Angelica jar. Each leg's dev jar gets a class loader of its
 * own whose parent is this class's, so every Angelica class comes from that jar, and Guava, GTNHLib, log4j, ASM and the
 * Launch stand-in from the shared classpath (S9 plan P3.1, P6). Each loader initializes its own SystemProperties under
 * the task's {@code -Dangelica.unmappedGL=FAIL}, then its own GLSMRedirector. Nothing loads GLStateManager, which needs
 * Minecraft and LWJGL: its methods are read from the jar ({@link GlBytecode#publicStatics}), so no GL context, no
 * natives and no GLSM start are needed (23:82 (3)). A leg that cannot be set up fails its own tests only. This class
 * names no Angelica type, so it loads where none is.
 * <p>
 * On each leg: the jar is the release the build resolved (L1); every shipped class goes through the leg's redirector
 * as in game without a throw, and no LWJGL name is left in any of them but D01's lookup (L2; 25 §4 step 4); every
 * rewritten call is a public static method of that jar's GLStateManager with the call's descriptor, under the jar's
 * own folds (L3; step 3); and from the CI-only leg on, the jar folds :gl's capabilities as the floor does (L4).
 * Synthetic classes, transformed and scanned but never defined, so LWJGL never loads, show that each check can fail
 * (S9 plan P10, critic A2): FAIL rejects an unmapped call and A09 (N1), a rewritten call with no GLSM method and a jar
 * without glPolygonMode fail the link check (N2, N3), a fold set without GL_BLEND fails the expected calls (N4), a
 * second LWJGL class literal or a second lookup in D01's method is reported (N5), and so are the calls FAIL lets
 * through: a GLSync descriptor and a debug-output call (N6).
 */
@Tag("glsm-linkage")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlsmLinkageTest {

    /** The legs in glsmLinkageTest's order. */
    private static final List<String> LEG_NAMES = Collections
        .unmodifiableList(Arrays.asList("floor", "ci-only", "pack", "latest"));
    private static final String SYSTEM_PROPERTIES = "com.gtnewhorizons.angelica.config.SystemProperties";
    private static final String REDIRECTOR = "com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector";
    /** The base entries S9 reads (S9 plan P2.1); a Java 8 client loads these, so each must be major 52. */
    private static final List<String> ENTRIES = Collections.unmodifiableList(
        Arrays.asList(
            REDIRECTOR.replace('.', '/') + ".class",
            "com/gtnewhorizons/angelica/glsm/GLStateManager.class",
            SYSTEM_PROPERTIES.replace('.', '/') + ".class"));
    /** The multi-release copies lwjgl3ify's Java 17 and 21 clients load (25 §5.1; LK L9). */
    private static final int[] RELEASES = { 17, 21 };
    /** The version of mcmod.info's angelica entry; the jar also holds NotFine's, Embeddium's and MCPatcherForge's. */
    private static final Pattern MOD_VERSION = Pattern
        .compile("\"modid\"\\s*:\\s*\"angelica\"[^}]*?\"version\"\\s*:\\s*\"([^\"]*)\"");
    /**
     * The shipped classes S9 scans, the prefix and an anchor class: :gl's, gl.host's included, which the root shades
     * unrelocated (H51; S9 plan P8.1). The root's own client classes join when the TESR step adds them: from the root's
     * built jar, passed to the task as a file input and read with ZipFile, never the root's classpath in this JVM, and
     * with the owners of that jar's getNamedClassMethodRedirects followed, X01's OpenGlHelper (S9 plan P8.2, critic
     * A10).
     */
    private static final String[] SHIPPED = { "io/github/mindbound/opengpu/gl/",
        "io/github/mindbound/opengpu/gl/GlBackend.class" };
    /** :gl's class of GL constants only, which no instruction of its takes from LWJGL (S9 critic A7). */
    private static final String GL_FORMATS = "io.github.mindbound.opengpu.gl.GlFormats";
    /** The rows L3 names, by their GLStateManager members: C02 (GL_VENDOR, Q-S8-3), C99 (H41), C100 (S8's fix loop). */
    private static final Map<String, String> ROWS = rows();
    private static final String POLYGON_MODE = "glPolygonMode(II)V";
    /** GL_BLEND, F01's capability. */
    private static final int BLEND = 0x0BE2;
    /** What the log and linkage.txt say, in order. */
    private static final List<String> REPORT = Collections.synchronizedList(new ArrayList<String>());

    private static List<Leg> legs;
    private static Map<String, byte[]> shippedClasses;

    /** glsmLinkageTest's legs, read once; without its properties this fails and never skips. */
    static synchronized List<Leg> legs() {
        if (legs == null) {
            String names = System.getProperty("opengpu.linkage.legs");
            if (names == null || names.isEmpty()) {
                throw new IllegalStateException("no opengpu.linkage.legs: run through :gl-testkit:glsmLinkageTest");
            }
            List<Leg> list = new ArrayList<>();
            for (String name : names.split(",")) {
                list.add(new Leg(name));
            }
            legs = Collections.unmodifiableList(list);
        }
        return legs;
    }

    /** The legs after the floor, which L4 compares with it. */
    static List<Leg> laterLegs() {
        List<Leg> all = legs();
        return all.subList(1, all.size());
    }

    @Test
    @Order(0)
    void theTestJvmHoldsNoAngelica(TestReporter reporter) throws IOException {
        ClassLoader own = GlsmLinkageTest.class.getClassLoader();
        for (String entry : ENTRIES) {
            Assertions.assertNull(
                own.getResource(entry),
                entry + " is on the test's own classpath, so every leg would get its classes from there");
        }
        List<Leg> all = legs();
        List<String> names = new ArrayList<>();
        for (Leg leg : all) {
            names.add(leg.name);
        }
        Assertions.assertEquals(LEG_NAMES, names, "the legs glsmLinkageTest passed");
        for (int i = 0; i < all.size(); i++) {
            Leg leg = all.get(i);
            Assertions.assertTrue(
                leg.jar.isFile() && leg.jar.getName()
                    .endsWith(".jar"),
                leg + ": no jar at " + leg.jar);
            report(
                reporter,
                "linkage." + leg.name,
                "linkage " + leg.name
                    + ": Angelica "
                    + leg.version
                    + " ("
                    + leg.sourceText()
                    + ") "
                    + leg.jar.getName());
            // Not a failure: the latest release can be the pack's, or -Popengpu.angelica.latest a pinned one
            for (int j = 0; j < i; j++) {
                Leg earlier = all.get(j);
                if (earlier.jar.getCanonicalFile()
                    .equals(leg.jar.getCanonicalFile())) {
                    report(
                        reporter,
                        "linkage." + leg.name + ".sameAs",
                        "linkage " + leg.name + " = " + earlier.name + " " + earlier.version + ": one jar, two legs");
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(1)
    void theJarIsTheResolvedRelease(Leg leg, TestReporter reporter) throws Exception {
        try (ZipFile zip = new ZipFile(leg.jar)) {
            Assertions.assertEquals(leg.version, modVersion(zip), leg + ": mcmod.info's angelica version");
            for (String entry : ENTRIES) {
                Assertions.assertEquals(52, major(zip, entry), leg + ": the class file major of " + entry);
            }
        }
        Class<?> redirector = leg.redirector();
        Assertions.assertEquals(
            leg.jar.getCanonicalFile(),
            new File(
                redirector.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).getCanonicalFile(),
            leg + ": where its GLSMRedirector came from");
        for (Leg other : legs()) {
            Class<?> theirs = other == leg ? null : other.redirectorOrNull();
            Assertions.assertNotSame(theirs, redirector, leg + " and " + other + " share one GLSMRedirector");
        }
        Assertions.assertEquals(
            "FAIL",
            String.valueOf(
                leg.systemProperties()
                    .getField("UNMAPPED_GL")
                    .get(null)),
            leg + ": unmappedGL in its own SystemProperties (glsmLinkageTest sets -Dangelica.unmappedGL=FAIL)");
        String target = leg.target();
        List<Integer> copies = new ArrayList<>();
        try (ZipFile zip = new ZipFile(leg.jar)) {
            Assertions.assertNotNull(zip.getEntry(target + ".class"), leg + ": no " + target + ".class");
            for (int release : RELEASES) {
                if (zip.getEntry(versioned(release, target)) != null) {
                    copies.add(release);
                }
            }
        }
        Set<String> statics = leg.statics();
        Assertions.assertFalse(statics.isEmpty(), leg + ": " + target + " has no public static method");
        // LK L9's re-check: the Java 17 and 21 copies declare the same public static methods as the base, each read
        // from the entry a JVM of that release loads, which is asserted, so a reader that falls back to the base
        // cannot pass for the re-check (S9 review TS2)
        List<String> differences = new ArrayList<>();
        List<String> compared = new ArrayList<>();
        for (int release : RELEASES) {
            List<String> read = new ArrayList<>();
            Set<String> copy = GlBytecode.publicStatics(leg.jar, target, release, read);
            Assertions.assertEquals(
                copies.contains(release) ? versioned(release, target) : target + ".class",
                read.get(0),
                leg + ": the " + target + " a Java " + release + " JVM loads");
            if (copies.contains(release)) {
                compared.add("versions/" + release);
            }
            for (String method : statics) {
                if (!copy.contains(method)) {
                    differences.add("Java " + release + " lacks " + method);
                }
            }
            for (String method : copy) {
                if (!statics.contains(method)) {
                    differences.add("only Java " + release + " has " + method);
                }
            }
        }
        Assertions
            .assertEquals(new ArrayList<String>(), differences, leg + ": " + differences.size() + " difference(s)");
        report(
            reporter,
            "linkage." + leg.name + ".glsm",
            "linkage " + leg.name
                + ": "
                + target
                + " has "
                + statics.size()
                + " public static methods, the same in "
                + (compared.isEmpty() ? "no multi-release copy" : String.join(" and ", compared))
                + "; folds "
                + foldText(leg.folds()));
    }

    /**
     * 25 §4 step 4 as 24:248's FAIL run: every shipped class through the leg's redirector, each checked on its own. A
     * class that calls GL must pass the constant-pool filter and be changed; any other comes out byte for byte as it
     * went in. GlFormats, which only takes GL constants, holds no LWJGL name (S9 critic A7). In :gl's jar it passes the
     * filter on javac's orphan LWJGL class entries, which the root's reobfuscated jar, the one that ships, drops, so in
     * game the filter skips it. Either way it comes out unchanged, and only that is asserted (S9 review F2). No class
     * may be excluded, and none may hold an LWJGL name after the transform but PassState's D01 lookup, once (25:695).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(2)
    void everyShippedClassTransformsUnderFail(Leg leg, TestReporter reporter) throws Exception {
        String target = leg.target();
        Map<Integer, String> folds = leg.folds();
        List<String> problems = new ArrayList<>();
        Set<String> calling = new TreeSet<>();
        Set<String> changed = new TreeSet<>();
        List<String> lookups = new ArrayList<>();
        int constantsOnly = 0;
        int folded = 0;
        for (Shipped c : leg.shipped()) {
            if (c.thrown != null) {
                problems.add(c.name + ": " + c.thrown);
                continue;
            }
            GlsmLoader.Transform t = c.transform;
            if (t.excluded) {
                problems.add(c.name + ": a core exclusion matched, so in game the redirector leaves it as it is");
            }
            List<String> before = GlBytecode.calls(c.original, target);
            if (!before.isEmpty()) {
                calling.add(c.name);
                if (!t.filtered || !t.changed) {
                    problems.add(c.name + ": calls GL, but filtered " + t.filtered + ", changed " + t.changed);
                }
            } else if (t.changed || !Arrays.equals(t.original, t.defined)) {
                problems.add(c.name + ": calls no GL, but the redirector changed it");
            } else if (t.filtered) {
                constantsOnly++;
            }
            if (t.changed) {
                changed.add(c.name);
            }
            for (String call : before) {
                Integer cap = GlBytecode.capability(call.substring(call.indexOf('.') + 1));
                if (cap != null && folds.containsKey(cap)) {
                    folded++;
                }
            }
            Scan scan = scan(c.name, t.defined);
            problems.addAll(scan.problems);
            for (int i = 0; i < scan.lookups; i++) {
                lookups.add(c.name);
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), problems, leg + ": " + problems.size() + " problem(s)");
        Assertions.assertEquals(
            Collections.singletonList(GlBytecode.DRIVER_DIRECT[0]),
            lookups,
            leg + ": the classes holding D01's lookup (25:695)");
        Assertions.assertFalse(calling.isEmpty(), leg + ": no shipped class calls GL");
        Assertions
            .assertEquals(calling, changed, leg + ": the classes the redirector changed, against those calling GL");
        Shipped formats = leg.shipped(GL_FORMATS);
        Assertions.assertEquals(
            new ArrayList<String>(),
            GlBytecode.lwjglReferences(formats.original),
            GL_FORMATS + " takes LWJGL's GL constants only");
        Assertions.assertFalse(formats.transform.changed, leg + ": the redirector changed " + GL_FORMATS);
        report(
            reporter,
            "linkage." + leg.name + ".classes",
            "linkage " + leg.name
                + ": "
                + leg.shipped()
                    .size()
                + " classes of :gl under unmappedGL=FAIL, "
                + changed.size()
                + " rewritten, "
                + (leg.shipped()
                    .size() - changed.size())
                + " unchanged ("
                + constantsOnly
                + " through the constant-pool filter in :gl's jar on javac's orphan LWJGL class entries alone,"
                + " which the reobfuscated root jar drops), "
                + folded
                + " folds, "
                + lookups.size()
                + " driver-direct lookup left as it is");
    }

    /**
     * 25 §4 step 3 on the leg's own jar: in each class that calls GL, the calls on GLStateManager after the transform
     * are its GL calls in order, each of the same name and descriptor but a glEnable or glDisable of a capability in
     * that jar's glCapRedirects, which becomes its enable/disable method (25 §4.1's target column; 25:239), and each is
     * a public static method of that jar's GLStateManager with that descriptor. A target a later jar renames fails
     * here by design, though 25 §4 step 3 would let it link: {@link #linkage} expects each call's own name, so the fix
     * is 25 §4.1's target column and that jar's name in linkage() (25:546; S9 review F3). The number of distinct
     * targets is recorded per leg and compared with no other leg's: a new fold changes it without a link failure (S9
     * critic A3), which L4 reports.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(3)
    void everyRewrittenCallLinks(Leg leg, TestReporter reporter) throws Exception {
        String target = leg.target();
        Set<String> statics = leg.statics();
        Linkage linkage = linkage(leg.shipped(), target, leg.folds(), statics);
        Assertions.assertEquals(
            new ArrayList<String>(),
            linkage.problems,
            leg + ": "
                + linkage.problems.size()
                + " problem(s); a target this jar renames needs 25 §4.1's target column and this jar's name in"
                + " GlsmLinkageTest.linkage()");
        for (Map.Entry<String, String> row : ROWS.entrySet()) {
            Assertions.assertTrue(
                linkage.targets.contains(row.getValue()) && statics.contains(row.getValue()),
                leg + ": " + row.getKey() + " " + target + "." + row.getValue() + " is not among the linked targets");
        }
        report(
            reporter,
            "linkage." + leg.name + ".links",
            "linkage " + leg.name
                + ": "
                + linkage.sites
                + " rewritten call sites to "
                + linkage.targets.size()
                + " distinct "
                + target
                + " methods, each public static with the call's descriptor; "
                + String.join(", ", ROWS.keySet())
                + " among them");
    }

    /**
     * Every capability :gl passes glEnable or glDisable as a constant is folded on this jar exactly when it is on the
     * floor's, whose set CallSetTest's FOLDED and callset.txt's F rows encode. A fold of a capability :gl does not use
     * is logged, not failed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("laterLegs")
    @Order(4)
    void foldsMatchTheFloor(Leg leg, TestReporter reporter) throws Exception {
        Leg floor = legs().get(0);
        Map<Integer, String> floorFolds = floor.folds();
        Map<Integer, String> folds = leg.folds();
        Set<Integer> used = new TreeSet<>();
        for (byte[] classFile : shippedClasses().values()) {
            for (String call : GlBytecode.calls(classFile, floor.target())) {
                Integer cap = GlBytecode.capability(call.substring(call.indexOf('.') + 1));
                if (cap != null) {
                    used.add(cap);
                }
            }
        }
        Assertions.assertFalse(used.isEmpty(), "no glEnable or glDisable of a constant in :gl's classes");
        List<String> problems = new ArrayList<>();
        List<String> usedText = new ArrayList<>();
        for (int cap : used) {
            usedText.add(String.format("0x%04X", cap));
            if (floorFolds.containsKey(cap) != folds.containsKey(cap)) {
                problems.add(
                    String.format(
                        "0x%04X is %s on %s but %s on %s",
                        cap,
                        floorFolds.containsKey(cap) ? "folded" : "not folded",
                        floor,
                        folds.containsKey(cap) ? "folded" : "not folded",
                        leg));
            }
        }
        Assertions.assertEquals(
            new ArrayList<String>(),
            problems,
            leg + ": update callset.txt's F and C rows and 25 §4.1 (25:239)");
        Set<Integer> all = new TreeSet<>(floorFolds.keySet());
        all.addAll(folds.keySet());
        List<String> others = new ArrayList<>();
        for (int cap : all) {
            if (!used.contains(cap) && !Objects.equals(floorFolds.get(cap), folds.get(cap))) {
                others.add(String.format("0x%04X %s on the floor, %s here", cap, floorFolds.get(cap), folds.get(cap)));
            }
        }
        report(
            reporter,
            "linkage." + leg.name + ".folds",
            "linkage " + leg.name
                + ": folds :gl's "
                + used.size()
                + " capabilities "
                + usedText
                + " as the floor does; "
                + (others.isEmpty() ? "no other fold differs" : "for capabilities :gl does not use, " + others));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(5)
    void failRejectsAnUnmappedCall(Leg leg) throws Exception {
        // A name no redirect map can hold
        assertRejected(leg, "synthetic.Unmapped", GlBytecode.GL_OWNER + "GL11", "glNotInGlsm", "()V", "");
    }

    /** A09, unmapped from 2.2.8 to 2.2.32 (24:197) and by source at 2.2.36, as its own test, so a red run names it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(6)
    void a09StaysUnmapped(Leg leg) throws Exception {
        assertRejected(
            leg,
            "synthetic.ClearBufferu",
            GlBytecode.GL_OWNER + "GL30",
            "glClearBufferu",
            "(IILjava/nio/IntBuffer;)V",
            "; if this jar maps A09, update 24:197 and 25 §4.2's A09 row");
    }

    /** glViewport is mapped by name, but no GLStateManager method can have this descriptor. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(7)
    void aRewrittenCallWithoutAGlsmMethodFailsTheLinkCheck(Leg leg) throws Exception {
        String name = "synthetic.Viewport";
        byte[] original = callOf(name, GlBytecode.GL_OWNER + "GL11", "glViewport", "(Ljava/lang/Object;)V");
        GlsmLoader.Transform t = GlBytecode.redirect(leg.transformer(), name, original);
        Assertions.assertTrue(t.changed, leg + ": the redirector left " + name + " as it was");
        Linkage linkage = linkage(
            Collections.singletonList(new Shipped(name, original, t, null)),
            leg.target(),
            leg.folds(),
            leg.statics());
        Assertions.assertEquals(
            Collections.singletonList(name + ": no public static " + leg.target() + ".glViewport(Ljava/lang/Object;)V"),
            linkage.problems,
            leg.toString());
    }

    /** L3's check over the real shipped classes, on this jar's method list without C99: exactly C99's sites fail. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(8)
    void aJarWithoutGlPolygonModeFailsTheLinkCheck(Leg leg) throws Exception {
        String target = leg.target();
        Set<String> statics = new TreeSet<>(leg.statics());
        Assertions.assertTrue(statics.remove(POLYGON_MODE), leg + ": C99 " + POLYGON_MODE + " is no public static");
        List<String> expected = new ArrayList<>();
        for (Shipped c : leg.shipped()) {
            for (String call : GlBytecode.calls(c.original, target)) {
                if (call.equals(GlBytecode.GL_OWNER + "GL11." + POLYGON_MODE)) {
                    expected.add(c.name + ": no public static " + target + "." + POLYGON_MODE);
                }
            }
        }
        Assertions.assertFalse(expected.isEmpty(), "no C99 call in :gl's classes");
        Assertions
            .assertEquals(expected, linkage(leg.shipped(), target, leg.folds(), statics).problems, leg.toString());
    }

    /**
     * L3's expected calls on the floor without GL_BLEND's fold: exactly F01's sites, where disableBlend()V is found.
     */
    @Test
    @Order(9)
    void aFoldSetWithoutBlendFailsTheTargetCheck() throws Exception {
        Leg floor = legs().get(0);
        String target = floor.target();
        Map<Integer, String> folds = floor.folds();
        String blend = folds.remove(BLEND);
        Assertions.assertNotNull(blend, floor + " does not fold GL_BLEND (F01)");
        List<String> expected = new ArrayList<>();
        for (Shipped c : floor.shipped()) {
            List<String> before = GlBytecode.calls(c.original, target);
            for (int i = 0; i < before.size(); i++) {
                String member = before.get(i)
                    .substring(
                        before.get(i)
                            .indexOf('.') + 1);
                if (Integer.valueOf(BLEND)
                    .equals(GlBytecode.capability(member))) {
                    expected.add(
                        c.name + ": call "
                            + i
                            + ": expected "
                            + target
                            + "."
                            + member
                            + ", got "
                            + target
                            + "."
                            + (member.startsWith("glEnable") ? "enable" : "disable")
                            + blend
                            + "()V");
                }
            }
        }
        Assertions.assertFalse(expected.isEmpty(), "no F01 site in :gl's classes");
        Assertions.assertEquals(expected, linkage(floor.shipped(), target, folds, floor.statics()).problems);
    }

    /**
     * L2's scan: D01's lookup passes in PassState only and only in its own form, so a second lookup in its method, by
     * another literal, by D01's literal kept in a local or beside a second GL name, is reported (S9 review F1, TS1).
     */
    @Test
    @Order(10)
    void aSecondLwjglLiteralIsReported() {
        String passState = GlBytecode.DRIVER_DIRECT[0];
        String gl11 = GlBytecode.DRIVER_DIRECT[1];
        String d01 = GlBytecode.DRIVER_DIRECT[2];
        Scan own = scan(passState, lookupOf(passState, gl11, false, null, d01));
        Assertions.assertEquals(new ArrayList<String>(), own.problems, "D01's own lookup");
        Assertions.assertEquals(1, own.lookups, "D01's own lookup");
        String elsewhere = "io.github.mindbound.opengpu.gl.GlBackend";
        Scan[] scans = { scan(passState, lookupOf(passState, gl11, false, null, "glFlush")),
            scan(elsewhere, lookupOf(elsewhere, gl11, false, null, d01)),
            scan(passState, lookupOf(passState, gl11, false, null, d01, "glFinish")),
            scan(passState, lookupOf(passState, gl11, true, null, "glFinish", d01)),
            scan(passState, lookupOf(passState, gl11, false, "glFinish", d01)) };
        for (Scan s : scans) {
            Assertions.assertEquals(0, s.lookups, s.problems.toString());
            Assertions.assertFalse(s.problems.isEmpty(), "no problem");
            for (String problem : s.problems) {
                Assertions.assertTrue(problem.contains("only D01's lookup may"), s.problems.toString());
            }
        }
    }

    /**
     * What FAIL lets through without a throw (S9 critic A2) is still caught by L2's scan: A12's GL32.glFenceSync, whose
     * descriptor names GLSync, which the redirector's unmapped check skips and which keeps GLSync in its descriptor
     * even if a later jar maps the name; and a debug-output call, which the redirector only logs, reported when this
     * jar leaves it on LWJGL and, when the jar sends it elsewhere or removes it, recorded as that with no scan problem
     * (S9 review TS4).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(11)
    void aCallFailLetsThroughIsReported(Leg leg, TestReporter reporter) throws Exception {
        GlBytecode.Redirector glsm = leg.transformer();
        String fence = "synthetic.Fence";
        byte[] fenceClass = callOf(fence, GlBytecode.GL_OWNER + "GL32", "glFenceSync", "(II)Lorg/lwjgl/opengl/GLSync;");
        GlsmLoader.Transform fenced = Assertions
            .assertDoesNotThrow(() -> GlBytecode.redirect(glsm, fence, fenceClass), leg + ": glFenceSync under FAIL");
        Scan fenceScan = scan(fence, fenced.defined);
        Assertions.assertEquals(1, fenceScan.problems.size(), leg + ": " + fenceScan.problems);
        Assertions.assertTrue(
            fenceScan.problems.get(0)
                .contains("glFenceSync"),
            leg + ": " + fenceScan.problems);
        String debug = "synthetic.DebugOutput";
        String debugName = "glDebugMessageInsertARB";
        byte[] debugClass = callOf(
            debug,
            GlBytecode.GL_OWNER + "ARBDebugOutput",
            debugName,
            "(IIIILjava/nio/ByteBuffer;)V");
        GlsmLoader.Transform debugged = Assertions
            .assertDoesNotThrow(() -> GlBytecode.redirect(glsm, debug, debugClass), leg + ": a debug call under FAIL");
        List<String> debugCalls = callsNamed(debugged.defined, debugName);
        boolean debugLeft = false;
        for (String call : debugCalls) {
            debugLeft |= call.startsWith(GlBytecode.LWJGL);
        }
        Scan debugScan = scan(debug, debugged.defined);
        if (debugLeft) {
            Assertions.assertEquals(1, debugScan.problems.size(), leg + ": " + debugScan.problems);
            Assertions.assertTrue(
                debugScan.problems.get(0)
                    .contains(debugName),
                leg + ": " + debugScan.problems);
        } else {
            // Sent to GLStateManager or another owner, or removed: nothing of LWJGL's is left to report
            Assertions
                .assertEquals(new ArrayList<String>(), debugScan.problems, leg + ": the call became " + debugCalls);
        }
        report(
            reporter,
            "linkage." + leg.name + ".passThrough",
            "linkage " + leg.name
                + ": unmappedGL=FAIL lets GL32.glFenceSync (a GLSync descriptor) through, "
                + (fenced.changed ? "rewritten to " + leg.target() : "left on LWJGL")
                + ", and ARBDebugOutput."
                + debugName
                + ", "
                + (debugLeft ? "left on LWJGL"
                    : debugCalls.isEmpty() ? "removed" : "rewritten to " + String.join(", ", debugCalls))
                + "; the scan reports "
                + (debugLeft ? "both" : "the fence"));
    }

    @AfterAll
    static void writeReport() throws IOException {
        synchronized (GlsmLinkageTest.class) {
            if (legs != null) {
                for (Leg leg : legs) {
                    leg.close();
                }
            }
        }
        String dir = System.getProperty("opengpu.gl.reportDir");
        if (dir != null) {
            File file = new File(dir, "linkage.txt");
            file.getParentFile()
                .mkdirs();
            try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                synchronized (REPORT) {
                    for (String line : REPORT) {
                        out.write(line + "\n");
                    }
                }
            }
        }
    }

    // The checks

    /** What L2's scan finds in one class file. */
    static final class Scan {

        final List<String> problems = new ArrayList<>();
        /** D01's lookups: class literals in D01's form, in PassState ({@link GlBytecode#literalProblem}). */
        int lookups;
    }

    /**
     * L2's scan of one class file: every LWJGL name in it is a problem (25 §4 step 4: on 2.2.x none passes through),
     * but D01's lookup, which it counts.
     */
    static Scan scan(String name, byte[] classFile) {
        Scan scan = new Scan();
        List<String> left = new ArrayList<>(GlBytecode.lwjglReferences(classFile));
        for (GlBytecode.Literal literal : GlBytecode.literals(classFile)) {
            left.remove(GlBytecode.reference(literal));
            String problem = GlBytecode.literalProblem(name, literal);
            if (problem == null) {
                scan.lookups++;
            } else {
                scan.problems.add(problem);
            }
        }
        for (String reference : left) {
            scan.problems.add(name + ": " + reference + ", an LWJGL name the transform left");
        }
        return scan;
    }

    /** What L3's check finds in a set of classes. */
    static final class Linkage {

        final List<String> problems = new ArrayList<>();
        /** The distinct GLStateManager members the defined bytes call. */
        final Set<String> targets = new TreeSet<>();
        int sites;
    }

    /**
     * L3's check over {@code classes}: in each that calls GL, the calls on {@code target} after the transform must be
     * the original GL calls in order, each of the same name and descriptor but a glEnable or glDisable of a capability
     * in {@code folds}, which becomes enable/disable&lt;suffix&gt;()V, and each must be in {@code statics}. One problem
     * per call site.
     */
    static Linkage linkage(List<Shipped> classes, String target, Map<Integer, String> folds, Set<String> statics) {
        Linkage linkage = new Linkage();
        for (Shipped c : classes) {
            if (c.thrown != null) {
                linkage.problems.add(c.name + ": not transformed, " + c.thrown);
                continue;
            }
            List<String> before = GlBytecode.calls(c.original, target);
            if (before.isEmpty()) {
                continue;
            }
            List<String> expected = new ArrayList<>();
            for (String call : before) {
                String member = call.substring(call.indexOf('.') + 1);
                Integer cap = GlBytecode.capability(member);
                if (cap != null && folds.containsKey(cap)) {
                    expected.add(
                        target + "." + (member.startsWith("glEnable") ? "enable" : "disable") + folds.get(cap) + "()V");
                } else {
                    expected.add(target + "." + member);
                }
                if ((member.startsWith("glEnable(") || member.startsWith("glDisable(")) && cap == null) {
                    linkage.problems.add(c.name + ": " + member + " without a constant capability (25:239)");
                }
            }
            List<String> after = GlBytecode.calls(c.transform.defined, target);
            if (expected.size() != after.size()) {
                linkage.problems.add(c.name + ": expected " + expected + "\n  got " + after);
            } else {
                for (int i = 0; i < expected.size(); i++) {
                    if (!expected.get(i)
                        .equals(after.get(i))) {
                        linkage.problems
                            .add(c.name + ": call " + i + ": expected " + expected.get(i) + ", got " + after.get(i));
                    }
                }
            }
            for (String call : after) {
                if (!call.startsWith(target + ".")) {
                    linkage.problems.add(c.name + ": " + call + " is left on LWJGL");
                    continue;
                }
                String member = call.substring(target.length() + 1)
                    .replaceAll(" \\[.*]$", "");
                linkage.sites++;
                linkage.targets.add(member);
                if (!statics.contains(member)) {
                    linkage.problems.add(c.name + ": no public static " + target + "." + member);
                }
            }
        }
        return linkage;
    }

    /** That the redirector throws FAIL's own message for the one call of a synthetic class (S9 plan P2.5). */
    private static void assertRejected(Leg leg, String name, String owner, String method, String descriptor,
        String hint) throws Exception {
        GlBytecode.Redirector glsm = leg.transformer();
        byte[] original = callOf(name, owner, method, descriptor);
        IllegalStateException thrown = Assertions.assertThrows(
            IllegalStateException.class,
            () -> GlBytecode.redirect(glsm, name, original),
            leg + ": unmappedGL=FAIL let " + owner + "." + method + descriptor + " through" + hint);
        Assertions.assertEquals(
            "Unmapped GL call " + owner + "." + method + descriptor + " in " + name,
            thrown.getMessage(),
            leg + hint);
    }

    // Classes

    /** :gl's shipped classes, name to class file, as the test's classpath has them, read once. */
    static synchronized Map<String, byte[]> shippedClasses() throws IOException, URISyntaxException {
        if (shippedClasses == null) {
            Map<String, byte[]> classes = new LinkedHashMap<>();
            for (String name : GlBytecode.classes(SHIPPED[0], SHIPPED[1])) {
                classes.put(name, GlBytecode.bytes(GlsmLinkageTest.class.getClassLoader(), name));
            }
            Assertions.assertTrue(classes.size() >= 15, "only " + classes.size() + " classes under " + SHIPPED[0]);
            shippedClasses = Collections.unmodifiableMap(classes);
        }
        return shippedClasses;
    }

    /** A synthetic class {@code name} whose one static method calls {@code owner.method} with zeros and nulls. */
    private static byte[] callOf(String name, String owner, String method, String descriptor) {
        MethodNode m = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            m.instructions.add(zero(argument));
        }
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, descriptor, false));
        int size = Type.getReturnType(descriptor)
            .getSize();
        if (size > 0) {
            m.instructions.add(new InsnNode(size == 2 ? Opcodes.POP2 : Opcodes.POP));
        }
        return classOf(name, m);
    }

    /**
     * A synthetic class {@code name} whose one method looks each of {@code names} up on {@code owner} in D01's form,
     * MethodHandles.publicLookup().findStatic(Owner.class, name, MethodType.methodType(int.class)): each with a class
     * literal, or with {@code shared} all through one literal kept in a local. Then, unless it is null, it holds the
     * string {@code beside}.
     */
    private static byte[] lookupOf(String name, String owner, boolean shared, String beside, String... names) {
        MethodNode m = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        if (shared) {
            m.instructions.add(new LdcInsnNode(Type.getObjectType(owner)));
            m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        }
        for (String looked : names) {
            m.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/invoke/MethodHandles",
                    "publicLookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;",
                    false));
            m.instructions.add(shared ? new VarInsnNode(Opcodes.ALOAD, 0) : new LdcInsnNode(Type.getObjectType(owner)));
            m.instructions.add(new LdcInsnNode(looked));
            m.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;"));
            m.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/invoke/MethodType",
                    "methodType",
                    "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
                    false));
            m.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandles$Lookup",
                    "findStatic",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                    false));
            m.instructions.add(new InsnNode(Opcodes.POP));
        }
        if (beside != null) {
            m.instructions.add(new LdcInsnNode(beside));
            m.instructions.add(new InsnNode(Opcodes.POP));
        }
        return classOf(name, m);
    }

    /** Every call named {@code method} in {@code classFile}, on any owner, as owner.name+descriptor. */
    private static List<String> callsNamed(byte[] classFile, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals(method)) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }

    /** The entry of {@code owner}'s copy for a Java {@code release} JVM in a multi-release jar. */
    private static String versioned(int release, String owner) {
        return "META-INF/versions/" + release + "/" + owner + ".class";
    }

    private static AbstractInsnNode zero(Type type) {
        switch (type.getSort()) {
            case Type.LONG:
                return new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT:
                return new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE:
                return new InsnNode(Opcodes.DCONST_0);
            case Type.OBJECT:
            case Type.ARRAY:
                return new InsnNode(Opcodes.ACONST_NULL);
            default:
                return new InsnNode(Opcodes.ICONST_0);
        }
    }

    // Built with ASM and never defined: the transform and the scans only read it
    private static byte[] classOf(String name, MethodNode method) {
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = name.replace('.', '/');
        node.superName = "java/lang/Object";
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    // One line in the test log, the test report and linkage.txt
    private static void report(TestReporter reporter, String key, String line) {
        System.out.println("[opengpu-glsm] " + line);
        REPORT.add(line);
        reporter.publishEntry(key, line);
    }

    private static String modVersion(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("mcmod.info");
        Assertions.assertNotNull(entry, zip.getName() + " has no mcmod.info");
        String info;
        try (InputStream in = zip.getInputStream(entry)) {
            info = new String(GlBytecode.readAll(in), StandardCharsets.UTF_8);
        }
        Matcher matcher = MOD_VERSION.matcher(info);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int major(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        Assertions.assertNotNull(entry, zip.getName() + " has no " + name);
        try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {
            Assertions.assertEquals(0xCAFEBABE, in.readInt(), name + " is no class file");
            in.readUnsignedShort();
            return in.readUnsignedShort();
        }
    }

    private static Map<String, String> rows() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("C02", "glGetString(I)Ljava/lang/String;");
        rows.put("C99", POLYGON_MODE);
        rows.put("C100", "glFinish()V");
        return Collections.unmodifiableMap(rows);
    }

    /** The redirector's fold table (25:239): capability to the suffix of GLStateManager's enable/disable methods. */
    @SuppressWarnings("unchecked")
    static Map<Integer, String> folds(Class<?> redirector) throws ReflectiveOperationException {
        Field field = redirector.getDeclaredField("glCapRedirects");
        field.setAccessible(true);
        return new TreeMap<>((Map<Integer, String>) field.get(null));
    }

    private static String foldText(Map<Integer, String> folds) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, String> fold : folds.entrySet()) {
            parts.add(String.format("0x%04X %s", fold.getKey(), fold.getValue()));
        }
        return folds.size() + " [" + String.join(", ", parts) + "]";
    }

    /** One shipped class on one leg: its class file and what the leg's redirector made of it, or threw. */
    static final class Shipped {

        final String name;
        final byte[] original;
        final GlsmLoader.Transform transform;
        final Throwable thrown;

        Shipped(String name, byte[] original, GlsmLoader.Transform transform, Throwable thrown) {
            this.name = name;
            this.original = original;
            this.transform = transform;
            this.thrown = thrown;
        }

        static Shipped through(GlBytecode.Redirector glsm, String name, byte[] original) {
            try {
                return new Shipped(name, original, GlBytecode.redirect(glsm, name, original), null);
            } catch (RuntimeException | LinkageError e) {
                return new Shipped(name, original, null, e);
            }
        }
    }

    /**
     * A leg's GLSMRedirector as GlBytecode calls it, by reflection, since this class names no Angelica type (S9 plan
     * P6.1). What the redirector throws comes out as itself, not wrapped in an InvocationTargetException.
     */
    static final class ReflectiveRedirector implements GlBytecode.Redirector {

        private final Object redirector;
        private final Method coreExclusions;
        private final Method shouldTransform;
        private final Method transformClassNode;

        ReflectiveRedirector(Object redirector) throws NoSuchMethodException {
            Class<?> type = redirector.getClass();
            this.redirector = redirector;
            coreExclusions = type.getMethod("getCoreExclusions");
            shouldTransform = type.getMethod("shouldTransform", byte[].class);
            // The redirector's ClassNode is this JVM's: ASM comes from the shared classpath
            transformClassNode = type.getMethod("transformClassNode", String.class, ClassNode.class);
        }

        @Override
        public String[] coreExclusions() {
            return (String[]) call(coreExclusions);
        }

        @Override
        public boolean shouldTransform(byte[] classFile) {
            return (Boolean) call(shouldTransform, (Object) classFile);
        }

        @Override
        public boolean transformClassNode(String name, ClassNode node) {
            return (Boolean) call(transformClassNode, name, node);
        }

        private Object call(Method method, Object... arguments) {
            try {
                return method.invoke(redirector, arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new IllegalStateException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * One leg: its name, the release and source the build resolved, and its dev jar. Its class loader is set up on
     * first use, alone, and a failure is kept, so that only this leg's tests fail and each with the same cause.
     */
    static final class Leg {

        final String name;
        final String version;
        /** "pinned", or NexusRelease's "nexus", "remembered@&lt;fetchedAt&gt;" or "property". */
        final String source;
        final File jar;
        private URLClassLoader loader;
        private Class<?> systemProperties;
        private Class<?> redirector;
        private Object instance;
        private IllegalStateException failure;
        private String target;
        private Set<String> statics;
        private GlBytecode.Redirector transformer;
        private List<Shipped> shipped;

        Leg(String name) {
            this.name = name;
            version = property(name, "version");
            source = property(name, "source");
            jar = new File(property(name, "jar"));
        }

        private static String property(String leg, String key) {
            String value = System.getProperty("opengpu.linkage." + leg + "." + key);
            if (value == null) {
                throw new IllegalStateException(
                    "no opengpu.linkage." + leg + "." + key + ": run through :gl-testkit:glsmLinkageTest");
            }
            return value;
        }

        /** Where the release came from, as the log says it (S9 plan P4.4). */
        String sourceText() {
            if (source.equals("pinned")) {
                return "pinned in OpenGpuVersions";
            }
            if (source.equals("nexus")) {
                return "Nexus <release>, read online";
            }
            if (source.startsWith("remembered@")) {
                return "Nexus <release>, remembered from an online read at " + source.substring("remembered@".length());
            }
            if (source.equals("property")) {
                return "forced by -Popengpu.angelica.latest";
            }
            throw new IllegalStateException(this + ": no such source as " + source);
        }

        /** This leg's own SystemProperties, initialized. */
        synchronized Class<?> systemProperties() {
            setUp();
            return systemProperties;
        }

        /** This leg's own GLSMRedirector class, initialized after its SystemProperties. */
        synchronized Class<?> redirector() {
            setUp();
            return redirector;
        }

        /** A GLSMRedirector of this leg's, made by its public constructor, as Angelica's transformer makes one. */
        synchronized Object instance() {
            setUp();
            return instance;
        }

        /** This leg's GLSMRedirector class, or null when the leg cannot be set up: its own tests say why. */
        synchronized Class<?> redirectorOrNull() {
            try {
                return redirector();
            } catch (IllegalStateException e) {
                return null;
            }
        }

        /** Its redirector's getTargetClassName(), GLStateManager's internal name. */
        synchronized String target() throws ReflectiveOperationException {
            if (target == null) {
                target = (String) redirector().getMethod("getTargetClassName")
                    .invoke(null);
            }
            return target;
        }

        /** The public static methods of its GLStateManager, read from the jar's base entry (S9 plan P3.2). */
        synchronized Set<String> statics() throws ReflectiveOperationException, IOException {
            if (statics == null) {
                statics = Collections.unmodifiableSet(GlBytecode.publicStatics(jar, target()));
            }
            return statics;
        }

        /** A copy of its redirector's fold table. */
        Map<Integer, String> folds() throws ReflectiveOperationException {
            return GlsmLinkageTest.folds(redirector());
        }

        /** Its GLSMRedirector instance as GlBytecode calls it. */
        synchronized GlBytecode.Redirector transformer() throws NoSuchMethodException {
            if (transformer == null) {
                transformer = new ReflectiveRedirector(instance());
            }
            return transformer;
        }

        /** The shipped classes through its redirector, once, each with its own result. */
        synchronized List<Shipped> shipped() throws IOException, URISyntaxException, NoSuchMethodException {
            if (shipped == null) {
                GlBytecode.Redirector glsm = transformer();
                List<Shipped> list = new ArrayList<>();
                for (Map.Entry<String, byte[]> c : shippedClasses().entrySet()) {
                    list.add(Shipped.through(glsm, c.getKey(), c.getValue()));
                }
                shipped = Collections.unmodifiableList(list);
            }
            return shipped;
        }

        /** The shipped class {@code className}, which must have transformed. */
        Shipped shipped(String className) throws IOException, URISyntaxException, NoSuchMethodException {
            for (Shipped c : shipped()) {
                if (c.name.equals(className)) {
                    Assertions.assertNull(c.thrown, this + ": " + className);
                    return c;
                }
            }
            throw new AssertionError(this + ": no shipped class " + className);
        }

        private void setUp() {
            if (redirector == null && failure == null) {
                try {
                    loader = new URLClassLoader(
                        new URL[] { jar.toURI()
                            .toURL() },
                        GlsmLinkageTest.class.getClassLoader());
                    systemProperties = Class.forName(SYSTEM_PROPERTIES, true, loader);
                    Class<?> type = Class.forName(REDIRECTOR, true, loader);
                    instance = type.getConstructor()
                        .newInstance();
                    redirector = type;
                } catch (IOException | ReflectiveOperationException | LinkageError e) {
                    failure = new IllegalStateException(whyNot(e), e);
                }
            }
            if (failure != null) {
                throw new IllegalStateException(failure.getMessage(), failure.getCause());
            }
        }

        // The cause in words: a class or member missing outside Angelica means the shared libraries fall short
        private String whyNot(Throwable e) {
            Throwable cause = e;
            while ((cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
                cause = cause.getCause();
            }
            String message = String.valueOf(cause.getMessage());
            if (cause instanceof ClassNotFoundException && message.startsWith("com.gtnewhorizons.angelica.")) {
                return this + ": the jar has no " + message + ", so it has no unmappedGL=FAIL to run";
            }
            boolean missing = cause instanceof NoClassDefFoundError || cause instanceof NoSuchMethodError
                || cause instanceof NoSuchFieldError
                || cause instanceof IncompatibleClassChangeError;
            if (missing && !message.replace('/', '.')
                .contains("com.gtnewhorizons.angelica.")) {
                return this + ": the shared test libraries (OpenGpuVersions.GTNHLIB, GUAVA_MC, LOG4J_MC and ASM on "
                    + "glsmTest's runtime classpath) lack what Angelica "
                    + version
                    + " needs, probably as they are older than its own: "
                    + cause;
            }
            return this + ": its GLSMRedirector could not be set up: " + cause;
        }

        void close() throws IOException {
            if (loader != null) {
                loader.close();
            }
        }

        @Override
        public String toString() {
            return name + " " + version;
        }
    }
}
