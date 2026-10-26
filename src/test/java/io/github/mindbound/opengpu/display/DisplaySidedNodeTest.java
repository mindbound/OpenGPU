package io.github.mindbound.opengpu.display;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.fixtures.FakeNodes;

/**
 * The display's faces (24:222; 15:97-111; S11 plan P12.2, P15.3): OC's network asks a {@code SidedEnvironment} for its
 * node side by side (Network.scala:453-500), and the display offers it on the five faces but the front, for every
 * facing; the client's {@code canConnect}, which draws cables, says the same.
 */
@Timeout(60)
class DisplaySidedNodeTest {

    // 24:222: a node on the five non-front faces and none on the front, which decision 11 keeps from keyboards too
    @Test
    void theNodeIsOnTheFiveFacesButTheFront() {
        DisplayTile display = Displays.on(new FakeNodes());
        Assertions.assertNotNull(display.node());
        for (ForgeDirection facing : ForgeDirection.VALID_DIRECTIONS) {
            int rotations = facing == ForgeDirection.UP || facing == ForgeDirection.DOWN ? 4 : 1;
            for (int rotation = 0; rotation < rotations; rotation++) {
                display.place(DisplayShape.placed(facing, rotation));
                int faces = 0;
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                    if (side == facing) {
                        Assertions.assertNull(display.sidedNode(side), "the front, " + facing);
                        Assertions.assertFalse(display.canConnect(side), "the front, " + facing);
                    } else {
                        Assertions.assertSame(display.node(), display.sidedNode(side), side + ", " + facing);
                        Assertions.assertTrue(display.canConnect(side), side + ", " + facing);
                        faces++;
                    }
                }
                Assertions.assertEquals(5, faces);
            }
        }
    }

    // A client's tile entity has no node, so it offers none; its cables still draw on the five faces
    @Test
    void aClientsDisplayOffersNoNodeButDrawsItsCables() {
        DisplayTile client = Displays.withoutNode();
        client.place(DisplayShape.placed(ForgeDirection.EAST, 0));
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            Assertions.assertNull(client.sidedNode(side), side.toString());
            Assertions.assertEquals(side != ForgeDirection.EAST, client.canConnect(side), side.toString());
        }
    }
}
