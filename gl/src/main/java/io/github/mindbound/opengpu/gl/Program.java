package io.github.mindbound.opengpu.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * A program linked from {@code #version 330 core} source (20:93-99; C43-C55), with its {@code GlLedger} row (host,
 * {@code program}, its name, 0 B). A shader that fails to compile or a program that fails to link throws with both info
 * logs (20:240, :251; H63); {@link #uniform} throws for a name the linker dropped, so a shader edit that loses a
 * uniform fails at link time rather than drawing with location -1. GL thread, inside a pass.
 */
final class Program {

    private final String name;
    private final int id;
    private final String[] uniformNames;
    private final int[] uniformLocations;

    private Program(String name, int id, String[] uniformNames, int[] uniformLocations) {
        this.name = name;
        this.id = id;
        this.uniformNames = uniformNames;
        this.uniformLocations = uniformLocations;
    }

    /**
     * Compiles, links and looks up {@code uniforms}; the shaders are detached and deleted after the link.
     *
     * @throws IllegalStateException with the logs, for a failed compile or link or a uniform the program lacks
     */
    static Program link(String name, String vertex, String fragment, String[] uniforms, GlLedger ledger) {
        int vs = compile(name, "vertex", GL20.GL_VERTEX_SHADER, vertex);
        int fs;
        try {
            fs = compile(name, "fragment", GL20.GL_FRAGMENT_SHADER, fragment);
        } catch (RuntimeException e) {
            GL20.glDeleteShader(vs);
            throw e;
        }
        int id = GL20.glCreateProgram();
        GL20.glAttachShader(id, vs);
        GL20.glAttachShader(id, fs);
        GL20.glLinkProgram(id);
        boolean linked = GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == GL11.GL_TRUE;
        String log = linked ? "" : programLog(id);
        GL20.glDetachShader(id, vs);
        GL20.glDetachShader(id, fs);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (!linked) {
            GL20.glDeleteProgram(id);
            throw new IllegalStateException("program " + name + " failed to link (20:240): " + log);
        }
        int[] locations = new int[uniforms.length];
        for (int i = 0; i < uniforms.length; i++) {
            locations[i] = GL20.glGetUniformLocation(id, uniforms[i]);
            if (locations[i] < 0) {
                GL20.glDeleteProgram(id);
                throw new IllegalStateException(
                    "program " + name + " has no active uniform " + uniforms[i] + " after linking");
            }
        }
        ledger.add(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.PROGRAM, name, 0);
        return new Program(name, id, uniforms.clone(), locations);
    }

    String name() {
        return name;
    }

    int id() {
        return id;
    }

    /**
     * The location of a uniform given to {@link #link}.
     *
     * @throws IllegalStateException for any other name
     */
    int uniform(String uniform) {
        for (int i = 0; i < uniformNames.length; i++) {
            if (uniformNames[i].equals(uniform)) {
                return uniformLocations[i];
            }
        }
        throw new IllegalStateException("program " + name + " looked up no uniform " + uniform);
    }

    /** {@code glDeleteProgram} (C55) and the ledger row. */
    void delete(GlLedger ledger) {
        GL20.glDeleteProgram(id);
        ledger.remove(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.PROGRAM, name, 0);
    }

    private static int compile(String name, String stage, int type, String source) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            int length = Math.max(1, GL20.glGetShaderi(shader, GL20.GL_INFO_LOG_LENGTH));
            String log = GL20.glGetShaderInfoLog(shader, length);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException(
                "the " + stage + " shader of program " + name + " failed to compile (20:240): " + log);
        }
        return shader;
    }

    private static String programLog(int id) {
        int length = Math.max(1, GL20.glGetProgrami(id, GL20.GL_INFO_LOG_LENGTH));
        return GL20.glGetProgramInfoLog(id, length);
    }
}
