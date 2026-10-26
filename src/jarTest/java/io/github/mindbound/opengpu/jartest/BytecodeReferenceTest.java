package io.github.mindbound.opengpu.jartest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The bytecode reference test of docs/research/24-architecture-gl-first.md §3.1, on the jars that ship (Shadow rewrites
 * type references and string constants while relocating, so only the built jars show what ships). Common code,
 * everything outside OpenGPU's client package and :gl's package, must not name LWJGL, the client packages of
 * Minecraft, FML or Forge, or OpenGPU's own client code: the dedicated server has none of them, and the dev runServer
 * classpath has LWJGL, which would hide a leak (21 §1.3). The scan sees what a dedicated server loads: FML's
 * SideTransformer strips members marked {@code @SideOnly(Side.CLIENT)} there and refuses classes so marked
 * (SideTransformer.java:44-78), so the usual client overrides in common blocks and items pass. Decision 9's two
 * lwjgl3ify footguns are checked in every class. Since M0 step S11, common code also loads no class by name, which
 * closes 24:90's first known gap for OpenGPU's own code, and sends nothing on an FML channel (13:9); and the tick
 * listener that drains every frame status to the server thread is wired as D16 has it, which no test that loads
 * classes may see.
 */
class BytecodeReferenceTest {

    private static final String OPENGPU = "io/github/mindbound/opengpu/";
    private static final String[] CLIENT_CODE = { OPENGPU + "client/", OPENGPU + "gl/" };
    private static final String[] CLIENT_ONLY = { "org/lwjgl/", "net/minecraft/client/", "cpw/mods/fml/client/",
        "net/minecraftforge/client/", OPENGPU + "client/", OPENGPU + "gl/" };
    private static final String[] CLIENT_CODE_BY_NAME = { "io.github.mindbound.opengpu.client.",
        "io.github.mindbound.opengpu.gl.", OPENGPU + "client/", OPENGPU + "gl/" };
    private static final String SIDE_ONLY = "Lcpw/mods/fml/relauncher/SideOnly;";
    private static final String SIDED_PROXY = "Lcpw/mods/fml/common/SidedProxy;";
    private static final String[] FML_SENDERS = { "cpw/mods/fml/common/network/FMLEventChannel",
        "cpw/mods/fml/common/network/simpleimpl/SimpleNetworkWrapper" };
    private static final String[] REPLY_OWNERS = { "cpw/mods/fml/common/network/FMLNetworkEvent$CustomPacketEvent",
        "cpw/mods/fml/common/network/FMLNetworkEvent$ServerCustomPacketEvent",
        "cpw/mods/fml/common/network/FMLNetworkEvent$ClientCustomPacketEvent" };
    /**
     * commonCodeSendsNoFmlChannelPacket's named exceptions, each a method as "class.method" in internal names, which
     * may call an FML channel's {@code send*} because it runs on the server thread only (13:139). None in M0, whose
     * server sends nothing on its channel. M4's pool send needs none: it goes through
     * {@code NetworkManager.scheduleOutboundPacket} (13:117), which this check does not match (S11 decisions D12).
     */
    private static final Set<String> SENDS_ALLOWED = Collections.<String>emptySet();
    private static final String COMMON_PROXY = OPENGPU + "CommonProxy";
    private static final String TICK_LISTENER = OPENGPU + "server/TickListener";
    private static final String SESSION = OPENGPU + "server/ServerSession";
    private static final String TICK_QUEUE = OPENGPU + "server/TickQueue";
    private static final String FML_HANDLER = "cpw/mods/fml/common/FMLCommonHandler";
    private static final String EVENT_BUS = "cpw/mods/fml/common/eventhandler/EventBus";
    private static final String SERVER_TICK = "cpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent";
    private static final String PHASE = "cpw/mods/fml/common/gameevent/TickEvent$Phase";
    private static final String SUBSCRIBE = "Lcpw/mods/fml/common/eventhandler/SubscribeEvent;";

    static Stream<Path> shippedJars() {
        return JarContents.shippedJars();
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void commonCodeNamesNoClientClass(Path jar) throws IOException {
        List<String> problems = new ArrayList<>();
        for (ClassNode node : JarContents.classes(jar)) {
            if (startsWithAny(node.name, CLIENT_CODE)) {
                continue;
            }
            if (isClientOnly(node.visibleAnnotations)) {
                problems
                    .add(node.name + " is @SideOnly(CLIENT) outside the client packages; a server refuses to load it");
                continue;
            }
            serverView(node);
            References references = References.of(node);
            for (String type : references.types) {
                if (startsWithAny(type, CLIENT_ONLY)) {
                    problems.add(node.name + " references " + type);
                }
            }
            for (String string : references.strings) {
                if (containsAny(string, CLIENT_CODE_BY_NAME)) {
                    problems.add(node.name + " names OpenGPU's client code in \"" + string + "\"");
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void sidedProxiesNameAClassOnEachSide(Path jar) throws IOException {
        List<ClassNode> classes = JarContents.classes(jar);
        Set<String> names = new HashSet<>();
        for (ClassNode node : classes) {
            names.add(node.name);
        }
        List<String> problems = new ArrayList<>();
        int proxies = 0;
        for (ClassNode node : classes) {
            for (FieldNode field : node.fields) {
                AnnotationNode proxy = find(field.visibleAnnotations, SIDED_PROXY);
                if (proxy == null) {
                    continue;
                }
                proxies++;
                // FML loads both by name, the client one only on a client (ProxyInjector.java:58-59)
                String where = node.name + "." + field.name;
                String client = value(proxy, "clientSide").replace('.', '/');
                String server = value(proxy, "serverSide").replace('.', '/');
                if (!client.startsWith(OPENGPU + "client/") || !names.contains(client)) {
                    problems.add(where + ": clientSide " + client + " is no class under " + OPENGPU + "client/");
                }
                if (startsWithAny(server, CLIENT_CODE) || !names.contains(server)) {
                    problems.add(where + ": serverSide " + server + " is no common class in the jar");
                }
            }
        }
        Assertions.assertNotEquals(0, proxies, "no @SidedProxy field found; OpenGPU.proxy is one");
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void noClassNamesAnLwjglClassInAString(Path jar) throws IOException {
        // Decision 9's second footgun: lwjgl3ify remaps LWJGL type references, not strings (14 §2)
        List<String> problems = new ArrayList<>();
        for (ClassNode node : JarContents.classes(jar)) {
            for (String string : References.of(node).strings) {
                if (string.contains("org.lwjgl.") || string.contains("org/lwjgl/")) {
                    problems.add(node.name + ": \"" + string + "\"");
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void nothingOptsOutOfLwjgl3ify(Path jar) throws IOException {
        // Decision 9's first footgun: lwjgl3ify skips a class marked @Lwjgl3Aware and every class of a jar whose
        // manifest says Lwjgl3ify-Aware (14 §2)
        List<String> problems = new ArrayList<>();
        if (JarContents.manifest(jar)
            .getMainAttributes()
            .getValue("Lwjgl3ify-Aware") != null) {
            problems.add("the manifest sets Lwjgl3ify-Aware");
        }
        for (ClassNode node : JarContents.classes(jar)) {
            for (String type : References.of(node).types) {
                if (type.startsWith("me/eigenraven/lwjgl3ify/")) {
                    problems.add(node.name + " references " + type);
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void classFilesAreJava8(Path jar) throws IOException {
        // The one Java 8 convention, on the artifact: OpenGPU's own classes are major 52, shaded libraries at most 52
        List<String> problems = new ArrayList<>();
        for (String name : JarContents.names(jar)) {
            if (name.startsWith("META-INF/versions/")) {
                problems.add(name + ": the jar is not to be multi-release");
            }
        }
        for (ClassNode node : JarContents.classes(jar)) {
            int major = node.version & 0xFFFF;
            boolean own = node.name.startsWith(OPENGPU) && !node.name.startsWith(OPENGPU + "shadow/");
            if (own ? major != 52 : major > 52) {
                problems.add(node.name + " is major " + major);
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void ownReferencesResolveInTheJar(Path jar) throws IOException {
        // A class lost to relocation or minimizing would fail only where it is first loaded, in game
        List<ClassNode> classes = JarContents.classes(jar);
        Set<String> names = new HashSet<>();
        for (ClassNode node : classes) {
            names.add(node.name);
        }
        List<String> problems = new ArrayList<>();
        for (ClassNode node : classes) {
            for (String type : References.of(node).types) {
                if (type.startsWith(OPENGPU) && !names.contains(type)) {
                    problems.add(node.name + " references " + type + ", which the jar lacks");
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void commonCodeLoadsNoClassReflectively(Path jar) throws IOException {
        // 24:90's first known gap: a class loaded by name has no type reference for the checks above to read, so
        // Class.forName("net.minecraft.client.Minecraft") in common code would load a client class on a dedicated
        // server unseen (S11 plan P15.8, check 7). Every call that takes a class name and returns a Class counts, on
        // any owner and by any name: a call through a subclass, such as launchwrapper's LaunchClassLoader, names that
        // subclass (JLS 13.1), and helpers such as FML's ReflectionHelper.getClass load by name inside the library,
        // where no check of OpenGPU's code sees it (ReflectionHelper.java:152-159) [proposal]. :gl's MethodHandles use
        // is client code
        List<String> problems = new ArrayList<>();
        for (ClassNode node : commonClasses(jar)) {
            for (MethodNode method : node.methods) {
                for (Handle use : uses(method)) {
                    if (loadsAClassByName(use)) {
                        problems.add(node.name + "." + method.name + " calls " + use.getOwner() + "." + use.getName());
                    }
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void commonCodeSendsNoFmlChannelPacket(Path jar) throws IOException {
        // 13:9: an FML channel's send sets two attributes of one shared embedded channel and then writes, so two
        // threads that send at once can swap their targets. The ban is stricter than 13:117 and 13:139, which allow
        // sends from the server thread [proposal]: S11's server sends nothing on its channel, and a later send from
        // the server thread gets a named entry in SENDS_ALLOWED (S11 plan P15.8, check 8). M4's pool send goes
        // through NetworkManager.scheduleOutboundPacket (13:117), which no rule here matches. A listener's reply is
        // a send too, since FML sends it from the handler (FMLEventChannel.java:104-108); reply is declared in
        // CustomPacketEvent, but javac names the static type of the qualifying expression as the field's owner
        // (JLS 13.1), so all three event classes count (S11 plan U5.8)
        List<String> problems = new ArrayList<>();
        for (ClassNode node : commonClasses(jar)) {
            for (MethodNode method : node.methods) {
                String where = node.name + "." + method.name;
                for (Handle use : uses(method)) {
                    if (sendsOnAnFmlChannel(use) && !SENDS_ALLOWED.contains(where)) {
                        problems.add(where + " calls " + use.getOwner() + "." + use.getName());
                    } else if (setsAReply(use)) {
                        problems.add(where + " sets " + use.getOwner() + ".reply, which FML sends");
                    }
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void theTickListenerIsOnFmlsBusAndDrainsTheSessionsQueueAtEachTicksStart(Path jar) throws IOException {
        // D16 (Q-S11-17): every frame status reaches Lua through the session's tick queue, which only TickListener
        // drains, and no root test may load FML's bus, its tick events or the listener (S11 plan P15.1, U5.9), so the
        // wiring is read here, in the server view of the shipped bytecode [proposal]. CommonProxy.preInit registers
        // TickListener.INSTANCE on FMLCommonHandler's bus, the one FML posts ServerTickEvent on
        // (FMLCommonHandler.java:245-248, :258-261), not on Forge's MinecraftForge.EVENT_BUS; and the listener's one
        // public @SubscribeEvent method takes a ServerTickEvent, returns unless its phase is START, never reads END,
        // and drains ServerSession.current()'s tickQueue()
        ClassNode proxy = null;
        ClassNode listener = null;
        for (ClassNode node : commonClasses(jar)) {
            if (COMMON_PROXY.equals(node.name)) {
                proxy = node;
            } else if (TICK_LISTENER.equals(node.name)) {
                listener = node;
            }
        }
        Assertions.assertNotNull(proxy, COMMON_PROXY + " is not a common class of the jar");
        Assertions.assertNotNull(listener, TICK_LISTENER + " is not a common class of the jar");
        List<String> problems = new ArrayList<>();
        MethodNode preInit = method(proxy, "preInit", "(Lcpw/mods/fml/common/event/FMLPreInitializationEvent;)V");
        List<String> registration = Arrays.asList(
            "invokestatic " + FML_HANDLER + ".instance()L" + FML_HANDLER + ";",
            "invokevirtual " + FML_HANDLER + ".bus()L" + EVENT_BUS + ";",
            "getstatic " + TICK_LISTENER + ".INSTANCE",
            "invokevirtual " + EVENT_BUS + ".register(Ljava/lang/Object;)V");
        if (preInit == null) {
            problems.add(COMMON_PROXY + " has no preInit(FMLPreInitializationEvent)");
        } else if (Collections.indexOfSubList(code(preInit), registration) < 0) {
            problems.add(COMMON_PROXY + ".preInit does not run " + registration + "; it runs " + code(preInit));
        }
        List<MethodNode> subscribers = new ArrayList<>();
        for (MethodNode method : listener.methods) {
            if (find(method.visibleAnnotations, SUBSCRIBE) != null) {
                subscribers.add(method);
            }
        }
        if (subscribers.size() != 1) {
            problems.add(TICK_LISTENER + " has " + subscribers.size() + " @SubscribeEvent methods, not 1");
        } else {
            MethodNode tick = subscribers.get(0);
            List<String> code = code(tick);
            List<String> phaseCheck = Arrays
                .asList("getfield " + SERVER_TICK + ".phase", "getstatic " + PHASE + ".START", "if_acmpeq", "return");
            List<String> drain = Arrays.asList(
                "invokestatic " + SESSION + ".current()L" + SESSION + ";",
                "invokevirtual " + SESSION + ".tickQueue()L" + TICK_QUEUE + ";",
                "invokevirtual " + TICK_QUEUE + ".drain()I");
            String where = TICK_LISTENER + "." + tick.name;
            if ((tick.access & Opcodes.ACC_PUBLIC) == 0 || (tick.access & Opcodes.ACC_STATIC) != 0) {
                problems.add(where + " is not a public instance method, which FML's EventBus needs");
            }
            if (!("(L" + SERVER_TICK + ";)V").equals(tick.desc)) {
                problems.add(where + " takes " + tick.desc + ", not a ServerTickEvent");
            }
            if (Collections.indexOfSubList(code, phaseCheck) < 0) {
                problems.add(where + " does not return unless the phase is START: " + code);
            }
            if (code.contains("getstatic " + PHASE + ".END")) {
                problems.add(where + " reads Phase.END");
            }
            if (!inOrder(code, drain)) {
                problems.add(where + " does not run " + drain + " in that order: " + code);
            }
        }
        assertNone(problems);
    }

    /**
     * What a dedicated server loads (SideTransformer.java:53-78), less what sidedProxiesNameAClassOnEachSide checks.
     */
    private static void serverView(ClassNode node) {
        node.fields.removeIf(field -> isClientOnly(field.visibleAnnotations));
        node.methods.removeIf(method -> isClientOnly(method.visibleAnnotations));
        for (FieldNode field : node.fields) {
            if (field.visibleAnnotations != null) {
                field.visibleAnnotations.removeIf(annotation -> SIDED_PROXY.equals(annotation.desc));
            }
        }
        // InnerClasses entries name nested types without loading them, so a stripped member's types can linger there
        node.innerClasses.clear();
    }

    /**
     * commonCodeNamesNoClientClass's scope as a dedicated server loads it: the classes outside the client packages, in
     * their server view. A client-only class there fails that check already.
     */
    private static List<ClassNode> commonClasses(Path jar) throws IOException {
        List<ClassNode> common = new ArrayList<>();
        for (ClassNode node : JarContents.classes(jar)) {
            if (!startsWithAny(node.name, CLIENT_CODE) && !isClientOnly(node.visibleAnnotations)) {
                serverView(node);
                common.add(node);
            }
        }
        return common;
    }

    /**
     * Each method and field a method's code uses, as a {@link Handle} whose tag says how: its calls and field accesses,
     * and the method handles of its invokedynamic and ldc instructions, so a method reference such as
     * {@code Class::forName} counts as a call.
     */
    private static List<Handle> uses(MethodNode method) {
        List<Handle> uses = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                uses.add(new Handle(tag(call.getOpcode()), call.owner, call.name, call.desc, call.itf));
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                uses.add(new Handle(tag(field.getOpcode()), field.owner, field.name, field.desc, false));
            } else if (instruction instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
                uses.add(dynamic.bsm);
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle) {
                        uses.add((Handle) argument);
                    }
                }
            } else if (instruction instanceof LdcInsnNode && ((LdcInsnNode) instruction).cst instanceof Handle) {
                uses.add((Handle) ((LdcInsnNode) instruction).cst);
            }
        }
        return uses;
    }

    /** The class's method of that name and descriptor, or null. */
    private static MethodNode method(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    /**
     * A method's instructions as text, labels, line numbers and frames left out: calls as "invokestatic
     * owner.name(descriptor)", field accesses as "getstatic owner.name", and the rest by the opcode's name alone.
     */
    private static List<String> code(MethodNode method) {
        List<String> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            int opcode = instruction.getOpcode();
            if (opcode < 0) {
                continue;
            }
            String name = opcodeName(opcode);
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                code.add(name + " " + call.owner + "." + call.name + call.desc);
            } else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                code.add(name + " " + field.owner + "." + field.name);
            } else {
                code.add(name);
            }
        }
        return code;
    }

    /** The opcodes the wiring check names, by their JVMS names; any other as "opcode n". */
    private static String opcodeName(int opcode) {
        switch (opcode) {
            case Opcodes.GETSTATIC:
                return "getstatic";
            case Opcodes.GETFIELD:
                return "getfield";
            case Opcodes.INVOKESTATIC:
                return "invokestatic";
            case Opcodes.INVOKEVIRTUAL:
                return "invokevirtual";
            case Opcodes.IF_ACMPEQ:
                return "if_acmpeq";
            case Opcodes.IF_ACMPNE:
                return "if_acmpne";
            case Opcodes.RETURN:
                return "return";
            default:
                return "opcode " + opcode;
        }
    }

    /** Whether {@code code} holds every one of {@code wanted}, in that order, not necessarily next to each other. */
    private static boolean inOrder(List<String> code, List<String> wanted) {
        int at = 0;
        for (String instruction : code) {
            if (at < wanted.size() && instruction.equals(wanted.get(at))) {
                at++;
            }
        }
        return at == wanted.size();
    }

    /** The method handle kind of a call or field instruction (JVMS 5.4.3.5). */
    private static int tag(int opcode) {
        switch (opcode) {
            case Opcodes.GETFIELD:
                return Opcodes.H_GETFIELD;
            case Opcodes.GETSTATIC:
                return Opcodes.H_GETSTATIC;
            case Opcodes.PUTFIELD:
                return Opcodes.H_PUTFIELD;
            case Opcodes.PUTSTATIC:
                return Opcodes.H_PUTSTATIC;
            case Opcodes.INVOKESTATIC:
                return Opcodes.H_INVOKESTATIC;
            case Opcodes.INVOKESPECIAL:
                return Opcodes.H_INVOKESPECIAL;
            case Opcodes.INVOKEINTERFACE:
                return Opcodes.H_INVOKEINTERFACE;
            default:
                return Opcodes.H_INVOKEVIRTUAL;
        }
    }

    private static boolean isCall(Handle use) {
        return use.getTag() >= Opcodes.H_INVOKEVIRTUAL;
    }

    /**
     * A call that takes a String or a String[] and returns a Class, on any owner: Class.forName, ClassLoader's
     * loadClass, findClass, findLoadedClass, findSystemClass and defineClass, MethodHandles.Lookup.findClass, and the
     * helpers that wrap them, such as FML's ReflectionHelper.getClass.
     */
    private static boolean loadsAClassByName(Handle use) {
        if (!isCall(use) || !"Ljava/lang/Class;".equals(
            Type.getReturnType(use.getDesc())
                .getDescriptor())) {
            return false;
        }
        for (Type argument : Type.getArgumentTypes(use.getDesc())) {
            String descriptor = argument.getDescriptor();
            if ("Ljava/lang/String;".equals(descriptor) || "[Ljava/lang/String;".equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sendsOnAnFmlChannel(Handle use) {
        String name = use.getName();
        return isCall(use) && isAny(use.getOwner(), FML_SENDERS) && name.startsWith("send");
    }

    private static boolean setsAReply(Handle use) {
        return use.getTag() == Opcodes.H_PUTFIELD && "reply".equals(use.getName())
            && isAny(use.getOwner(), REPLY_OWNERS);
    }

    /** Whether the annotations hold {@code @SideOnly(Side.CLIENT)}, read as SideTransformer reads it (:91-110). */
    private static boolean isClientOnly(List<AnnotationNode> annotations) {
        AnnotationNode sideOnly = find(annotations, SIDE_ONLY);
        if (sideOnly == null || sideOnly.values == null) {
            return false;
        }
        for (int i = 0; i + 1 < sideOnly.values.size(); i += 2) {
            Object value = sideOnly.values.get(i + 1);
            if ("value".equals(sideOnly.values.get(i)) && value instanceof String[]
                && "CLIENT".equals(((String[]) value)[1])) {
                return true;
            }
        }
        return false;
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String descriptor) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static String value(AnnotationNode annotation, String name) {
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (name.equals(annotation.values.get(i))) {
                    return String.valueOf(annotation.values.get(i + 1));
                }
            }
        }
        return "";
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String prefix : prefixes) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAny(String s, String[] values) {
        for (String value : values) {
            if (s.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String s, String[] parts) {
        for (String part : parts) {
            if (s.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNone(List<String> problems) {
        Assertions
            .assertTrue(problems.isEmpty(), () -> problems.size() + " problem(s):\n" + String.join("\n", problems));
    }
}
