package io.github.mindbound.opengpu.jartest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * jarTest's checks 10 and 11 (M0 step S12; S12 plan P14.4 with amendments I6 and F9; S12 review strength-5 and
 * strength-6): 25 §4's steps 1-2 over the root's classes, every OpenGPU class of the jar but {@code :gl}'s, on the jars
 * that ship, where the -dev jar names Minecraft's members by their MCP names and the release jar by their SRG names.
 * CallSetTest runs the same steps over :gl's classes; before S12 the root had no GL code. Check 10: every call on
 * LWJGL or {@code OpenGlHelper} is a plain {@code invokestatic} that matches a row of {@code clientcalls.txt} for its
 * own class (ScreenDraw's bracket, GlCaps' probe), each {@code glEnable}/{@code glDisable} with its capability as an
 * int constant right before the call, where GLSMRedirector folds it (25:239), and every row is called in its class; a
 * class outside {@code client/}, the common code's {@code @SideOnly(CLIENT)} members included, has no row, so it makes
 * no such call; none is on 25 §4.2's deny-list, names {@code GLSync}, sets a constant generic attribute (V1) or is a
 * {@code Mouse} call outside {@code client/gui/}; no call reaches vanilla's GL helpers, which make GL objects or bind
 * textures out of this check's sight ({@code TextureUtil}, {@code GLAllocation}, {@code RenderHelper} and any
 * {@code bindTexture(ResourceLocation)}); no {@code invokedynamic} or {@code ldc} handle to these, LWJGL class literal
 * or GL entry point's name beside a reflective call reaches GL past the redirector (R-5); and no other instruction
 * names LWJGL, but the probe's reads of the {@code ContextCapabilities} fields its P01 row returns (declared field and
 * method types are glsmLinkageTest's L2, not this check's). Check 11 (S12 decisions D23): {@code ScreenDraw.world}
 * makes C04 with R-4's bits but {@code GL_TEXTURE_BIT}, F09, X01, C11 of its texture, the {@code Tessellator}'s
 * {@code draw()}, C11 of the literal 0 and C05, and {@code gui} C11 of its texture, the draw and C11 of 0, in that
 * order and nothing more, each row once but C11 twice, with nothing between the first and the last of them but those
 * and nothing after the last, so no path leaves the bracket or the binding and no other call, a vanilla helper's or
 * one that can throw before the draw, sits inside it (S12 review gl-2); and no other method pushes or pops (R-4,
 * R-6). Beside them, {@code clientcalls.txt}'s deny-list is held to {@code :gl}'s {@code callset.txt} (S12 decisions
 * D13). glsmLinkageTest (gl-testkit) runs steps 3-4 over the same classes of the same two jars.
 */
class ClientCallSetTest {

    private static final String OPENGPU = "io/github/mindbound/opengpu/";
    private static final String CLIENT = OPENGPU + "client/";
    private static final String GUI = CLIENT + "gui/";
    /** :gl's classes, which CallSetTest checks against callset.txt; every other OpenGPU class is checked here. */
    private static final String GL_PROJECT = OPENGPU + "gl/";
    private static final String LWJGL = "org/lwjgl/";
    private static final String OPENGL = LWJGL + "opengl/";
    /** X01's owner, a Minecraft class whose methods GLSMRedirector maps by name (25 §4.1). */
    private static final String OPEN_GL_HELPER = "net/minecraft/client/renderer/OpenGlHelper";
    /**
     * Vanilla's GL helpers, whose GL this check cannot see: texture uploads, display lists and buffers, and lighting
     * (S12 review strength-5). Gui's, the font's and RenderBlocks' draws stay allowed, as DisplayScreen and
     * DisplayChassis use them on purpose.
     */
    private static final Set<String> GL_HELPERS = new HashSet<>(
        Arrays.asList(
            "net/minecraft/client/renderer/texture/TextureUtil",
            "net/minecraft/client/renderer/GLAllocation",
            "net/minecraft/client/renderer/RenderHelper"));
    /**
     * {@code bindTexture(ResourceLocation)} by its MCP name and by the SRG names of TextureManager's, Render's and
     * TileEntitySpecialRenderer's, on any owner, since a subclass's own call names the subclass.
     */
    private static final Set<String> BIND_TEXTURE = new HashSet<>(
        Arrays.asList("bindTexture", "func_110577_a", "func_110776_a", "func_147499_a"));
    private static final String BIND_TEXTURE_DESC = "(Lnet/minecraft/util/ResourceLocation;)V";
    /** The quad's draw, {@code Tessellator.draw()I} by any name: its only no-argument method that returns an int. */
    private static final String TESSELLATOR = "net/minecraft/client/renderer/Tessellator";
    /** The GUI's raw window pixels (03:51; S12 plan P12.4): no GL, so GLSM leaves it, and no row holds it. */
    private static final String MOUSE = LWJGL + "input/Mouse";
    private static final String GLSYNC = "L" + OPENGL + "GLSync;";
    /** The capabilities GLSMRedirector's glCapRedirects folds at 2.2.8, as CallSetTest has them (25:239). */
    private static final Set<Integer> FOLDED = new HashSet<>(
        Arrays.asList(3008, 3042, 2929, 2884, 2896, 3553, 2912, 32826, 3089));
    /** A string constant that names a GL entry point: glGetError, or LWJGL's native form nglGetError. */
    private static final Pattern GL_NAME = Pattern.compile("n?gl[A-Z][A-Za-z0-9_]*");
    private static final String SCREEN_DRAW = CLIENT + "render/ScreenDraw";
    private static final String PUSH = OPENGL + "GL11.glPushAttrib(I)V";
    private static final String POP = OPENGL + "GL11.glPopAttrib()V";
    /**
     * R-4's bits but {@code GL_TEXTURE_BIT}, {@code GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT |
     * GL_LIGHTING_BIT} (25:436-445; 20:169; S12 decisions D23).
     */
    private static final int PUSHED = 0x6140;
    /** {@code GL_TEXTURE_2D}, C11's target. */
    private static final int TEXTURE_2D = 3553;
    /** The quad's {@code draw()}, as check 11 lists it among a bracket method's calls. */
    private static final String DRAW = "draw()";
    /** C11 of the bracket method's texture, its first parameter (S12 decisions D23). */
    private static final String BIND = "C11(texture)";
    /** C11 of the literal 0, an {@code iconst_0} (S12 decisions D23). */
    private static final String UNBIND = "C11(0)";
    /** Each bracket method of ScreenDraw and its calls, in order (S12 plan P14.4; S12 decisions D23). */
    private static final Map<String, List<String>> BRACKETS = brackets();

    private static final Rows ROWS = Rows.load();

    static Stream<Path> shippedJars() {
        return JarContents.shippedJars();
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void clientCodeCallsOnlyItsRows(Path jar) throws IOException {
        boolean release = isRelease(jar);
        List<String> problems = new ArrayList<>();
        Set<String> classes = new HashSet<>();
        Set<String> used = new TreeSet<>();
        for (ClassNode node : checkedClasses(jar)) {
            classes.add(node.name);
            for (MethodNode method : node.methods) {
                boolean reflective = reflective(method);
                for (AbstractInsnNode insn : method.instructions) {
                    check(node, method, insn, release, reflective, problems, used);
                }
            }
        }
        // Each row is the call of a class that ships, so the scan above has seen every call it names in this jar, the
        // release jar's SRG name for X01 included
        for (Row row : ROWS.rows) {
            if (!classes.contains(row.owner)) {
                problems.add(row + ": no such class in " + jar.getFileName());
            } else if (!used.contains(row.key())) {
                problems.add(row + ": its class does not make this call in " + jar.getFileName());
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void theScreenBracketChangesOnlyItsState(Path jar) throws IOException {
        boolean release = isRelease(jar);
        List<String> problems = new ArrayList<>();
        ClassNode screen = null;
        for (ClassNode node : checkedClasses(jar)) {
            if (node.name.equals(SCREEN_DRAW)) {
                screen = node;
            }
            for (MethodNode method : node.methods) {
                if (node.name.equals(SCREEN_DRAW) && BRACKETS.containsKey(method.name)) {
                    continue;
                }
                for (AbstractInsnNode insn : method.instructions) {
                    String text = callText(insn);
                    if (PUSH.equals(text) || POP.equals(text)) {
                        problems.add(
                            node.name + "." + method.name + ": " + text + " outside ScreenDraw's two brackets (R-6)");
                    }
                }
            }
        }
        Assertions.assertNotNull(screen, SCREEN_DRAW + " is not in " + jar.getFileName());
        for (Map.Entry<String, List<String>> bracket : BRACKETS.entrySet()) {
            List<MethodNode> found = new ArrayList<>();
            for (MethodNode method : screen.methods) {
                if (method.name.equals(bracket.getKey())) {
                    found.add(method);
                }
            }
            if (found.size() != 1) {
                problems.add(SCREEN_DRAW + " has " + found.size() + " methods named " + bracket.getKey() + ", not 1");
            } else {
                checkBracket(screen, found.get(0), bracket.getValue(), release, problems);
            }
        }
        assertNone(problems);
    }

    /**
     * One deny-list (S12 decisions D13): clientcalls.txt's A rows are {@code :gl}'s callset.txt's, the same ids for the
     * same calls, in both directions, and every other row whose id callset.txt has names the same call there, with the
     * same folded constant. F09 and X01 are 25 §4.1's ids, which callset.txt has no row for, and P01 is S12's. The
     * jarTest task hands this test callset.txt as a file input.
     */
    @Test
    void theDenyListAndTheSharedIdsAreCallsetsOwn() throws IOException {
        Map<String, String> callset = new TreeMap<>();
        for (String line : Files.readAllLines(JarContents.file("opengpu.callset"), StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(" ", 2);
            Assertions.assertNull(callset.put(fields[0], fields[1]), "callset.txt repeats " + fields[0]);
        }
        Map<String, String> callsetDenied = new TreeMap<>();
        for (Map.Entry<String, String> row : callset.entrySet()) {
            if (row.getKey()
                .startsWith("A")) {
                callsetDenied.put(row.getKey(), row.getValue());
            }
        }
        Assertions.assertFalse(callsetDenied.isEmpty(), "callset.txt has no A row");
        Map<String, String> denied = new TreeMap<>();
        for (Map.Entry<String, String> row : ROWS.denied.entrySet()) {
            denied.put(row.getValue(), row.getKey());
        }
        Assertions.assertEquals(callsetDenied, denied, "clientcalls.txt's deny-list against callset.txt's");
        List<String> problems = new ArrayList<>();
        for (Row row : ROWS.rows) {
            String shared = callset.get(row.id);
            String own = row.call + (row.constant == null ? "" : " " + row.constant);
            if (shared != null && !shared.equals(own)) {
                problems.add(row + ": callset.txt's " + row.id + " is " + shared);
            }
        }
        assertNone(problems);
    }

    // Check 10

    private static void check(ClassNode node, MethodNode method, AbstractInsnNode insn, boolean release,
        boolean reflective, List<String> problems, Set<String> used) {
        String where = node.name + "." + method.name;
        if (insn instanceof MethodInsnNode) {
            checkCall(node, where, (MethodInsnNode) insn, release, problems, used);
        } else if (insn instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) insn;
            // The probe reads the booleans of the object its P01 row returns, and keeps none of them as an object
            if (field.owner.startsWith(LWJGL)
                && !(field.getOpcode() == Opcodes.GETFIELD && ROWS.returns(node.name, field.owner))) {
                problems.add(where + ": the field " + field.owner + "." + field.name + ", which no row gives");
            }
        } else if (insn instanceof InvokeDynamicInsnNode) {
            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
            List<Object> handles = new ArrayList<>(Arrays.asList(indy.bsmArgs));
            handles.add(indy.bsm);
            for (Object argument : handles) {
                if (argument instanceof Handle && reachesGl((Handle) argument)) {
                    problems.add(where + ": an invokedynamic handle to " + text((Handle) argument) + " (R-5)");
                }
            }
        } else if (insn instanceof LdcInsnNode) {
            checkConstant(where, ((LdcInsnNode) insn).cst, reflective, problems);
        } else if (insn instanceof TypeInsnNode && ((TypeInsnNode) insn).desc.contains(LWJGL)) {
            problems.add(where + ": the type " + ((TypeInsnNode) insn).desc + ", which no row gives");
        }
    }

    /**
     * R-5 for an ldc: no LWJGL class literal, which could look an LWJGL member up past GLSMRedirector; no method handle
     * to LWJGL or OpenGlHelper; and no GL entry point's name in a method that looks members up.
     */
    private static void checkConstant(String where, Object constant, boolean reflective, List<String> problems) {
        if (constant instanceof Type) {
            Type type = (Type) constant;
            if (type.getSort() == Type.OBJECT && type.getInternalName()
                .startsWith(LWJGL)) {
                problems.add(where + ": the class literal " + type.getInternalName() + " (R-5)");
            }
        } else if (constant instanceof Handle) {
            if (reachesGl((Handle) constant)) {
                problems.add(where + ": a method handle to " + text((Handle) constant) + " (R-5)");
            }
        } else if (constant instanceof String && reflective) {
            if (GL_NAME.matcher((String) constant)
                .matches()) {
                problems.add(where + ": the GL entry point \"" + constant + "\" beside a reflective call (R-5)");
            }
        }
    }

    private static void checkCall(ClassNode node, String where, MethodInsnNode call, boolean release,
        List<String> problems, Set<String> used) {
        String text = call.owner + "." + call.name + call.desc;
        String at = where + ": " + text;
        if (isGlHelper(call.owner, call.name, call.desc)) {
            problems.add(at + ": a vanilla GL helper, whose GL calls no row and no check sees (S12 review strength-5)");
            return;
        }
        if (!isGl(call.owner)) {
            return;
        }
        if (call.owner.equals(MOUSE)) {
            if (!node.name.startsWith(GUI)) {
                problems.add(at + ": Mouse outside client/gui/, whose raw window pixels it gives (S12 plan P12.4)");
            }
            return;
        }
        if (call.getOpcode() != Opcodes.INVOKESTATIC) {
            problems.add(at + " is no invokestatic (25 §4 step 1)");
            return;
        }
        if (!call.owner.startsWith(OPENGL) && !call.owner.equals(OPEN_GL_HELPER)) {
            problems.add(at + ": an LWJGL call outside org/lwjgl/opengl/, which no row holds");
            return;
        }
        String denied = ROWS.denied.get(text);
        if (denied != null) {
            problems.add(at + " is " + denied + " of 25 §4.2's deny-list");
        }
        if (call.desc.contains(GLSYNC)) {
            problems.add(at + ": a GLSync descriptor, which GLSM's unmapped-call check skips (25 §4 step 2)");
        }
        if (call.name.startsWith("glVertexAttrib") && !call.name.equals("glVertexAttribIPointer")
            && !call.name.equals("glVertexAttribPointer")
            && !call.name.equals("glVertexAttribDivisor")) {
            problems.add(at + ": a constant generic attribute (V1, 24:203)");
        }
        Integer constant = null;
        if (call.owner.startsWith(OPENGL) && (call.name.equals("glEnable") || call.name.equals("glDisable"))) {
            constant = constantBefore(call);
            if (constant == null) {
                problems.add(at + " takes no int constant right before the call, where GLSM folds it (25:239)");
                return;
            }
        }
        Row row = ROWS.match(node.name, text, constant, release);
        if (row == null) {
            problems.add(
                at + (constant == null ? "" : " with " + constant)
                    + " matches no row of "
                    + node.name.substring(OPENGPU.length())
                    + " in clientcalls.txt"
                    + (node.name.startsWith(CLIENT) ? "" : ", which has rows for client/ classes only"));
            return;
        }
        used.add(row.key());
    }

    // Check 11

    /**
     * One bracket method: its calls on LWJGL and OpenGlHelper, with the {@code Tessellator}'s {@code draw()} among
     * them, are {@code expected}'s in order and nothing more (R-6's one push level; S12 decisions D23), each C11 named
     * by what it binds ({@link #bound}), its push takes R-4's bits but {@code GL_TEXTURE_BIT} as the constant right
     * before the call, it has no try block (S12 amendments I6: javac would copy a finally block's pop or bind), between
     * the first and the last of those calls there is no jump, switch, return or throw, which a path could leave the
     * bracket or the binding by, no invokedynamic, and no call but its rows and the draw, and after the last there is
     * nothing but the return. A call into OpenGPU's own code or a vanilla helper would hide GL calls, and the quad's
     * {@code startDrawingQuads()} and vertices, which can throw while another renderer's quad is open, come before the
     * first, so a suppressed throw never leaves a push unpopped or the texture bound (S12 review gl-2 and strength-5).
     */
    private static void checkBracket(ClassNode node, MethodNode method, List<String> expected, boolean release,
        List<String> problems) {
        String where = node.name + "." + method.name;
        List<String> made = new ArrayList<>();
        List<AbstractInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (isDraw(call)) {
                made.add(DRAW);
                calls.add(call);
                continue;
            }
            if (!isGl(call.owner)) {
                continue;
            }
            String text = call.owner + "." + call.name + call.desc;
            Integer constant = call.name.equals("glEnable") || call.name.equals("glDisable") ? constantBefore(call)
                : null;
            Row row = ROWS.match(node.name, text, constant, release);
            String step = row != null ? row.id : constant == null ? text : text + " " + constant;
            made.add(row != null && row.id.equals("C11") ? step + "(" + bound(call) + ")" : step);
            calls.add(call);
            if (text.equals(PUSH)) {
                Integer bits = constantBefore(call);
                if (!Integer.valueOf(PUSHED)
                    .equals(bits)) {
                    problems.add(
                        String.format(
                            "%s pushes %s, not 0x%X, R-4's bits but GL_TEXTURE_BIT (GL_ENABLE_BIT |"
                                + " GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_LIGHTING_BIT; S12 decisions D23)",
                            where,
                            bits == null ? "no constant" : String.format("0x%X", bits),
                            PUSHED));
                }
            }
        }
        if (!made.equals(expected)) {
            problems.add(where + " makes " + made + ", not " + expected + " (R-4, R-6; S12 decisions D23)");
        }
        if (!method.tryCatchBlocks.isEmpty()) {
            problems.add(where + " has a try block, whose finally javac would copy (S12 amendments I6)");
        }
        if (calls.isEmpty()) {
            return;
        }
        AbstractInsnNode first = calls.get(0);
        AbstractInsnNode last = calls.get(calls.size() - 1);
        String span = "between its " + made.get(0) + " and its " + made.get(made.size() - 1);
        for (AbstractInsnNode insn = first.getNext(); insn != null && insn != last; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                || insn instanceof LookupSwitchInsnNode
                || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN
                || opcode == Opcodes.ATHROW) {
                problems.add(where + ": a jump, switch, return or throw " + span + " (R-6)");
            } else if (insn instanceof InvokeDynamicInsnNode) {
                problems.add(where + ": an invokedynamic " + span);
            } else if (insn instanceof MethodInsnNode && !isGl(((MethodInsnNode) insn).owner)
                && !isDraw((MethodInsnNode) insn)) {
                    problems.add(
                        where + " calls "
                            + callText(insn)
                            + " "
                            + span
                            + ", where only its rows and the Tessellator's draw() belong");
                }
        }
        // Nothing after the last call but the return: no restore and no other call (S12 decisions D23)
        List<Integer> after = new ArrayList<>();
        for (AbstractInsnNode insn = last.getNext(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                after.add(insn.getOpcode());
            }
        }
        if (!after.equals(Collections.singletonList(Opcodes.RETURN))) {
            problems.add(
                where + " has the opcodes "
                    + after
                    + " after its "
                    + made.get(made.size() - 1)
                    + ", not the return alone");
        }
    }

    /**
     * What a C11 call binds, as its two operands right before the call give it: {@code texture}, the bracket method's
     * first parameter (local 0 of a static method), or {@code 0}, the literal {@code iconst_0}, each on
     * {@code GL_TEXTURE_2D} or else named with the target it has; any other operand is named by its last opcode.
     */
    private static String bound(MethodInsnNode call) {
        AbstractInsnNode operand = call.getPrevious();
        if (operand == null) {
            return "no operand";
        }
        String what;
        if (operand instanceof VarInsnNode && operand.getOpcode() == Opcodes.ILOAD
            && ((VarInsnNode) operand).var == 0) {
            what = "texture";
        } else if (operand.getOpcode() == Opcodes.ICONST_0) {
            what = "0";
        } else {
            return "an operand ending in opcode " + operand.getOpcode();
        }
        Integer target = constantBefore(operand);
        return Integer.valueOf(TEXTURE_2D)
            .equals(target) ? what : what + " on target " + target;
    }

    private static Map<String, List<String>> brackets() {
        Map<String, List<String>> brackets = new LinkedHashMap<>();
        brackets.put("world", Arrays.asList("C04", "F09", "X01", BIND, DRAW, UNBIND, "C05"));
        brackets.put("gui", Arrays.asList(BIND, DRAW, UNBIND));
        return Collections.unmodifiableMap(brackets);
    }

    // Helpers

    /** Whether {@code jar} is the release jar, reobfJar's, which names Minecraft's members by SRG names. */
    private static boolean isRelease(Path jar) {
        return jar.equals(JarContents.jar("opengpu.obfJar"));
    }

    /**
     * The jar's OpenGPU classes but {@code :gl}'s: the client classes, which hold every row, and the rest, the common
     * code with its {@code @SideOnly(CLIENT)} members, whose server view check 1 strips (S12 review strength-6).
     */
    private static List<ClassNode> checkedClasses(Path jar) throws IOException {
        List<ClassNode> checked = new ArrayList<>();
        boolean client = false;
        for (ClassNode node : JarContents.classes(jar)) {
            if (node.name.startsWith(OPENGPU) && !node.name.startsWith(GL_PROJECT)) {
                checked.add(node);
                client |= node.name.startsWith(CLIENT);
            }
        }
        Assertions.assertTrue(client, jar.getFileName() + " has no class under " + CLIENT);
        return checked;
    }

    /** A call on LWJGL, or on X01's owner. */
    private static boolean isGl(String owner) {
        return owner.startsWith(LWJGL) || owner.equals(OPEN_GL_HELPER);
    }

    /** A call on one of vanilla's GL helpers, or a {@code bindTexture(ResourceLocation)} on any owner. */
    private static boolean isGlHelper(String owner, String name, String desc) {
        return GL_HELPERS.contains(owner) || BIND_TEXTURE.contains(name) && desc.equals(BIND_TEXTURE_DESC);
    }

    /** A handle to LWJGL, X01's owner or a vanilla GL helper. */
    private static boolean reachesGl(Handle handle) {
        return isGl(handle.getOwner()) || isGlHelper(handle.getOwner(), handle.getName(), handle.getDesc());
    }

    /** The {@code Tessellator}'s {@code draw()}, by its descriptor, so in both jars. */
    private static boolean isDraw(MethodInsnNode call) {
        return call.owner.equals(TESSELLATOR) && call.desc.equals("()I");
    }

    /** A call as owner.name(descriptor), or null for another instruction. */
    private static String callText(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode)) {
            return null;
        }
        MethodInsnNode call = (MethodInsnNode) insn;
        return call.owner + "." + call.name + call.desc;
    }

    private static String text(Handle handle) {
        return handle.getOwner() + "." + handle.getName() + handle.getDesc();
    }

    /** Whether the method looks members up: a call on MethodHandles.Lookup, Class or java.lang.reflect. */
    private static boolean reflective(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                String owner = ((MethodInsnNode) insn).owner;
                if (owner.equals("java/lang/invoke/MethodHandles$Lookup") || owner.equals("java/lang/Class")
                    || owner.startsWith("java/lang/reflect/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The int constant pushed right before the call, as GLSMRedirector reads it, or null. */
    private static Integer constantBefore(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        if (previous instanceof IntInsnNode && previous.getOpcode() != Opcodes.NEWARRAY) {
            return ((IntInsnNode) previous).operand;
        }
        if (previous instanceof LdcInsnNode && ((LdcInsnNode) previous).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) previous).cst;
        }
        if (previous != null && previous.getOpcode() >= Opcodes.ICONST_M1 && previous.getOpcode() <= Opcodes.ICONST_5) {
            return previous.getOpcode() - Opcodes.ICONST_0;
        }
        return null;
    }

    private static void assertNone(List<String> problems) {
        Assertions
            .assertTrue(problems.isEmpty(), () -> problems.size() + " problem(s):\n" + String.join("\n", problems));
    }

    // clientcalls.txt

    /** One row of a client class: its id, its call with MCP names, its folded constant or its SRG name. */
    static final class Row {

        /** The class's internal name. */
        final String owner;
        final String id;
        final String call;
        final Integer constant;
        /** For a Minecraft method, its name in the release jar. */
        final String srg;

        Row(String owner, String id, String call, Integer constant, String srg) {
            this.owner = owner;
            this.id = id;
            this.call = call;
            this.constant = constant;
            this.srg = srg;
        }

        /** The call as {@code release} or the -dev jar names it. */
        String call(boolean release) {
            if (!release || srg == null) {
                return call;
            }
            return call.substring(0, call.indexOf('.') + 1) + srg + call.substring(call.indexOf('('));
        }

        String key() {
            return owner + " " + id;
        }

        @Override
        public String toString() {
            return owner.substring(CLIENT.length()) + " " + id + " " + call;
        }
    }

    /** clientcalls.txt: the rows of each client class, and 25 §4.2's deny-list by call. */
    static final class Rows {

        final List<Row> rows = new ArrayList<>();
        final Map<String, String> denied = new HashMap<>();

        static Rows load() {
            Rows set = new Rows();
            Set<String> keys = new HashSet<>();
            try (InputStream in = ClientCallSetTest.class.getResourceAsStream("clientcalls.txt")) {
                Assertions.assertNotNull(in, "clientcalls.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(" ");
                    Assertions.assertTrue(fields.length == 3 || fields.length == 4, line);
                    String id = fields[1];
                    String call = fields[2];
                    Assertions.assertTrue(keys.add(fields[0] + " " + id), "duplicate row " + line);
                    if (fields[0].equals("*")) {
                        Assertions
                            .assertTrue(id.startsWith("A") && fields.length == 3 && call.startsWith(OPENGL), line);
                        set.denied.put(call, id);
                        continue;
                    }
                    Integer constant = null;
                    String srg = null;
                    if (call.startsWith(OPENGL)) {
                        // A C or P row as it is called, or an F row with its folded constant
                        Assertions.assertEquals(id.startsWith("F"), fields.length == 4, line);
                        Assertions.assertTrue(id.matches("[CFP][0-9]+"), line);
                        constant = fields.length == 4 ? Integer.valueOf(fields[3]) : null;
                        Assertions.assertTrue(constant == null || FOLDED.contains(constant), line);
                    } else {
                        // An X row: a Minecraft method, by its MCP name and its SRG name
                        Assertions.assertTrue(
                            call.startsWith(OPEN_GL_HELPER + ".") && id.matches("X[0-9]+") && fields.length == 4,
                            line);
                        srg = fields[3];
                        Assertions.assertTrue(srg.matches("func_[0-9]+_[a-z]+"), line);
                    }
                    set.rows.add(new Row(CLIENT + fields[0], id, call, constant, srg));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return set;
        }

        /**
         * The row of class {@code owner} that {@code call} matches in the -dev jar or, with {@code release}, the
         * release jar: a glEnable or glDisable of a folded capability by its F row's constant, any other call by a row
         * with no constant; or null.
         */
        Row match(String owner, String call, Integer constant, boolean release) {
            boolean folded = constant != null && FOLDED.contains(constant);
            for (Row row : rows) {
                if (row.owner.equals(owner) && row.call(release)
                    .equals(call) && (folded ? constant.equals(row.constant) : row.constant == null)) {
                    return row;
                }
            }
            return null;
        }

        /** Whether a row of class {@code owner} returns an object of {@code type}, whose fields that class may read. */
        boolean returns(String owner, String type) {
            for (Row row : rows) {
                if (row.owner.equals(owner) && row.call.endsWith(")L" + type + ";")) {
                    return true;
                }
            }
            return false;
        }
    }
}
