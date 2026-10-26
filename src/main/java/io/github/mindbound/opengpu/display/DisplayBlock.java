package io.github.mindbound.opengpu.display;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.github.mindbound.opengpu.OpenGPU;
import li.cil.oc.api.CreativeTab;

/**
 * The cube display's block, {@code opengpu:screen} (RM:34; 24:248; Q-S11-2), whose tile entity is the display's OC
 * environment, {@link DisplayTile}. Iron, hardness 2, in OC's creative tab. A placed display faces its placer, by OC's
 * pitch and yaw rule ({@link Placement}; Q-S11-15).
 * <p>
 * <b>How a client draws it</b> (S12 plan P9). Its render type is the client chassis's ISBRH id, which
 * {@code ClientProxy.init} sets through {@link #useRenderType} before any world loads, after OC's own pattern
 * (SimpleBlock.scala:47, Proxy.scala:50-51); until then, and always on a dedicated server, which draws nothing, it is
 * 0, a plain cube. So this common class names no client class (24:90). It stays an opaque full cube in M0
 * ({@code isOpaqueCube} and {@code renderAsNormalBlock} keep their defaults, which Block's constructor caches,
 * Block.java:449-458); M1.5's thickness changes that (RM:110). Its three icons are the block atlas's, registered on a
 * client only: the casing, {@value #SIDE_ICON}, on five faces; the screen's front, {@value #FRONT_ICON}, a bezel of
 * {@code ScreenSurface.BEZEL} around dark glass, on the facing, where it shows the letterbox's bands, an unbound
 * display and a display past the render distance; and {@value #NO_GRAPHICS_ICON}, which the TESR draws over the inner
 * square of a display on a client without graphics (P8.6). The art is generated placeholders, which other art can
 * replace at the same names and square power-of-two sizes with no code change (Q-S12-3 (a)). Every member that names
 * a client type carries {@code @SideOnly(CLIENT)} (24:90), so a dedicated server strips them and jarTest's server
 * view never sees one (24 §3.1 check 1).
 * <p>
 * <b>How a player opens its GUI</b> (S12 plan P12.1 with amendment F16). A right-click calls {@link DisplayOpener} on
 * the client, whose opener {@code ClientProxy.init} installs; the block itself names no client class for it.
 */
public class DisplayBlock extends BlockContainer {

    /** The casing's icon, 16 × 16, on every face but the front (S12 plan P9.3). */
    public static final String SIDE_ICON = OpenGPU.MODID + ":screen_side";
    /** The front's icon, 64 × 64: a 9-texel bezel, 2.25/16 of the face, around dark glass (S12 plan P9.3). */
    public static final String FRONT_ICON = OpenGPU.MODID + ":screen_front";
    /** The "no graphics" bitmap, 64 × 64, drawn by the TESR, never on a block face (20:242; S12 plan P8.6). */
    public static final String NO_GRAPHICS_ICON = OpenGPU.MODID + ":screen_no_graphics";

    /**
     * The render type: 0 until a client sets the chassis's id. Written once on the client main thread in FML init; read
     * there and, under Celeritas, on its meshing threads, hence volatile.
     */
    private static volatile int renderType;

    /** The casing's icon; null until the block atlas registers its icons, on a client only. */
    @SideOnly(Side.CLIENT)
    private IIcon sideIcon;
    /** The front's icon; null until the block atlas registers its icons, on a client only. */
    @SideOnly(Side.CLIENT)
    private IIcon frontIcon;
    /** The "no graphics" bitmap; null until the block atlas registers its icons, on a client only. */
    @SideOnly(Side.CLIENT)
    private IIcon noGraphicsIcon;

    /** Builds the block; a plain JVM can, with OC's tab null there. */
    public DisplayBlock() {
        super(Material.iron);
        setBlockName(OpenGPU.MODID + ".screen");
        setHardness(2f);
        // OC makes its tab in its own preInit, which runs before OpenGPU's (required-after)
        setCreativeTab(CreativeTab.instance);
    }

    /**
     * Sets the render type every display block returns from then on: {@code ClientProxy.init} passes its chassis's
     * render id, from {@code RenderingRegistry.getNextAvailableRenderId()}, before it registers the chassis (S12 plan
     * P9.2). FML's ids start at 42 (RenderingRegistry.java:38), above vanilla's types, so the integrated server's
     * checks for vanilla types (rails 9, stairs 10, fences 11) see what they see for 0.
     */
    public static void useRenderType(int id) {
        renderType = id;
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new DisplayTile();
    }

    /**
     * The client chassis's render id once {@link #useRenderType} has set it, else 0, a plain cube. Not
     * {@code @SideOnly} in 1.7.10 (Block.java:528), so common code answers it. A type other than -1 also keeps the TESR
     * out of Iris's shadow pass (20:170).
     */
    @Override
    public int getRenderType() {
        return renderType;
    }

    /**
     * Gives the placed display the placer's shape, on both sides, as OC's blocks do after placing
     * (Item.scala:91-99; S11 plan P12.1); the server's reaches clients in the display's description.
     */
    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof DisplayTile) {
            ((DisplayTile) tile).place(Placement.shape(placer.rotationPitch, placer.rotationYaw));
        }
    }

    /**
     * A right-click on any face opens the display's GUI on the client, through {@link DisplayOpener} (24:222; S12 plan
     * P12.1 with amendment F16; Q-S12-6 (a)), as OC's screen opens its own only where {@code world.isRemote}
     * (Screen.scala:345-352); the only side test is {@code isRemote} (24:175). It answers true on both sides, so the
     * held item is not used, and the server, which repeats the call (ItemInWorldManager.java:384, :409), agrees with
     * the client. Vanilla calls neither side's for a player who sneaks with an item in hand
     * (PlayerControllerMP.java:374-377), and an item's {@code onItemUseFirst}, such as OC's analyzer's, runs first, so
     * both keep working. With no display tile entity there yet, as while a placement is still arriving, it opens
     * nothing.
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof DisplayTile) {
                DisplayOpener.open((DisplayTile) tile);
            }
        }
        return true;
    }

    // ---- The client's icons (S12 plan P9.3)

    /**
     * Registers the three icons in the block atlas, at {@code assets/opengpu/textures/blocks/<name>.png}
     * (TextureMap.java:316-319, :381), on the client thread at every stitch, the first before FML init. The casing is
     * also the block's own icon, which vanilla's particles and fallbacks read.
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void registerBlockIcons(IIconRegister register) {
        sideIcon = register.registerIcon(SIDE_ICON);
        frontIcon = register.registerIcon(FRONT_ICON);
        noGraphicsIcon = register.registerIcon(NO_GRAPHICS_ICON);
        blockIcon = sideIcon;
    }

    /**
     * A face in a chunk mesh, from the chassis's {@code renderStandardBlock}: the front on the side the display's tile
     * entity faces, the casing elsewhere; with no display there, as while a placement is still arriving, the default
     * shape's facing [proposal; S12 build, U3]. On the client thread or, under Celeritas, a meshing thread, whose chunk
     * slice hands out the client's tile entity (WorldSlice.java:382-391); its shape is volatile (S12 plan P8.8).
     */
    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(IBlockAccess world, int x, int y, int z, int side) {
        TileEntity tile = world.getTileEntity(x, y, z);
        DisplayShape shape = tile instanceof DisplayTile ? ((DisplayTile) tile).shape() : DisplayShape.DEFAULT;
        return side == shape.facing()
            .ordinal() ? frontIcon : sideIcon;
    }

    /**
     * A face with no world, as the chassis's inventory path draws it: the front on the default shape's facing, SOUTH,
     * side 3, which vanilla's slot transform turns to the viewer on the left, beside the top and the east side, since
     * the chassis adds no turn of its own (RenderItem.java:444-463); OC's screens show theirs there too
     * (Screen.scala:263-265). The casing elsewhere.
     */
    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(int side, int meta) {
        return side == DisplayShape.DEFAULT.facing()
            .ordinal() ? frontIcon : sideIcon;
    }

    /**
     * The "no graphics" bitmap's icon in the block atlas, for the TESR of a client without graphics (20:242; 24:230;
     * S12 plan P8.6), or null before the first stitch. The client thread only; the atlas gives a new icon at each
     * stitch, so the TESR asks for it at each draw.
     */
    @SideOnly(Side.CLIENT)
    public IIcon noGraphicsIcon() {
        return noGraphicsIcon;
    }
}
