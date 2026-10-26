package io.github.mindbound.opengpu.client.gui;

import java.util.Objects;

import io.github.mindbound.opengpu.net.PointerEvent;

/**
 * The display GUI's pointer gestures, a pure state machine (24:140, :222; 15:186; S12 plan P12.5): fed with each
 * buffered mouse event by the screen's {@code handleMouseInput}, already mapped to surface pixels by
 * {@link PointerMapping} (NaN for a position with no mapping), and ticked once per client tick by its
 * {@code updateScreen}. What it decides goes to a {@link Sink}, {@code client.InputSender} in game:
 * <ul>
 * <li><b>touch</b>: a press of button 0 or 1 inside the picture, sent at once with the button, starts a gesture. The
 * other buttons send nothing; the middle one stays free for M1's paste, as OC's GUI sends only 0 and 1
 * (Screen.scala:39-46; 24:222). A press outside starts nothing;</li>
 * <li><b>drag</b>: while that button is held, a move inside the picture updates the gesture's position, and each tick
 * sends at most one drag, so at most 20 a second (15:186), at the latest position with its fraction, and only when
 * that position's pixel, its x and y floored, differs from the pixel of the last touch or drag sent (S12 decisions
 * D26). So a move within one pixel sends nothing, which at magnification 4 is a move within 4 × 4 window pixels, and
 * a display out of precise mode, which floors the position, is never sent two drags in a row with the same integers.
 * The rule is the same in precise mode, which is the server's to apply ({@code net.InputHandler}; S11 plan U5.5), as
 * in OC's screen GUI, which sends a drag only when the pointer's cell changes horizontally or its half cell
 * vertically, a square of OC's 1:2 cell, in its precise mode too (Screen.scala:72-82). A move outside sends nothing
 * and keeps the last inside position;</li>
 * <li><b>drop</b>: the release of that button sends drop at the release position when it is inside, else at the last
 * inside position (15:186), and ends the gesture; a drag not yet sent is dropped with it, since the drop carries the
 * final position [proposal];</li>
 * <li>a second button pressed during a gesture is ignored until the first is released, its release too
 * [proposal];</li>
 * <li><b>scroll</b>: each wheel event inside the picture sends scroll with the wheel's sign, -1 or 1, as OC sends
 * only the sign (Screen.scala:25-37; S11 plan U5.3), gesture or not;</li>
 * <li><b>close</b>: the GUI closing while a gesture is held sends its drop at the last inside position
 * ({@link #close}).</li>
 * </ul>
 * Every send reads the {@link Target} afresh: nothing is sent while its id is null or its size is 0 × 0, which
 * {@code PointerPacket.encode} could not address, and a gesture that goes on after a description under a new id (every
 * attach and detach sends one, 28:1151) sends under the new id (S12 plan P12.2). The client main thread only.
 */
public final class Gesture {

    /** Where the gesture's events go: {@code InputSender.send} in game. */
    public interface Sink {

        /**
         * One pointer event for the display {@code displayId}, at surface pixel {@code (px, py)}, unfloored; the data
         * byte is the button for touch, drag and drop and the wheel's sign for scroll.
         */
        void send(PointerEvent.Kind kind, String displayId, float px, float py, byte data);
    }

    /** The display as the GUI sees it, read at each send: the last description's id and bound surface size. */
    public interface Target {

        /** The display's id, or null before a description has carried one. */
        String displayId();

        /** The bound surface's width in pixels, 0 while unbound. */
        int surfaceWidth();

        /** The bound surface's height in pixels, 0 while unbound. */
        int surfaceHeight();
    }

    private static final int NO_BUTTON = -1;

    private final Target target;
    private final Sink sink;

    /** The button of the gesture in progress, or {@link #NO_BUTTON}. */
    private int button = NO_BUTTON;
    /** The gesture's last inside position. */
    private double lastX;
    private double lastY;
    /** Whether a move inside came since the last tick. */
    private boolean moved;
    /** The last position sent by touch or drag, as the packet's floats: {@link #tick} compares its pixel. */
    private float sentX;
    private float sentY;

    /**
     * A gesture machine over {@code target}, sending to {@code sink}.
     *
     * @throws NullPointerException for a null argument
     */
    public Gesture(Target target, Sink sink) {
        this.target = Objects.requireNonNull(target, "target");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** A press of {@code pressed} at {@code (px, py)}: touch, when it starts a gesture. */
    public void press(int pressed, double px, double py) {
        if ((pressed != 0 && pressed != 1) || button != NO_BUTTON || !inside(px, py)) {
            return;
        }
        button = pressed;
        lastX = px;
        lastY = py;
        moved = false;
        send(PointerEvent.Kind.TOUCH, px, py, pressed);
    }

    /** A move to {@code (px, py)}: the gesture's position, when a gesture is in progress and it is inside. */
    public void move(double px, double py) {
        if (button == NO_BUTTON || !inside(px, py)) {
            return;
        }
        lastX = px;
        lastY = py;
        moved = true;
    }

    /** A release of {@code released} at {@code (px, py)}: drop, when it ends the gesture in progress. */
    public void release(int released, double px, double py) {
        if (button == NO_BUTTON || released != button) {
            return;
        }
        button = NO_BUTTON;
        moved = false;
        if (inside(px, py)) {
            send(PointerEvent.Kind.DROP, px, py, released);
        } else {
            send(PointerEvent.Kind.DROP, lastX, lastY, released);
        }
    }

    /** A wheel event of {@code dWheel} at {@code (px, py)}: scroll with its sign, when it is inside. */
    public void scroll(int dWheel, double px, double py) {
        if (dWheel == 0 || !inside(px, py)) {
            return;
        }
        send(PointerEvent.Kind.SCROLL, px, py, Integer.signum(dWheel));
    }

    /**
     * One client tick: at most one drag, at the gesture's latest position, when its pixel differs from that of the
     * last touch or drag sent (S12 decisions D26).
     */
    public void tick() {
        if (button == NO_BUTTON || !moved) {
            return;
        }
        moved = false;
        // The floats floored here are the ones a packet carries and the server floors, so the pixel tested is the one
        // a display out of precise mode delivers, even where a double and its float would fall on two sides of an edge
        if (Math.floor((float) lastX) != Math.floor(sentX) || Math.floor((float) lastY) != Math.floor(sentY)) {
            send(PointerEvent.Kind.DRAG, lastX, lastY, button);
        }
    }

    /**
     * The GUI closes, by Escape, its display gone or any other screen: a gesture in progress sends drop at its last
     * inside position with its button and ends, a drag not yet sent going with it, as at a release, so a Lua program
     * never sees touch and drag without their drop; with no gesture, nothing [proposal; S12 decisions D12]. A
     * departure from OC, whose screen GUI sends a mouse-up only for a release it sees (Screen.scala:57-70) and on
     * closing releases the held keys alone (InputBuffer.scala:66-72), which leaves a program's drag open.
     */
    public void close() {
        if (button == NO_BUTTON) {
            return;
        }
        int held = button;
        button = NO_BUTTON;
        moved = false;
        send(PointerEvent.Kind.DROP, lastX, lastY, held);
    }

    /** Whether a gesture is in progress: a button pressed inside and not yet released. */
    public boolean active() {
        return button != NO_BUTTON;
    }

    private boolean inside(double px, double py) {
        return target.displayId() != null
            && PointerMapping.inside(px, py, target.surfaceWidth(), target.surfaceHeight());
    }

    private void send(PointerEvent.Kind kind, double px, double py, int data) {
        String id = target.displayId();
        if (id == null || target.surfaceWidth() <= 0 || target.surfaceHeight() <= 0) {
            return;
        }
        float x = (float) px;
        float y = (float) py;
        if (kind == PointerEvent.Kind.TOUCH || kind == PointerEvent.Kind.DRAG) {
            sentX = x;
            sentY = y;
        }
        sink.send(kind, id, x, y, (byte) data);
    }
}
