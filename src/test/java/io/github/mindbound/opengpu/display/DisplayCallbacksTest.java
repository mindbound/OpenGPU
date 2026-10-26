package io.github.mindbound.opengpu.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import li.cil.oc.api.machine.Callback;

/**
 * The display component's callbacks in M0, {@code isPrecise} and {@code setPrecise} (24:222; 15:187; S11 plan U5.6,
 * U5.9), through OC's own scan, dispatch and argument reader (OcCalls), and the flag's NBT: saved beside the shape as
 * OC saves {@code oc:precise}, marked dirty on a change, false when missing, and kept out of the description.
 */
@Timeout(60)
class DisplayCallbacksTest {

    private static final String IS_PRECISE_DOC = "function():boolean -- Whether touch, drag, drop and scroll carry "
        + "fractional pixel coordinates.";
    private static final String SET_PRECISE_DOC = "function(enabled:boolean):boolean -- Sets whether touch, drag, drop "
        + "and scroll carry fractional pixel coordinates; returns the old setting.";

    private static Object[] call(DisplayTile display, String name, Object... values) throws Exception {
        return OcCalls.call(display, name, new RecordingContext(), values);
    }

    // U5.6: exactly OC's pair, isPrecise direct and setPrecise not, since it marks the tile entity dirty
    @Test
    void ocFindsIsPreciseDirectAndSetPreciseNotDirect() throws Exception {
        Map<String, Callback> callbacks = OcCalls.callbacks(Displays.on(new FakeNodes()));
        Assertions.assertEquals(Arrays.asList("isPrecise", "setPrecise"), new ArrayList<>(callbacks.keySet()));
        Callback isPrecise = callbacks.get("isPrecise");
        Callback setPrecise = callbacks.get("setPrecise");
        Assertions.assertTrue(isPrecise.direct());
        Assertions.assertFalse(setPrecise.direct());
        Assertions.assertEquals(IS_PRECISE_DOC, isPrecise.doc());
        Assertions.assertEquals(SET_PRECISE_DOC, setPrecise.doc());
        Assertions.assertEquals(Integer.MAX_VALUE, isPrecise.limit());
        Assertions.assertEquals(Integer.MAX_VALUE, setPrecise.limit());
    }

    // U5.6: setPrecise returns the old setting, as OC's (TextBuffer.scala:213-220), and isPrecise reads the new one
    @Test
    void setPreciseReturnsTheOldSettingAndIsPreciseTheNew() throws Exception {
        DisplayTile display = Displays.on(new FakeNodes());
        Assertions.assertArrayEquals(new Object[] { false }, call(display, "isPrecise"));
        Assertions.assertArrayEquals(new Object[] { false }, call(display, "setPrecise", true));
        Assertions.assertArrayEquals(new Object[] { true }, call(display, "isPrecise"));
        Assertions.assertTrue(display.isPrecise());
        Assertions.assertArrayEquals(new Object[] { true }, call(display, "setPrecise", true));
        Assertions.assertArrayEquals(new Object[] { true }, call(display, "setPrecise", false));
        Assertions.assertArrayEquals(new Object[] { false }, call(display, "isPrecise"));
        Assertions.assertFalse(display.isPrecise());
    }

    // U5.6: a change marks the tile entity dirty, so its chunk is saved with the new setting (Chunk.java:1111-1125),
    // and only a change does
    @Test
    void setPreciseMarksTheTileEntityDirtyOnAChangeOnly() throws Exception {
        CountingDirty display = new CountingDirty();
        call(display, "setPrecise", false);
        Assertions.assertEquals(0, display.marked, "false to false");
        call(display, "setPrecise", true);
        Assertions.assertEquals(1, display.marked, "false to true");
        call(display, "setPrecise", true);
        Assertions.assertEquals(1, display.marked, "true to true");
        call(display, "setPrecise", false);
        Assertions.assertEquals(2, display.marked, "true to false");
    }

    // U5.6: the argument is OC's checkBoolean, whose error Lua sees as error(message, 0) (28:291)
    @Test
    void aNonBooleanArgumentIsOcsArgumentError() throws Exception {
        DisplayTile display = Displays.on(new FakeNodes());
        IllegalArgumentException number = Assertions
            .assertThrows(IllegalArgumentException.class, () -> call(display, "setPrecise", 1.0));
        Assertions.assertEquals("bad argument #1 (boolean expected, got number)", number.getMessage());
        IllegalArgumentException missing = Assertions
            .assertThrows(IllegalArgumentException.class, () -> call(display, "setPrecise"));
        Assertions.assertEquals("bad arguments #1 (boolean expected, got no value)", missing.getMessage());
        Assertions.assertFalse(display.isPrecise());
    }

    // U5.6: opengpu:precise, a boolean beside the shape keys, back on a fresh tile entity
    @Test
    void preciseRoundTripsBesideTheShape() {
        Displays.mapTileEntity();
        FakeNodes nodes = new FakeNodes();
        DisplayTile saved = Displays.on(nodes);
        saved.place(DisplayShape.placed(ForgeDirection.UP, 2));
        Assertions.assertArrayEquals(new Object[] { false }, invokeSetPrecise(saved));
        NBTTagCompound nbt = new NBTTagCompound();
        saved.writeToNBT(nbt);
        Assertions.assertTrue(nbt.hasKey(DisplayTile.PRECISE, 1), "a byte tag, as NBT keeps a boolean");
        Assertions.assertTrue(nbt.getBoolean(DisplayTile.PRECISE));
        DisplayTile loaded = Displays.on(nodes);
        loaded.readFromNBT(nbt);
        Assertions.assertTrue(loaded.isPrecise());
        Assertions.assertEquals(saved.shape(), loaded.shape());
    }

    // U5.6: a tile entity saved before the key existed, or with it missing, reads false
    @Test
    void aMissingKeyReadsFalse() {
        Displays.mapTileEntity();
        FakeNodes nodes = new FakeNodes();
        DisplayTile saved = Displays.on(nodes);
        invokeSetPrecise(saved);
        NBTTagCompound nbt = new NBTTagCompound();
        saved.writeToNBT(nbt);
        nbt.removeTag(DisplayTile.PRECISE);
        DisplayTile loaded = Displays.on(nodes);
        invokeSetPrecise(loaded);
        loaded.readFromNBT(nbt);
        Assertions.assertFalse(loaded.isPrecise());
    }

    // U5.6: the client always sends fractions and the server converts, so the description does not carry the flag
    @Test
    void theDescriptionHoldsNoPreciseKey() {
        FakeNodes nodes = new FakeNodes();
        DisplayTile display = Displays.on(nodes);
        nodes.join(display.node());
        invokeSetPrecise(display);
        NBTTagCompound tag = ((S35PacketUpdateTileEntity) display.getDescriptionPacket()).func_148857_g();
        Assertions.assertFalse(tag.hasKey(DisplayTile.PRECISE), tag.toString());
    }

    /**
     * A display that counts its markDirty calls, since a tile entity with no world marks nothing
     * (TileEntity.java:167-179).
     */
    private static final class CountingDirty extends DisplayTile {

        int marked;

        CountingDirty() {
            super(new FakeNodes());
        }

        @Override
        public void markDirty() {
            marked++;
        }
    }

    /** setPrecise(true) through OC's dispatch, its answer. */
    private static Object[] invokeSetPrecise(DisplayTile display) {
        try {
            return call(display, "setPrecise", true);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
