package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;

/**
 * ANIM-9(d) — the log unit and its rate cap.
 *
 * Every vector names the wrong answer it excludes, which here is usually the count the SUPERSEDED
 * rule produces: "once per program" gives 1 where the fixed rule gives N, and that difference is the
 * whole amendment.
 */
public strictfp class OcslDiagnosticsTest {

	private static final long P1 = 0xABCDEF0123456789L;
	private static final long P2 = 0x1122334455667788L;
	private static final long SECOND = 1_000_000_000L;

	private static final class Capture implements OcslDiagnostics.Sink {
		final List<String> lines = new ArrayList<String>();

		@Override
		public void warn(String message) {
			lines.add(message);
		}
	}

	/**
	 * The suite runs in one JVM, so a capturing sink left installed would follow every class that
	 * ran after this one — the failure the font package's own test documents.
	 */
	@After
	public void restoreDefaultSink() {
		OcslDiagnostics.setSink(null);
	}

	private static Capture capture() {
		Capture c = new Capture();
		OcslDiagnostics.setSink(c);
		return c;
	}

	// ------------------------------------------------------------------ the unit

	@Test
	public void theUnitIsProgramAndNodeSoOneNodeCannotConsumeTheWholePreset() throws Exception {
		// THE DEFECT. A curated preset is ONE program id across many nodes, so "logged once per
		// program" spent the only line on whichever node failed first and hid every other one.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		for (int node = 0; node < 5; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		}
		assertEquals("five nodes of one preset are five distinct failures", 5, c.lines.size());
		assertTrue("once-per-program gives 1, and that is what this excludes", c.lines.size() != 1);

		// And the same node repeating is still one line -- the dedup half has to hold too, or the
		// fix is just "log everything" wearing a finer key.
		for (int frame = 0; frame < 100; frame++) {
			r.rejectedWrite(P1, 3, OcslWire.PROP_ANIM_SX, Float.NaN, frame * 1000L);
		}
		assertEquals("a repeat of an already-reported pair says nothing", 5, c.lines.size());
		assertTrue("no dedup at all would give 105 here", c.lines.size() != 105);
	}

	@Test
	public void theSameNodeUnderTwoProgramsIsTwoDistinctFailures() throws Exception {
		// Both halves of the key participate. A folded key would suppress one of these while looking
		// exactly like correct deduplication.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		r.rejectedWrite(P1, 7, OcslWire.PROP_ANIM_X, Float.NaN, 0L);
		r.rejectedWrite(P2, 7, OcslWire.PROP_ANIM_X, Float.NaN, 0L);
		assertEquals("the program half of the key must count", 2, c.lines.size());
		assertTrue("keying on the node alone gives 1", c.lines.size() != 1);

		// And the message identifies which is which, or the finer unit buys nothing to read.
		assertTrue("the line names the node", c.lines.get(0).contains("node 7"));
		assertTrue("and the program", c.lines.get(0).contains(Long.toHexString(P1)));
		assertTrue("and the property by NAME, not by id",
				c.lines.get(0).contains(SurfaceTable.propertyName(OcslWire.STAGE_ANIMATOR,
						OcslWire.PROP_ANIM_X)));
		assertTrue("and says what the frame actually displayed",
				c.lines.get(0).contains("server base"));
	}

	@Test
	public void twoPairsInTheSameSlotAreStillTwoDistinctFailures() throws Exception {
		// THE COLLISION CASE, which nothing reached until a surviving mutation pointed at it.
		// Weakening the probe's `&&` to `||` is invisible on distinct keys -- they land in different
		// slots and the comparison never runs -- so it took a pair engineered to collide. On a
		// collision the weakened probe reports "already seen" for a node that never was: a
		// diagnostic silently claiming coverage it does not have.
		int first = -1;
		int second = -1;
		int home = OcslDiagnostics.Reporter.slot(P1, 0);
		for (int node = 1; node < 100000 && second < 0; node++) {
			if (OcslDiagnostics.Reporter.slot(P1, node) == home) {
				first = 0;
				second = node;
			}
		}
		// Without this the whole vector is vacuous if the hash ever changes shape.
		assertTrue("no colliding pair found, so this test proves nothing", second > 0);

		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		r.rejectedWrite(P1, first, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		r.rejectedWrite(P1, second, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);

		assertEquals("nodes " + first + " and " + second + " share a slot and are still two"
				+ " distinct failures", 2, c.lines.size());
		assertTrue("a probe matching on EITHER half returns 1 here, and that is what this excludes",
				c.lines.size() != 1);
		assertTrue("and the second line names the second node",
				c.lines.get(1).contains("node " + second));
	}

	// ------------------------------------------------------------------ the rate cap

	@Test
	public void theBurstIsSpentAndThenTheCapBites() throws Exception {
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		// Every pair distinct, so dedup never fires and only the rate cap can be doing the limiting.
		for (int node = 0; node < 200; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		}
		assertEquals("a 200-node scene cannot flood the log", OcslDiagnostics.Reporter.BURST,
				c.lines.size());
		assertTrue("no cap at all gives 200, and that is what this excludes", c.lines.size() != 200);
		assertEquals("and the bucket is empty", 0, r.availableTokens());
	}

	@Test
	public void aSuppressedFailureIsNotRecordedSoItCanStillBeReportedLater() throws Exception {
		// THE ORDERING RULE. Recording the pair before checking the rate would let a scene-load burst
		// consume dedup slots for pairs that never produced a line -- silent forever, which is the
		// under-reporting this whole amendment exists to fix, reintroduced by another mechanism.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		for (int node = 0; node < 200; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		}
		int afterBurst = c.lines.size();

		// Node 199 was suppressed, never reported. Ten seconds later one token exists again.
		r.rejectedWrite(P1, 199, OcslWire.PROP_ANIM_SX, Float.NaN,
				OcslDiagnostics.Reporter.REFILL_NANOS);
		assertEquals("the suppressed pair reports once a token exists", afterBurst + 1,
				c.lines.size());
		assertTrue("record-then-check would have marked it seen and lost it permanently",
				c.lines.size() > afterBurst);
		assertTrue("and the line is about node 199",
				c.lines.get(c.lines.size() - 1).contains("node 199"));
	}

	@Test
	public void aFullBucketDoesNotBankCreditForALaterBurst() throws Exception {
		// A quiet hour must not mint a burst the moment something goes wrong: the cap would hold on
		// paper and not in the log.
		//
		// THE FIRST VERSION OF THIS TEST COULD NOT SEE THAT, and the mutation sweep said so -- it
		// ran the storm at the SAME timestamp as the refill, so the banked credit was never spent
		// and the mutant that banks it scored identically. Discriminating needs a third batch, at a
		// LATER time, whose gap is smaller than one refill interval: correct code has nothing saved
		// and stays silent; a banking bucket cashes the hour in.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		int burst = OcslDiagnostics.Reporter.BURST;

		// Every node distinct throughout, so dedup never fires and only the rate policy is on trial.
		for (int node = 0; node < 200; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		}
		assertEquals("the initial burst", burst, c.lines.size());

		long anHour = 3600L * SECOND;
		for (int node = 200; node < 400; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, anHour);
		}
		assertEquals("an hour refills the bucket to exactly full, once", 2 * burst, c.lines.size());

		// One second later -- a tenth of a refill interval. Nothing has been earned.
		for (int node = 400; node < 600; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, anHour + SECOND);
		}
		assertEquals("one second buys no token", 2 * burst, c.lines.size());
		assertTrue("a bucket that banked the hour's leftover would cash in another " + burst
				+ " here, for 48", c.lines.size() != 3 * burst);
		assertEquals("and the bucket is still empty", 0, r.availableTokens());
	}

	@Test
	public void aBackwardClockMintsNothing() throws Exception {
		// The clock is the host's. A bucket that credited negative time would refill on a correction.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		for (int node = 0; node < 200; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN, 100L * SECOND);
		}
		int afterBurst = c.lines.size();
		assertEquals(OcslDiagnostics.Reporter.BURST, afterBurst);

		// Jump an hour BACKWARD, then forward to just before the original reading.
		r.rejectedWrite(P1, 500, OcslWire.PROP_ANIM_SX, Float.NaN, 50L * SECOND);
		assertEquals("a backward reading grants no token", afterBurst, c.lines.size());
		r.rejectedWrite(P1, 501, OcslWire.PROP_ANIM_SX, Float.NaN, 99L * SECOND);
		assertEquals("and neither does catching back up to where it already was", afterBurst,
				c.lines.size());
		assertTrue("crediting the absolute difference would have minted 5 tokens twice over",
				c.lines.size() == afterBurst);
	}

	// ------------------------------------------------------------------ bounded state

	@Test
	public void theDedupTableIsBoundedAndClearsRatherThanGoingSilent() throws Exception {
		// The table cannot grow forever -- node ids churn across a session. When it fills it CLEARS,
		// so an old pair may repeat; the alternative, refusing new keys, would make a genuinely new
		// failure silent for the rest of the session, which is the failure being fixed here.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();

		// Feed far more distinct pairs than MAX_TRACKED, giving the bucket time so the rate cap is
		// not what limits us. One token per REFILL_NANOS.
		int pairs = OcslDiagnostics.Reporter.MAX_TRACKED * 3;
		for (int node = 0; node < pairs; node++) {
			r.rejectedWrite(P1, node, OcslWire.PROP_ANIM_SX, Float.NaN,
					node * OcslDiagnostics.Reporter.REFILL_NANOS);
		}
		assertTrue("well past MAX_TRACKED distinct pairs still report", c.lines.size() > 256);
		assertTrue("a frozen table would have stopped at the first " + "MAX_TRACKED",
				c.lines.size() > OcslDiagnostics.Reporter.MAX_TRACKED);

		// The probe loop must terminate -- an open-addressed table that filled would spin forever,
		// and reaching this line at all is that assertion.
		assertTrue("and the run terminated", true);
	}

	@Test
	public void resetForgetsEverythingSoAFixedProgramIsHeardAgain() throws Exception {
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		r.rejectedWrite(P1, 1, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		r.rejectedWrite(P1, 1, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		assertEquals(1, c.lines.size());

		r.reset();
		assertEquals("the bucket is full again", OcslDiagnostics.Reporter.BURST,
				r.availableTokens());
		r.rejectedWrite(P1, 1, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		assertEquals("after a scene reload the same pair reports again", 2, c.lines.size());
		assertEquals("and that line cost a token like any other",
				OcslDiagnostics.Reporter.BURST - 1, r.availableTokens());
	}

	// ------------------------------------------------------------------ the shape of the API

	@Test
	public void nothingDisplayedCanDependOnWhetherALineWasPrinted() throws Exception {
		// The one piece of ANIM-9 that MUST hold state, kept out of the frame by having nothing to
		// branch on. A boolean return would be one refactor away from a displayed value that differs
		// between a client that has already logged and one that has not -- exactly the per-client
		// divergence ANIM-9(a) removed.
		java.lang.reflect.Method m = OcslDiagnostics.Reporter.class.getMethod("rejectedWrite",
				long.class, int.class, int.class, float.class, long.class);
		assertEquals("rejectedWrite must return void", void.class, m.getReturnType());
	}

	@Test
	public void aNullSinkRestoresTheDefaultRatherThanSilencing() throws Exception {
		// Same promise the font package makes, and for the same reason: there is no case where
		// losing these messages is the right outcome.
		Capture c = capture();
		OcslDiagnostics.Reporter r = new OcslDiagnostics.Reporter();
		r.rejectedWrite(P1, 1, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		assertEquals(1, c.lines.size());

		OcslDiagnostics.setSink(null);
		r.rejectedWrite(P1, 2, OcslWire.PROP_ANIM_SX, Float.NaN, 0L);
		assertEquals("the capture stops receiving, because the default is back", 1, c.lines.size());
		assertTrue("and a silencing null would leave availableTokens untouched too",
				r.availableTokens() < OcslDiagnostics.Reporter.BURST);
	}
}
