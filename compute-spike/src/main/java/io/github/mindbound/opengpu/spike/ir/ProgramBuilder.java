package io.github.mindbound.opengpu.spike.ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Builds a {@link Program} statement by statement, as a front end would (S14 plan P3.3). Each statement takes the next
 * kernel line, from 1, in the order it is begun, so an {@code If} or a {@code For} has the line before its body's.
 * Nothing is checked until {@link Validator#check}, so tests can build programs the validator refuses.
 */
public final class ProgramBuilder {

    private final String name;
    private final int dimensions;
    private final List<Type> locals = new ArrayList<>();
    private final List<Access> bindings = new ArrayList<>();
    private final List<Type> uniforms = new ArrayList<>();
    private final Deque<List<Stmt>> open = new ArrayDeque<>();
    private int line = 1;

    public ProgramBuilder(String name, int dimensions) {
        this.name = name;
        this.dimensions = dimensions;
        open.push(new ArrayList<>());
    }

    /** Adds a binding and returns its index. */
    public int binding(Access access) {
        bindings.add(access);
        return bindings.size() - 1;
    }

    /** Adds a uniform and returns its expression. */
    public Expr uniform(Type type) {
        uniforms.add(type);
        return new Expr.Uniform(uniforms.size() - 1, type);
    }

    /** Adds a local and returns its slot. */
    public int local(Type type) {
        locals.add(type);
        return locals.size() - 1;
    }

    /** The local in {@code slot}, with the type it was added with. */
    public Expr get(int slot) {
        return new Expr.Local(slot, locals.get(slot));
    }

    /** The line the next statement takes. */
    public int nextLine() {
        return line;
    }

    public ProgramBuilder assign(int slot, Expr value) {
        return add(new Stmt.Assign(line++, slot, value));
    }

    public ProgramBuilder store(int binding, Expr index, Expr value) {
        return add(new Stmt.Store(line++, binding, index, value));
    }

    public ProgramBuilder ret() {
        return add(new Stmt.Return(line++));
    }

    public ProgramBuilder ifThen(Compare condition, Runnable then) {
        int at = line++;
        return add(new Stmt.If(at, condition, block(then), Collections.<Stmt>emptyList()));
    }

    public ProgramBuilder ifElse(Compare condition, Runnable then, Runnable orElse) {
        int at = line++;
        List<Stmt> thenBlock = block(then);
        return add(new Stmt.If(at, condition, thenBlock, block(orElse)));
    }

    /** {@code for (slot = from; slot < to; slot++) body}. */
    public ProgramBuilder loop(int slot, int from, int to, Runnable body) {
        int at = line++;
        return add(new Stmt.For(at, slot, from, to, block(body)));
    }

    public Program build() {
        if (open.size() != 1) {
            throw new IllegalStateException("a block is still open");
        }
        return new Program(name, locals, bindings, uniforms, dimensions, open.peek());
    }

    private List<Stmt> block(Runnable statements) {
        open.push(new ArrayList<>());
        statements.run();
        return open.pop();
    }

    private ProgramBuilder add(Stmt statement) {
        open.peek()
            .add(statement);
        return this;
    }
}
