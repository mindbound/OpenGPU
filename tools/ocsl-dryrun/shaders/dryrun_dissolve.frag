#version 120
// OCSL codegen dry run — program 3: dissolve/burn (pixel stage, node effect).
// Fragment-only program: node effects bind NO vertex shader in v1, so uv and
// tint arrive via the fixed-function compatibility varyings gl_TexCoord[0]
// and gl_Color (see issues — the spec should state this plumbing).
// NOTE: the built-in register is named `input` in the spec, but `input` is a
// RESERVED WORD in GLSL 1.20 — codegen must rename (here: u_input).

uniform sampler2D u_input;      // built-in `input` register — node content
uniform sampler2D u_slot0;      // user slot 0 — noise texture
uniform float     u_threshold;  // user uniform "threshold" (1 component)
uniform float     u_edgeWidth;  // user uniform "edgeWidth" (1 component)
// TIME / INPUT_TEXEL / QUAD_SIZE registers are unreferenced by this program
// and therefore not emitted (codegen declares only referenced registers).

// Codegen-emitted domain guard: smoothstep with edges not provably
// compile-time constants with e0 < e1. GLSL leaves smoothstep undefined for
// edge0 >= edge1 (0/0 at equality); this lowering is total and is mirrored
// verbatim by the CPU VM. edgeWidth == 0 degenerates to a step at e0.
float ocsl_smoothstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / max(e1 - e0, 1e-6), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

void main() {
    vec4  col   = texture2D(u_input, gl_TexCoord[0].st);   // fetch 1
    vec4  nzTex = texture2D(u_slot0, gl_TexCoord[0].st);   // fetch 2
    float n     = nzTex.x;

    float alive = step(u_threshold, n);          // 0 = dissolved, 1 = kept
    float hi    = u_threshold + u_edgeWidth;
    float glow  = 1.0 - ocsl_smoothstep(u_threshold, hi, n);

    vec3 baseRgb = col.xyz;
    vec3 burnRgb = mix(baseRgb, vec3(1.0, 0.35, 0.05), glow);

    bool inBand = (u_threshold <= n) && (n < hi);

    // IR SELECT on a vec3 with a scalar bool condition: lowered to the
    // GLSL 1.20 scalar-condition ternary, which accepts vector arms of a
    // common type. Deliberately NOT mix(baseRgb, burnRgb, float(inBand)):
    // 0.0 * Inf == NaN would let the discarded arm contaminate the result,
    // breaking the strict-pick semantics the spec must commit to (issue 1).
    vec3 rgb = inBand ? burnRgb : baseRgb;

    float a = col.w * alive * gl_Color.a;
    gl_FragColor = vec4(rgb * gl_Color.xyz, a);
}