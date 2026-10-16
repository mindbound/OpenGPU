package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;
import java.util.Locale;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * One GL texture name of OpenGPU's with its {@code GlLedger} row: a surface, a present texture, a device's texture, the
 * glyph atlas or the palette. Every texture is bound on unit 0 (28:588; H53) with {@code GL_NEAREST},
 * {@code GL_CLAMP_TO_EDGE} and {@code GL_TEXTURE_MAX_LEVEL} 0, set before its first upload (28:585; 20:37-38; the
 * last keeps GLSM's emulated mipmap generation inert). Every {@code glTexImage2D}, a null one included, and every
 * {@code glTexSubImage2D} runs inside the pass's unpack bracket with no unpack PBO bound (S8 amendments B), and every
 * buffer is checked first (B9): LWJGL 2 checks no size for {@code GL_RED} or {@code GL_RED_INTEGER}. GL thread.
 */
final class GlTexture {

    final int name;
    final int width;
    final int height;
    /** The bytes of one texel in the upload format, for the buffer checks. */
    final int bytesPerTexel;
    final int pixelFormat;
    final int pixelType;
    private final long session;
    private final int deviceId;
    private final String kind;
    private final String label;
    private final long bytes;

    private GlTexture(int name, int width, int height, int bytesPerTexel, int pixelFormat, int pixelType, long session,
        int deviceId, String kind, String label, long bytes) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.bytesPerTexel = bytesPerTexel;
        this.pixelFormat = pixelFormat;
        this.pixelType = pixelType;
        this.session = session;
        this.deviceId = deviceId;
        this.kind = kind;
        this.label = label;
        this.bytes = bytes;
    }

    /**
     * {@code glGenTextures} (C29), the parameters (C30) and {@code glTexImage2D} (C31) with {@code texels}, or null
     * for GL's unspecified contents, then the ledger row of {@code w·h·bytesPerTexel} bytes. Leaves it bound on the
     * active unit, which must be unit 0.
     *
     * @throws IllegalArgumentException for texels not at position 0 with exactly {@code w·h·bytesPerTexel} remaining
     */
    static GlTexture create(GlLedger ledger, long session, int deviceId, String kind, String label, int internalFormat,
        int width, int height, int pixelFormat, int pixelType, int bytesPerTexel, ByteBuffer texels) {
        long bytes = (long) width * height * bytesPerTexel;
        if (texels != null) {
            checkBuffer(texels, bytes, kind + " " + label);
        }
        int name = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, pixelFormat, pixelType, texels);
        ledger.add(session, deviceId, kind, label, bytes);
        return new GlTexture(
            name,
            width,
            height,
            bytesPerTexel,
            pixelFormat,
            pixelType,
            session,
            deviceId,
            kind,
            label,
            bytes);
    }

    /**
     * {@code glTexSubImage2D} (C32) of a region, bound on the active unit (unit 0).
     *
     * @throws IllegalArgumentException for a region outside the texture, or texels not at position 0 with exactly
     *                                  {@code w·h·bytesPerTexel} remaining
     */
    void upload(int x, int y, int w, int h, ByteBuffer texels) {
        if (x < 0 || y < 0 || w < 1 || h < 1 || w > width - x || h > height - y) {
            throw new IllegalArgumentException(
                String.format(
                    Locale.ROOT,
                    "region (%d, %d) %dx%d is outside %s %s's %dx%d",
                    x,
                    y,
                    w,
                    h,
                    kind,
                    label,
                    width,
                    height));
        }
        checkBuffer(texels, (long) w * h * bytesPerTexel, kind + " " + label);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, pixelFormat, pixelType, texels);
    }

    /** {@code glDeleteTextures} (C33); the ledger row goes at call time (28:604). */
    void delete(GlLedger ledger) {
        GL11.glDeleteTextures(name);
        ledger.remove(session, deviceId, kind, label, bytes);
    }

    long bytes() {
        return bytes;
    }

    /**
     * B9: a buffer at position 0 holding exactly {@code bytes}, since LWJGL 2 checks no size for {@code GL_RED} or
     * {@code GL_RED_INTEGER} and a short buffer would read native memory past its end.
     *
     * @throws IllegalArgumentException otherwise
     */
    static void checkBuffer(ByteBuffer buffer, long bytes, String what) {
        if (buffer == null || !buffer.isDirect() || buffer.position() != 0 || buffer.remaining() != bytes) {
            throw new IllegalArgumentException(
                what + " needs a direct buffer at position 0 with "
                    + bytes
                    + " bytes remaining, not "
                    + (buffer == null ? "null"
                        : buffer.isDirect() ? "position " + buffer.position() + ", " + buffer.remaining() + " remaining"
                            : "a heap buffer"));
        }
    }
}
