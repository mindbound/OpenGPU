package io.github.mindbound.opengpu.display;

import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import io.github.mindbound.opengpu.OpenGPU;
import li.cil.oc.api.CreativeTab;

/**
 * The cube display's block, {@code opengpu:screen} (RM:34; 24:248; Q-S11-2), whose tile entity is the display's OC
 * environment, {@link DisplayTile}. Iron, hardness 2, in OC's creative tab. A placed display faces its placer, by OC's
 * pitch and yaw rule ({@link Placement}; Q-S11-15). Its render type, opacity and icons are S12's, so until then a dev
 * client shows it as a plain cube with the missing texture. A client override it gains carries
 * {@code @SideOnly(CLIENT)} (24:90).
 */
public class DisplayBlock extends BlockContainer {

    /** Builds the block; a plain JVM can, with OC's tab null there. */
    public DisplayBlock() {
        super(Material.iron);
        setBlockName(OpenGPU.MODID + ".screen");
        setHardness(2f);
        // OC makes its tab in its own preInit, which runs before OpenGPU's (required-after)
        setCreativeTab(CreativeTab.instance);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new DisplayTile();
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
}
