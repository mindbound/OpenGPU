package com.gtnewhorizons.angelica.glsm.backend;

import java.nio.*;
import java.util.*;

/** Generated headless stub. Only getMinGLSLVersion/isAvailable/getName/getPriority matter. */
public class HeadlessStubBackend extends RenderBackend {
    @Override public void init() {  }
    @Override public void shutdown() {  }
    @Override public boolean isAvailable() { return true; }
    @Override public String getName() { return "headless-stub"; }
    @Override public boolean hasContext() { return false; }
    @Override public int getMinGLSLVersion() { return Integer.getInteger("stub.glsl", 330); }
    @Override public void flush() {  }
    @Override public void finish() {  }
    @Override public void enable(int cap) {  }
    @Override public void enablei(int cap, int index) {  }
    @Override public void disable(int cap) {  }
    @Override public void disablei(int cap, int index) {  }
    @Override public void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {  }
    @Override public void blendFunc(int sfactor, int dfactor) {  }
    @Override public void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {  }
    @Override public void blendEquation(int mode) {  }
    @Override public void blendEquationSeparate(int modeRGB, int modeAlpha) {  }
    @Override public void blendColor(float red, float green, float blue, float alpha) {  }
    @Override public void depthFunc(int func) {  }
    @Override public void depthMask(boolean flag) {  }
    @Override public void colorMask(boolean red, boolean green, boolean blue, boolean alpha) {  }
    @Override public void cullFace(int mode) {  }
    @Override public void frontFace(int mode) {  }
    @Override public void polygonMode(int face, int mode) {  }
    @Override public void polygonOffset(float factor, float units) {  }
    @Override public void stencilFunc(int func, int ref, int mask) {  }
    @Override public void stencilOp(int sfail, int dpfail, int dppass) {  }
    @Override public void stencilMask(int mask) {  }
    @Override public void stencilFuncSeparate(int face, int func, int ref, int mask) {  }
    @Override public void stencilOpSeparate(int face, int sfail, int dpfail, int dppass) {  }
    @Override public void stencilMaskSeparate(int face, int mask) {  }
    @Override public void viewport(int x, int y, int width, int height) {  }
    @Override public void depthRange(double nearVal, double farVal) {  }
    @Override public void scissor(int x, int y, int width, int height) {  }
    @Override public void clearColor(float red, float green, float blue, float alpha) {  }
    @Override public void clearDepth(double depth) {  }
    @Override public void clearStencil(int s) {  }
    @Override public void clear(int mask) {  }
    @Override public void lineWidth(float width) {  }
    @Override public void pointSize(float size) {  }
    @Override public void logicOp(int opcode) {  }
    @Override public void hint(int target, int mode) {  }
    @Override public void drawArrays(int mode, int first, int count) {  }
    @Override public void drawElements(int mode, int count, int type, long indices) {  }
    @Override public void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {  }
    @Override public void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {  }
    @Override public void drawElementsInstanced(int mode, int count, int type, long indices, int primcount) {  }
    @Override public void drawArraysInstanced(int mode, int first, int count, int primcount) {  }
    @Override public void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex) {  }
    @Override public void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {  }
    @Override public void drawBuffer(int mode) {  }
    @Override public void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {  }
    @Override public void dispatchComputeIndirect(long offset) {  }
    @Override public int genTextures() { return 0; }
    @Override public void genTextures(IntBuffer textures) {  }
    @Override public void deleteTextures(int texture) {  }
    @Override public void bindTexture(int target, int texture) {  }
    @Override public void activeTexture(int texture) {  }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels) {  }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {  }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels) {  }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels) {  }
    @Override public void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixelBufferOffset) {  }
    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {  }
    @Override public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {  }
    @Override public void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {  }
    @Override public void texParameteri(int target, int pname, int param) {  }
    @Override public void texParameterf(int target, int pname, float param) {  }
    @Override public void texParameteriv(int target, int pname, IntBuffer params) {  }
    @Override public void texParameterfv(int target, int pname, FloatBuffer params) {  }
    @Override public int getTexParameteri(int target, int pname) { return 0; }
    @Override public float getTexParameterf(int target, int pname) { return 0f; }
    @Override public int getTexLevelParameteri(int target, int level, int pname) { return 0; }
    @Override public void generateMipmap(int target) {  }
    @Override public void pixelStorei(int pname, int param) {  }
    @Override public int genSamplers() { return 0; }
    @Override public void deleteSamplers(int sampler) {  }
    @Override public void bindSampler(int unit, int sampler) {  }
    @Override public void samplerParameteri(int sampler, int pname, int param) {  }
    @Override public void samplerParameterf(int sampler, int pname, float param) {  }
    @Override public int genFramebuffers() { return 0; }
    @Override public void deleteFramebuffers(int framebuffer) {  }
    @Override public void bindFramebuffer(int target, int framebuffer) {  }
    @Override public void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {  }
    @Override public void framebufferTexture(int target, int attachment, int texture, int level) {  }
    @Override public int checkFramebufferStatus(int target) { return 0; }
    @Override public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {  }
    @Override public void drawBuffers(int buffer) {  }
    @Override public void drawBuffers(IntBuffer bufs) {  }
    @Override public void readBuffer(int mode) {  }
    @Override public void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {  }
    @Override public void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {  }
    @Override public void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {  }
    @Override public void getTexImage(int target, int level, int format, int type, ByteBuffer pixels) {  }
    @Override public void getTexImage(int target, int level, int format, int type, IntBuffer pixels) {  }
    @Override public void getTexImage(int target, int level, int format, int type, long pixelBufferOffset) {  }
    @Override public int getFramebufferAttachmentParameteri(int target, int attachment, int pname) { return 0; }
    @Override public int createShader(int type) { return 0; }
    @Override public void deleteShader(int shader) {  }
    @Override public void shaderSource(int shader, CharSequence source) {  }
    @Override public void compileShader(int shader) {  }
    @Override public int createProgram() { return 0; }
    @Override public void deleteProgram(int program) {  }
    @Override public void attachShader(int program, int shader) {  }
    @Override public void detachShader(int program, int shader) {  }
    @Override public void linkProgram(int program) {  }
    @Override public void useProgram(int program) {  }
    @Override public String getShaderInfoLog(int shader, int maxLength) { return ""; }
    @Override public void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {  }
    @Override public String getProgramInfoLog(int program, int maxLength) { return ""; }
    @Override public void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {  }
    @Override public int getShaderi(int shader, int pname) { return 0; }
    @Override public int getProgrami(int program, int pname) { return 0; }
    @Override public void getProgramiv(int program, int pname, IntBuffer params) {  }
    @Override public String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType) { return ""; }
    @Override public void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {  }
    @Override public void bindAttribLocation(int program, int index, CharSequence name) {  }
    @Override public int getAttribLocation(int program, CharSequence name) { return 0; }
    @Override public int getAttribLocation(int program, ByteBuffer name) { return 0; }
    @Override public int getUniformLocation(int program, CharSequence name) { return 0; }
    @Override public int getUniformLocation(int program, ByteBuffer name) { return 0; }
    @Override public void getShaderSource(int shader, IntBuffer length, ByteBuffer source) {  }
    @Override public void uniform1i(int location, int v0) {  }
    @Override public void uniform1f(int location, float v0) {  }
    @Override public void uniform2f(int location, float v0, float v1) {  }
    @Override public void uniform2i(int location, int v0, int v1) {  }
    @Override public void uniform3f(int location, float v0, float v1, float v2) {  }
    @Override public void uniform3i(int location, int v0, int v1, int v2) {  }
    @Override public void uniform4f(int location, float v0, float v1, float v2, float v3) {  }
    @Override public void uniform4i(int location, int v0, int v1, int v2, int v3) {  }
    @Override public void uniform2(int location, FloatBuffer value) {  }
    @Override public void uniform3(int location, FloatBuffer value) {  }
    @Override public void uniform4(int location, FloatBuffer value) {  }
    @Override public void uniform1iv(int location, IntBuffer value) {  }
    @Override public void uniform2iv(int location, IntBuffer value) {  }
    @Override public void uniform3iv(int location, IntBuffer value) {  }
    @Override public void uniform4iv(int location, IntBuffer value) {  }
    @Override public void uniformMatrix3(int location, boolean transpose, FloatBuffer value) {  }
    @Override public void uniformMatrix4(int location, boolean transpose, FloatBuffer value) {  }
    @Override public void vertexAttrib2f(int index, float v0, float v1) {  }
    @Override public void vertexAttrib3f(int index, float v0, float v1, float v2) {  }
    @Override public void vertexAttrib4f(int index, float v0, float v1, float v2, float v3) {  }
    @Override public int genBuffers() { return 0; }
    @Override public void deleteBuffers(int buffer) {  }
    @Override public void deleteBuffers(IntBuffer buffers) {  }
    @Override public void bindBuffer(int target, int buffer) {  }
    @Override public void bindBufferBase(int target, int index, int buffer) {  }
    @Override public void bufferData(int target, long size, int usage) {  }
    @Override public void bufferData(int target, ByteBuffer data, int usage) {  }
    @Override public void bufferData(int target, FloatBuffer data, int usage) {  }
    @Override public void bufferData(int target, ShortBuffer data, int usage) {  }
    @Override public void bufferData(int target, IntBuffer data, int usage) {  }
    @Override public void bufferData(int target, DoubleBuffer data, int usage) {  }
    @Override public void bufferData(int target, int[] data, int usage) {  }
    @Override public void bufferData(int target, float[] data, int usage) {  }
    @Override public void bufferSubData(int target, long offset, ByteBuffer data) {  }
    @Override public void bufferSubData(int target, long offset, ShortBuffer data) {  }
    @Override public void bufferSubData(int target, long offset, IntBuffer data) {  }
    @Override public void bufferSubData(int target, long offset, FloatBuffer data) {  }
    @Override public void bufferSubData(int target, long offset, DoubleBuffer data) {  }
    @Override public ByteBuffer mapBuffer(int target, int access) { return null; }
    @Override public ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer old_buffer) { return null; }
    @Override public boolean unmapBuffer(int target) { return false; }
    @Override public void bufferStorage(int target, ByteBuffer data, int flags) {  }
    @Override public void bufferStorage(int target, long size, int flags) {  }
    @Override public void getBufferSubData(int target, long offset, ByteBuffer data) {  }
    @Override public void getBufferSubData(int target, long offset, ShortBuffer data) {  }
    @Override public void getBufferSubData(int target, long offset, IntBuffer data) {  }
    @Override public void getBufferSubData(int target, long offset, FloatBuffer data) {  }
    @Override public void getBufferSubData(int target, long offset, DoubleBuffer data) {  }
    @Override public int getBufferParameteri(int target, int pname) { return 0; }
    @Override public boolean isBuffer(int buffer) { return false; }
    @Override public ByteBuffer mapBufferRange(int target, long offset, long length, int access) { return null; }
    @Override public void flushMappedBufferRange(int target, long offset, long length) {  }
    @Override public int genVertexArrays() { return 0; }
    @Override public void deleteVertexArrays(int array) {  }
    @Override public void bindVertexArray(int array) {  }
    @Override public boolean isVertexArray(int array) { return false; }
    @Override public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {  }
    @Override public void vertexAttribIPointer(int index, int size, int type, int stride, long pointer) {  }
    @Override public void enableVertexAttribArray(int index) {  }
    @Override public void disableVertexAttribArray(int index) {  }
    @Override public void vertexAttribDivisor(int index, int divisor) {  }
    @Override public void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride) {  }
    @Override public void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) {  }
    @Override public void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) {  }
    @Override public void vertexAttribBinding(int attribindex, int bindingindex) {  }
    @Override public int createTextures(int target) { return 0; }
    @Override public void bindTextureUnit(int unit, int texture) {  }
    @Override public void textureParameteri(int texture, int target, int pname, int param) {  }
    @Override public void textureParameterf(int texture, int target, int pname, float param) {  }
    @Override public void textureParameteriv(int texture, int target, int pname, IntBuffer params) {  }
    @Override public void texStorage1D(int target, int levels, int internalFormat, int width) {  }
    @Override public void texStorage2D(int target, int levels, int internalFormat, int width, int height) {  }
    @Override public void texStorage3D(int target, int levels, int internalFormat, int width, int height, int depth) {  }
    @Override public void textureStorage1D(int texture, int levels, int internalFormat, int width) {  }
    @Override public void textureStorage2D(int texture, int levels, int internalFormat, int width, int height) {  }
    @Override public void textureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) {  }
    @Override public void generateTextureMipmap(int texture) {  }
    @Override public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {  }
    @Override public void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {  }
    @Override public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {  }
    @Override public void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {  }
    @Override public int createFramebuffers() { return 0; }
    @Override public void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level) {  }
    @Override public void namedFramebufferReadBuffer(int framebuffer, int mode) {  }
    @Override public void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs) {  }
    @Override public void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {  }
    @Override public int createBuffers() { return 0; }
    @Override public void namedBufferData(int buffer, long size, int usage) {  }
    @Override public void namedBufferData(int buffer, ByteBuffer data, int usage) {  }
    @Override public void namedBufferData(int buffer, FloatBuffer data, int usage) {  }
    @Override public void namedBufferSubData(int buffer, long offset, ByteBuffer data) {  }
    @Override public void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {  }
    @Override public int getTextureParameteri(int texture, int target, int pname) { return 0; }
    @Override public int getTextureLevelParameteri(int texture, int level, int pname) { return 0; }
    @Override public int getInteger(int pname) { return 0; }
    @Override public void getInteger(int pname, IntBuffer params) {  }
    @Override public float getFloat(int pname) { return 0f; }
    @Override public void getFloat(int pname, FloatBuffer params) {  }
    @Override public boolean getBoolean(int pname) { return false; }
    @Override public void getBoolean(int pname, ByteBuffer params) {  }
    @Override public String getString(int pname) { return ""; }
    @Override public String getStringi(int name, int index) { return ""; }
    @Override public int getError() { return 0; }
    @Override public long fenceSync(int condition, int flags) { return 0L; }
    @Override public int clientWaitSync(long sync, int flags, long timeout) { return 0; }
    @Override public void deleteSync(long sync) {  }
    @Override public void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data) {  }
    @Override public void clearTexImage(int texture, int level, int format, int type) {  }
    @Override public void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {  }
    @Override public void memoryBarrier(int barriers) {  }
    @Override public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,                                            int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,                                            int srcWidth, int srcHeight, int srcDepth) {  }
}
