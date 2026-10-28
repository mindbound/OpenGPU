package io.github.mindbound.opengpu.spike.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A compute program (S14 plan P3.3): its name, which names its class, its locals' types by slot, up to four bindings
 * with their access (22:153), its uniforms' types, a 1D or 2D grid, and the body each invocation runs. It is immutable
 * and unchecked; {@link Validator#check} says whether it is a program the emitter takes.
 */
public final class Program {

    public final String name;
    public final List<Type> locals;
    public final List<Access> bindings;
    public final List<Type> uniforms;
    /** 1 (x) or 2 (x and y). */
    public final int dimensions;
    public final List<Stmt> body;

    public Program(String name, List<Type> locals, List<Access> bindings, List<Type> uniforms, int dimensions,
        List<Stmt> body) {
        this.name = Objects.requireNonNull(name, "name");
        this.locals = copy(locals, "locals");
        this.bindings = copy(bindings, "bindings");
        this.uniforms = copy(uniforms, "uniforms");
        this.dimensions = dimensions;
        this.body = Stmt.copy(body, "body");
    }

    private static <T> List<T> copy(List<T> list, String what) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(list, what));
        for (T t : copy) {
            Objects.requireNonNull(t, what);
        }
        return Collections.unmodifiableList(copy);
    }
}
