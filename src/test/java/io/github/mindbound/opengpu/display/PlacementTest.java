package io.github.mindbound.opengpu.display;

import java.lang.reflect.Field;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.BareWorld;
import io.github.mindbound.opengpu.fixtures.FakeNodes;

/**
 * Where a placed display faces (Q-S11-15; S11 plan P12.1, P12.3, P15.3; amendments C1): against the placer's look by
 * OC's {@code Rotatable} rule (Rotatable.scala:20-22, :54-57, :67-76), with the pitch's ±45° edges as
 * {@link Math#round(float)} decides them, and the yaw's quadrant as the rotation on a floor or a ceiling. Minecraft's
 * yaw 0 looks south, 90 west, 180 north and 270 east; its pitch -90 looks up and 90 down.
 */
@Timeout(60)
class PlacementTest {

    // Rotatable.scala:54-57 then :67-71: a level look gives a wall facing the placer, with no rotation
    @Test
    void aLevelLookGivesTheWallFacingThePlacer() {
        Object[][] rows = { { 0f, ForgeDirection.NORTH }, { 90f, ForgeDirection.EAST }, { 180f, ForgeDirection.SOUTH },
            { 270f, ForgeDirection.WEST }, { -90f, ForgeDirection.WEST }, { 360f, ForgeDirection.NORTH },
            { 450f, ForgeDirection.EAST }, { -180f, ForgeDirection.SOUTH }, { 44.99f, ForgeDirection.NORTH },
            { 45f, ForgeDirection.EAST }, { -45f, ForgeDirection.NORTH }, { -45.01f, ForgeDirection.WEST } };
        for (Object[] row : rows) {
            float yaw = (Float) row[0];
            for (float pitch : new float[] { 0f, 30f, -30f, 44.99f, -45f }) {
                DisplayShape shape = Placement.shape(pitch, yaw);
                Assertions.assertEquals(row[1], shape.facing(), "pitch " + pitch + ", yaw " + yaw);
                Assertions.assertEquals(0, shape.rotation(), "pitch " + pitch + ", yaw " + yaw);
            }
        }
    }

    // Amendments C1: looking down by 45° or more faces the picture up, looking up by more than 45° faces it down;
    // Math.round rounds -0.5 to 0 and 0.5 to 1, so the edges differ by side
    @Test
    void thePitchEdgesAreOcsRounding() {
        Object[][] rows = { { 90f, ForgeDirection.UP }, { 45f, ForgeDirection.UP }, { 44.99f, ForgeDirection.NORTH },
            { 0f, ForgeDirection.NORTH }, { -45f, ForgeDirection.NORTH }, { -45.01f, ForgeDirection.DOWN },
            { -90f, ForgeDirection.DOWN } };
        for (Object[] row : rows) {
            Assertions.assertEquals(row[1], Placement.facing((Float) row[0], 0f), "pitch " + row[0]);
        }
        // Outside Minecraft's -90..90 the table's ends hold
        Assertions.assertEquals(ForgeDirection.UP, Placement.facing(200f, 0f));
        Assertions.assertEquals(ForgeDirection.DOWN, Placement.facing(-200f, 0f));
    }

    // P12.3: on a floor or a ceiling the rotation is the yaw's quadrant, 0 south, 1 west, 2 north, 3 east
    @Test
    void aFloorOrCeilingTakesTheYawsQuadrant() {
        Object[][] rows = { { 0f, 0 }, { 90f, 1 }, { 180f, 2 }, { 270f, 3 }, { -90f, 3 }, { 360f, 0 }, { 44.99f, 0 },
            { 45f, 1 }, { -45f, 0 }, { -45.01f, 3 }, { 134.99f, 1 }, { 135f, 2 }, { 720f + 90f, 1 } };
        for (Object[] row : rows) {
            float yaw = (Float) row[0];
            DisplayShape floor = Placement.shape(60f, yaw);
            DisplayShape ceiling = Placement.shape(-60f, yaw);
            Assertions.assertEquals(ForgeDirection.UP, floor.facing());
            Assertions.assertEquals(ForgeDirection.DOWN, ceiling.facing());
            Assertions.assertEquals(row[1], floor.rotation(), "yaw " + yaw);
            Assertions.assertEquals(row[1], ceiling.rotation(), "yaw " + yaw);
            Assertions.assertEquals(row[1], Placement.quadrant(yaw), "yaw " + yaw);
            Assertions.assertEquals(0, Placement.rotation(ForgeDirection.EAST, yaw), "a wall's rotation, yaw " + yaw);
        }
    }

    // P12.1: the block gives its placed tile entity the placer's shape, which is the cube with no tilt
    @Test
    void theBlockGivesItsTileEntityThePlacersShape() throws Exception {
        BareWorld world = BareWorld.allocate(false);
        DisplayTile tile = Displays.on(new FakeNodes());
        tile.xCoord = 3;
        tile.yCoord = 64;
        tile.zCoord = -5;
        world.put(tile);
        new DisplayBlock().onBlockPlacedBy(world, 3, 64, -5, placer(70f, 180f), null);
        Assertions.assertEquals(DisplayShape.of(ForgeDirection.UP, 2, 16, 0, 0), tile.shape());
        Assertions.assertEquals(ForgeDirection.UP, tile.facing());
        // Where no display's tile entity is, the block changes nothing
        new DisplayBlock().onBlockPlacedBy(world, 0, 0, 0, placer(0f, 0f), null);
        Assertions.assertEquals(ForgeDirection.UP, tile.facing());
    }

    /** A placer with no constructor run, as {@link BareWorld}: only its pitch and yaw are set. */
    private static EntityLivingBase placer(float pitch, float yaw) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        EntityLivingBase placer = (EntityLivingBase) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(theUnsafe.get(null), Placer.class);
        placer.rotationPitch = pitch;
        placer.rotationYaw = yaw;
        return placer;
    }

    /** A concrete living entity for {@link #placer}, whose constructor never runs (EntityLivingBase is abstract). */
    private static final class Placer extends EntityLivingBase {

        private Placer() {
            super((World) null);
        }

        @Override
        public ItemStack getHeldItem() {
            return null;
        }

        @Override
        public ItemStack getEquipmentInSlot(int slot) {
            return null;
        }

        @Override
        public void setCurrentItemOrArmor(int slot, ItemStack stack) {}

        @Override
        public ItemStack[] getLastActiveItems() {
            return new ItemStack[0];
        }
    }
}
