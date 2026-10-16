/*
 * llvmpipe: glGetTexImage into a PBO returns a texture's contents from before a draw into it that was issued earlier.
 *
 * Build: gcc -O1 -o lp_stale_gettex lp_stale_gettex.c /usr/lib/x86_64-linux-gnu/libEGL.so.1
 * Run:   EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ./lp_stale_gettex [c|f] [iterations]
 *        no argument: the bug (most readbacks hold the previous iteration's value)
 *        c: the last draw before the copy samples another texture instead of A (control: no stale readback)
 *        f: glFinish right before glGetTexImage (control: no stale readback)
 *
 * Each iteration, after a glFinish: a slow draw into Q (fenced), a slow fill of A with v, a small draw into B, glFlush,
 * a client wait for Q's fence, a draw into P whose program samples A (texelFetch), then glGetTexImage(A) into a PBO and
 * glGetBufferSubData. GL requires the copy to see the fill. On llvmpipe, lp_setup_is_resource_referenced() returns the
 * first scene slot that names A at all; a scene that only samples A (or merely has it bound, as the fill and the draws
 * around it do once A stays bound to unit 0) in a lower slot answers LP_REFERENCED_FOR_READ, so
 * llvmpipe_flush_resource(read_only) skips its finish and the CPU copy in _mesa_GetTexSubImage_sw maps A while the
 * fill's scene is still queued or rasterizing.
 *
 * No GL headers needed: the few prototypes and enums used are declared here.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

typedef void *EGLDisplay;
typedef void *EGLContext;
typedef void *EGLConfig;
typedef void *EGLSurface;
typedef int32_t EGLint;
typedef unsigned int EGLBoolean;
typedef unsigned int EGLenum;
extern void *eglGetProcAddress(const char *);
extern EGLBoolean eglInitialize(EGLDisplay, EGLint *, EGLint *);
extern EGLBoolean eglBindAPI(EGLenum);
extern EGLContext eglCreateContext(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
extern EGLBoolean eglMakeCurrent(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
extern EGLint eglGetError(void);
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#define EGL_OPENGL_API 0x30A2
#define EGL_CONTEXT_MAJOR_VERSION 0x3098
#define EGL_CONTEXT_MINOR_VERSION 0x30FB
#define EGL_CONTEXT_OPENGL_PROFILE_MASK 0x30FD
#define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT 0x1
#define EGL_NONE 0x3038

typedef unsigned int GLenum, GLuint, GLbitfield;
typedef int GLint, GLsizei;
typedef unsigned char GLubyte;
typedef char GLchar;
typedef float GLfloat;
typedef intptr_t GLintptr;
typedef intptr_t GLsizeiptr;
typedef uint64_t GLuint64;
typedef struct __GLsync *GLsync;
#define GL_TEXTURE_2D 0x0DE1
#define GL_TEXTURE_MIN_FILTER 0x2801
#define GL_TEXTURE_MAG_FILTER 0x2800
#define GL_NEAREST 0x2600
#define GL_TEXTURE_MAX_LEVEL 0x813D
#define GL_R8UI 0x8232
#define GL_RGBA8 0x8058
#define GL_RED_INTEGER 0x8D94
#define GL_RGBA 0x1908
#define GL_UNSIGNED_BYTE 0x1401
#define GL_FRAMEBUFFER 0x8D40
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_FRAMEBUFFER_COMPLETE 0x8CD5
#define GL_PIXEL_PACK_BUFFER 0x88EB
#define GL_STREAM_READ 0x88E1
#define GL_PACK_ALIGNMENT 0x0D05
#define GL_TRIANGLES 0x0004
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
#define GL_SYNC_GPU_COMMANDS_COMPLETE 0x9117
#define GL_RENDERER 0x1F01

#define FN(ret, name, args) static ret (*name) args;
FN(void, glGenTextures, (GLsizei, GLuint *))
FN(void, glBindTexture, (GLenum, GLuint))
FN(void, glTexParameteri, (GLenum, GLenum, GLint))
FN(void, glTexImage2D, (GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *))
FN(void, glGenFramebuffers, (GLsizei, GLuint *))
FN(void, glBindFramebuffer, (GLenum, GLuint))
FN(void, glFramebufferTexture2D, (GLenum, GLenum, GLenum, GLuint, GLint))
FN(GLenum, glCheckFramebufferStatus, (GLenum))
FN(void, glGenBuffers, (GLsizei, GLuint *))
FN(void, glBindBuffer, (GLenum, GLuint))
FN(void, glBufferData, (GLenum, GLsizeiptr, const void *, GLenum))
FN(void, glGetBufferSubData, (GLenum, GLintptr, GLsizeiptr, void *))
FN(void, glGetTexImage, (GLenum, GLint, GLenum, GLenum, void *))
FN(void, glPixelStorei, (GLenum, GLint))
FN(void, glViewport, (GLint, GLint, GLsizei, GLsizei))
FN(GLuint, glCreateShader, (GLenum))
FN(void, glShaderSource, (GLuint, GLsizei, const GLchar *const *, const GLint *))
FN(void, glCompileShader, (GLuint))
FN(void, glGetShaderiv, (GLuint, GLenum, GLint *))
FN(void, glGetShaderInfoLog, (GLuint, GLsizei, GLsizei *, GLchar *))
FN(GLuint, glCreateProgram, (void))
FN(void, glAttachShader, (GLuint, GLuint))
FN(void, glLinkProgram, (GLuint))
FN(void, glGetProgramiv, (GLuint, GLenum, GLint *))
FN(void, glUseProgram, (GLuint))
FN(GLint, glGetUniformLocation, (GLuint, const GLchar *))
FN(void, glUniform1i, (GLint, GLint))
FN(void, glUniform1ui, (GLint, GLuint))
FN(void, glUniform4f, (GLint, GLfloat, GLfloat, GLfloat, GLfloat))
FN(void, glGenVertexArrays, (GLsizei, GLuint *))
FN(void, glBindVertexArray, (GLuint))
FN(void, glDrawArrays, (GLenum, GLint, GLsizei))
FN(void, glFinish, (void))
FN(void, glFlush, (void))
FN(GLsync, glFenceSync, (GLenum, GLbitfield))
FN(GLenum, glClientWaitSync, (GLsync, GLbitfield, GLuint64))
FN(void, glDeleteSync, (GLsync))
FN(const GLubyte *, glGetString, (GLenum))
FN(GLenum, glGetError, (void))

#define LOAD(name)                                                                                                     \
    do {                                                                                                               \
        *(void **)&name = eglGetProcAddress(#name);                                                                    \
        if (!name) {                                                                                                   \
            fprintf(stderr, "no %s\n", #name);                                                                         \
            exit(2);                                                                                                   \
        }                                                                                                              \
    } while (0)

enum { W = 320, H = 200 };

static const char *VS = "#version 330 core\n"
                        "uniform vec4 u_rect;\n"
                        "const vec2 C[6] = vec2[6](vec2(0,0), vec2(1,0), vec2(0,1), vec2(0,1), vec2(1,0), vec2(1,1));\n"
                        "void main() { vec2 c = C[gl_VertexID % 6];"
                        " gl_Position = vec4(mix(u_rect.xy, u_rect.zw, c), 0.0, 1.0); }\n";
/* No sampler: the scenes it draws still list whatever texture is bound to unit 0 */
static const char *SLOW_FS = "#version 330 core\n"
                             "uniform uint u_value;\n"
                             "uniform int u_loops;\n"
                             "layout(location = 0) out uint o;\n"
                             "void main() {\n"
                             "    uint x = u_value;\n"
                             "    float f = gl_FragCoord.x;\n"
                             "    for (int k = 0; k < u_loops; k++) { f = sin(f) * 1.5; }\n"
                             "    if (f > 7.0) x = 0u;\n"
                             "    o = x;\n"
                             "}\n";
static const char *SAMPLE_FS = "#version 330 core\n"
                               "uniform usampler2D u_s;\n"
                               "layout(location = 0) out vec4 o;\n"
                               "void main() { uint v = texelFetch(u_s, ivec2(gl_FragCoord.xy), 0).r;"
                               " o = vec4(float(v) / 255.0); }\n";

static GLuint shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[4000];
        glGetShaderInfoLog(s, sizeof log, NULL, log);
        fprintf(stderr, "compile: %s\n", log);
        exit(2);
    }
    return s;
}

static GLuint program(const char *fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, shader(GL_VERTEX_SHADER, VS));
    glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        fprintf(stderr, "link failed\n");
        exit(2);
    }
    return p;
}

static GLuint texture(GLenum internal, GLenum format) {
    GLuint t;
    glGenTextures(1, &t);
    glBindTexture(GL_TEXTURE_2D, t);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
    glTexImage2D(GL_TEXTURE_2D, 0, internal, W, H, 0, format, GL_UNSIGNED_BYTE, NULL);
    return t;
}

static GLuint fbo(GLuint tex) {
    GLuint f;
    glGenFramebuffers(1, &f);
    glBindFramebuffer(GL_FRAMEBUFFER, f);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        fprintf(stderr, "fbo incomplete\n");
        exit(2);
    }
    return f;
}

int main(int argc, char **argv) {
    const int control_other = argc > 1 && strchr(argv[1], 'c');
    const int control_finish = argc > 1 && strchr(argv[1], 'f');
    const int n = argc > 2 ? atoi(argv[2]) : 200;

    EGLDisplay (*getPlatformDisplay)(EGLenum, void *, const EGLint *) = eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, NULL, NULL);
    if (!dpy || !eglInitialize(dpy, NULL, NULL) || !eglBindAPI(EGL_OPENGL_API)) {
        fprintf(stderr, "EGL init failed 0x%x\n", eglGetError());
        return 2;
    }
    const EGLint attribs[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                               EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, NULL, NULL, attribs);
    if (!ctx || !eglMakeCurrent(dpy, NULL, NULL, ctx)) {
        fprintf(stderr, "context failed 0x%x\n", eglGetError());
        return 2;
    }
    LOAD(glGenTextures); LOAD(glBindTexture); LOAD(glTexParameteri); LOAD(glTexImage2D); LOAD(glGenFramebuffers);
    LOAD(glBindFramebuffer); LOAD(glFramebufferTexture2D); LOAD(glCheckFramebufferStatus); LOAD(glGenBuffers);
    LOAD(glBindBuffer); LOAD(glBufferData); LOAD(glGetBufferSubData); LOAD(glGetTexImage); LOAD(glPixelStorei);
    LOAD(glViewport); LOAD(glCreateShader); LOAD(glShaderSource); LOAD(glCompileShader); LOAD(glGetShaderiv);
    LOAD(glGetShaderInfoLog); LOAD(glCreateProgram); LOAD(glAttachShader); LOAD(glLinkProgram); LOAD(glGetProgramiv);
    LOAD(glUseProgram); LOAD(glGetUniformLocation); LOAD(glUniform1i); LOAD(glUniform1ui); LOAD(glUniform4f);
    LOAD(glGenVertexArrays); LOAD(glBindVertexArray); LOAD(glDrawArrays); LOAD(glFinish); LOAD(glFlush);
    LOAD(glFenceSync); LOAD(glClientWaitSync); LOAD(glDeleteSync); LOAD(glGetString); LOAD(glGetError);
    printf("GL_RENDERER %s\n", (const char *)glGetString(GL_RENDERER));

    GLuint slow = program(SLOW_FS), sample = program(SAMPLE_FS);
    GLint uRectS = glGetUniformLocation(slow, "u_rect"), uValue = glGetUniformLocation(slow, "u_value");
    GLint uLoops = glGetUniformLocation(slow, "u_loops"), uRectE = glGetUniformLocation(sample, "u_rect");
    glUseProgram(sample);
    glUniform1i(glGetUniformLocation(sample, "u_s"), 0);
    GLuint vao;
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    GLuint a = texture(GL_R8UI, GL_RED_INTEGER), c = texture(GL_R8UI, GL_RED_INTEGER);
    GLuint q = texture(GL_R8UI, GL_RED_INTEGER), b = texture(GL_R8UI, GL_RED_INTEGER);
    GLuint p = texture(GL_RGBA8, GL_RGBA);
    GLuint fa = fbo(a), fq = fbo(q), fb = fbo(b), fp = fbo(p);
    GLuint pbo;
    glGenBuffers(1, &pbo);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
    glBufferData(GL_PIXEL_PACK_BUFFER, W * H, NULL, GL_STREAM_READ);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glViewport(0, 0, W, H);
    static unsigned char out[W * H];
    int stale = 0, partial = 0;
    for (int i = 0; i < n; i++) {
        unsigned v = 1 + i % 250;
        glFinish();
        /* 1. a slow draw into Q, fenced */
        glBindFramebuffer(GL_FRAMEBUFFER, fq);
        glUseProgram(slow);
        glUniform1i(uLoops, 200);
        glUniform1ui(uValue, 7);
        glUniform4f(uRectS, -1, -1, 1, 1);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        /* 2. the slow fill of A with v */
        glBindFramebuffer(GL_FRAMEBUFFER, fa);
        glUniform1ui(uValue, v);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        /* 3. a small draw into B, so A is not the bound framebuffer when it is sampled */
        glBindFramebuffer(GL_FRAMEBUFFER, fb);
        glUniform1ui(uValue, 3);
        glUniform1i(uLoops, 0);
        glUniform4f(uRectS, -1, -1, -0.9f, -0.9f);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glFlush();
        glClientWaitSync(fence, 0, 10000000000ull);
        glDeleteSync(fence);
        /* 4. a small draw into P sampling A (or C) */
        glBindFramebuffer(GL_FRAMEBUFFER, fp);
        glUseProgram(sample);
        glUniform4f(uRectE, -1, -1, -0.9f, -0.9f);
        glBindTexture(GL_TEXTURE_2D, control_other ? c : a);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        if (control_finish)
            glFinish();
        /* 5. the copy: GL requires it to see step 2 */
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
        glBindTexture(GL_TEXTURE_2D, a);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RED_INTEGER, GL_UNSIGNED_BYTE, (void *)0);
        glGetBufferSubData(GL_PIXEL_PACK_BUFFER, 0, W * H, out);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        int wrong = 0;
        for (int k = 0; k < W * H; k++)
            wrong += out[k] != v;
        if (wrong == W * H)
            stale++;
        else if (wrong)
            partial++;
    }
    printf("variant %s: %d of %d readbacks wholly stale, %d partly; glGetError 0x%x\n", argc > 1 ? argv[1] : "bug",
           stale, n, partial, glGetError());
    return stale + partial ? 1 : 0;
}
