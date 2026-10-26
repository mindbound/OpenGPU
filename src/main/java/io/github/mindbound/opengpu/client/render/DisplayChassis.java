package io.github.mindbound.opengpu.client.render;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.IBlockAccess;

import com.gtnewhorizons.angelica.api.ThreadSafeISBRH;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;

/**
 * The display's chassis, a normal ISBRH (24:36; 02:78; 20:170; 25:471-473; S12 plan P9.1 with amendment F7), under
 * the render id that {@code ClientProxy.init} takes from FML and gives {@code DisplayBlock} before it registers this
 * handler (P9.2). It draws the block's six faces with the icons {@code DisplayBlock.getIcon} gives: the screen's front
 * on its facing, the casing on the other five; the TESR draws the picture over the front (P8). A render type other
 * than -1 also keeps the TESR out of Iris's shadow pass (ShadowRenderer.java:753-768; 20:170).
 * <ul>
 * <li>In the world, {@link #renderWorldBlock} ends as OC's chassis does, in {@code RenderBlocks.renderStandardBlock},
 * and only adds vertices to the chunk's Tessellator: no draw and no GL call (02:78). Under Celeritas it may run on a
 * meshing thread, where the world is a chunk slice that hands out the display's tile entity (WorldSlice.java:382-391),
 * so the class is marked {@code @ThreadSafeISBRH(perThread = false)} from Angelica's floor API (R-11, 25:471-473): it
 * holds no state but its final id, and one instance serves every thread. Without Angelica the annotation's class is
 * absent and the JVM ignores it, as OC's own chassis relies on (02:78).</li>
 * <li>In inventories, in the hand and on the ground, vanilla's {@code RenderBlocks.renderBlockAsItem} calls
 * {@link #renderInventoryBlock} after it has set the render bounds from the block (RenderBlocks.java:7934-7935,
 * :8359-8361; S12 amendments K), which in M0 is always the full cube. That path is R-8's (25:456-460): no item renderer
 * hands the TESR dispatcher a tile entity with no world.</li>
 * </ul>
 * It makes no GL call anywhere, so jarTest's client checks see no row of it, and it needs no matrix call: the
 * inventory cube is centred through the Tessellator's own translation, which is Java state, not GL.
 */
@ThreadSafeISBRH(perThread = false)
public final class DisplayChassis implements ISimpleBlockRenderingHandler {

    /** Half a block: the inventory cube is centred on the item transform's origin, as vanilla's cubes are. */
    private static final float HALF = 0.5f;

    private final int renderId;

    /**
     * A chassis under FML's render id {@code renderId}.
     *
     * @param renderId the id {@code RenderingRegistry.getNextAvailableRenderId()} gave, which {@code DisplayBlock}
     *                 returns as its render type
     */
    public DisplayChassis(int renderId) {
        this.renderId = renderId;
    }

    /**
     * The cube in an inventory slot, in the hand or on the ground, its icons those of {@code getIcon(side, meta)}: the
     * front on the default facing's side, the one vanilla's inventory transform turns to the viewer (S12 plan P9.3).
     * <p>
     * This draws through its own {@code Tessellator.startDrawingQuads()} and {@code draw()}, a departure from 02:78's
     * "no {@code Tessellator.draw()}" and 02:99's "no draws" [proposal; S12 amendments F7]. Those rules guard the world
     * path, whose quads go into a chunk's Tessellator that Angelica captures. The inventory path has no Tessellator
     * started for it: vanilla's own cubes start and draw one here (RenderBlocks.java:8370-8415), and so does OC's
     * chassis (BlockRenderer.scala:22-59). Angelica strips Tessellator calls from {@code renderWorldBlock} only
     * (IsbrhTessellatorAbuseTransform.java:28-39), and its item display-list cache skips ISBRH render types
     * (MixinRenderBlocks.java:38-57 in its item renderer mixins), so this draw runs fresh on the client thread each
     * time.
     */
    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {
        Tessellator tessellator = Tessellator.instance;
        // In place of vanilla's glTranslatef(-0.5, -0.5, -0.5): Tessellator state, so no GL call and no matrix row
        tessellator.addTranslation(-HALF, -HALF, -HALF);
        tessellator.startDrawingQuads();
        tessellator.setNormal(0f, -1f, 0f);
        renderer.renderFaceYNeg(block, 0, 0, 0, renderer.getBlockIconFromSideAndMetadata(block, 0, metadata));
        tessellator.setNormal(0f, 1f, 0f);
        renderer.renderFaceYPos(block, 0, 0, 0, renderer.getBlockIconFromSideAndMetadata(block, 1, metadata));
        tessellator.setNormal(0f, 0f, -1f);
        renderer.renderFaceZNeg(block, 0, 0, 0, renderer.getBlockIconFromSideAndMetadata(block, 2, metadata));
        tessellator.setNormal(0f, 0f, 1f);
        renderer.renderFaceZPos(block, 0, 0, 0, renderer.getBlockIconFromSideAndMetadata(block, 3, metadata));
        tessellator.setNormal(-1f, 0f, 0f);
        renderer.renderFaceXNeg(block, 0, 0, 0, renderer.getBlockIconFromSideAndMetadata(block, 4, metadata));
        tessellator.setNormal(1f, 0f, 0f);
        renderer.renderFaceXPos(block, 0, 0, 0, renderer.getBlockIconFromSideAndMetadata(block, 5, metadata));
        tessellator.draw();
        tessellator.addTranslation(HALF, HALF, HALF);
    }

    /**
     * The block in a chunk mesh: {@code RenderBlocks.renderStandardBlock}, whose faces take their icons from
     * {@code DisplayBlock.getIcon(world, x, y, z, side)}, and its result, whether anything was drawn. On the client
     * thread or a Celeritas meshing thread; it only adds vertices.
     */
    @Override
    public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        return renderer.renderStandardBlock(block, x, y, z);
    }

    /** True: the item is the 3D cube, through {@link #renderInventoryBlock} (RenderBlocks.java:8422-8441). */
    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return true;
    }

    /** The render id this chassis was built with, which FML keys the handler by. */
    @Override
    public int getRenderId() {
        return renderId;
    }
}
