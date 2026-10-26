package io.github.mindbound.opengpu.client.gui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.display.DisplayShape;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.display.Displays;
import io.github.mindbound.opengpu.fixtures.BareWorld;

/**
 * The display GUI's members that need no running client (S12 plan P12.2, P12.3; P14.1: its drawing and its input are
 * not run in a test): it does not pause the game, it stays open only while its tile entity is the one at its position,
 * its close ends a held gesture with a drop (S12 decisions D12), and the lang file names its "no signal" label. The
 * screen is built with a client tile entity, no node, and, but for the close row, which feeds its gesture directly, a
 * sink that fails, since nothing else here sends.
 */
@Timeout(60)
class DisplayScreenTest {

    private static final Gesture.Sink NO_SEND = (kind, id, px, py, data) -> Assertions
        .fail("nothing is sent here: " + kind);

    private static DisplayTile placed(BareWorld world, int x, int y, int z) {
        DisplayTile tile = Displays.withoutNode();
        tile.xCoord = x;
        tile.yCoord = y;
        tile.zCoord = z;
        world.put(tile);
        return tile;
    }

    // P12.2; K4: false, so the integrated server keeps ticking and the GUI's packets and the frames' statuses go on
    @Test
    void theGuiDoesNotPauseTheGame() {
        DisplayScreen screen = new DisplayScreen(Displays.withoutNode(), NoRenderHost.INSTANCE, () -> null, NO_SEND);
        Assertions.assertFalse(screen.doesGuiPauseGame());
    }

    // P12.2: the GUI shows its display while its tile entity is valid and the one at its position, and closes once it
    // is broken, unloaded, replaced or in no world
    @Test
    void itShowsItsDisplayOnlyWhileItsTileEntityIsTheOneAtItsPosition() {
        BareWorld world = BareWorld.allocate(true);
        DisplayTile shown = placed(world, 1, 64, -2);
        Assertions.assertTrue(DisplayScreen.showsItsDisplay(shown));
        DisplayTile replacing = placed(world, 1, 64, -2);
        Assertions.assertFalse(DisplayScreen.showsItsDisplay(shown), "replaced at its position");
        Assertions.assertTrue(DisplayScreen.showsItsDisplay(replacing));
        replacing.invalidate();
        Assertions.assertFalse(DisplayScreen.showsItsDisplay(replacing), "broken or unloaded");
        Assertions.assertFalse(DisplayScreen.showsItsDisplay(Displays.withoutNode()), "in no world");
    }

    // D12: the screen's close, Escape's or any other, ends a held gesture with its drop at the last inside position
    @Test
    void closingTheGuiMidGestureSendsTheDrop() {
        List<String> sent = new ArrayList<>();
        DisplayTile tile = Displays.described("opengpu_screen-2", DisplayShape.DEFAULT, 320, 200);
        DisplayScreen screen = new DisplayScreen(
            tile,
            NoRenderHost.INSTANCE,
            () -> null,
            (kind, id, px, py, data) -> sent.add(kind.signal() + " " + id + " " + px + " " + py + " " + data));
        screen.gesture()
            .press(0, 10.5, 20.5);
        screen.gesture()
            .move(30.5, 40.5);
        screen.onGuiClosed();
        screen.onGuiClosed();
        Assertions.assertEquals(
            Arrays.asList("touch opengpu_screen-2 10.5 20.5 0", "drop opengpu_screen-2 30.5 40.5 0"),
            sent);
    }

    // P12.3: the unbound GUI's label is the lang file's, so the game shows "No signal", not the key
    @Test
    void theLangFileNamesTheNoSignalLabel() throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = DisplayScreenTest.class.getResourceAsStream("/assets/opengpu/lang/en_US.lang")) {
            Assertions.assertNotNull(in, "the lang file is on the classpath");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lines.add(line);
            }
        }
        Assertions.assertEquals("gui.opengpu.screen.noSignal", DisplayScreen.NO_SIGNAL);
        Assertions.assertTrue(lines.contains(DisplayScreen.NO_SIGNAL + "=No signal"), lines::toString);
    }
}
