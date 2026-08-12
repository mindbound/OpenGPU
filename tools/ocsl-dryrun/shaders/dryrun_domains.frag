#version 120
/* OCSL codegen dry-run — PROGRAM 4 "numeric-domain torture"
   stage: pixel / material attachment.
   Charged (post-unroll) IR cost: 95 ops, 4 fetches, unroll product 4.
   Real lowered cost with guards: ~118 op-equivalents (+24%).
   User uniform components: 3 (u_swirl 1 + u_bias 2); built-in overhead assumed
   tint(4)+time(1)=5 of the GL 2.1 64-component floor.
   Vertex side: generated fixed-function pass-through supplies v_uv (per spec's
   vertex-stage replacement obligation); not part of this listing. */

varying vec2 v_uv;

uniform vec4      ocsl_tint;   /* material tint register (binding ASSUMED uniform — issue I-14) */
uniform float     ocsl_time;   /* pre-wrapped scene-relative time; wrap applied CPU-side */
uniform sampler2D ocsl_slot0;  /* slot 0 — bilinear, clamp-to-edge, no mips via sampler state */

uniform float u_swirl;
uniform vec2  u_bias;

/* ---- codegen-emitted domain guards (each is the lowering of ONE IR op) ----
   Normative pattern: SANITIZE THE INPUT so the computed lane is always finite,
   THEN select with ?:.  Never blend with mix(): mix(0.0, NaN, 0.0) = NaN — a
   lerp-lowered "select" launders NaN through the weight-zero lane.  GLSL 1.20
   has no isnan/isinf, so guards must PREVENT non-finite values, not detect
   them.  With both lanes finite, even a driver that lowers ?: through lerp
   cannot manufacture a NaN.  Epsilon 1e-37 is a normal float32; see issue I-10
   for the 24-bit-fragment-hardware caveat. */

/* IR NORM — spec-defined: normalize(vec(0)) = 0-vector.  Naked cost ~3, lowered 6. */
vec2 ocsl_normalize2(vec2 v) {
    float l = length(v);
    return (l > 0.0) ? (v / max(l, 1.0e-37)) : vec2(0.0);
}

/* IR LOG — guard mandated by spec, VALUE unpinned; proposed: log(x <= 0) = 0.
   Naked cost 1, lowered 4. */
float ocsl_log(float x) {
    return (x > 0.0) ? log(max(x, 1.0e-37)) : 0.0;
}

/* IR POW — guard mandated, table unpinned; proposed: pow(x <= 0, y) = 0,
   including pow(0,0) = 0.  The CPU VM must implement THIS table, not raw
   Java Math.pow (Math.pow(-2,2)=4 is unreproducible in GLSL 1.20).
   Naked cost 1, lowered 4. */
float ocsl_pow(float x, float y) {
    return (x > 0.0) ? pow(max(x, 1.0e-37), y) : 0.0;
}

/* IR MOD — GLSL mod() is already floor-mod: mod(-0.5, 1.5) = 1.0, negative
   operands need NO guard.  The only hole is y = 0 (internal x/y); proposed:
   mod(x, 0) = 0.  Emitted here unconditionally even though this program's
   divisor is the literal 1.5 — see issue I-7 (emission policy unspecified).
   Naked cost ~4 (sub/mul/floor/div), lowered 8. */
float ocsl_mod(float x, float y) {
    float ys = (abs(y) > 0.0) ? y : 1.0;
    return (abs(y) > 0.0) ? (x - ys * floor(x / ys)) : 0.0;
}

/* IR ATAN2 — spec: (0,0) implementation-defined and excluded from conformance,
   which PERMITS a driver NaN.  Proposed amendment: atan2(0,0) = 0 for all zero
   sign combinations, by sanitizing x so hardware atan never sees (0,0);
   atan(0.0, 1.0) = 0 exactly on any driver.  Naked cost 1, lowered 6. */
float ocsl_atan2(float y, float x) {
    return atan(y, ((abs(x) + abs(y)) > 0.0) ? x : 1.0);
}

/* IR SQRT — absent from the domain table (issue I-4); proposed: sqrt(x<0) = 0.
   The cheapest guard in the set.  Naked cost 1, lowered 2. */
float ocsl_sqrt(float x) {
    return sqrt(max(x, 0.0));
}

/* IR DIV — NO defined result exists for x/0 (issue I-1, GO-BLOCKER);
   proposed: x/0 = 0 and 0/0 = 0 ("safe divide", consistent in spirit with
   normalize(vec(0)) = 0).  Naked cost 1, lowered 5. */
float ocsl_div(float x, float y) {
    float q = x / ((abs(y) > 0.0) ? y : 1.0);
    return (abs(y) > 0.0) ? q : 0.0;
}

void main() {
    /* IR 000-023 */
    vec2  p   = v_uv - vec2(0.5, 0.5);
    vec2  dir = ocsl_normalize2(p);                      /* 001 NORM  — zero vector at center   */
    float r   = length(p);                               /* 002 LEN   — domain-safe             */
    float ang = ocsl_atan2(p.y, p.x);                    /* 005 ATAN2 — pole at center          */
    float lg  = ocsl_log((v_uv.x - 0.25) + u_bias.x);    /* 010 LOG   — arg <= 0 left quarter   */
    float pw  = ocsl_pow(v_uv.y - 0.5, 2.5 + u_swirl);   /* 014 POW   — base < 0 lower half     */
    float fm  = ocsl_mod(v_uv.x * 4.0 - 2.0, 1.5);       /* 017 MOD   — negative lhs            */
    float sq  = ocsl_sqrt(0.25 - dot(p, p));             /* 020 SQRT  — arg < 0 outside disc    */
    float dv  = ocsl_div(sin(ocsl_time), v_uv.x - 0.5);  /* 023 DIV   — divisor 0 on centerline */

    /* IR 024-035: s.loop(4, vec3(0), fn) — FULLY UNROLLED per spec (4 fetches). */
    vec3 acc = vec3(0.0, 0.0, 0.0);
    acc = acc * 0.5 + texture2D(ocsl_slot0, fract(v_uv * 1.7 + acc.xy * 0.13)).xyz * 0.5; /* i=0 */
    acc = acc * 0.5 + texture2D(ocsl_slot0, fract(v_uv * 1.7 + acc.xy * 0.13)).xyz * 0.5; /* i=1 */
    acc = acc * 0.5 + texture2D(ocsl_slot0, fract(v_uv * 1.7 + acc.xy * 0.13)).xyz * 0.5; /* i=2 */
    acc = acc * 0.5 + texture2D(ocsl_slot0, fract(v_uv * 1.7 + acc.xy * 0.13)).xyz * 0.5; /* i=3 */

    /* IR 036-066 */
    vec3 c1 = vec3(fm, sq, clamp(pw, 0.0, 1.0));
    vec3 c2 = vec3(dir.x * 0.5 + 0.5,
                   dir.y * 0.5 + 0.5,
                   ang * 0.15915494 + 0.5);
    vec3 c3 = vec3(fract(lg * 0.25),
                   clamp(dv * 0.1 + 0.5, 0.0, 1.0),
                   fract(r * 2.0));

    vec3 m   = mix(c1, mix(c2, c3, fract(ocsl_time * 0.1)), 0.5);
    vec3 rgb = m * (acc * 0.5 + 0.5);

    /* IR 062 LT + 063 SEL — IR select lowers to ?: (true select), NEVER mix() */
    float mask = (v_uv.x < 0.5) ? 1.0 : 0.75;

    gl_FragColor = vec4(rgb * mask, 1.0) * ocsl_tint;
}