package io.github.mindbound.opengpu.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.FakeNodes;

/**
 * The display's shape in NBT (RM:34; 24:248; S11 plan P12.4, P15.3): every valid shape round-trips through the tile
 * entity's NBT beside the prefab's {@code oc:node}, each value a byte under its {@code opengpu:} key; a value that is
 * missing, not a byte or out of range reads as its default, and a tile entity logs one warning for it however often it
 * reads one.
 */
@Timeout(60)
class DisplayShapeTest {

    @BeforeAll
    static void mapTheTileEntity() {
        Displays.mapTileEntity();
    }

    /**
     * Every shape M0 has: the four walls, and the floor and the ceiling in four rotations, each at thickness 1 and 16.
     */
    private static List<DisplayShape> everyShape() {
        List<DisplayShape> shapes = new ArrayList<>();
        for (int thickness : new int[] { 1, DisplayShape.MAX_THICKNESS }) {
            for (ForgeDirection facing : ForgeDirection.VALID_DIRECTIONS) {
                int rotations = facing == ForgeDirection.UP || facing == ForgeDirection.DOWN ? 4 : 1;
                for (int rotation = 0; rotation < rotations; rotation++) {
                    shapes.add(DisplayShape.of(facing, rotation, thickness, 0, 0));
                }
            }
        }
        return shapes;
    }

    // P12.4: the five keys beside the prefab's oc:node and U5.6's opengpu:precise, and the shape back from a fresh tile
    // entity; a display never bound has no binder, so no opengpu:binder (D15; BindTest has a bound display's key set)
    @Test
    void everyShapeRoundTripsThroughTheTileEntitysNbtBesideItsNode() {
        FakeNodes nodes = new FakeNodes();
        List<DisplayShape> shapes = everyShape();
        Assertions.assertEquals(24, shapes.size());
        for (DisplayShape shape : shapes) {
            DisplayTile placed = Displays.on(nodes);
            placed.place(shape);
            NBTTagCompound nbt = new NBTTagCompound();
            placed.writeToNBT(nbt);
            // The keys as written, as P12.4 and U5.6 name them, not the constants: they are the saved format, which a
            // renamed key would break for every display saved before
            Assertions.assertEquals(
                new TreeSet<>(
                    Arrays.asList(
                        "id",
                        "x",
                        "y",
                        "z",
                        "oc:node",
                        "opengpu:facing",
                        "opengpu:rotation",
                        "opengpu:thickness",
                        "opengpu:tiltH",
                        "opengpu:tiltV",
                        "opengpu:precise")),
                new TreeSet<>(nbt.func_150296_c()),
                shape.toString());
            Assertions.assertEquals(Displays.TILE_ID, nbt.getString("id"));
            Assertions.assertEquals(
                placed.displayId(),
                nbt.getCompoundTag("oc:node")
                    .getString("address"));
            DisplayTile loaded = Displays.on(nodes);
            loaded.readFromNBT(nbt);
            Assertions.assertEquals(shape, loaded.shape());
            Assertions.assertEquals(0, loaded.shapeWarnings(), shape.toString());
        }
    }

    // P12.4: each value a byte, the facing as ForgeDirection's ordinal
    @Test
    void theValuesAreBytes() {
        NBTTagCompound nbt = new NBTTagCompound();
        DisplayShape.of(ForgeDirection.UP, 3, 9, 0, 0)
            .write(nbt);
        for (String key : Arrays.asList(
            DisplayShape.FACING,
            DisplayShape.ROTATION,
            DisplayShape.THICKNESS,
            DisplayShape.TILT_H,
            DisplayShape.TILT_V)) {
            Assertions.assertTrue(nbt.hasKey(key, 1), key);
        }
        Assertions.assertEquals(ForgeDirection.UP.ordinal(), nbt.getByte(DisplayShape.FACING));
        Assertions.assertEquals(3, nbt.getByte(DisplayShape.ROTATION));
        Assertions.assertEquals(9, nbt.getByte(DisplayShape.THICKNESS));
    }

    // P12.4: a tag with no shape reads as the default, one problem per key
    @Test
    void missingValuesReadAsTheDefault() {
        List<String> problems = new ArrayList<>();
        Assertions.assertEquals(DisplayShape.DEFAULT, DisplayShape.read(new NBTTagCompound(), problems));
        Assertions.assertEquals(
            Arrays.asList(
                DisplayShape.FACING + ": missing",
                DisplayShape.ROTATION + ": missing",
                DisplayShape.THICKNESS + ": missing",
                DisplayShape.TILT_H + ": missing",
                DisplayShape.TILT_V + ": missing"),
            problems);
        Assertions.assertEquals(ForgeDirection.SOUTH, DisplayShape.DEFAULT.facing());
        Assertions.assertEquals(16, DisplayShape.DEFAULT.thickness());
    }

    // P12.4: an out-of-range value, or one of another tag type, reads as its default and is reported; the rest stand
    @Test
    void outOfRangeValuesReadAsTheirDefaults() {
        Object[][] rows = {
            // key, value, the facing it is read beside, the field read
            { DisplayShape.FACING, (byte) 6, ForgeDirection.UP, ForgeDirection.SOUTH },
            { DisplayShape.FACING, (byte) 7, ForgeDirection.UP, ForgeDirection.SOUTH },
            { DisplayShape.FACING, (byte) -1, ForgeDirection.UP, ForgeDirection.SOUTH },
            { DisplayShape.ROTATION, (byte) 4, ForgeDirection.UP, 0 },
            { DisplayShape.ROTATION, (byte) -1, ForgeDirection.DOWN, 0 },
            { DisplayShape.ROTATION, (byte) 1, ForgeDirection.EAST, 0 },
            { DisplayShape.THICKNESS, (byte) 0, ForgeDirection.UP, 16 },
            { DisplayShape.THICKNESS, (byte) 17, ForgeDirection.UP, 16 },
            { DisplayShape.TILT_H, (byte) 5, ForgeDirection.UP, 0 },
            { DisplayShape.TILT_V, (byte) -90, ForgeDirection.UP, 0 } };
        for (Object[] row : rows) {
            String key = (String) row[0];
            NBTTagCompound nbt = new NBTTagCompound();
            DisplayShape.of((ForgeDirection) row[2], 0, 12, 0, 0)
                .write(nbt);
            nbt.setByte(key, (Byte) row[1]);
            List<String> problems = new ArrayList<>();
            DisplayShape shape = DisplayShape.read(nbt, problems);
            Assertions.assertEquals(1, problems.size(), key + " " + row[1] + ": " + problems);
            Assertions.assertTrue(
                problems.get(0)
                    .startsWith(key + " " + row[1] + ": "),
                problems.toString());
            Object field = key.equals(DisplayShape.FACING) ? shape.facing()
                : key.equals(DisplayShape.ROTATION) ? shape.rotation()
                    : key.equals(DisplayShape.THICKNESS) ? shape.thickness()
                        : key.equals(DisplayShape.TILT_H) ? shape.tiltH() : shape.tiltV();
            Assertions.assertEquals(row[3], field, key + " " + row[1]);
            Assertions.assertEquals(
                key.equals(DisplayShape.THICKNESS) ? 16 : 12,
                shape.thickness(),
                "the other values stand");
        }
        NBTTagCompound wide = new NBTTagCompound();
        DisplayShape.DEFAULT.write(wide);
        wide.setInteger(DisplayShape.THICKNESS, 8);
        List<String> problems = new ArrayList<>();
        Assertions.assertEquals(DisplayShape.DEFAULT, DisplayShape.read(wide, problems));
        Assertions.assertEquals(Collections.singletonList(DisplayShape.THICKNESS + ": not a byte"), problems);
    }

    // P12.4 [proposal]: one warning per tile entity, however many reads find problems
    @Test
    void aTileEntityWarnsOnceForItsShape() {
        NBTTagCompound bad = new NBTTagCompound();
        DisplayShape.DEFAULT.write(bad);
        bad.setByte(DisplayShape.THICKNESS, (byte) 0);
        DisplayTile tile = Displays.withoutNode();
        tile.readFromNBT(bad);
        tile.readFromNBT(bad);
        Assertions.assertEquals(1, tile.shapeWarnings());
        Assertions.assertEquals(DisplayShape.DEFAULT, tile.shape());
        DisplayTile other = Displays.withoutNode();
        other.readFromNBT(bad);
        Assertions.assertEquals(1, other.shapeWarnings());
        DisplayTile good = Displays.withoutNode();
        NBTTagCompound fine = new NBTTagCompound();
        DisplayShape.DEFAULT.write(fine);
        good.readFromNBT(fine);
        Assertions.assertEquals(0, good.shapeWarnings());
    }

    // P12.3, P12.4: a shape is made only of values M0 has; a placed one is the cube with no tilt
    @Test
    void ofRefusesWhatReadWouldNotAcceptAndPlacedIsTheCube() {
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.UNKNOWN, 0, 16, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(null, 0, 16, 0, 0));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.NORTH, 1, 16, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.UP, 4, 16, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.UP, 0, 0, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.UP, 0, 17, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.UP, 0, 16, 1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> DisplayShape.of(ForgeDirection.UP, 0, 16, 0, 1));
        DisplayShape placed = DisplayShape.placed(ForgeDirection.DOWN, 2);
        Assertions.assertEquals(DisplayShape.of(ForgeDirection.DOWN, 2, 16, 0, 0), placed);
        Assertions.assertEquals(
            DisplayShape.of(ForgeDirection.DOWN, 2, 16, 0, 0)
                .hashCode(),
            placed.hashCode());
        Assertions.assertNotEquals(DisplayShape.placed(ForgeDirection.DOWN, 3), placed);
        Assertions.assertEquals("DisplayShape[DOWN r2, 16 px, tilt 0/0]", placed.toString());
    }
}
