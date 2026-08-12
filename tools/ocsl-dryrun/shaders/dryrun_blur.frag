#version 120
// OCSL codegen dry-run -- PROGRAM 2: separable 9-tap Gaussian, post-chain entry.
// Source IR: stage=post-chain, format v1 + amendments A1 (counter itof), A2 (uv register).
// Loop bound 9 is compile-time constant -> FULLY UNROLLED below; the loop counter is
// materialized as a float literal per iteration (r2 = 0.0 .. 8.0), which is all "int
// never reaches GLSL" can mean once the counter is readable.
// Register set mapping (post-chain): uv -> gl_TexCoord[0].st (engine draws the fullscreen
// quad through fixed-function vertex processing / a shared trivial VS supplying 0..1
// texcoords, so this fragment shader is complete and self-contained); input -> u_input on
// texture unit 0; inputTexelSize -> u_inputTexelSize; time is unread so codegen does not
// declare it (only read registers are declared/bound).
// Binding contract (sample() semantics live in sampler STATE, not GLSL 120 code):
// GL_LINEAR min/mag, no mipmapping, GL_CLAMP_TO_EDGE on both axes -- also the only
// NPOT-legal combination on GL 2.1 floor hardware, which the scene FBO requires.

uniform sampler2D u_input;          // built-in register: input (ping-pong source)
uniform vec2      u_inputTexelSize; // built-in register: inputTexelSize
uniform vec2      u_dir;            // per-entry uniform: dir -- (1,0) H pass, (0,1) V pass

void main() {
    vec2  uv = gl_TexCoord[0].st;          // built-in register: uv  [A2]
    vec2  r0 = u_inputTexelSize * u_dir;   // 00: step
    vec4  r1 = vec4(0.0, 0.0, 0.0, 0.0);   // 01: fold init (acc)
    float r2; float r3; float r4; float r5; float r6; float r7;
    vec2  r8; vec2  r9;
    vec4  r10; vec4 r11;

    // ---- unrolled iteration i = 0 ----
    r2  = 0.0;                             // 02: itof(i)
    r3  = r2 - 4.0;                        // 03: x = -4
    r4  = r3 * r3;                         // 04
    r5  = r4 * -0.125;                     // 05
    r6  = exp(r5);                         // 06
    r7  = r6 * 0.2041634;                  // 07: w
    r8  = r0 * r3;                         // 08
    r9  = uv + r8;                         // 09
    r10 = texture2D(u_input, r9);          // 10: fetch 1/9
    r11 = r10 * r7;                        // 11
    r1  = r1 + r11;                        // 12

    // ---- unrolled iteration i = 1 ----
    r2  = 1.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 2/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 2 ----
    r2  = 2.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 3/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 3 ----
    r2  = 3.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 4/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 4 (center tap) ----
    r2  = 4.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 5/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 5 ----
    r2  = 5.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 6/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 6 ----
    r2  = 6.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 7/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 7 ----
    r2  = 7.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 8/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    // ---- unrolled iteration i = 8 ----
    r2  = 8.0;
    r3  = r2 - 4.0;
    r4  = r3 * r3;
    r5  = r4 * -0.125;
    r6  = exp(r5);
    r7  = r6 * 0.2041634;
    r8  = r0 * r3;
    r9  = uv + r8;
    r10 = texture2D(u_input, r9);          // fetch 9/9
    r11 = r10 * r7;
    r1  = r1 + r11;

    gl_FragColor = r1;                     // 14: out
}