package io.github.mindbound.opengpu.gltestkit.glsmprobe;

import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import io.github.mindbound.opengpu.gltestkit.GlThread;

/**
 * The GLSM probes' render target (the GLSM spike, roadmap M0): a W x H texture on COLOR_ATTACHMENT0 of its own
 * framebuffer, as in GlSmokeTest, read back through glReadPixels and glGetTexImage. Every class in this package is
 * OpenGPU's own GL-calling code in the render core's form, LWJGL calls only (docs/research/23-gl-testing-strategy.md
 * §2.1), and each runs twice on a GLSM leg: as compiled, the raw copy, and through GLSMRedirector by GlsmLoader, the
 * GLSM copy. So: no GLSM types, no GL work in a constructor or static initializer (probes are instantiated off the GL
 * thread), no lambdas or method references (25 R-5), and every glEnable or glDisable takes its capability constant in
 * the call, where the redirector folds it.
 */
public final class ProbeTarget {

    public static final int W = 7;
    public static final int H = 5;
    // The scissored clears' box, in GL's bottom-up window coordinates
    public static final int SX = 2;
    public static final int SY = 1;
    public static final int SW = 3;
    public static final int SH = 2;
    /** Rgba8ClearProbe's full-clear colour, in 1/255 steps, which convert exactly; StateProbe sets it too. */
    public static final int CLEAR_R = 51;
    public static final int CLEAR_G = 102;
    public static final int CLEAR_B = 153;
    public static final int CLEAR_A = 204;
    private static final byte SENTINEL = 0x7E;

    private final int format;
    private final int type;
    private final int bytesPerPixel;
    private final int texture;
    private final int framebuffer;

    private ProbeTarget(int format, int type, int bytesPerPixel, int texture, int framebuffer) {
        this.format = format;
        this.type = type;
        this.bytesPerPixel = bytesPerPixel;
        this.texture = texture;
        this.framebuffer = framebuffer;
    }

    /** Creates the target and binds it for drawing and reading, with a W x H viewport and dither off (23 §3.1). */
    public static ProbeTarget open(int internalFormat, int format, int type, int bytesPerPixel) {
        ProbeTarget target = new ProbeTarget(
            format,
            type,
            bytesPerPixel,
            GL11.glGenTextures(),
            GL30.glGenFramebuffers());
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, W, H, 0, format, type, (ByteBuffer) null);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.framebuffer);
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            target.texture,
            0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            target.close();
            throw new IllegalStateException("the probe framebuffer is incomplete: 0x" + Integer.toHexString(status));
        }
        GL11.glViewport(0, 0, W, H);
        GL11.glDisable(GL11.GL_DITHER);
        return target;
    }

    /** Limits the following clears to the scissor box. */
    public static void scissorOn() {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(SX, SY, SW, SH);
    }

    /** Ends scissorOn with the context's defaults. */
    public static void scissorOff() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
    }

    /** The target's bytes through glReadPixels and through glGetTexImage, the production readback (24 §3.7). */
    public byte[][] read() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        ByteBuffer viaReadPixels = sentinel(W * H * bytesPerPixel);
        GL11.glReadPixels(0, 0, W, H, format, type, viaReadPixels);
        ByteBuffer viaGetTexImage = sentinel(W * H * bytesPerPixel);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, type, viaGetTexImage);
        return new byte[][] { copy(viaReadPixels), copy(viaGetTexImage) };
    }

    /** Restores what open and read changed, and deletes the framebuffer before its texture (25 R-10). */
    public void close() {
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glEnable(GL11.GL_DITHER);
        GL11.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glDeleteFramebuffers(framebuffer);
        GL11.glDeleteTextures(texture);
    }

    // Read buffers start as 0x7E, so a byte the driver never wrote shows
    private static ByteBuffer sentinel(int size) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(size);
        for (int i = 0; i < size; i++) {
            buffer.put(i, SENTINEL);
        }
        return buffer;
    }

    private static byte[] copy(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate()
            .get(out);
        return out;
    }
}
