package io.github.mindbound.opengpu.client.render;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;

import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.display.ScreenSurface;

/**
 * The display's TESR (R-4, 25:436-445; 20:169; 24:36; S12 plan P8 with amendments I2, I6 and I9), bound to
 * {@link DisplayTile} by {@code ClientProxy.init} before any world loads, whatever host is installed, since a host
 * without graphics still draws its "no graphics" bitmap (24:230). Each call reads the client tile entity's description
 * only, its id, shape and surface size (no OC node exists on a client), picks the face with {@link ScreenFace#choose},
 * and draws at most one quad through {@link ScreenDraw#world}:
 * <ul>
 * <li>{@link ScreenFace#PRESENT}: the installed {@link GlRenderHost}'s present texture of the device bound to the
 * display ({@link GlRenderHost#presentTexture}) over {@code ScreenSurface.picture(W, H)}, letterboxed inside the bezel
 * (P8.3). A released device's texture is deleted only by a later START pass, which runs before this frame's TESRs, so
 * the name is valid here (P5.5);</li>
 * <li>{@link ScreenFace#NO_GRAPHICS}: the "no graphics" icon from the block atlas over the inner square, through the
 * same calls with the atlas's texture name and the icon's corners (P8.6): vanilla uploaded the atlas once, so OpenGPU
 * makes no GL object, no ledger row and no call outside its rows for it. The icon comes from the supplier given at
 * construction, and while it gives none this face draws nothing (S12 amendments I9);</li>
 * <li>{@link ScreenFace#NONE}: nothing, so the chassis's dark front shows.</li>
 * </ul>
 * It returns early when the camera is behind the picture's plane ({@link ScreenFace#seenFromFront}). The render box
 * and the 64-block render distance are the tile entity's (P8.7); pass 0 only, vanilla's default, and with anaglyph it
 * runs twice a frame, which the idempotent bind and quad allow. The client main thread only, the host core's GL thread
 * (H57).
 */
public final class DisplayRenderer extends TileEntitySpecialRenderer {

    private final RenderHost host;
    /** The host when it is a {@link GlRenderHost}, the only one with present textures, else null. */
    private final GlRenderHost gl;
    private final Supplier<IIcon> noGraphics;

    /**
     * A TESR for the client's installed host.
     *
     * @param host       the installed {@code Installation}'s host: a {@link GlRenderHost}, or {@code NoRenderHost} on a
     *                   client without graphics
     * @param noGraphics the "no graphics" icon in the block atlas, asked for on each such face; null while there is
     *                   none
     * @throws NullPointerException for a null host or supplier
     */
    public DisplayRenderer(RenderHost host, Supplier<IIcon> noGraphics) {
        this.host = Objects.requireNonNull(host, "host");
        this.gl = host instanceof GlRenderHost ? (GlRenderHost) host : null;
        this.noGraphics = Objects.requireNonNull(noGraphics, "noGraphics");
    }

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
        if (!(tile instanceof DisplayTile)) {
            return;
        }
        DisplayTile display = (DisplayTile) tile;
        int width = display.surfaceWidth();
        int height = display.surfaceHeight();
        boolean bound = width > 0 && height > 0;
        int texture = bound && gl != null ? gl.presentTexture(display.displayId()) : 0;
        ScreenFace face = ScreenFace.choose(bound, host.available(), texture);
        if (face == ScreenFace.NONE) {
            return;
        }
        ScreenSurface surface = ScreenSurface.of(display.shape());
        if (face == ScreenFace.PRESENT) {
            double[][] corners = surface.picture(width, height);
            if (ScreenFace.seenFromFront(surface.normal(), corners[ScreenSurface.TOP_LEFT], x, y, z)) {
                ScreenDraw.world(texture, 0, 0, 1, 1, corners, x, y, z);
            }
            return;
        }
        IIcon icon = noGraphics.get();
        if (icon == null) {
            return;
        }
        // The square bitmap fills the inner square
        double[][] corners = surface.picture(1, 1);
        if (!ScreenFace.seenFromFront(surface.normal(), corners[ScreenSurface.TOP_LEFT], x, y, z)) {
            return;
        }
        ITextureObject atlas = Minecraft.getMinecraft()
            .getTextureManager()
            .getTexture(TextureMap.locationBlocksTexture);
        if (atlas == null) {
            return;
        }
        ScreenDraw.world(
            atlas.getGlTextureId(),
            icon.getMinU(),
            icon.getMinV(),
            icon.getMaxU(),
            icon.getMaxV(),
            corners,
            x,
            y,
            z);
    }
}
