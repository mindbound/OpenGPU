package io.github.mindbound.opengpu.client.render;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;

import io.github.mindbound.opengpu.display.ScreenSurface;

/**
 * The screen's GL calls, the one class of root client code that draws, so that {@code jarTest}'s checks see every
 * call in one place (R-4, R-6, 25:436-452; 20:169; 02 §5 step 3; S12 plan P8.4 with amendments I2 and I6; S12
 * decisions D23). Two methods, each one {@link Tessellator} quad built first, then its calls around the quad's
 * {@code draw()}:
 * <ul>
 * <li>{@link #world}, the TESR's bracket: C04 {@code glPushAttrib} of {@link #PUSHED}, F09
 * {@code glDisable(GL_LIGHTING)} with the constant in the call, so GLSM folds it to {@code disableLighting()V}, X01
 * {@code OpenGlHelper.setLightmapTextureCoords(lightmapTexUnit, 240, 240)}, C11 {@code glBindTexture} of the texture
 * on unit 0, the draw, C11 of 0, C05 {@code glPopAttrib}, and nothing after the pop;</li>
 * <li>{@link #gui}, the GUI's: C11 of the texture, the draw, C11 of 0, with no push or pop, since the binding is the
 * only state it changes.</li>
 * </ul>
 * <b>Unit 0's binding</b> is changed by {@code glBindTexture} both ways and never restored by a pop (S12 decisions
 * D23). Under Angelica a pop restores a pushed binding in GLSM's cache (GLStateManager.java:3631-3635 at 2.2.8) and
 * in the driver (:3791-3805) only, without the {@code TEXTURE_BIND} event and the {@code RenderSystem} update
 * that {@code glBindTexture} makes when it misses the cache (:1986-2000), so Iris would keep its bind-driven uniforms
 * and PBR maps on our texture while the next renderer draws with the restored one (S12 review gl-3). Through
 * {@code glBindTexture}, GLSM's cache, {@code RenderSystem} and Iris agree on every change. After the draw it binds 0
 * rather than leave our texture bound, so a later START pass never saves, deletes and rebinds the name of a texture it
 * has just deleted (O-16); Iris takes 0 as an untracked texture, with the default PBR maps and an {@code atlasSize} of
 * 0, as it takes ours (CommonUniforms.java:79-89 and PBRTextureManager.java:74-92 at 2.2.8). The binding it found is
 * not put back: vanilla's TESRs leave their own textures bound, the chest's (TileEntityChestRenderer.java:80-105, with
 * nothing rebound by its end at :175-178) and the sign's and its font's (TileEntitySignRenderer.java:61-91), and the
 * dispatcher sets only the lightmap and the colour before each TESR (TileEntityRendererDispatcher.java:121-125), so no
 * later renderer can rely on the binding a TESR found; in a GUI, vanilla's draws bind their own textures, as the font
 * does (FontRenderer.java:217, :247).
 * <p>
 * Nothing else: no blend, alpha-test, depth-mask, colour-mask or per-unit texture change, no {@code glActiveTexture},
 * program, framebuffer, viewport, matrix, upload, {@code glGet}, second push, display list, flush, finish or sync, no
 * method reference or reflection, no generic attribute (R-4, R-5, R-6, V1). X01 is set and never restored: the
 * dispatcher sets each tile entity's lightmap and the colour white before its TESR
 * (TileEntityRendererDispatcher.java:121-125), as vanilla's and OC's TESRs rely on, and vanilla's
 * {@code lastBrightness} fields are stale under Angelica in a dev run (P8.4). X02 is not called: the dispatcher's white
 * applies to the world quad, which has no vertex colour or brightness of its own, and the GUI quad carries white
 * vertices through the {@code Tessellator}. The quad's vertices are the {@code Tessellator}'s Java state only, and its
 * {@code draw()} reads the current colour, lightmap and, for a quad without its own, normal when it runs, vanilla's and
 * Angelica's alike (TessellatorStreamingDrawer.java:96-139 at 2.2.8), so building it first changes nothing it draws.
 * <p>
 * No try/finally (S12 amendments I6): javac's copy of a finally block would give the pop, or the bind of 0, two call
 * sites. An exception inside the bracket would leave the push unpopped for the rest of the session, and it does not
 * always end the game: Celeritas suppresses a {@code RuntimeException} from the TESR of an invalid tile entity, such as
 * a display broken this frame (CeleritasWorldRenderer.java:571-590 at Angelica 2.2.8), while elsewhere it becomes a
 * crash report (TileEntityRendererDispatcher.java:133-151; EntityRenderer.java:1137-1142 for a screen). So every call
 * that can throw by itself comes first, before the push and before the first bind: the corner check and the
 * {@code Tessellator}'s {@code startDrawingQuads()}, which throws while another renderer's quad is still open
 * (Tessellator.java:253-259), and the vertices. Between the push and the pop there are only the rows above and the
 * quad's {@code draw()}, and between the two binds only the draw, which {@code jarTest}'s check 11 holds (S12 review
 * gl-2) [proposal]. The client main thread only, GLSM's main thread under Angelica.
 */
public final class ScreenDraw {

    /**
     * 25 R-4's bits but {@code GL_TEXTURE_BIT}, {@code 0x6140}, pushed once by {@link #world} (R-6):
     * {@code GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_LIGHTING_BIT}, never
     * {@code GL_ALL_ATTRIB_BITS}. Unit 0's binding is left out, since the bracket binds 0 itself and a pop's restore
     * would bypass GLSM's bind event (S12 decisions D23). A compile-time constant, so the push's operand is the literal
     * that {@code jarTest} reads.
     */
    static final int PUSHED = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
        | GL11.GL_DEPTH_BUFFER_BIT
        | GL11.GL_LIGHTING_BIT;

    /** X01's lightmap coordinates, full bright in a dark room: an emissive panel (02:77; 20:169). */
    static final float FULL_BRIGHT = 240f;

    /** The GUI quad's vertex colour, opaque white. */
    private static final int WHITE = 0xFFFFFF;

    private ScreenDraw() {}

    /**
     * The TESR's quad: {@code texture} over the four {@code corners}, given in {@code ScreenSurface}'s order (TOP_LEFT
     * to BOTTOM_LEFT, block coordinates), each moved by the dispatcher's camera-relative {@code (x, y, z)}, with no
     * matrix call (S12 plan P8.3). It emits them top-left, bottom-left, bottom-right, top-right, counter-clockwise from
     * the front, which the world pass's {@code GL_CULL_FACE} with GL's default back face and front face keeps
     * (EntityRenderer.java:1192; S12 amendments I2). The top-left corner takes {@code (u0, v0)} and the bottom-right
     * {@code (u1, v1)}: for a present texture (0, 0) to (1, 1), whose row 0 is the surface's top row
     * (GlBackend.java:364); for an atlas icon its own corners.
     * <p>
     * The quad carries the picture's outward normal, {@link #normal}, so that a shader pack lights it as the face it
     * is: Angelica gives a quad without normals GLSM's current normal, whatever another renderer last set
     * (TessellatorStreamingDrawer.java:116-120 at 2.2.8), such as a sign's, which faces away (S12 review gl-1)
     * [proposal]. With lighting off, as the bracket leaves it, fixed-function GL ignores it.
     * <p>
     * It leaves unit 0 bound to 0 (S12 decisions D23).
     *
     * @throws IllegalArgumentException unless {@code corners} holds four corners, before any GL call
     */
    public static void world(int texture, double u0, double v0, double u1, double v1, double[][] corners, double x,
        double y, double z) {
        if (corners.length != 4) {
            throw new IllegalArgumentException(corners.length + " corners, not 4");
        }
        double[] tl = corners[ScreenSurface.TOP_LEFT];
        double[] tr = corners[ScreenSurface.TOP_RIGHT];
        double[] br = corners[ScreenSurface.BOTTOM_RIGHT];
        double[] bl = corners[ScreenSurface.BOTTOM_LEFT];
        float[] normal = normal(corners);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setNormal(normal[0], normal[1], normal[2]);
        tessellator.addVertexWithUV(x + tl[0], y + tl[1], z + tl[2], u0, v0);
        tessellator.addVertexWithUV(x + bl[0], y + bl[1], z + bl[2], u0, v1);
        tessellator.addVertexWithUV(x + br[0], y + br[1], z + br[2], u1, v1);
        tessellator.addVertexWithUV(x + tr[0], y + tr[1], z + tr[2], u1, v0);
        GL11.glPushAttrib(PUSHED);
        GL11.glDisable(GL11.GL_LIGHTING);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, FULL_BRIGHT, FULL_BRIGHT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        tessellator.draw();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glPopAttrib();
    }

    /**
     * The outward unit normal of a picture whose four {@code corners} are in {@code ScreenSurface}'s order: the cross
     * product of its right edge, bottom-left to bottom-right, and its left edge, bottom-left to top-left, which points
     * at a viewer in front, as x right and y up give z toward the viewer. It holds for a tilted plane too (RM:110).
     */
    static float[] normal(double[][] corners) {
        double[] tl = corners[ScreenSurface.TOP_LEFT];
        double[] br = corners[ScreenSurface.BOTTOM_RIGHT];
        double[] bl = corners[ScreenSurface.BOTTOM_LEFT];
        double rx = br[0] - bl[0];
        double ry = br[1] - bl[1];
        double rz = br[2] - bl[2];
        double ux = tl[0] - bl[0];
        double uy = tl[1] - bl[1];
        double uz = tl[2] - bl[2];
        double nx = ry * uz - rz * uy;
        double ny = rz * ux - rx * uz;
        double nz = rx * uy - ry * ux;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new float[] { (float) (nx / length), (float) (ny / length), (float) (nz / length) };
    }

    /**
     * The GUI's quad: {@code texture} over the rectangle from {@code (left, top)} to {@code (right, bottom)} in GUI
     * units, y growing down, at z 0, with opaque white vertices. It emits the corners in the order of vanilla's
     * {@code drawTexturedModalRect}, bottom-left, bottom-right, top-right, top-left (Gui.java:136-147), which is
     * counter-clockwise on screen under the GUI's projection (S12 amendments I2). The top-left corner takes
     * {@code (u0, v0)} and the bottom-right {@code (u1, v1)}. The GUI places the rectangle at window pixels divided by
     * the scale factor, so each vertex lands on a window pixel (S12 plan P12.3).
     * <p>
     * No push and no pop: the binding is the only state it changes, and it leaves unit 0 bound to 0 (S12 decisions
     * D23).
     */
    public static void gui(int texture, double u0, double v0, double u1, double v1, double left, double top,
        double right, double bottom) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.setColorOpaque_I(WHITE);
        tessellator.addVertexWithUV(left, bottom, 0, u0, v1);
        tessellator.addVertexWithUV(right, bottom, 0, u1, v1);
        tessellator.addVertexWithUV(right, top, 0, u1, v0);
        tessellator.addVertexWithUV(left, top, 0, u0, v0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        tessellator.draw();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }
}
