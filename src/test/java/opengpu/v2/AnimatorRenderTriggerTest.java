package opengpu.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ServerScene;

/**
 * Phase 3.4: ANIM-19's re-render term.
 *
 * WHAT IS AND IS NOT COVERED HERE. The term itself is one conjunct in
 * {@code SceneRenderer.renderIfNeeded}, which needs GL and Forge to load, so no JVM test can drive
 * the guard. What IS testable is the predicate the conjunct asks — and that is where the decision
 * lives; the guard line is a composition of it with four booleans that already had their own
 * coverage.
 *
 * SINCE 2026-08-21 THE CONJUNCT ASKS {@code AnimatorOverlay.wouldEvaluate(state)}, not
 * {@code SceneState.hasAttachedAnimator()} directly — ANIM-16's budget can leave a scene with
 * animators attached and no animator work owed this frame. The two answer identically under the
 * only policy production wires, which is asserted in
 * {@code AnimatorOverlayTest.underTheShippedPolicyWouldEvaluateMatchesHasAttachedAnimator}; that
 * equivalence is what keeps the cases below meaningful, and if it is ever broken deliberately
 * these tests describe the OLD guard and must be moved rather than merely re-read.
 *
 * This is the third increment running where the deciding half was deliberately factored into a
 * pure class so the Forge-bound half is a thin composition. Stating it because the pattern is now
 * the house answer to "no test can see this": make the part that DECIDES visible to tests, and
 * leave only the dispatch in the dark.
 *
 * The guard as a whole is answered by the in-game channel instead, and its discriminator is
 * recorded at the line: frozen-when-settled indicts the trigger, moving-but-wrong indicts the
 * evaluator.
 */
public class AnimatorRenderTriggerTest {

	private static final String SCENE = "gpu-node-address";

	private static ServerScene sceneWithNodes(int count) {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		for (int i = 1; i < count; i++) {
			server.createNode(V2Wire.NODE_GROUP, 0);
		}
		return server;
	}

	/**
	 * BOTH DIRECTIONS, because each failure mode is a different shipped bug and each is invisible
	 * to the other's test: stuck-true re-renders every static scene forever (the "wrong fix" the
	 * obligation named), stuck-false freezes every animated one (the bug ANIM-19 exists to close).
	 */
	@Test
	public void anUnanimatedSceneReportsNoAnimator() {
		assertFalse("an empty scene has nothing attached",
				new ServerScene(SCENE).state().hasAttachedAnimator());
		assertFalse("nodes alone are not animation — this is the assertion that keeps a static"
				+ " display free of per-frame work",
				sceneWithNodes(4).state().hasAttachedAnimator());
	}

	@Test
	public void oneAttachedNodeIsEnough() {
		ServerScene server = sceneWithNodes(4);
		server.setAnimator(2, 7, 100L);
		assertTrue("a single attachment must make the whole scene count as work",
				server.state().hasAttachedAnimator());
	}

	/**
	 * The attached node is the LAST one, so a scan that inspected only the first node — or that
	 * returned on the first UNattached one — reports false and freezes the scene.
	 */
	@Test
	public void anAttachmentOnTheLastNodeIsStillFound() {
		ServerScene server = sceneWithNodes(5);
		int last = server.state().nodes.lastKey().intValue();
		server.setAnimator(last, 9, 100L);
		assertTrue("the walk must not stop before the end", server.state().hasAttachedAnimator());
	}

	/** Detaching the only animator returns the scene to free-when-settled. */
	@Test
	public void detachingTheLastAnimatorClearsTheTerm() {
		ServerScene server = sceneWithNodes(3);
		server.setAnimator(2, 7, 100L);
		assertTrue(server.state().hasAttachedAnimator());
		server.setAnimator(2, 0, 0L);
		assertFalse("after a detach the scene must stop paying to re-render",
				server.state().hasAttachedAnimator());
	}

	/** Two attached, one detached: the term holds while any remain. */
	@Test
	public void theTermHoldsWhileAnyAnimatorRemains() {
		ServerScene server = sceneWithNodes(4);
		server.setAnimator(2, 7, 100L);
		server.setAnimator(3, 8, 100L);
		server.setAnimator(2, 0, 0L);
		assertTrue("one detach must not clear a term the other attachment still justifies",
				server.state().hasAttachedAnimator());
	}

	/**
	 * FREEING the animated node clears the term, with no drop site anywhere — the payoff of
	 * putting `animator` on the node rather than in a side map. A side map would have had to be
	 * cleaned on five paths for this to hold, and this test would have passed on four of them.
	 */
	@Test
	public void freeingTheAnimatedNodeClearsTheTerm() {
		ServerScene server = sceneWithNodes(3);
		int last = server.state().nodes.lastKey().intValue();
		server.setAnimator(last, 7, 100L);
		assertTrue(server.state().hasAttachedAnimator());
		server.freeNode(last);
		assertFalse("a freed node takes its attachment, and therefore the render term, with it",
				server.state().hasAttachedAnimator());
	}

	/**
	 * A DANGLING attachment still counts as work, and that is correct rather than an oversight:
	 * the evaluator will find no program and compose nothing, but the node's animator field is
	 * what the trigger asks about, and ANIM-17 makes dangling a legal resting state. Treating it
	 * as "no work" would be a second, quieter place where a freed program changes render
	 * behaviour — and the two places could disagree.
	 */
	@Test
	public void aDanglingAttachmentStillCountsAsWork() {
		ServerScene server = sceneWithNodes(3);
		server.setAnimator(2, 4242, 100L); // no such program
		assertTrue("the trigger asks about the attachment, not about the program table",
				server.state().hasAttachedAnimator());
	}
}
