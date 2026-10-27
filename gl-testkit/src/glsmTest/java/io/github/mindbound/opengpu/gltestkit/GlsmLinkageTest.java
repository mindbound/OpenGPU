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
import java.util.EnumMap;
import java.util.Enumeration;
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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
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
 * the task's {@code -Dangelica.unmappedGL=FAIL}, then its own GLSMRedirector. Nothing loads GLStateManager or another
 * GLSM class, which need Minecraft and LWJGL: their methods are read from the jar
 * ({@link GlBytecode#publicStatics}), so no GL context, no natives and no GLSM start are needed (23:82 (3)). A leg
 * that cannot be set up fails its own tests only. This class names no Angelica type, so it loads where none is.
 * <p>
 * On each leg: the jar is the release the build resolved (L1); every shipped class goes through the leg's redirector
 * as in game without a throw, and no LWJGL name is left in any of them but D01's lookup (L2; 25 §4 step 4); every
 * rewritten call is a public static method, with the call's descriptor, of the jar's GLSM class that owns its name,
 * under the jar's own folds (L3; step 3); and from the CI-only leg on, the jar folds :gl's capabilities as the floor
 * does (L4).
 * <p>
 * The GLSM classes are each leg's own ({@link Glsm}), chosen by reflection on its redirector, so the test sets no
 * ceiling on Angelica's releases. Up to 2.2.39 GLSMRedirector's getTargetClassName() names one, GLStateManager, which
 * owns every GLSM method, as since S9. 2.2.40 split GLStateManager into owner classes (Angelica PR #2305; CI run
 * 40473671907, whose latest leg failed on the missing getTargetClassName()): its redirector gives the classes as
 * glsmClasses() and each GLSM name's owner as getTargetOwner(name), GLStateManager for a name it does not list. L1
 * reads every class and its multi-release copies, L3 links each call on its own owner, the scans take a call on any
 * GLSM class as rewritten, and N3 removes glPolygonMode from the class that owns it.
 * Synthetic classes, transformed and scanned but never defined, so LWJGL never loads, show that each check can fail
 * (S9 plan P10, critic A2): FAIL rejects an unmapped call and A09 (N1), a rewritten call with no GLSM method and a jar
 * without glPolygonMode fail the link check (N2, N3), a fold set without GL_BLEND fails the expected calls (N4), a
 * second LWJGL class literal or a second lookup in D01's method is reported (N5), and so are the calls FAIL lets
 * through: a GLSync descriptor and a debug-output call (N6).
 * <p>
 * From M0 step S12 the root's classes join L2, L3 and L4 (S12 plan P13.3, P14.5; 25:721), its client classes and,
 * since S12's review (strength-6), its common ones, whose {@code @SideOnly(CLIENT)} members run on a client too: those
 * of the root's two shipped jars outside :gl's package, the -dev jar with Minecraft's MCP names and the release jar
 * with its SRG names, which the task passes as file inputs and this class reads with ZipFile, never through its own
 * classpath ({@link RootJar}). L3 follows the calls on the owners of each jar's getNamedClassMethodRedirects, X01's
 * OpenGlHelper among them: in the -dev jar X01 links to setLightmapTextureCoords(IFF)V, and in the release jar its SRG
 * name, which no map of 2.2.8 to 2.2.36 holds, is left to vanilla's body, which is allowed for that one call only.
 * GLContext.getCapabilities(), no gl name, is the one call GLSM passes through by design (the P01 row of the root's
 * clientcalls.txt, 25 §4.1's new row; S12 amendments F9), and L2 leaves only GLContext and ContextCapabilities in
 * GlCaps and LWJGL's Mouse in client/gui/, by class. Two more synthetic cases fail as planted: A01 in a root client
 * class fails L3 on the jars whose GLStateManager lacks it, the floor and the CI-only leg (N7), and those LWJGL names
 * fail L2 outside their classes (N8).
 */
@Tag("glsm-linkage")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GlsmLinkageTest {

    /** The legs in glsmLinkageTest's order. */
    private static final List<String> LEG_NAMES = Collections
        .unmodifiableList(Arrays.asList("floor", "ci-only", "pack", "latest"));
    private static final String SYSTEM_PROPERTIES = "com.gtnewhorizons.angelica.config.SystemProperties";
    private static final String REDIRECTOR = "com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector";
    /** The GLSM class of every name up to Angelica 2.2.39, and from 2.2.40 the owner of a name no other class owns. */
    private static final String GL_STATE_MANAGER = "com/gtnewhorizons/angelica/glsm/GLStateManager";
    /**
     * The base entries S9 reads besides the GLSM classes (S9 plan P2.1); a Java 8 client loads these, so each must be
     * major 52. L1 reads every GLSM class the same way, GLStateManager among them while it is one, so no release that
     * renames it or stops using it as the default owner fails here (review CK3).
     */
    private static final List<String> ENTRIES = Collections.unmodifiableList(
        Arrays.asList(REDIRECTOR.replace('.', '/') + ".class", SYSTEM_PROPERTIES.replace('.', '/') + ".class"));
    /** The multi-release copies lwjgl3ify's Java 17 and 21 clients load (25 §5.1; LK L9). */
    private static final int[] RELEASES = { 17, 21 };
    /** The version of mcmod.info's angelica entry; the jar also holds NotFine's, Embeddium's and MCPatcherForge's. */
    private static final Pattern MOD_VERSION = Pattern
        .compile("\"modid\"\\s*:\\s*\"angelica\"[^}]*?\"version\"\\s*:\\s*\"([^\"]*)\"");
    /**
     * The shipped classes S9 scans, the prefix and an anchor class: :gl's, gl.host's included, which the root shades
     * unrelocated (H51; S9 plan P8.1). The root's own client classes are read from its two built jars instead
     * ({@link RootJar}), never from the root's classpath in this JVM, with the owners of each jar's
     * getNamedClassMethodRedirects followed, X01's OpenGlHelper (S9 plan P8.2, critic A10; S12 plan P14.5).
     */
    private static final String[] SHIPPED = { "io/github/mindbound/opengpu/gl/",
        "io/github/mindbound/opengpu/gl/GlBackend.class" };
    /**
     * The root's classes, as entries of its jars: every OpenGPU class but {@link #SHIPPED}'s, :gl's, which the legs
     * read from :gl's own output; so the client classes and the common ones, whose {@code @SideOnly(CLIENT)} members
     * also run on a client (S12 plan P14.5; S12 review strength-6).
     */
    private static final String ROOT = "io/github/mindbound/opengpu/";
    /** The root's client classes, which hold all of clientcalls.txt's rows. */
    private static final String ROOT_CLIENT = ROOT + "client/";
    private static final String ROOT_CLIENT_CLASS = "io.github.mindbound.opengpu.client.";
    private static final String ROOT_GUI = ROOT_CLIENT_CLASS + "gui.";
    private static final String ROOT_RENDER = ROOT_CLIENT_CLASS + "render.";
    /** The probe, which alone may keep GLContext and ContextCapabilities (14 §2.5). */
    private static final String GL_CAPS = ROOT_RENDER + "GlCaps";
    /** The screen's bracket, whose X01 the release jar calls by its SRG name. */
    private static final String SCREEN_DRAW = ROOT_RENDER + "ScreenDraw";
    private static final String GL_CONTEXT = GlBytecode.GL_OWNER + "GLContext";
    private static final String CONTEXT_CAPABILITIES = GlBytecode.GL_OWNER + "ContextCapabilities";
    /** LWJGL's mouse, which the GUI reads for raw window pixels (S12 plan P12.4): no GL, so GLSM leaves it. */
    private static final String MOUSE = GlBytecode.LWJGL + "input/Mouse";
    /**
     * The one call GLSM passes through by design on every 2.2.x jar: no gl name (25:259; 14 §2.5), the P01 row of the
     * root's clientcalls.txt.
     */
    private static final String PASS_THROUGH = GL_CONTEXT + ".getCapabilities()L" + CONTEXT_CAPABILITIES + ";";
    private static final String OPEN_GL_HELPER = "net/minecraft/client/renderer/OpenGlHelper";
    /** X01's SRG name, which the release jar calls and no GLSMRedirector map of 2.2.8 to 2.2.36 holds. */
    private static final String X01_SRG_NAME = "func_77475_a";
    private static final String X01_SRG = OPEN_GL_HELPER + "." + X01_SRG_NAME + "(IFF)V";
    /**
     * The root's rows L3 links, by their GLSM members, each on the class that owns its name (25 §4.1's target column;
     * clientcalls.txt).
     */
    private static final Map<String, String> ROOT_ROWS = rootRows();
    /** An LWJGL internal name inside a reference's text. */
    private static final Pattern LWJGL_NAME = Pattern.compile(GlBytecode.LWJGL + "[A-Za-z0-9_/$]+");
    /** :gl's class of GL constants only, which no instruction of its takes from LWJGL (S9 critic A7). */
    private static final String GL_FORMATS = "io.github.mindbound.opengpu.gl.GlFormats";
    /** The rows L3 names, by their GLSM members: C02 (GL_VENDOR, Q-S8-3), C99 (H41), C100 (S8's fix loop). */
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
        Glsm glsm = leg.glsm();
        Assertions.assertNotEquals(0, glsm.methods(), leg + ": " + glsm + ": no public static method");
        // LK L9's re-check, on each GLSM class: the Java 17 and 21 copies declare the same public static methods as
        // the base, each read from the entry a JVM of that release loads, the highest META-INF/versions/n with n at
        // most its release, which is asserted, so a reader that falls back to the base cannot pass for the re-check
        // (S9 review TS2). On 2.2.40 some classes have a versions/17 copy and no versions/21 one, which Java 21 loads
        Map<Integer, Integer> copies = new TreeMap<>();
        List<String> differences = new ArrayList<>();
        try (ZipFile zip = new ZipFile(leg.jar)) {
            for (String owner : glsm.classes) {
                Assertions.assertEquals(52, major(zip, owner + ".class"), leg + ": the class file major of " + owner);
                Set<String> statics = glsm.statics(owner);
                for (int release : RELEASES) {
                    if (zip.getEntry(versioned(release, owner)) != null) {
                        copies.merge(release, 1, Integer::sum);
                    }
                    List<String> read = new ArrayList<>();
                    Set<String> copy = GlBytecode.publicStatics(leg.jar, owner, release, read);
                    Assertions.assertEquals(
                        loaded(zip, release, owner),
                        read.get(0),
                        leg + ": the " + owner + " a Java " + release + " JVM loads");
                    for (String method : statics) {
                        if (!copy.contains(method)) {
                            differences.add(glsmName(owner) + ": Java " + release + " lacks " + method);
                        }
                    }
                    for (String method : copy) {
                        if (!statics.contains(method)) {
                            differences.add(glsmName(owner) + ": only Java " + release + " has " + method);
                        }
                    }
                }
            }
        }
        Assertions
            .assertEquals(new ArrayList<String>(), differences, leg + ": " + differences.size() + " difference(s)");
        List<String> compared = new ArrayList<>();
        for (Map.Entry<Integer, Integer> copy : copies.entrySet()) {
            compared.add("versions/" + copy.getKey() + (glsm.single() ? "" : " (" + copy.getValue() + " of them)"));
        }
        report(
            reporter,
            "linkage." + leg.name + ".glsm",
            "linkage " + leg.name
                + ": "
                + glsm
                + (glsm.single() ? ", has " : " have ")
                + glsm.methods()
                + " public static methods"
                + (glsm.single() ? "" : " " + glsm.counts())
                + ", the same in "
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
        Glsm glsm = leg.glsm();
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
            List<String> before = calls(c.original, glsm);
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
     * 25 §4 step 3 on the leg's own jar: in each class that calls GL, the calls on the jar's GLSM classes after the
     * transform are its GL calls in order, each of the same name and descriptor on the class that owns that name but a
     * glEnable or glDisable of a capability in that jar's glCapRedirects, which becomes its enable/disable method on
     * that method's owner (25 §4.1's target column; 25:239), and each is a public static method of the class it calls,
     * with that descriptor. A target a later jar renames fails here by design, though 25 §4 step 3 would let it link:
     * {@link #linkage} expects each call's own name, so the fix is 25 §4.1's target column and that jar's name in
     * linkage() (25:546; S9 review F3). A target a later jar moves to another GLSM class does not: each name's owner
     * is the jar's own ({@link Glsm}). The number of distinct targets is recorded per leg and compared with no other
     * leg's: a new fold changes it without a link failure (S9 critic A3), which L4 reports.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(3)
    void everyRewrittenCallLinks(Leg leg, TestReporter reporter) throws Exception {
        Glsm glsm = leg.glsm();
        Linkage linkage = linkage(leg.shipped(), glsm, leg.folds());
        Assertions.assertEquals(
            new ArrayList<String>(),
            linkage.problems,
            leg + ": "
                + linkage.problems.size()
                + " problem(s); a target this jar renames needs 25 §4.1's target column and this jar's name in"
                + " GlsmLinkageTest.linkage()");
        for (Map.Entry<String, String> row : ROWS.entrySet()) {
            String owner = glsm.ownerOf(row.getValue());
            Assertions.assertTrue(
                linkage.targets.contains(row.getValue()) && glsm.links(owner, row.getValue()),
                leg + ": " + row.getKey() + " " + owner + "." + row.getValue() + " is not among the linked targets");
        }
        report(
            reporter,
            "linkage." + leg.name + ".links",
            "linkage " + leg.name
                + ": "
                + sitesText(glsm, linkage.sites, linkage.targets, linkage.owners)
                + ", each public static with the call's descriptor; "
                + String.join(", ", ROWS.keySet())
                + " among them");
    }

    /**
     * Every capability :gl, or since S12 the root's code, passes glEnable or glDisable as a constant is folded on this
     * jar exactly when it is on the floor's, whose set CallSetTest's FOLDED and callset.txt's F rows encode, and for
     * the root ClientCallSetTest's FOLDED and clientcalls.txt's F09. A fold of a capability neither uses is logged,
     * not failed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("laterLegs")
    @Order(4)
    void foldsMatchTheFloor(Leg leg, TestReporter reporter) throws Exception {
        Leg floor = legs().get(0);
        Map<Integer, String> floorFolds = floor.folds();
        Map<Integer, String> folds = leg.folds();
        List<byte[]> classFiles = new ArrayList<>(shippedClasses().values());
        for (RootJar jar : RootJar.values()) {
            classFiles.addAll(
                jar.classes()
                    .values());
        }
        Set<Integer> used = new TreeSet<>();
        for (byte[] classFile : classFiles) {
            for (String call : calls(classFile, floor.glsm())) {
                Integer cap = GlBytecode.capability(call.substring(call.indexOf('.') + 1));
                if (cap != null) {
                    used.add(cap);
                }
            }
        }
        Assertions.assertFalse(used.isEmpty(), "no glEnable or glDisable of a constant in :gl's or the root's classes");
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
                + ": folds the "
                + used.size()
                + " capabilities of :gl and the root's classes "
                + usedText
                + " as the floor does; "
                + (others.isEmpty() ? "no other fold differs" : "for capabilities neither uses, " + others));
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

    /** glViewport is mapped by name, but no GLSM method can have this descriptor. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(7)
    void aRewrittenCallWithoutAGlsmMethodFailsTheLinkCheck(Leg leg) throws Exception {
        String name = "synthetic.Viewport";
        byte[] original = callOf(name, GlBytecode.GL_OWNER + "GL11", "glViewport", "(Ljava/lang/Object;)V");
        GlsmLoader.Transform t = GlBytecode.redirect(leg.transformer(), name, original);
        Assertions.assertTrue(t.changed, leg + ": the redirector left " + name + " as it was");
        Glsm glsm = leg.glsm();
        Linkage linkage = linkage(Collections.singletonList(new Shipped(name, original, t, null)), glsm, leg.folds());
        Assertions.assertEquals(
            Collections.singletonList(
                name + ": no public static " + glsm.owner("glViewport") + ".glViewport(Ljava/lang/Object;)V"),
            linkage.problems,
            leg.toString());
    }

    /**
     * L3's check over the real shipped classes, on this jar's method lists with C99 removed from the GLSM class that
     * owns its name, the one the redirector sends it to: exactly C99's sites fail. On a split leg C99 is also given to
     * another GLSM class, so only a check on each call's own owner reports those sites, not one that takes a method
     * from any GLSM class (review CK1); on a single-class leg there is no other class to give it to.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(8)
    void aJarWithoutGlPolygonModeFailsTheLinkCheck(Leg leg) throws Exception {
        Glsm glsm = leg.glsm();
        String owner = glsm.ownerOf(POLYGON_MODE);
        Set<String> statics = new TreeSet<>(glsm.statics(owner));
        Assertions.assertTrue(
            statics.remove(POLYGON_MODE),
            leg + ": C99 " + owner + "." + POLYGON_MODE + " is no public static");
        Glsm without = glsm.with(owner, statics);
        for (String other : glsm.classes) {
            if (!other.equals(owner)) {
                Set<String> more = new TreeSet<>(glsm.statics(other));
                more.add(POLYGON_MODE);
                without = without.with(other, more);
                break;
            }
        }
        List<String> expected = new ArrayList<>();
        for (Shipped c : leg.shipped()) {
            for (String call : calls(c.original, glsm)) {
                if (call.equals(GlBytecode.GL_OWNER + "GL11." + POLYGON_MODE)) {
                    expected.add(c.name + ": no public static " + owner + "." + POLYGON_MODE);
                }
            }
        }
        Assertions.assertFalse(expected.isEmpty(), "no C99 call in :gl's classes");
        Assertions.assertEquals(expected, linkage(leg.shipped(), without, leg.folds()).problems, leg.toString());
    }

    /**
     * L3's expected calls on the floor without GL_BLEND's fold: exactly F01's sites, where disableBlend()V is found.
     */
    @Test
    @Order(9)
    void aFoldSetWithoutBlendFailsTheTargetCheck() throws Exception {
        Leg floor = legs().get(0);
        Glsm glsm = floor.glsm();
        Map<Integer, String> folds = floor.folds();
        String blend = folds.remove(BLEND);
        Assertions.assertNotNull(blend, floor + " does not fold GL_BLEND (F01)");
        List<String> expected = new ArrayList<>();
        for (Shipped c : floor.shipped()) {
            List<String> before = calls(c.original, glsm);
            for (int i = 0; i < before.size(); i++) {
                String member = before.get(i)
                    .substring(
                        before.get(i)
                            .indexOf('.') + 1);
                if (Integer.valueOf(BLEND)
                    .equals(GlBytecode.capability(member))) {
                    String fold = (member.startsWith("glEnable") ? "enable" : "disable") + blend;
                    expected.add(
                        c.name + ": call "
                            + i
                            + ": expected "
                            + glsm.ownerOf(member)
                            + "."
                            + member
                            + ", got "
                            + glsm.owner(fold)
                            + "."
                            + fold
                            + "()V");
                }
            }
        }
        Assertions.assertFalse(expected.isEmpty(), "no F01 site in :gl's classes");
        Assertions.assertEquals(expected, linkage(floor.shipped(), glsm, folds).problems);
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
                + (fenced.changed ? "rewritten to " + leg.glsm()
                    .owner("glFenceSync") : "left on LWJGL")
                + ", and ARBDebugOutput."
                + debugName
                + ", "
                + (debugLeft ? "left on LWJGL"
                    : debugCalls.isEmpty() ? "removed" : "rewritten to " + String.join(", ", debugCalls))
                + "; the scan reports "
                + (debugLeft ? "both" : "the fence"));
    }

    /**
     * L2 for the root's classes, its client classes and, since S12's review (strength-6), its common ones (S12 plan
     * P14.5; amendments F9): each class of both root jars outside :gl's package goes through the leg's redirector as in
     * game without a throw and with no core exclusion; a class with a call GLSM rewrites, one
     * that {@link #expectedCalls} expects on another owner or name, is changed, and any other comes out byte for byte
     * as it went in; and no LWJGL name is left after the transform but, by class, GLContext and ContextCapabilities in
     * GlCaps, which GLSM passes through by design (14 §2.5), and LWJGL's Mouse in client/gui/, which is no GL. No LWJGL
     * class literal is allowed: D01's lookup is :gl's PassState's alone. The first leg also lists the classes read.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(12)
    void theRootsClientClassesTransformUnderFail(Leg leg, TestReporter reporter) throws Exception {
        Glsm glsm = leg.glsm();
        Map<Integer, String> folds = leg.folds();
        Map<String, Map<String, String>> named = leg.named();
        List<String> problems = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        Set<String> left = new TreeSet<>();
        for (RootJar jar : RootJar.values()) {
            Set<String> changed = new TreeSet<>();
            for (Shipped c : leg.root(jar)) {
                String where = jar + " " + c.name;
                if (c.thrown != null) {
                    problems.add(where + ": " + c.thrown);
                    continue;
                }
                GlsmLoader.Transform t = c.transform;
                if (t.excluded) {
                    problems.add(where + ": a core exclusion matched, so in game the redirector leaves it as it is");
                }
                List<String> before = routedCalls(c.original, glsm, named.keySet());
                boolean rewrites = !expectedCalls(c.name, before, glsm, folds, named, new ArrayList<String>())
                    .equals(before);
                if (rewrites && (!t.filtered || !t.changed)) {
                    problems.add(
                        where + ": makes calls GLSM rewrites, but filtered " + t.filtered + ", changed " + t.changed);
                } else if (!rewrites && (t.changed || !Arrays.equals(t.original, t.defined))) {
                    problems.add(where + ": makes no call GLSM rewrites, but the redirector changed it");
                }
                if (t.changed) {
                    changed.add(simpleName(c.name));
                }
                RootScan scan = rootScan(c.name, t.defined);
                problems.addAll(scan.problems);
                left.addAll(scan.left);
            }
            parts.add(
                leg.root(jar)
                    .size() + " classes of the root's "
                    + jar
                    + " jar, "
                    + changed.size()
                    + " rewritten "
                    + changed);
        }
        Assertions.assertEquals(new ArrayList<String>(), problems, leg + ": " + problems.size() + " problem(s)");
        if (leg == legs().get(0)) {
            List<String> jars = new ArrayList<>();
            for (RootJar jar : RootJar.values()) {
                List<String> names = new ArrayList<>();
                int others = 0;
                for (String name : jar.classes()
                    .keySet()) {
                    if (name.startsWith(ROOT_CLIENT_CLASS)) {
                        names.add(simpleName(name));
                    } else {
                        others++;
                    }
                }
                jars.add(
                    jar + " jar "
                        + jar.file()
                            .getName()
                        + ", "
                        + names.size()
                        + " client classes "
                        + names
                        + " and "
                        + others
                        + " others outside gl/");
            }
            report(reporter, "linkage.root.classes", "linkage root: the classes of the " + String.join("; ", jars));
        }
        report(
            reporter,
            "linkage." + leg.name + ".root",
            "linkage " + leg.name
                + ": under unmappedGL=FAIL "
                + String.join("; ", parts)
                + "; LWJGL names left by design "
                + left);
    }

    /**
     * L3 for the root's classes, its client classes and its common ones (S12 plan P14.5; S12 review strength-6): in
     * each class of both root jars outside :gl's package, the calls on LWJGL's GL owners and on the owners of the jar's
     * getNamedClassMethodRedirects become, in order, what {@link #expectedCalls}
     * says, and each rewritten one is a public static method, with the call's descriptor, of the jar's GLSM class that
     * owns its name. Each row of the root's clientcalls.txt links to its 25 §4.1 target in both jars, X01 to
     * setLightmapTextureCoords(IFF)V in the -dev jar; in the release jar X01's SRG name is left to vanilla's body while
     * the jar's map lacks it, as on every leg today, and its target is checked once a jar maps it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(13)
    void theRootsClientCallsLink(Leg leg, TestReporter reporter) throws Exception {
        Glsm glsm = leg.glsm();
        Map<String, Map<String, String>> named = leg.named();
        Map<String, String> helper = named.get(OPEN_GL_HELPER);
        boolean mapsSrg = helper != null && helper.containsKey(X01_SRG_NAME);
        List<String> problems = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (RootJar jar : RootJar.values()) {
            RootLinkage linkage = rootLinkage(leg.root(jar), jar, glsm, leg.folds(), named);
            problems.addAll(linkage.problems);
            boolean vanilla = jar == RootJar.RELEASE && !mapsSrg;
            for (Map.Entry<String, String> row : ROOT_ROWS.entrySet()) {
                if (vanilla && row.getKey()
                    .equals("X01")) {
                    continue;
                }
                String owner = glsm.ownerOf(row.getValue());
                if (!linkage.targets.contains(row.getValue()) || !glsm.links(owner, row.getValue())) {
                    problems.add(jar + ": " + row.getKey() + " " + owner + "." + row.getValue() + " is not linked");
                }
            }
            List<String> expected = vanilla ? Collections.singletonList(simpleName(SCREEN_DRAW) + " " + X01_SRG)
                : new ArrayList<String>();
            if (!expected.equals(linkage.vanilla)) {
                problems.add(jar + ": left to vanilla's body " + linkage.vanilla + ", not " + expected);
            }
            parts.add(
                jar + " jar: "
                    + sitesText(glsm, linkage.sites, linkage.targets, linkage.owners)
                    + ", passed through by design "
                    + linkage.passThrough
                    + ", left to vanilla's body "
                    + (linkage.vanilla.isEmpty() ? "none" : linkage.vanilla));
        }
        Assertions.assertEquals(new ArrayList<String>(), problems, leg + ": " + problems.size() + " problem(s)");
        report(
            reporter,
            "linkage." + leg.name + ".rootLinks",
            "linkage " + leg.name + ": the root's calls, " + String.join("; ", parts));
    }

    /**
     * N7 (S12 plan P14.5): a root client class calling A01, GL11.glReadPixels(IIIIIIJ)V, which GLSM maps by name,
     * fails L3 where the jar's GLSM class that owns glReadPixels has no method of that descriptor: on the floor and
     * the CI-only leg, as 25 §4.2 has it (NSME from 2.2.0 to 2.2.21). Where a later jar has one it links, which is
     * recorded.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legs")
    @Order(14)
    void aRootClassCallingA01FailsTheLinkCheckWhereGlsmLacksIt(Leg leg, TestReporter reporter) throws Exception {
        String name = ROOT_RENDER + "SyntheticReadPixels";
        String member = "glReadPixels(IIIIIIJ)V";
        byte[] original = callOf(name, GlBytecode.GL_OWNER + "GL11", "glReadPixels", "(IIIIIIJ)V");
        GlsmLoader.Transform t = GlBytecode.redirect(leg.transformer(), name, original);
        Assertions.assertTrue(t.changed, leg + ": the redirector left " + name + " as it was");
        Glsm glsm = leg.glsm();
        String owner = glsm.ownerOf(member);
        boolean links = glsm.links(owner, member);
        if (LEG_NAMES.subList(0, 2)
            .contains(leg.name)) {
            Assertions.assertFalse(
                links,
                leg + ": " + owner + " has " + member + ", which 25 §4.2 gives no 2.2.x before 2.2.22");
        }
        RootLinkage linkage = rootLinkage(
            Collections.singletonList(new Shipped(name, original, t, null)),
            RootJar.DEV,
            glsm,
            leg.folds(),
            leg.named());
        Assertions
            .assertEquals(
                links ? new ArrayList<String>()
                    : Collections
                        .singletonList(RootJar.DEV + " " + name + ": no public static " + owner + "." + member),
                linkage.problems,
                leg.toString());
        report(
            reporter,
            "linkage." + leg.name + ".a01",
            "linkage " + leg.name
                + ": A01 GL11."
                + member
                + " in a root client class "
                + (links ? "links on " + owner + ", as from 2.2.22 (25 §4.2)"
                    : "fails L3: no public static " + owner + "." + member));
    }

    /**
     * N8 (S12 plan P14.5; mutants M83a and M83b): L2's exceptions for the root are by class. A call of
     * GLContext.getCapabilities(), which GLSM passes through, and a field of type GLContext are left in GlCaps and
     * reported in any other class, and LWJGL's Mouse is left in client/gui/ and reported elsewhere. Each case goes
     * through the floor's redirector, which changes none of them.
     */
    @Test
    @Order(15)
    void anLwjglNameLeftOutsideItsClassIsReported() throws Exception {
        Leg floor = legs().get(0);
        String renderer = ROOT_RENDER + "DisplayRenderer";
        String capabilities = "()L" + CONTEXT_CAPABILITIES + ";";
        Object[][] cases = { { GL_CAPS, GL_CONTEXT, "getCapabilities", capabilities, true },
            { renderer, GL_CONTEXT, "getCapabilities", capabilities, false },
            { GL_CAPS, GL_CONTEXT, null, "L" + GL_CONTEXT + ";", true },
            { renderer, GL_CONTEXT, null, "L" + GL_CONTEXT + ";", false },
            { ROOT_GUI + "DisplayScreen", MOUSE, "getEventX", "()I", true },
            { renderer, MOUSE, "getEventX", "()I", false } };
        for (Object[] c : cases) {
            String name = (String) c[0];
            String owner = (String) c[1];
            String method = (String) c[2];
            String descriptor = (String) c[3];
            boolean byDesign = (Boolean) c[4];
            byte[] original = method != null ? callOf(name, owner, method, descriptor) : fieldOf(name, descriptor);
            GlsmLoader.Transform t = GlBytecode.redirect(floor.transformer(), name, original);
            String what = name + " with "
                + (method != null ? owner + "." + method + descriptor : "a field " + descriptor);
            Assertions.assertFalse(t.changed, floor + ": the redirector changed " + what);
            RootScan scan = rootScan(name, t.defined);
            if (byDesign) {
                Assertions.assertEquals(new ArrayList<String>(), scan.problems, what);
                Assertions.assertFalse(scan.left.isEmpty(), what + ": nothing left by design");
            } else {
                Assertions.assertEquals(1, scan.problems.size(), what + ": " + scan.problems);
                Assertions.assertTrue(
                    scan.problems.get(0)
                        .contains(owner),
                    what + ": " + scan.problems);
            }
        }
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
        /** The distinct GLSM members the defined bytes call, as name+descriptor. */
        final Set<String> targets = new TreeSet<>();
        /** The rewritten call sites on each GLSM class they call. */
        final Map<String, Integer> owners = new TreeMap<>();
        int sites;
    }

    /**
     * L3's check over {@code classes}: in each that calls GL, the calls on {@code glsm}'s classes after the transform
     * must be the original GL calls in order, each of the same name and descriptor on the GLSM class that owns that
     * name but a glEnable or glDisable of a capability in {@code folds}, which becomes enable/disable&lt;suffix&gt;()V
     * on that method's owner, and each must be a public static method of the class it calls. One problem per call
     * site.
     */
    static Linkage linkage(List<Shipped> classes, Glsm glsm, Map<Integer, String> folds) {
        Linkage linkage = new Linkage();
        for (Shipped c : classes) {
            if (c.thrown != null) {
                linkage.problems.add(c.name + ": not transformed, " + c.thrown);
                continue;
            }
            List<String> before = calls(c.original, glsm);
            if (before.isEmpty()) {
                continue;
            }
            List<String> expected = new ArrayList<>();
            for (String call : before) {
                String member = call.substring(call.indexOf('.') + 1);
                Integer cap = GlBytecode.capability(member);
                if (cap != null && folds.containsKey(cap)) {
                    String fold = (member.startsWith("glEnable") ? "enable" : "disable") + folds.get(cap);
                    expected.add(glsm.owner(fold) + "." + fold + "()V");
                } else {
                    expected.add(glsm.ownerOf(member) + "." + member);
                }
                if ((member.startsWith("glEnable(") || member.startsWith("glDisable(")) && cap == null) {
                    linkage.problems.add(c.name + ": " + member + " without a constant capability (25:239)");
                }
            }
            List<String> after = calls(c.transform.defined, glsm);
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
                String owner = call.substring(0, call.indexOf('.'));
                if (!glsm.holds(owner)) {
                    linkage.problems.add(c.name + ": " + call + " is left on LWJGL");
                    continue;
                }
                String member = call.substring(owner.length() + 1)
                    .replaceAll(" \\[.*]$", "");
                linkage.sites++;
                linkage.targets.add(member);
                linkage.owners.merge(owner, 1, Integer::sum);
                if (!glsm.links(owner, member)) {
                    linkage.problems.add(c.name + ": no public static " + owner + "." + member);
                }
            }
        }
        return linkage;
    }

    /** What L2's scan finds in one of the root's classes. */
    static final class RootScan {

        final List<String> problems = new ArrayList<>();
        /** Each LWJGL name left by design, as "class name" with both short. */
        final Set<String> left = new TreeSet<>();
    }

    /**
     * L2's scan of one of the root's classes, the dotted {@code name}: every LWJGL name in it is a problem, but
     * those {@link #leftByDesign} allows in that class, which it collects. Every LWJGL class literal is a problem.
     */
    static RootScan rootScan(String name, byte[] classFile) {
        RootScan scan = new RootScan();
        List<String> references = new ArrayList<>(GlBytecode.lwjglReferences(classFile));
        for (GlBytecode.Literal literal : GlBytecode.literals(classFile)) {
            references.remove(GlBytecode.reference(literal));
            scan.problems.add(name + ": the class literal " + literal + " is a way past the redirector (R-5)");
        }
        for (String reference : references) {
            List<String> names = new ArrayList<>();
            Matcher matcher = LWJGL_NAME.matcher(reference);
            boolean byDesign = true;
            while (matcher.find()) {
                names.add(matcher.group());
                byDesign &= leftByDesign(name, matcher.group());
            }
            if (!byDesign) {
                scan.problems.add(name + ": " + reference + ", an LWJGL name the transform left");
                continue;
            }
            for (String lwjgl : names) {
                scan.left.add(simpleName(name) + " " + lwjgl.substring(lwjgl.lastIndexOf('/') + 1));
            }
        }
        return scan;
    }

    /**
     * L2's exceptions for the root (S12 plan P14.5; amendments F9), by class: GLContext and ContextCapabilities in
     * GlCaps, which GLSM passes through by design (14 §2.5), and LWJGL's Mouse in client/gui/, which is no GL.
     */
    private static boolean leftByDesign(String className, String lwjgl) {
        if (className.equals(GL_CAPS)) {
            return lwjgl.equals(GL_CONTEXT) || lwjgl.equals(CONTEXT_CAPABILITIES);
        }
        return className.startsWith(ROOT_GUI) && lwjgl.equals(MOUSE);
    }

    /** What L3's check finds in the root's classes of one jar. */
    static final class RootLinkage {

        final List<String> problems = new ArrayList<>();
        /** The distinct GLSM members the defined bytes call, as name+descriptor. */
        final Set<String> targets = new TreeSet<>();
        /** The rewritten call sites on each GLSM class they call. */
        final Map<String, Integer> owners = new TreeMap<>();
        /** The calls GLSM passes through by design, as "class call". */
        final List<String> passThrough = new ArrayList<>();
        /**
         * The calls left to their owner's own body, as "class call": X01's SRG name in the release jar's ScreenDraw.
         */
        final List<String> vanilla = new ArrayList<>();
        int sites;
    }

    /**
     * L3's check over the root's classes of {@code jar}: in each, the {@link #routedCalls} after the transform
     * must be the {@link #expectedCalls} of those before it, in order; each rewritten one must be a public static
     * method of the GLSM class it calls; and a call left as it is must be the pass-through, or X01's SRG name in the
     * release jar's ScreenDraw, which is left to vanilla's body. One problem per call site.
     */
    static RootLinkage rootLinkage(List<Shipped> classes, RootJar jar, Glsm glsm, Map<Integer, String> folds,
        Map<String, Map<String, String>> named) {
        RootLinkage linkage = new RootLinkage();
        for (Shipped c : classes) {
            String where = jar + " " + c.name;
            if (c.thrown != null) {
                linkage.problems.add(where + ": not transformed, " + c.thrown);
                continue;
            }
            List<String> before = routedCalls(c.original, glsm, named.keySet());
            if (before.isEmpty()) {
                continue;
            }
            List<String> expected = expectedCalls(where, before, glsm, folds, named, linkage.problems);
            List<String> after = routedCalls(c.transform.defined, glsm, named.keySet());
            if (expected.size() != after.size()) {
                linkage.problems.add(where + ": expected " + expected + "\n  got " + after);
            } else {
                for (int i = 0; i < expected.size(); i++) {
                    if (!expected.get(i)
                        .equals(after.get(i))) {
                        linkage.problems
                            .add(where + ": call " + i + ": expected " + expected.get(i) + ", got " + after.get(i));
                    }
                }
            }
            for (String call : after) {
                String plain = call.replaceAll(" \\[.*]$", "");
                String owner = call.substring(0, call.indexOf('.'));
                if (glsm.holds(owner)) {
                    String member = plain.substring(owner.length() + 1);
                    linkage.sites++;
                    linkage.targets.add(member);
                    linkage.owners.merge(owner, 1, Integer::sum);
                    if (!glsm.links(owner, member)) {
                        linkage.problems.add(where + ": no public static " + owner + "." + member);
                    }
                } else if (plain.equals(PASS_THROUGH)) {
                    linkage.passThrough.add(simpleName(c.name) + " " + plain);
                } else if (jar == RootJar.RELEASE && c.name.equals(SCREEN_DRAW) && plain.equals(X01_SRG)) {
                    linkage.vanilla.add(simpleName(c.name) + " " + plain);
                } else {
                    linkage.problems.add(
                        where + ": "
                            + call
                            + (plain.startsWith(GlBytecode.LWJGL) ? " is left on LWJGL"
                                : " is left to its owner's own body, which only X01's SRG name in the release jar's"
                                    + " ScreenDraw may be"));
                }
            }
        }
        return linkage;
    }

    /**
     * What GLSM makes of each of {@code before}'s calls, which {@link #routedCalls} lists, each rewritten one on the
     * GLSM class that owns its new name: a glEnable or glDisable of a capability in {@code folds} its enable/disable
     * method (25:239); another call on a GL owner the same name, as {@link #linkage} expects it, or the name the jar's
     * map for that owner gives; the call GLSM passes through by design, and a call already on a GLSM class, as it is
     * (from 2.2.40 GLSM moves a call on GLStateManager whose name another class owns to that class; OpenGPU makes no
     * such call); a call on another of the jar's named owners, such as X01's OpenGlHelper, the name that owner's map
     * gives, or,
     * when the map lacks the name, the call as it is, left to its owner's own body.
     */
    private static List<String> expectedCalls(String where, List<String> before, Glsm glsm, Map<Integer, String> folds,
        Map<String, Map<String, String>> named, List<String> problems) {
        List<String> expected = new ArrayList<>();
        for (String call : before) {
            String plain = call.replaceAll(" \\[.*]$", "");
            String owner = call.substring(0, call.indexOf('.'));
            String member = call.substring(call.indexOf('.') + 1);
            String method = member.substring(0, member.indexOf('('));
            Map<String, String> map = named.get(owner);
            Integer cap = GlBytecode.capability(member);
            if (plain.equals(PASS_THROUGH) || glsm.holds(owner)) {
                expected.add(call);
            } else if (owner.startsWith(GlBytecode.GL_OWNER) && cap != null && folds.containsKey(cap)) {
                String fold = (method.equals("glEnable") ? "enable" : "disable") + folds.get(cap);
                expected.add(glsm.owner(fold) + "." + fold + "()V");
            } else if (map != null && map.containsKey(method)) {
                String name = map.get(method);
                expected.add(glsm.owner(name) + "." + name + member.substring(member.indexOf('(')));
            } else if (owner.startsWith(GlBytecode.GL_OWNER)) {
                if ((method.equals("glEnable") || method.equals("glDisable")) && cap == null) {
                    problems.add(where + ": " + member + " without a constant capability (25:239)");
                }
                expected.add(glsm.owner(method) + "." + member);
            } else {
                expected.add(call);
            }
        }
        return expected;
    }

    /**
     * {@link GlBytecode#calls}'s list over a jar's GLSM classes: the calls in {@code classFile} on LWJGL's GL owners
     * and on any of {@code glsm}'s classes, in order, as owner.name+descriptor, a glEnable or glDisable with the int
     * constant right before it appended in brackets.
     */
    static List<String> calls(byte[] classFile, Glsm glsm) {
        return routedCalls(classFile, glsm, Collections.<String>emptySet());
    }

    /**
     * The calls in {@code classFile} on LWJGL's GL owners, on any of {@code glsm}'s classes and on {@code owners}, the
     * jar's named owners, in order, as owner.name+descriptor, a glEnable or glDisable with the int constant right
     * before it appended in brackets: {@link #calls}'s list with the named owners' calls in their places.
     */
    static List<String> routedCalls(byte[] classFile, Glsm glsm, Set<String> owners) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!call.owner.startsWith(GlBytecode.GL_OWNER) && !glsm.holds(call.owner)
                    && !owners.contains(call.owner)) {
                    continue;
                }
                String text = call.owner + "." + call.name + call.desc;
                if (call.name.equals("glEnable") || call.name.equals("glDisable")) {
                    AbstractInsnNode previous = call.getPrevious();
                    Integer constant = null;
                    if (previous instanceof IntInsnNode) {
                        constant = ((IntInsnNode) previous).operand;
                    } else if (previous instanceof LdcInsnNode && ((LdcInsnNode) previous).cst instanceof Integer) {
                        constant = (Integer) ((LdcInsnNode) previous).cst;
                    }
                    text += " [" + constant + "]";
                }
                calls.add(text);
            }
        }
        return calls;
    }

    /** A root client class's dotted name under client/, as the report gives it. */
    private static String simpleName(String name) {
        return name.startsWith(ROOT_CLIENT_CLASS) ? name.substring(ROOT_CLIENT_CLASS.length()) : name;
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

    /** A synthetic class {@code name} with one static field of type {@code descriptor} and an empty method. */
    private static byte[] fieldOf(String name, String descriptor) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
        node.name = name.replace('.', '/');
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_STATIC, "field", descriptor, null, null));
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
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

    /**
     * The entry of {@code owner} a Java {@code release} JVM loads from {@code zip}, a multi-release jar: the highest
     * META-INF/versions/n with n at most {@code release} that holds it, else its base entry. Read from the zip's
     * entries, not through {@link GlBytecode#publicStatics}, whose choice L1 checks against it.
     */
    private static String loaded(ZipFile zip, int release, String owner) {
        for (int n = release; n > 8; n--) {
            if (zip.getEntry(versioned(n, owner)) != null) {
                return versioned(n, owner);
            }
        }
        return owner + ".class";
    }

    /** A GLSM class's simple name, as a split jar's report lines give it. */
    private static String glsmName(String owner) {
        return owner.substring(owner.lastIndexOf('/') + 1);
    }

    /**
     * The report's words for L3's {@code sites} rewritten call sites to {@code targets}: on a jar with one GLSM class
     * as since S9, and on a split jar with the sites on each GLSM class they call, {@code owners}, and how many are on
     * a class other than GLStateManager.
     */
    private static String sitesText(Glsm glsm, int sites, Set<String> targets, Map<String, Integer> owners) {
        if (glsm.single()) {
            return sites + " rewritten call sites to "
                + targets.size()
                + " distinct "
                + glsm.classes.get(0)
                + " methods";
        }
        List<String> parts = new ArrayList<>();
        int moved = 0;
        for (Map.Entry<String, Integer> owner : owners.entrySet()) {
            parts.add(glsmName(owner.getKey()) + " " + owner.getValue());
            if (!owner.getKey()
                .equals(GL_STATE_MANAGER)) {
                moved += owner.getValue();
            }
        }
        return sites + " rewritten call sites to "
            + targets.size()
            + " distinct methods of "
            + owners.size()
            + " of the "
            + glsm.classes.size()
            + " GLSM classes "
            + parts
            + ", "
            + moved
            + " of the sites on a class other than GLStateManager";
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

    private static Map<String, String> rootRows() {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("C01", "glGetInteger(I)I");
        rows.put("C02", "glGetString(I)Ljava/lang/String;");
        rows.put("C04", "glPushAttrib(I)V");
        rows.put("C05", "glPopAttrib()V");
        rows.put("C11", "glBindTexture(II)V");
        rows.put("F09", "disableLighting()V");
        rows.put("X01", "setLightmapTextureCoords(IFF)V");
        return Collections.unmodifiableMap(rows);
    }

    /** The redirector's fold table (25:239): capability to the suffix of GLSM's enable/disable methods. */
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
     * A leg's GLSM classes, those its redirector sends rewritten calls to, with each one's public static methods read
     * from the jar's base entry, never by loading the class (S9 plan P3.2). The shape is chosen by reflection on the
     * leg's GLSMRedirector, so that every release is read as it is built, before Angelica 2.2.40's split and after it.
     * Where the redirector has getTargetOwner(String), as from 2.2.40, which split GLStateManager into owner classes
     * (Angelica PR #2305; CI run 40473671907), the classes are its glsmClasses() and the owner of a GLSM method name is
     * its getTargetOwner(name), GLStateManager for a name no other class owns: the owner its transformClassNode gives
     * a call it rewrites to that name. getTargetOwner decides, so a getTargetClassName() kept or brought back beside
     * it for older callers changes nothing (review CK2). Where it has none, as up to 2.2.39, the one class its
     * getTargetClassName() names, GLStateManager, owns every GLSM name, as since S9.
     */
    static final class Glsm {

        /** The GLSM classes, as internal names, in the redirector's order. */
        final List<String> classes;
        /** The redirector's getTargetOwner(String), or null where one class owns every name. */
        private final Method targetOwner;
        /** Each GLSM class's public static methods, as name+descriptor. */
        private final Map<String, Set<String>> statics;

        private Glsm(List<String> classes, Method targetOwner, Map<String, Set<String>> statics) {
            this.classes = Collections.unmodifiableList(new ArrayList<>(classes));
            this.targetOwner = targetOwner;
            this.statics = Collections.unmodifiableMap(new LinkedHashMap<>(statics));
        }

        /** The GLSM classes of {@code redirector}, a leg's initialized GLSMRedirector class, read from {@code jar}. */
        static Glsm of(Class<?> redirector, File jar) throws ReflectiveOperationException, IOException {
            List<String> classes = new ArrayList<>();
            Method targetOwner = publicMethod(redirector, "getTargetOwner", String.class);
            if (targetOwner != null) {
                // Any collection of names: a release that returns a Set in place of the List is read alike (CK3)
                for (Object owner : (Iterable<?>) redirector.getMethod("glsmClasses")
                    .invoke(null)) {
                    classes.add((String) owner);
                }
            } else {
                classes.add(
                    (String) redirector.getMethod("getTargetClassName")
                        .invoke(null));
            }
            Map<String, Set<String>> statics = new LinkedHashMap<>();
            for (String owner : classes) {
                statics.put(owner, Collections.unmodifiableSet(GlBytecode.publicStatics(jar, owner)));
            }
            return new Glsm(classes, targetOwner, statics);
        }

        // The redirector's public method of that name and parameters, or null where it has none
        private static Method publicMethod(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }

        /** Whether one class owns every GLSM name, as up to Angelica 2.2.39. */
        boolean single() {
            return targetOwner == null;
        }

        /** The GLSM class that owns the GLSM method {@code name}: a rewritten call to that name has it as its owner. */
        String owner(String name) {
            if (targetOwner == null) {
                return classes.get(0);
            }
            try {
                return (String) targetOwner.invoke(null, name);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("GLSMRedirector.getTargetOwner(\"" + name + "\")", e);
            }
        }

        /** The owner of {@code member}, a GLSM method or a {@link #calls} entry's member, by its name. */
        String ownerOf(String member) {
            return owner(member.substring(0, member.indexOf('(')));
        }

        /** Whether {@code owner}, an internal name, is one of the GLSM classes. */
        boolean holds(String owner) {
            return statics.containsKey(owner);
        }

        /** The public static methods of the GLSM class {@code owner}. */
        Set<String> statics(String owner) {
            Set<String> methods = statics.get(owner);
            if (methods == null) {
                throw new IllegalArgumentException(owner + " is no GLSM class of " + classes);
            }
            return methods;
        }

        /** Whether a call of {@code member} on {@code owner} links: a public static method of that GLSM class. */
        boolean links(String owner, String member) {
            Set<String> methods = statics.get(owner);
            return methods != null && methods.contains(member);
        }

        /** A copy in which the GLSM class {@code owner} has only {@code methods} as its public static methods. */
        Glsm with(String owner, Set<String> methods) {
            statics(owner);
            Map<String, Set<String>> copy = new LinkedHashMap<>(statics);
            copy.put(owner, Collections.unmodifiableSet(new TreeSet<>(methods)));
            return new Glsm(classes, targetOwner, copy);
        }

        /** The public static methods of all the GLSM classes together. */
        int methods() {
            int methods = 0;
            for (Set<String> owner : statics.values()) {
                methods += owner.size();
            }
            return methods;
        }

        /** Each class's number of public static methods, by its simple name, in the redirector's order. */
        List<String> counts() {
            List<String> counts = new ArrayList<>();
            for (Map.Entry<String, Set<String>> owner : statics.entrySet()) {
                counts.add(
                    glsmName(owner.getKey()) + " "
                        + owner.getValue()
                            .size());
            }
            return counts;
        }

        /** The shape, as L1's report line names it. */
        @Override
        public String toString() {
            return single() ? classes.get(0) + ", the one GLSM class (getTargetClassName)"
                : classes.size() + " GLSM classes (glsmClasses, each name's owner by getTargetOwner)";
        }
    }

    /**
     * One of the root's two shipped jars, whose classes outside :gl's L2, L3 and L4 read (S12 plan P13.3, P14.5; S12
     * review strength-6): the -dev jar, shadowJar's, with Minecraft's MCP names, and the release jar, reobfJar's, with
     * its SRG names. glsmLinkageTest passes each as a file input in a system property, and its classes are read with
     * ZipFile, never from this JVM's classpath, which holds no root class.
     */
    enum RootJar {

        DEV("opengpu.linkage.rootDevJar", "-dev"),
        RELEASE("opengpu.linkage.rootObfJar", "release");

        private final String property;
        private final String label;
        private Map<String, byte[]> classes;

        RootJar(String property, String label) {
            this.property = property;
            this.label = label;
        }

        File file() {
            String path = System.getProperty(property);
            if (path == null || path.isEmpty()) {
                throw new IllegalStateException("no " + property + ": run through :gl-testkit:glsmLinkageTest");
            }
            return new File(path);
        }

        /** Its OpenGPU classes outside :gl's package, dotted name to class file, read once. */
        synchronized Map<String, byte[]> classes() throws IOException {
            if (classes == null) {
                Assertions.assertNull(
                    GlsmLinkageTest.class.getClassLoader()
                        .getResource(ROOT_CLIENT + "render/ScreenDraw.class"),
                    "the root's client classes are on this JVM's classpath");
                Map<String, byte[]> read = new TreeMap<>();
                try (ZipFile zip = new ZipFile(file())) {
                    for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith(ROOT)
                            || name.startsWith(SHIPPED[0])
                            || !name.endsWith(".class")) {
                            continue;
                        }
                        try (InputStream in = zip.getInputStream(entry)) {
                            read.put(
                                name.substring(0, name.length() - ".class".length())
                                    .replace('/', '.'),
                                GlBytecode.readAll(in));
                        }
                    }
                }
                for (String anchor : new String[] { GL_CAPS, SCREEN_DRAW }) {
                    Assertions.assertTrue(read.containsKey(anchor), file().getName() + " has no " + anchor);
                }
                classes = Collections.unmodifiableMap(read);
            }
            return classes;
        }

        @Override
        public String toString() {
            return label;
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
        private Glsm glsm;
        private GlBytecode.Redirector transformer;
        private List<Shipped> shipped;
        private Map<String, Map<String, String>> named;
        private final Map<RootJar, List<Shipped>> root = new EnumMap<>(RootJar.class);

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

        /**
         * Its GLSM classes: where its redirector has getTargetOwner(String), as from Angelica 2.2.40, its glsmClasses()
         * with each name's owner by getTargetOwner, else the one class its getTargetClassName() names; each with its
         * public static methods read from the jar's base entry ({@link Glsm}).
         */
        synchronized Glsm glsm() throws ReflectiveOperationException, IOException {
            if (glsm == null) {
                glsm = Glsm.of(redirector(), jar);
            }
            return glsm;
        }

        /** A copy of its redirector's fold table. */
        Map<Integer, String> folds() throws ReflectiveOperationException {
            return GlsmLinkageTest.folds(redirector());
        }

        /**
         * A copy of its redirector's getNamedClassMethodRedirects(): each owner GLSM maps by name, such as X01's
         * OpenGlHelper and the ARB and EXT classes, to its map of a call's name to its GLSM name, on the class that
         * owns
         * that name (25:721).
         */
        @SuppressWarnings("unchecked")
        synchronized Map<String, Map<String, String>> named() throws ReflectiveOperationException {
            if (named == null) {
                Map<String, Map<String, String>> copy = new TreeMap<>();
                Object maps = redirector().getMethod("getNamedClassMethodRedirects")
                    .invoke(null);
                for (Map.Entry<String, Map<String, String>> owner : ((Map<String, Map<String, String>>) maps)
                    .entrySet()) {
                    copy.put(owner.getKey(), Collections.unmodifiableMap(new TreeMap<>(owner.getValue())));
                }
                named = Collections.unmodifiableMap(copy);
            }
            return named;
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

        /** The root's classes of {@code jar} through its redirector, once, each with its own result. */
        synchronized List<Shipped> root(RootJar jar) throws IOException, NoSuchMethodException {
            List<Shipped> list = root.get(jar);
            if (list == null) {
                GlBytecode.Redirector glsm = transformer();
                List<Shipped> through = new ArrayList<>();
                for (Map.Entry<String, byte[]> c : jar.classes()
                    .entrySet()) {
                    through.add(Shipped.through(glsm, c.getKey(), c.getValue()));
                }
                list = Collections.unmodifiableList(through);
                root.put(jar, list);
            }
            return list;
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
