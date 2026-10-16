package io.github.mindbound.opengpu.gltestkit.host;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

/**
 * GL work another mod does between OpenGPU's passes, on the current context and every leg (S8's fix loop after U6):
 * textures made and deleted the way GLSM's {@code glDeleteTextures} deletes them, by first re-specifying the storage
 * as 1×1 {@code GL_R8} ({@code deferDeleteTexture}, Angelica 2.2.8's {@code GLStateManager.java:2201-2254}, which
 * keeps the name until the next {@code glGenTextures}). Done by hand here, so the
 * compatibility leg sees it too; on the GLSM legs GLSM shrinks each texture once more and deletes it at the next
 * {@code glGenTextures}. The texture binding of the active unit and the unpack buffer are restored. The uploads pass
 * no data ({@code null}), so no client memory is read (S8 amendments G, B9), with the unpack buffer unbound. GL thread
 * only; GL-calling code in plan §0's form: GL11-GL33 static calls, no lambdas, no GL work at class load.
 */
public final class GlForeignWork {

    /** The side of each texture before its shrink. */
    static final int SIDE = 16;

    private GlForeignWork() {}

    /**
     * Makes {@code count} {@code GL_R8} textures of {@link #SIDE}², re-specifies each as 1×1 and deletes them.
     *
     * @return count
     */
    public static int deleteTexturesAsGlsm(int count) {
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int[] names = new int[count];
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            for (int i = 0; i < count; i++) {
                names[i] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, names[i]);
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL30.GL_R8,
                    SIDE,
                    SIDE,
                    0,
                    GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
                // GLSM's shrink: one level, 1×1, the name kept
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL30.GL_R8,
                    1,
                    1,
                    0,
                    GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, bound);
            for (int name : names) {
                if (name != 0) {
                    GL11.glDeleteTextures(name);
                }
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
        return count;
    }
}
