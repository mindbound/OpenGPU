#version 120

// -- built-in registers delivered as uniforms --
uniform float u_time;        // pre-wrapped scene-relative time (wrap applied CPU-side per spec)

// -- user uniforms: 6 float components against the post-overhead budget --
uniform vec3 u_colorA;
uniform vec3 u_colorB;

// Fragment-only program: no vertex shader is bound, fixed-function T&L feeds the
// built-in varyings. uv register  -> gl_TexCoord[0].xy (fixed-function texcoord path);
// tint register -> gl_Color (per-node glColor4f; zero uniform cost, batching-friendly).
// This mapping holds because the program reads neither `position` nor `normal`; either
// of those would force the generated pass-through vertex shader (see issues).

void main() {
    // register bindings
    vec2  r0  = gl_TexCoord[0].xy;   // uv
    vec4  r2  = gl_Color;            // node tint RGBA
    // op stream, 1:1 with IR (2006-era compilers copy-propagate straight-line temps)
    vec2  r5  = r0 * 8.0;
    float r6  = r5.x;
    float r7  = r6 + u_time;
    float r8  = sin(r7);
    float r9  = r5.y;
    float r10 = u_time * 1.3;
    float r11 = r9 + r10;
    float r12 = cos(r11);
    float r13 = r8 + r12;
    vec2  r14 = vec2(1.2, 0.7);
    float r15 = dot(r5, r14);
    float r16 = u_time * 0.5;
    float r17 = r15 + r16;
    float r18 = sin(r17);
    float r19 = r13 + r18;
    float r20 = r19 / 6.0;
    float r21 = r20 + 0.5;
    vec3  r22 = vec3(r21);
    vec3  r23 = mix(u_colorA, u_colorB, r22);
    float r24 = mix(0.4, 1.0, r21);
    vec4  r25 = vec4(r23, r24);
    vec4  r26 = r25 * r2;
    gl_FragColor = r26;
}