package io.github.mindbound.opengpu.jartest;

import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

/**
 * Every type a class names and every String constant it holds, collected by running it through ASM's remapper, which
 * visits each place a class file names a type (constant pool, descriptors, signatures, annotations, frames, debug
 * information) and each constant (ldc, ConstantValue, annotation values, bootstrap arguments).
 */
final class References extends Remapper {

    final Set<String> types = new TreeSet<>();
    final Set<String> strings = new TreeSet<>();

    private References() {
        super(Opcodes.ASM9);
    }

    static References of(ClassNode node) {
        References references = new References();
        // The sink must not be null: ClassRemapper skips a method's code when its delegate returns no visitor
        node.accept(new ClassRemapper(new ClassNode(), references));
        return references;
    }

    @Override
    public String map(String internalName) {
        types.add(internalName);
        return internalName;
    }

    @Override
    public Object mapValue(Object value) {
        if (value instanceof String) {
            strings.add((String) value);
        }
        return super.mapValue(value);
    }
}
