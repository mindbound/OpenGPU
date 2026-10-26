package io.github.mindbound.opengpu.display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.BareWorld;
import io.github.mindbound.opengpu.fixtures.FakeIcons;

/**
 * The display block's client-facing members (S12 plan P9.2, P9.3, P12.1): the render type a client's chassis sets, the
 * opaque full cube, the three icons, each with its PNG on the classpath, the front on the facing in the world and on
 * the south side in an inventory, and the right-click, which opens the GUI through {@link DisplayOpener} on a client
 * only. No atlas and no client run here: the icons come from a recording register, the opener is a recording one,
 * put back to the no-op in {@code finally}, and the tile entities are a client's, with no node. The player is null:
 * the block reads none.
 */
@Timeout(60)
class DisplayBlockTest {

    // P9.2: 0, a plain cube, until ClientProxy.init gives the chassis's id; a dedicated server never sets it
    @Test
    void theRenderTypeIsZeroUntilAClientSetsTheChassisIdAndThenThatId() {
        DisplayBlock block = new DisplayBlock();
        Assertions.assertEquals(0, block.getRenderType());
        try {
            DisplayBlock.useRenderType(42);
            Assertions.assertEquals(42, block.getRenderType());
            Assertions
                .assertEquals(42, new DisplayBlock().getRenderType(), "every display block, made before or after");
        } finally {
            DisplayBlock.useRenderType(0);
        }
        Assertions.assertEquals(0, block.getRenderType());
    }

    // P9.2: isOpaqueCube and renderAsNormalBlock keep their defaults in M0 (K8: Block's constructor caches the first)
    @Test
    void theBlockIsAnOpaqueFullCube() {
        DisplayBlock block = new DisplayBlock();
        Assertions.assertTrue(block.isOpaqueCube());
        Assertions.assertTrue(block.renderAsNormalBlock());
        Assertions.assertTrue(block.isNormalCube());
        Assertions.assertArrayEquals(
            new double[] { 0, 0, 0, 1, 1, 1 },
            new double[] { block.getBlockBoundsMinX(), block.getBlockBoundsMinY(), block.getBlockBoundsMinZ(),
                block.getBlockBoundsMaxX(), block.getBlockBoundsMaxY(), block.getBlockBoundsMaxZ() });
    }

    // P9.3, P9.4: three names in the block atlas, each a square PNG of its size; the casing 16, the front and the
    // "no graphics" bitmap 64, whose 9-texel bezel is ScreenSurface.BEZEL of 64
    @Test
    void itRegistersItsThreeIconsEachWithASquarePngOfItsSize() throws IOException {
        DisplayBlock block = new DisplayBlock();
        FakeIcons register = new FakeIcons();
        block.registerBlockIcons(register);
        Assertions.assertEquals(
            Arrays.asList("opengpu:screen_side", "opengpu:screen_front", "opengpu:screen_no_graphics"),
            register.names());
        Assertions.assertArrayEquals(new int[] { 16, 16 }, FakeIcons.pngSize(DisplayBlock.SIDE_ICON, "blocks"));
        Assertions.assertArrayEquals(new int[] { 64, 64 }, FakeIcons.pngSize(DisplayBlock.FRONT_ICON, "blocks"));
        Assertions.assertArrayEquals(new int[] { 64, 64 }, FakeIcons.pngSize(DisplayBlock.NO_GRAPHICS_ICON, "blocks"));
        Assertions.assertEquals(9.0, ScreenSurface.BEZEL * 64);
        Assertions.assertEquals(
            DisplayBlock.NO_GRAPHICS_ICON,
            block.noGraphicsIcon()
                .getIconName());
    }

    // P9.3: in a chunk mesh the front is on the side the tile entity faces, whatever the rotation, and the casing on
    // the other five; with no display there yet, the default shape's facing, SOUTH
    @Test
    void inTheWorldTheFrontIsOnTheFacingAndTheCasingElsewhere() {
        DisplayBlock block = new DisplayBlock();
        block.registerBlockIcons(new FakeIcons());
        BareWorld remote = BareWorld.allocate(true);
        DisplayShape[] shapes = { DisplayShape.placed(ForgeDirection.NORTH, 0),
            DisplayShape.placed(ForgeDirection.SOUTH, 0), DisplayShape.placed(ForgeDirection.WEST, 0),
            DisplayShape.placed(ForgeDirection.EAST, 0), DisplayShape.placed(ForgeDirection.UP, 0),
            DisplayShape.placed(ForgeDirection.UP, 2), DisplayShape.placed(ForgeDirection.DOWN, 1),
            DisplayShape.placed(ForgeDirection.DOWN, 3) };
        int x = 0;
        for (DisplayShape shape : shapes) {
            DisplayTile display = Displays.withoutNode();
            display.xCoord = x;
            display.yCoord = 64;
            display.zCoord = -7;
            display.place(shape);
            remote.put(display);
            assertFrontOnlyOn(shape.facing(), side -> block.getIcon(remote, display.xCoord, 64, -7, side), shape);
            x += 2;
        }
        assertFrontOnlyOn(ForgeDirection.SOUTH, side -> block.getIcon(remote, 1, 64, -7, side), "no tile entity");
    }

    // P9.3: with no world, as the inventory path draws it, the front is on SOUTH (side 3), which vanilla's slot
    // transform turns to the viewer beside the top, whatever the item's damage
    @Test
    void inAnInventoryTheFrontIsOnTheSouthSide() {
        DisplayBlock block = new DisplayBlock();
        block.registerBlockIcons(new FakeIcons());
        for (int meta : new int[] { 0, 3, 15 }) {
            assertFrontOnlyOn(ForgeDirection.SOUTH, side -> block.getIcon(side, meta), "meta " + meta);
        }
    }

    // P12.1: a right-click answers true on both sides, so the held item is not used, display tile entity or not
    @Test
    void aRightClickAnswersTrueOnBothSides() {
        DisplayBlock block = new DisplayBlock();
        for (boolean remote : new boolean[] { false, true }) {
            BareWorld world = BareWorld.allocate(remote);
            placed(world, 3, 70, -9);
            for (int side = 0; side < 6; side++) {
                Assertions.assertTrue(
                    block.onBlockActivated(world, 3, 70, -9, null, side, 0.5f, 0.5f, 0.5f),
                    world + ", side " + side);
            }
            Assertions.assertTrue(
                block.onBlockActivated(world, 4, 70, -9, null, 2, 0.5f, 0.5f, 0.5f),
                world + ", no tile entity");
        }
    }

    // P12.1 with amendment F16: on a client's world it calls the installed opener with the display's tile entity, on
    // any face, and with no display tile entity there it opens nothing; the no-op goes back in finally
    @Test
    void onAClientARightClickCallsTheInstalledOpenerWithTheTileEntity() {
        DisplayBlock block = new DisplayBlock();
        BareWorld remote = BareWorld.allocate(true);
        DisplayTile display = placed(remote, 3, 70, -9);
        List<DisplayTile> opened = new ArrayList<>();
        try {
            DisplayOpener.install(opened::add);
            block.onBlockActivated(remote, 3, 70, -9, null, 3, 0.5f, 0.5f, 0.5f);
            block.onBlockActivated(remote, 3, 70, -9, null, 1, 0.25f, 1f, 0.75f);
            block.onBlockActivated(remote, 4, 70, -9, null, 3, 0.5f, 0.5f, 0.5f);
        } finally {
            DisplayOpener.install(DisplayOpener.NONE);
        }
        Assertions.assertEquals(Arrays.asList(display, display), opened);
        // With the no-op back, a right-click opens nothing and does not throw
        Assertions.assertTrue(block.onBlockActivated(remote, 3, 70, -9, null, 3, 0.5f, 0.5f, 0.5f));
        Assertions.assertEquals(2, opened.size());
    }

    // P12.1: on a server's world, the integrated server's included, the opener is never called (24:175's isRemote)
    @Test
    void onAServerARightClickOpensNothing() {
        DisplayBlock block = new DisplayBlock();
        BareWorld server = BareWorld.allocate(false);
        placed(server, 3, 70, -9);
        List<DisplayTile> opened = new ArrayList<>();
        try {
            DisplayOpener.install(opened::add);
            for (int side = 0; side < 6; side++) {
                block.onBlockActivated(server, 3, 70, -9, null, side, 0.5f, 0.5f, 0.5f);
            }
        } finally {
            DisplayOpener.install(DisplayOpener.NONE);
        }
        Assertions.assertEquals(Collections.emptyList(), opened);
    }

    private static DisplayTile placed(BareWorld world, int x, int y, int z) {
        DisplayTile display = Displays.withoutNode();
        display.xCoord = x;
        display.yCoord = y;
        display.zCoord = z;
        world.put(display);
        return display;
    }

    private interface Face {

        IIcon icon(int side);
    }

    private static void assertFrontOnlyOn(ForgeDirection front, Face face, Object what) {
        for (int side = 0; side < 6; side++) {
            String expected = side == front.ordinal() ? DisplayBlock.FRONT_ICON : DisplayBlock.SIDE_ICON;
            IIcon icon = face.icon(side);
            Assertions.assertNotNull(icon, what + ", side " + side);
            Assertions.assertEquals(expected, icon.getIconName(), what + ", side " + side);
        }
    }
}
