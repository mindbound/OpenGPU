package io.github.mindbound.opengpu.gltestkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The headless-GL smoke test (roadmap M0, step S4): a real context of the task's profile, and exact readback of the
 * formats M0 renders into, RGBA8 and the integer surfaces R8UI (T2) and R16UI (T3) of
 * docs/research/24-architecture-gl-first.md §3.7, through glReadPixels and through glGetTexImage, the production
 * readback. Targets are 7 x 5, so row padding and swapped axes show; read buffers start as 0x7E, so a byte the driver
 * never wrote shows too.
 */
@GlTest
class GlSmokeTest {

    private static final int W = 7;
    private static final int H = 5;
    // The second clear's scissor box, in GL's bottom-up window coordinates
    private static final int SX = 2;
    private static final int SY = 1;
    private static final int SW = 3;
    private static final int SH = 2;
    private static final byte SENTINEL = 0x7E;

    @Test
    void reportsItsContext(GlInfo info, TestReporter reporter) {
        reporter.publishEntry(info.asMap());
        Assertions.assertTrue(info.gl33, info.version);
    }

    @Test
    void rgba8ClearIsExact(GlThread gl) throws Exception {
        byte[][] reads = gl.call(() -> drawAndRead(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4, () -> {
            GL11.glClearColor(51 / 255f, 102 / 255f, 153 / 255f, 204 / 255f); // k/255 converts exactly
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            scissored(() -> {
                GL11.glClearColor(1f, 0f, 0f, 1f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            });
        }));
        assertPixels(reads, 4, bytes(51, 102, 153, 204), bytes(255, 0, 0, 255));
    }

    // glClear is undefined on integer buffers, so these two clear with glClearBuffer. Production clears integer
    // surfaces with a quad instead, since GLSM leaves glClearBuffer* unmapped (24 §3.7); these tests run without GLSM.

    @Test
    void r8uiClearIsExact(GlThread gl) throws Exception {
        byte[][] reads = gl.call(() -> drawAndRead(GL30.GL_R8UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, 1, () -> {
            GL30.glClearBufferu(GL11.GL_COLOR, 0, uint(0xA5));
            scissored(() -> GL30.glClearBufferu(GL11.GL_COLOR, 0, uint(0x5A)));
        }));
        assertPixels(reads, 1, bytes(0xA5), bytes(0x5A));
    }

    @Test
    void r16uiClearIsExact(GlThread gl) throws Exception {
        byte[][] reads = gl
            .call(() -> drawAndRead(GL30.GL_R16UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_SHORT, 2, () -> {
                GL30.glClearBufferu(GL11.GL_COLOR, 0, uint(0xBEEF));
                scissored(() -> GL30.glClearBufferu(GL11.GL_COLOR, 0, uint(0x1234)));
            }));
        assertPixels(reads, 2, u16(0xBEEF), u16(0x1234));
    }

    /** Draws into a W x H texture on COLOR_ATTACHMENT0, reads it back both ways, and deletes what it made. */
    private static byte[][] drawAndRead(int internalFormat, int format, int type, int bytesPerPixel,
        GlThread.GlAction draw) throws Exception {
        int texture = GL11.glGenTextures();
        int framebuffer = GL30.glGenFramebuffers();
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, W, H, 0, format, type, (ByteBuffer) null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            Assertions.assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));
            GL11.glViewport(0, 0, W, H);
            GL11.glDisable(GL11.GL_DITHER); // 23 §3.1
            draw.run();
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            ByteBuffer viaReadPixels = sentinel(W * H * bytesPerPixel);
            GL11.glReadPixels(0, 0, W, H, format, type, viaReadPixels);
            ByteBuffer viaGetTexImage = sentinel(W * H * bytesPerPixel);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, type, viaGetTexImage);
            return new byte[][] { copy(viaReadPixels), copy(viaGetTexImage) };
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glEnable(GL11.GL_DITHER);
            GL11.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(texture);
        }
    }

    private static void scissored(GlThread.GlAction clear) throws Exception {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(SX, SY, SW, SH);
        try {
            clear.run();
        } finally {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        }
    }

    /** Every byte of both readbacks: {@code inside} in the scissor box, {@code outside} elsewhere; rows bottom-up. */
    private static void assertPixels(byte[][] reads, int bytesPerPixel, byte[] outside, byte[] inside) {
        String[] via = { "glReadPixels", "glGetTexImage" };
        for (int r = 0; r < reads.length; r++) {
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    boolean in = x >= SX && x < SX + SW && y >= SY && y < SY + SH;
                    byte[] want = in ? inside : outside;
                    for (int c = 0; c < bytesPerPixel; c++) {
                        int got = reads[r][(y * W + x) * bytesPerPixel + c] & 0xFF;
                        if (got != (want[c] & 0xFF)) {
                            Assertions.fail(
                                via[r] + ": pixel ("
                                    + x
                                    + ", "
                                    + y
                                    + ") byte "
                                    + c
                                    + " is "
                                    + got
                                    + ", expected "
                                    + (want[c] & 0xFF));
                        }
                    }
                }
            }
        }
    }

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

    /** The four-component value glClearBufferuiv takes, red set. */
    private static IntBuffer uint(int value) {
        IntBuffer buffer = BufferUtils.createIntBuffer(4);
        buffer.put(0, value);
        return buffer;
    }

    private static byte[] u16(int value) {
        return ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? bytes(value & 0xFF, value >>> 8)
            : bytes(value >>> 8, value & 0xFF);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
