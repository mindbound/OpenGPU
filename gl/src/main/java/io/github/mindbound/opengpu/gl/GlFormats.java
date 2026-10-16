package io.github.mindbound.opengpu.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import io.github.mindbound.opengpu.core.cmd.Formats;

/**
 * The GL enums of OpenGPU's format codes (S8 plan §3.1; 28 §6.6; 24:197), which never travel in records (24:238).
 * Surfaces are integer targets, {@code GL_R8UI} (index8) and {@code GL_R16UI} (rgb565), read back as
 * {@code GL_RED_INTEGER}; textures are {@code GL_R8}, uploaded as {@code GL_RED}, and {@code GL_R16UI}, uploaded as
 * {@code GL_RED_INTEGER} (Q10, Q11). Constants only, no GL call.
 */
final class GlFormats {

    private GlFormats() {}

    /** The surface texture's internal format: {@code GL_R8UI} or {@code GL_R16UI}. */
    static int surfaceInternal(int format) {
        return format == Formats.INDEX8 ? GL30.GL_R8UI : checkRgb565(format, GL30.GL_R16UI);
    }

    /** The surface's pixel transfer format: {@code GL_RED_INTEGER} for both. */
    static int surfacePixelFormat(int format) {
        Formats.bytesPerPixel(format);
        return GL30.GL_RED_INTEGER;
    }

    /** The pixel type of both a surface and a texture: {@code GL_UNSIGNED_BYTE} or {@code GL_UNSIGNED_SHORT}. */
    static int pixelType(int format) {
        return format == Formats.INDEX8 ? GL11.GL_UNSIGNED_BYTE : checkRgb565(format, GL11.GL_UNSIGNED_SHORT);
    }

    /** A texture's internal format: {@code GL_R8} (28:583) or {@code GL_R16UI} (28:584). */
    static int textureInternal(int format) {
        return format == Formats.INDEX8 ? GL30.GL_R8 : checkRgb565(format, GL30.GL_R16UI);
    }

    /** A texture's pixel transfer format: {@code GL_RED} or {@code GL_RED_INTEGER}. */
    static int texturePixelFormat(int format) {
        return format == Formats.INDEX8 ? GL11.GL_RED : checkRgb565(format, GL30.GL_RED_INTEGER);
    }

    private static int checkRgb565(int format, int value) {
        if (format != Formats.RGB565) {
            throw new IllegalArgumentException("format " + format + " is undefined");
        }
        return value;
    }
}
