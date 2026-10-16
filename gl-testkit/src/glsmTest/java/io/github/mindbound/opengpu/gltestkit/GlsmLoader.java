package io.github.mindbound.opengpu.gltestkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;

/**
 * The GLSM spike's class loader (roadmap M0; docs/research/23-gl-testing-strategy.md §2.3): it runs the probe classes
 * through GLSMRedirector as Angelica's transformer runs every mod class in game, by the same steps: the core
 * exclusions, the constant-pool filter, ClassReader.accept(node, 0), transformClassNode with the dotted name and, only
 * when that changed something, a COMPUTE_MAXS ClassWriter (AngelicaRedirectorTransformer.java:22-47 at 2.2.8,
 * glsm/.../loading/ModRedirector.java:53-74 at 2.2.28). It owns the packages of {@link #OWNED}: the probes', :gl's
 * render core and :gl-testkit's host fixtures and scenarios (S8, H30), and defines those classes itself from the
 * parent's bytes, never asking the parent for them, so a probe's helpers and every :gl class a scenario reaches are
 * transformed with it. Owned and unowned code meet only in :core and JDK types, so no class exists twice on one path.
 * Everything else, GLSM, LWJGL, :core and its fixtures, JUnit and GlThread included, comes from the parent, so a
 * redirected call reaches the GLStateManager that GlsmExtension initialized. Loading needs no GL context; running a
 * probe does.
 */
final class GlsmLoader extends ClassLoader {

    /** The probes' package. */
    static final String PROBES = "io.github.mindbound.opengpu.gltestkit.glsmprobe.";

    /**
     * The owned package prefixes (S8 plan §3.13): the probes, :gl (its gl.host package included), and :gl-testkit's
     * host fixtures and scenarios. "io.github.mindbound.opengpu.gl." does not match the gltestkit packages.
     */
    static final List<String> OWNED = Collections.unmodifiableList(
        Arrays.asList(
            PROBES,
            "io.github.mindbound.opengpu.gl.",
            "io.github.mindbound.opengpu.gltestkit.host.",
            "io.github.mindbound.opengpu.gltestkit.scenario."));

    /** What happened to one probe class on its way in. */
    static final class Transform {

        final byte[] original;
        final byte[] defined;
        /** A core exclusion matched, which in game leaves the class as it is. */
        final boolean excluded;
        /** The constant-pool filter passed the class to the transform. */
        final boolean filtered;
        /** transformClassNode changed the class. */
        final boolean changed;

        Transform(byte[] original, byte[] defined, boolean excluded, boolean filtered, boolean changed) {
            this.original = original;
            this.defined = defined;
            this.excluded = excluded;
            this.filtered = filtered;
            this.changed = changed;
        }
    }

    private final GLSMRedirector redirector = new GLSMRedirector();
    private final Map<String, Transform> transforms = new ConcurrentHashMap<>();

    GlsmLoader() {
        super(GlsmLoader.class.getClassLoader());
    }

    /** A new instance of the transformed {@code name}, made by its public no-argument constructor. */
    <T> T instance(String name, Class<T> type) throws ReflectiveOperationException {
        return type.cast(
            loadClass(name).getConstructor()
                .newInstance());
    }

    /** The record of {@code name}, which this loads first. */
    Transform transform(String name) throws ClassNotFoundException {
        loadClass(name);
        return transforms.get(name);
    }

    /** Whether this loader defines {@code name} itself. */
    static boolean owns(String name) {
        for (String prefix : OWNED) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!owns(name)) {
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

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] original;
        try {
            original = bytes(getParent(), name);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        boolean excluded = false;
        for (String exclusion : redirector.getCoreExclusions()) {
            excluded |= name.startsWith(exclusion);
        }
        boolean filtered = !excluded && redirector.shouldTransform(original);
        boolean changed = false;
        byte[] defined = original;
        if (filtered) {
            ClassNode node = new ClassNode();
            new ClassReader(original).accept(node, 0);
            changed = redirector.transformClassNode(name, node);
            if (changed) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                defined = writer.toByteArray();
            }
        }
        transforms.put(name, new Transform(original, defined, excluded, filtered, changed));
        return defineClass(name, defined, 0, defined.length);
    }

    /** The class file of {@code name} as {@code loader} has it. */
    static byte[] bytes(ClassLoader loader, String name) throws IOException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no " + resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }
}
