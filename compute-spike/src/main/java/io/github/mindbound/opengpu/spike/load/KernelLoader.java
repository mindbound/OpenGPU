package io.github.mindbound.opengpu.spike.load;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * One program's class loader (22:178, 05:60; S14 plan P6.1): its parent is the spike's own loader, it defines the one
 * class through the protected {@code defineClass} (no {@code Lookup.defineClass}, hidden class or {@code Unsafe}), only
 * after {@link KernelScan} accepts its bytes, and it delegates exactly {@link #PARENT_NAMES} to the parent. Every other
 * name is a {@code ClassNotFoundException}, so a class that names one fails with {@code NoClassDefFoundError} when the
 * reference first resolves. The list is narrower than 22:178's {@code java.lang.*}, whose prefix also admits
 * {@code System}, {@code Runtime}, {@code Thread}, {@code ClassLoader}, {@code Class}, {@code reflect} and
 * {@code invoke} (S14 amendments F12). The scan checks members; this layer checks classes.
 */
public final class KernelLoader extends ClassLoader {

    /** The names a kernel's class may resolve, through the parent, besides its own. */
    public static final List<String> PARENT_NAMES = Collections.unmodifiableList(
        Arrays.asList(
            "java.lang.Object",
            "java.lang.Float",
            "java.lang.Math",
            "java.lang.StrictMath",
            Kernel.class.getName()));

    private final String className;
    private final List<String> asked = new ArrayList<>();

    /** A loader for the one class {@code className}, a binary name. */
    public KernelLoader(String className) {
        super(KernelLoader.class.getClassLoader());
        this.className = className;
    }

    /** Validates, emits, scans and defines the program's class, and returns a new instance of it. */
    public static Kernel load(Program program) {
        byte[] bytes = Emitter.emit(program);
        return instantiate(new KernelLoader(Emitter.className(program)).define(bytes));
    }

    /** A new instance of a class this loader defined. */
    public static Kernel instantiate(Class<?> type) {
        try {
            return (Kernel) type.getConstructor()
                .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kernel class " + type.getName() + " did not instantiate", e);
        }
    }

    /** Scans the bytes and only then defines them as this loader's class; a refused class is never defined. */
    public Class<?> define(byte[] bytes) {
        KernelScan.check(bytes, className);
        return defineUnscanned(bytes);
    }

    /** {@code defineClass} without the scan, for LoaderTest alone, which shows this layer apart from the scan's. */
    Class<?> defineUnscanned(byte[] bytes) {
        return defineClass(className, bytes, 0, bytes.length);
    }

    /** The names the JVM asked this loader for, first time each, in order. */
    public List<String> asked() {
        synchronized (asked) {
            return new ArrayList<>(asked);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (asked) {
            if (!asked.contains(name)) {
                asked.add(name);
            }
        }
        if (name.equals(className)) {
            Class<?> own = findLoadedClass(name);
            if (own == null) {
                throw new ClassNotFoundException(name + " is not defined");
            }
            return own;
        }
        if (PARENT_NAMES.contains(name)) {
            return getParent().loadClass(name);
        }
        throw new ClassNotFoundException(name + " is not on a kernel's list");
    }
}
