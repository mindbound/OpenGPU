package io.github.mindbound.opengpu.client.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.net.PointerEvent.Kind;

/**
 * The display GUI's gestures (S12 plan P12.5; 15:186; 24:140): touch at once for buttons 0 and 1, at most one drag a
 * tick and only when the pixel changed (S12 decisions D26), drop at the release or the last inside position, one
 * button at a time, scroll's sign, a drop when the GUI closes mid-gesture (S12 decisions D12), and nothing while the
 * display has no id or no surface. Positions are surface pixels as {@link PointerMapping} gives them, window pixels'
 * centres {@code k + (j + 0.5) / m}, with m = 4 in most D26 rows; the target is the tests' own, as a client tile
 * entity's description would be.
 */
@Timeout(60)
class GestureTest {

    private static final String ID = "opengpu_screen-2";

    /** A display's description, which a test changes as a new description would. */
    private static final class FakeTarget implements Gesture.Target {

        String id = ID;
        int width = 320;
        int height = 200;

        @Override
        public String displayId() {
            return id;
        }

        @Override
        public int surfaceWidth() {
            return width;
        }

        @Override
        public int surfaceHeight() {
            return height;
        }
    }

    private final FakeTarget target = new FakeTarget();
    private final List<String> sent = new ArrayList<>();
    private final Gesture gesture = new Gesture(
        target,
        (kind, id, px, py, data) -> sent.add(kind.signal() + " " + id + " " + px + " " + py + " " + data));

    private static String event(Kind kind, double px, double py, int data) {
        return event(kind, ID, px, py, data);
    }

    private static String event(Kind kind, String id, double px, double py, int data) {
        return kind.signal() + " " + id + " " + (float) px + " " + (float) py + " " + data;
    }

    private void assertSent(String... events) {
        Assertions.assertEquals(Arrays.asList(events), sent);
        sent.clear();
    }

    // P12.5: a press of button 0 or 1 inside sends touch with the button at once, with no tick
    @Test
    void aPressOfButtonZeroOrOneInsideSendsTouchAtOnce() {
        gesture.press(0, 10.5, 20.5);
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 0));
        gesture.release(0, 10.5, 20.5);
        sent.clear();
        gesture.press(1, 319.75, 199.75);
        assertSent(event(Kind.TOUCH, 319.75, 199.75, 1));
        Assertions.assertTrue(gesture.active());
    }

    // P12.5: buttons 2 and above send nothing, so the middle button stays free for M1's paste (24:222)
    @Test
    void theMiddleAndOtherButtonsSendNothing() {
        for (int button : new int[] { 2, 3, 7 }) {
            gesture.press(button, 10.5, 20.5);
            gesture.move(11.5, 20.5);
            gesture.tick();
            gesture.release(button, 11.5, 20.5);
        }
        assertSent();
        Assertions.assertFalse(gesture.active());
    }

    // P12.5: a press outside the picture starts no gesture, so its moves and its release send nothing; nor do moves,
    // ticks and releases with no button held
    @Test
    void aPressOutsideThePictureStartsNoGesture() {
        gesture.press(0, -0.25, 20.5);
        gesture.move(10.5, 20.5);
        gesture.tick();
        gesture.release(0, 10.5, 20.5);
        gesture.move(12.5, 20.5);
        gesture.tick();
        gesture.release(1, 12.5, 20.5);
        assertSent();
        Assertions.assertFalse(gesture.active());
    }

    // P12.5; 15:186: moves within one tick coalesce to one drag at the latest position, sent at the tick
    @Test
    void dragsCoalesceToOnePerTickAtTheLatestPosition() {
        gesture.press(0, 10.5, 20.5);
        sent.clear();
        gesture.move(11.5, 20.5);
        gesture.move(12.5, 21.5);
        gesture.move(13.5, 22.5);
        assertSent();
        gesture.tick();
        assertSent(event(Kind.DRAG, 13.5, 22.5, 0));
        gesture.tick();
        assertSent();
        gesture.move(14.5, 22.5);
        gesture.tick();
        gesture.move(15.5, 22.5);
        gesture.tick();
        assertSent(event(Kind.DRAG, 14.5, 22.5, 0), event(Kind.DRAG, 15.5, 22.5, 0));
    }

    // P12.5; D26: a drag goes only when the pixel changed since the last one sent, the touch's included, so a move to
    // another pixel and back within one tick sends nothing, and nor does a new fraction in the same pixel
    @Test
    void aDragIsSentOnlyWhenThePixelChangedSinceTheLastOneSent() {
        gesture.press(1, 10.5, 20.5);
        sent.clear();
        gesture.move(10.5, 20.5);
        gesture.tick();
        gesture.move(30.5, 40.5);
        gesture.move(10.75, 20.25);
        gesture.tick();
        assertSent();
        gesture.move(11.25, 20.5);
        gesture.tick();
        assertSent(event(Kind.DRAG, 11.25, 20.5, 1));
        gesture.move(11.25, 20.5);
        gesture.tick();
        assertSent();
    }

    // D26: after a touch, moves within its pixel send no drag, however many ticks pass: here every window pixel of the
    // 4 x 4 one picture pixel covers at magnification 4, in a new order each tick
    @Test
    void movesWithinOnePixelAfterATouchSendNoDragHoweverManyTicksPass() {
        gesture.press(0, 10.125, 20.125);
        sent.clear();
        for (int tick = 0; tick < 100; tick++) {
            for (int i = 0; i < 4; i++) {
                gesture.move(10 + (i + 0.5) / 4, 20 + ((tick + i) % 4 + 0.5) / 4);
            }
            gesture.tick();
        }
        assertSent();
        Assertions.assertTrue(gesture.active());
    }

    // D26: a move into the next pixel sends exactly one drag, at the tick, with the latest position unfloored, not the
    // first one in the new pixel
    @Test
    void aMoveIntoTheNextPixelSendsOneDragAtTheLatestUnflooredPosition() {
        gesture.press(0, 10.875, 20.625);
        sent.clear();
        gesture.move(11.125, 20.625);
        gesture.move(11.375, 20.875);
        gesture.move(11.625, 20.875);
        assertSent();
        gesture.tick();
        assertSent(event(Kind.DRAG, 11.625, 20.875, 0));
        gesture.tick();
        assertSent();
    }

    // D26: after a drag, a position within its pixel, kept or moved to another fraction, sends nothing more, tick
    // after tick
    @Test
    void positionsWithinThePixelOfTheLastDragSendNothingMore() {
        gesture.press(1, 10.5, 20.5);
        gesture.move(12.125, 21.125);
        gesture.tick();
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 1), event(Kind.DRAG, 12.125, 21.125, 1));
        gesture.move(12.375, 21.125);
        gesture.tick();
        gesture.move(12.875, 21.625);
        gesture.tick();
        gesture.move(12.625, 21.875);
        gesture.tick();
        gesture.move(12.625, 21.875);
        gesture.tick();
        gesture.tick();
        assertSent();
    }

    // D26: either pixel changing is enough: a change of the row alone, then of the column alone, sends one drag each
    @Test
    void aChangeOfTheRowAloneOrTheColumnAloneSendsOneDragEach() {
        gesture.press(0, 10.375, 20.625);
        sent.clear();
        gesture.move(10.375, 21.125);
        gesture.tick();
        gesture.tick();
        gesture.move(11.125, 21.125);
        gesture.tick();
        gesture.tick();
        assertSent(event(Kind.DRAG, 10.375, 21.125, 0), event(Kind.DRAG, 11.125, 21.125, 0));
    }

    // D26: a pixel change counts both ways: a move to a lower column, then to a lower row, sends one drag each
    @Test
    void aMoveToALowerColumnOrRowSendsOneDragEach() {
        gesture.press(0, 10.375, 20.625);
        sent.clear();
        gesture.move(9.875, 20.625);
        gesture.tick();
        gesture.move(9.875, 19.875);
        gesture.tick();
        assertSent(event(Kind.DRAG, 9.875, 20.625, 0), event(Kind.DRAG, 9.875, 19.875, 0));
    }

    // D26: the pixel compared is the last sent one's, not the last tick's: a drag the display could not take while its
    // size was 0 x 0 is still due when it is bound again, though the pointer stayed in that pixel
    @Test
    void thePixelComparedIsTheLastSentOnesNotTheLastTicks() {
        gesture.press(0, 10.5, 20.5);
        gesture.move(11.25, 20.5);
        target.width = 0;
        target.height = 0;
        gesture.tick();
        target.width = 320;
        target.height = 200;
        gesture.move(11.75, 20.5);
        gesture.tick();
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 0), event(Kind.DRAG, 11.75, 20.5, 0));
    }

    // P12.5: a move outside sends nothing and keeps the last inside position, which the next tick sends once
    @Test
    void movesOutsideSendNothingAndKeepTheLastInsidePosition() {
        gesture.press(0, 10.5, 20.5);
        sent.clear();
        gesture.move(50.5, 60.5);
        gesture.move(320, 60.5);
        gesture.move(Double.NaN, Double.NaN);
        gesture.tick();
        assertSent(event(Kind.DRAG, 50.5, 60.5, 0));
        gesture.move(-3, 60.5);
        gesture.tick();
        assertSent();
    }

    // P12.5; 15:186: a release inside drops at the release position, with the gesture's button
    @Test
    void aReleaseInsideDropsAtTheReleasePosition() {
        gesture.press(1, 10.5, 20.5);
        gesture.release(1, 100.25, 150.75);
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 1), event(Kind.DROP, 100.25, 150.75, 1));
        Assertions.assertFalse(gesture.active());
    }

    // P12.5; 15:186: a release outside drops at the last inside position
    @Test
    void aReleaseOutsideDropsAtTheLastInsidePosition() {
        gesture.press(0, 10.5, 20.5);
        gesture.move(40.5, 50.5);
        gesture.move(400.5, 50.5);
        gesture.release(0, 400.5, 50.5);
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 0), event(Kind.DROP, 40.5, 50.5, 0));
        gesture.press(0, 7.5, 8.5);
        gesture.release(0, Double.NaN, Double.NaN);
        assertSent(event(Kind.TOUCH, 7.5, 8.5, 0), event(Kind.DROP, 7.5, 8.5, 0));
    }

    // P12.5: a drag not yet sent goes with the release: only the drop, which carries the final position
    @Test
    void thePendingDragIsNotSentWithTheDrop() {
        gesture.press(0, 10.5, 20.5);
        gesture.move(11.5, 20.5);
        gesture.release(0, 12.5, 20.5);
        gesture.tick();
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 0), event(Kind.DROP, 12.5, 20.5, 0));
    }

    // D12: the GUI closing mid-gesture drops at the last inside position with the gesture's button, the pending drag
    // going with it, and ends the gesture, so a second close sends nothing
    @Test
    void closingMidGestureDropsAtTheLastInsidePositionWithItsButton() {
        gesture.press(1, 10.5, 20.5);
        gesture.move(30.5, 40.5);
        gesture.move(400.5, 40.5);
        gesture.close();
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 1), event(Kind.DROP, 30.5, 40.5, 1));
        Assertions.assertFalse(gesture.active());
        gesture.close();
        gesture.tick();
        gesture.release(1, 30.5, 40.5);
        assertSent();
    }

    // D12: with no gesture in progress, never begun, begun outside, or already dropped, the close sends nothing
    @Test
    void closingWithNoGestureInProgressSendsNothing() {
        gesture.close();
        gesture.press(0, -1.5, 20.5);
        gesture.close();
        assertSent();
        gesture.press(0, 10.5, 20.5);
        gesture.release(0, 11.5, 20.5);
        gesture.close();
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 0), event(Kind.DROP, 11.5, 20.5, 0));
    }

    // D26: the drop is not held to the pixel: it carries the exact release position, or for a release outside the
    // last inside position, though the pixel never changed and no drag went
    @Test
    void theDropCarriesTheExactPositionThoughThePixelNeverChanged() {
        gesture.press(0, 10.125, 20.125);
        gesture.move(10.625, 20.375);
        gesture.tick();
        gesture.release(0, 10.875, 20.875);
        assertSent(event(Kind.TOUCH, 10.125, 20.125, 0), event(Kind.DROP, 10.875, 20.875, 0));
        gesture.press(1, 10.125, 20.125);
        gesture.move(10.625, 20.375);
        gesture.tick();
        gesture.release(1, 400.5, 20.375);
        assertSent(event(Kind.TOUCH, 10.125, 20.125, 1), event(Kind.DROP, 10.625, 20.375, 1));
    }

    // D12, D26: the close still drops at the last inside position, which a move within the touch's pixel set
    @Test
    void closingAfterMovesWithinOnePixelDropsAtTheLastInsidePosition() {
        gesture.press(1, 10.125, 20.125);
        gesture.move(10.875, 20.625);
        gesture.tick();
        gesture.move(400.5, 20.625);
        gesture.tick();
        gesture.close();
        assertSent(event(Kind.TOUCH, 10.125, 20.125, 1), event(Kind.DROP, 10.875, 20.625, 1));
        Assertions.assertFalse(gesture.active());
    }

    // P12.5: a second button during a gesture is ignored, its press and its release, until the first is released
    @Test
    void aSecondButtonDuringAGestureIsIgnoredUntilTheFirstIsReleased() {
        gesture.press(0, 10.5, 20.5);
        gesture.press(1, 11.5, 20.5);
        gesture.release(1, 11.5, 20.5);
        gesture.move(12.5, 20.5);
        gesture.tick();
        gesture.release(0, 13.5, 20.5);
        gesture.release(1, 13.5, 20.5);
        gesture.press(1, 14.5, 20.5);
        assertSent(
            event(Kind.TOUCH, 10.5, 20.5, 0),
            event(Kind.DRAG, 12.5, 20.5, 0),
            event(Kind.DROP, 13.5, 20.5, 0),
            event(Kind.TOUCH, 14.5, 20.5, 1));
    }

    // P12.5; 24:140; S11 plan U5.3: scroll sends the wheel's sign only, OC's (Screen.scala:25-37), inside only, with
    // or without a gesture
    @Test
    void scrollSendsTheWheelsSignInsideThePicture() {
        gesture.scroll(120, 5.5, 6.5);
        gesture.scroll(-120, 5.5, 6.5);
        gesture.scroll(1, 5.5, 6.5);
        gesture.scroll(-1, 5.5, 6.5);
        gesture.scroll(0, 5.5, 6.5);
        gesture.scroll(120, 320.5, 6.5);
        gesture.scroll(120, Double.NaN, Double.NaN);
        gesture.press(0, 1.5, 1.5);
        gesture.scroll(-480, 2.5, 3.5);
        assertSent(
            event(Kind.SCROLL, 5.5, 6.5, 1),
            event(Kind.SCROLL, 5.5, 6.5, -1),
            event(Kind.SCROLL, 5.5, 6.5, 1),
            event(Kind.SCROLL, 5.5, 6.5, -1),
            event(Kind.TOUCH, 1.5, 1.5, 0),
            event(Kind.SCROLL, 2.5, 3.5, -1));
    }

    // D26: the pixel compared is the last touch's or drag's, never a scroll's: a move into the next pixel and a scroll
    // there within one tick still send that move's drag at the tick
    @Test
    void aScrollDuringAGestureLeavesTheDragsPixelAlone() {
        gesture.press(0, 10.5, 20.5);
        gesture.move(11.5, 20.5);
        gesture.scroll(120, 11.5, 20.5);
        gesture.tick();
        assertSent(
            event(Kind.TOUCH, 10.5, 20.5, 0),
            event(Kind.SCROLL, 11.5, 20.5, 1),
            event(Kind.DRAG, 11.5, 20.5, 0));
    }

    // P12.5: an unbound display, its description's size 0 x 0, gets nothing, and a gesture whose display goes unbound
    // sends nothing more, its drop included
    @Test
    void nothingIsSentWhileTheSurfaceIsZeroByZero() {
        target.width = 0;
        target.height = 0;
        gesture.press(0, 0.5, 0.5);
        gesture.scroll(120, 0.5, 0.5);
        assertSent();
        Assertions.assertFalse(gesture.active());
        target.width = 320;
        target.height = 200;
        gesture.press(0, 10.5, 20.5);
        gesture.move(11.5, 20.5);
        target.width = 0;
        target.height = 0;
        gesture.tick();
        gesture.release(0, 12.5, 20.5);
        assertSent(event(Kind.TOUCH, 10.5, 20.5, 0));
    }

    // P12.5: with no id yet (PointerPacket.encode needs one) nothing is sent, and nothing starts
    @Test
    void nothingIsSentForANullId() {
        target.id = null;
        gesture.press(0, 10.5, 20.5);
        gesture.move(11.5, 20.5);
        gesture.tick();
        gesture.release(0, 11.5, 20.5);
        gesture.scroll(120, 10.5, 20.5);
        assertSent();
        Assertions.assertFalse(gesture.active());
    }

    // P12.2, P12.5: the id is read at each send, so a gesture that goes on after a description under a new id, or
    // after its display was unbound and bound again, sends under the new id
    @Test
    void aResumedGestureAfterAnIdChangeUsesTheNewId() {
        gesture.press(0, 10.5, 20.5);
        target.id = "opengpu_screen-3";
        gesture.move(11.5, 20.5);
        gesture.tick();
        target.id = null;
        target.width = 0;
        target.height = 0;
        gesture.move(12.5, 20.5);
        gesture.tick();
        target.id = "opengpu_screen-4";
        target.width = 640;
        target.height = 400;
        gesture.move(500.5, 300.5);
        gesture.tick();
        gesture.release(0, 501.5, 300.5);
        assertSent(
            event(Kind.TOUCH, ID, 10.5, 20.5, 0),
            event(Kind.DRAG, "opengpu_screen-3", 11.5, 20.5, 0),
            event(Kind.DRAG, "opengpu_screen-4", 500.5, 300.5, 0),
            event(Kind.DROP, "opengpu_screen-4", 501.5, 300.5, 0));
    }
}
