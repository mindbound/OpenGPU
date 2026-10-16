package io.github.mindbound.opengpu.gl;

/**
 * How quads reach the GPU (28:415; G16, 24:296). Both use the same 20-byte record and the same shaders, which take the
 * corner from {@code gl_VertexID % 6}. Tier E allows no difference between them (23:137); EXPANDED is the default until
 * M0's G16 measurement (H39).
 */
public enum QuadMode {
    /** Each quad's record written six times, one per vertex, drawn with {@code glDrawArrays} (C83). */
    EXPANDED,
    /** Each quad's record written once, a per-instance attribute, drawn with {@code glDrawArraysInstanced} (C85). */
    INSTANCED
}
