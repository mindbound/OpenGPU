package io.github.mindbound.opengpu.core.fixtures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.LiveView;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;

/**
 * The open frame's commit model (28 §2.2, §3.5), which S7's {@code Device} implements: a submit is validated whole
 * against the live view and the running totals, and only an accepted one is appended and joins the totals, so a
 * rejected submit changes nothing. The segment array itself is kept, never copied (C14; 24:179). Not thread-safe.
 */
public final class TestFrame {

    private final LiveView view;
    private final List<byte[]> segments = new ArrayList<>();
    private FrameTotals totals;

    /** An empty open frame on the view. */
    public TestFrame(LiveView view) {
        this(view, FrameTotals.EMPTY);
    }

    /**
     * An open frame whose earlier segments are not kept, only their totals: for submits that start near a cap. The
     * next {@link #seal} starts from {@link FrameTotals#EMPTY}.
     *
     * @throws NullPointerException for a null view or null totals
     */
    public TestFrame(LiveView view, FrameTotals open) {
        if (view == null || open == null) {
            throw new NullPointerException("view and totals");
        }
        this.view = view;
        this.totals = open;
    }

    /**
     * {@code submit(s)} (28 §3.5): {@code Validator.validate(s, view, totals())}; on acceptance s is appended and the
     * totals become {@code totals().plus(v.totals())}, otherwise nothing changes.
     */
    public Validation submit(byte[] s) {
        Validation v = Validator.validate(s, view, totals);
        if (v.isAccepted()) {
            segments.add(s);
            totals = totals.plus(v.totals());
        }
        return v;
    }

    /** The open frame's running totals. */
    public FrameTotals totals() {
        return totals;
    }

    /** The accepted segments in order, unmodifiable; the arrays are the submitted ones. */
    public List<byte[]> segments() {
        return Collections.unmodifiableList(new ArrayList<>(segments));
    }

    /**
     * {@code present}'s seal (28 §2.1): the frame's segments in order, the body of its {@code FRAME} and what
     * {@link TierEOracle#runFrame} takes; a new, empty frame starts.
     */
    public byte[][] seal() {
        byte[][] frame = segments.toArray(new byte[0][]);
        segments.clear();
        totals = FrameTotals.EMPTY;
        return frame;
    }

    public LiveView view() {
        return view;
    }
}
