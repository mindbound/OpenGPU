package io.github.mindbound.opengpu.gltestkit.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.gl.GlBackend;

/**
 * Test-only synchronous reads on the current context, on every leg (S8 plan §2.4): A08's
 * {@code glGetTexImage(..., ByteBuffer)} of a whole texture level (25:412; {@code GLStateManager} declares it at 2.2.8
 * and 2.2.28), with the pack PBO unbound and the six pack values forced around it and restored after, so a hostile
 * start can neither skew the rows nor, on Intel's driver, crash the JVM (U4b's note). Never in {@code :gl}, whose
 * readback is the PBO ring (24:197). Before the read the texture's level-0 size is asked of GL and must give exactly
 * the bytes asked for, since LWJGL 2 checks no size for {@code GL_RED}/{@code GL_RED_INTEGER} (S8 amendments G, B9).
 * GL thread only; GL-calling code in plan §0's form: GL11-GL33 static calls, no lambdas, no GL work at class load.
 */
public final class GlReads {

    private static final int[] PACK = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
        GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST };
    /** Tightly packed rows, as {@code PixelStore} forces them (28 C9). */
    private static final int[] FORCED = { 1, 0, 0, 0, GL11.GL_FALSE, GL11.GL_FALSE };
    /** Read buffers start as this byte, so a byte the driver never wrote shows. */
    private static final byte SENTINEL = 0x7E;

    private GlReads() {}

    /**
     * {@link #texture(int, int, int, int)} inside the backend's bracket: its own {@code beginPass}/{@code endPass}
     * unless a pass is open (S8 amendments B: no GL work on a bare context).
     */
    public static byte[] texture(GlBackend backend, int name, int format, int type, int bytes) {
        boolean bracket = !backend.inPass();
        if (bracket) {
            backend.beginPass();
        }
        try {
            return texture(name, format, type, bytes);
        } finally {
            if (bracket) {
                backend.endPass();
            }
        }
    }

    /** An RGBA8 texture of the surface's size, such as the present texture (H27): R, G, B, A per pixel, row 0 first. */
    public static byte[] rgba(GlBackend backend, int name, SurfaceDesc surface) {
        return texture(backend, name, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4 * surface.pixelCount());
    }

    /** A surface texture ({@code GL_R8UI} or {@code GL_R16UI}) in the 28 §5.7 layout (S8 plan §3.1's readback row). */
    public static byte[] surface(GlBackend backend, int name, SurfaceDesc surface) {
        int type = surface.format == Formats.INDEX8 ? GL11.GL_UNSIGNED_BYTE : GL11.GL_UNSIGNED_SHORT;
        return texture(backend, name, GL30.GL_RED_INTEGER, type, surface.byteSize());
    }

    /**
     * Level 0 of the 2D texture {@code name}, bound on the active unit for the read and the unit's binding restored
     * after.
     *
     * @throws IllegalArgumentException for a format and type this does not size, or unless the level holds exactly
     *                                  {@code bytes} bytes (B9)
     */
    public static byte[] texture(int name, int format, int type, int bytes) {
        int bpp = bytesPerPixel(format, type);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        try {
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if ((long) width * height * bpp != bytes) {
                throw new IllegalArgumentException(
                    String.format(
                        Locale.ROOT,
                        "texture %d is %dx%d at %d B per pixel, not %d B (B9)",
                        name,
                        width,
                        height,
                        bpp,
                        bytes));
            }
            ByteBuffer out = ByteBuffer.allocateDirect(bytes)
                .order(ByteOrder.nativeOrder());
            for (int i = 0; i < bytes; i++) {
                out.put(i, SENTINEL);
            }
            out.clear();
            if (out.position() != 0 || out.remaining() != bytes) {
                throw new IllegalArgumentException("the read buffer is not at 0 with " + bytes + " B (B9)");
            }
            int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int[] saved = new int[PACK.length];
            for (int i = 0; i < PACK.length; i++) {
                saved[i] = GL11.glGetInteger(PACK[i]);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < PACK.length; i++) {
                GL11.glPixelStorei(PACK[i], FORCED[i]);
            }
            try {
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, format, type, out);
            } finally {
                for (int i = PACK.length - 1; i >= 0; i--) {
                    GL11.glPixelStorei(PACK[i], saved[i]);
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            }
            byte[] result = new byte[bytes];
            for (int i = 0; i < bytes; i++) {
                result[i] = out.get(i);
            }
            return result;
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
        }
    }

    /** {@code glFinish}: every command so far has completed, its queries' results included (the Readback scenario). */
    public static void finish() {
        GL11.glFinish();
    }

    private static int bytesPerPixel(int format, int type) {
        if (format == GL11.GL_RGBA && type == GL11.GL_UNSIGNED_BYTE) {
            return 4;
        }
        if ((format == GL30.GL_RED_INTEGER || format == GL11.GL_RED) && type == GL11.GL_UNSIGNED_BYTE) {
            return 1;
        }
        if (format == GL30.GL_RED_INTEGER && type == GL11.GL_UNSIGNED_SHORT) {
            return 2;
        }
        throw new IllegalArgumentException(String.format(Locale.ROOT, "format 0x%04X type 0x%04X", format, type));
    }
}
