package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import opengpu.v2.sync.ClientTransport;
import opengpu.v2.sync.MirrorClient;

/**
 * ANIM-16: the budget and the scene loop must be given the SAME population.
 *
 * <h2>The bug this pins</h2>
 *
 * Increment 5 rolled the budget over raw {@code usedScenes} while the render loop walked only the
 * subset with mirrors. A scene can be in {@code usedScenes} with no mirror — {@code ScreenRenderer}
 * calls {@code markUsed} "whether or not we can draw it this frame", and {@code MirrorClient}
 * evicts a mirror permanently after failed resyncs — and such a scene is skipped before it can
 * acquire a {@code SceneGl}. So every frame: {@code prune} deleted its budget entry (keyed on the
 * {@code SceneGl} map), the roll recreated it at the never-measured 30 us fallback, and being
 * forever unseen it was force-admitted and charged in pass 1. Five of those exceed the 125 us exit
 * threshold on their own — degradation that can never end, incurred entirely on behalf of scenes
 * that never render.
 *
 * Charging them zero would NOT have fixed it: {@code prune} destroys the entry before the next
 * frame can read it. Only agreeing on the population works.
 *
 * <h2>What this test can and cannot reach, stated exactly</h2>
 *
 * It drives {@link SceneRenderer#resolveWalk} — the deciding half — against a real
 * {@link MirrorClient} and a real {@link AnimatorBudget}, so the composition that was mismatched
 * is asserted end to end.
 *
 * It does NOT construct a {@code SceneRenderer}. That is not because the class needs Forge to
 * classload (it has no static initialiser, and this suite already builds Minecraft-typed objects
 * headlessly — {@code InputRouterTest} allocates an {@code EntityPlayerMP}); it is because the
 * CONSTRUCTOR builds a {@code FramebufferPass}, whose buffers come from LWJGL's
 * {@code BufferUtils}, which is not on the test runtime classpath. So the remaining uncovered
 * claim is narrow and worth naming: that {@code prePassSupported} passes this one list to both the
 * budget roll and the loop. It is a single shared local now, which makes disagreement structural
 * rather than something to remember — the same move {@code wouldEvaluate} makes for the render
 * guard.
 */
public class ScenePopulationTest {

	private static final ClientTransport SILENT = new ClientTransport() {
		public void sendToServer(byte[] envelope) {
			// The renderer never sends; no resync is driven here.
		}
	};

	private static Set<String> used(String... ids) {
		return new HashSet<String>(Arrays.asList(ids));
	}

	/** One frame: resolve the population the way prePassSupported does, then roll the budget. */
	private static void frame(MirrorClient mirrors, AnimatorBudget budget, Set<String> usedScenes) {
		List<String> walk = new ArrayList<String>();
		SceneRenderer.resolveWalk(mirrors, usedScenes, walk);
		budget.prune(new HashSet<String>(walk));
		budget.beginFrame(walk);
	}

	/**
	 * THE REGRESSION. Two scenes marked used, one mirrored: the budget must see exactly one.
	 *
	 * 30000 is one never-measured scene at the model fallback; 60000 would mean the mirrorless
	 * scene entered the budget's world and is spending capacity it can never account for. Far
	 * enough apart that nothing else explains the difference.
	 */
	@Test
	public void aSceneWithNoMirrorIsNotChargedToTheBudget() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("real");                  // creates on demand; "ghost" gets none
		AnimatorBudget budget = new AnimatorBudget();

		frame(mirrors, budget, used("real", "ghost"));

		assertEquals("only the mirrored scene may reach the budget", 1, budget.trackedScenes());
		assertEquals("one never-measured scene is 30 us of demand, not two",
				30_000L, budget.lastFrameDemand());
	}

	/**
	 * The failure was per-frame RECREATION, so one frame understates it. Ten frames of a
	 * persistent ghost must leave the budget exactly where one frame did.
	 */
	@Test
	public void aPersistentGhostNeverAccumulatesDemand() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("real");
		AnimatorBudget budget = new AnimatorBudget();

		for (int f = 0; f < 10; f++) {
			frame(mirrors, budget, used("real", "ghost"));
		}

		assertEquals(1, budget.trackedScenes());
		assertEquals("a ghost seen ten times is still not a scene",
				30_000L, budget.lastFrameDemand());
		assertEquals("and nothing it did counts as spend", 0L, budget.lastFrameSpend());
	}

	/**
	 * THE CONSEQUENCE, asserted directly: enough ghosts must not be able to latch degradation.
	 *
	 * Six mirrorless scenes were 180 us of permanent demand under the bug — above the 125 us exit
	 * — so a client that once entered degradation could never leave it. With the populations
	 * agreeing, they contribute nothing and the budget disengages as soon as real demand falls.
	 */
	@Test
	public void ghostScenesCannotLatchDegradation() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("real");
		AnimatorBudget budget = new AnimatorBudget();
		Set<String> withGhosts = used("real", "g0", "g1", "g2", "g3", "g4", "g5");

		// Drive it into degradation with SUSTAINED genuine load on the one real scene -- the
		// entry is debounced (two consecutive over-threshold frames), so one spike is a hitch.
		frame(mirrors, budget, withGhosts);
		budget.charge("real", 300_000L);
		frame(mirrors, budget, withGhosts);
		budget.charge("real", 300_000L);
		frame(mirrors, budget, withGhosts);
		assertTrue("300 us of sustained real load engages it", budget.degrading());

		// The real scene becomes cheap. Nothing else has any claim on the budget.
		for (int f = 0; f < AnimatorBudget.MAX_HOLD_FRAMES + 3; f++) {
			frame(mirrors, budget, withGhosts);
			budget.charge("real", 5_000L);
		}
		frame(mirrors, budget, withGhosts);
		assertFalse("six mirrorless scenes must not hold degradation open; demand="
				+ budget.lastFrameDemand(), budget.degrading());
	}

	/**
	 * THE BUG ITSELF, reproduced, so the assertions above are not vacuous.
	 *
	 * Every other test here calls {@code resolveWalk} first and feeds the budget its output —
	 * i.e. the FIXED composition — so none of them could fail against the old code, and passing
	 * proves only that the fixed arrangement works. This one drives the OLD arrangement directly:
	 * the unfiltered {@code usedScenes} into {@code beginFrame}, exactly as increment 5 shipped
	 * it. If this ever stops latching, the defect has become unreachable and the tests above have
	 * lost their subject — at which point they should be re-read, not trusted.
	 *
	 * It also documents the magnitude: six mirrorless scenes at the never-measured fallback are
	 * 180 us against a 125 us exit, so the client could never leave degradation once it entered.
	 */
	@Test
	public void theUnfilteredPopulationIsWhatLatchedDegradation() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("real");
		AnimatorBudget budget = new AnimatorBudget();
		Set<String> withGhosts = used("real", "g0", "g1", "g2", "g3", "g4", "g5");
		List<String> mirrored = new ArrayList<String>();
		SceneRenderer.resolveWalk(mirrors, withGhosts, mirrored);

		// THE OLD WIRING: prune keyed on the scenes that get GL state (the mirrored ones), but
		// the roll given every scene marked used.
		budget.prune(new HashSet<String>(mirrored));
		budget.beginFrame(withGhosts);
		budget.charge("real", 300_000L);
		budget.prune(new HashSet<String>(mirrored));
		budget.beginFrame(withGhosts);
		budget.charge("real", 300_000L);
		budget.prune(new HashSet<String>(mirrored));
		budget.beginFrame(withGhosts);
		assertTrue(budget.degrading());

		for (int f = 0; f < AnimatorBudget.MAX_HOLD_FRAMES + 3; f++) {
			budget.prune(new HashSet<String>(mirrored));
			budget.beginFrame(withGhosts);
			budget.charge("real", 5_000L);
		}
		budget.prune(new HashSet<String>(mirrored));
		budget.beginFrame(withGhosts);
		assertTrue("the old wiring must still demonstrate the latch, or the tests above are"
				+ " asserting against a defect that no longer exists; demand="
				+ budget.lastFrameDemand(), budget.degrading());
		assertTrue("and the demand must be the ghosts', not the real scene's",
				budget.lastFrameDemand() > 125_000L);
	}

	/** A scene whose mirror is evicted drops out — the reachable form of the bug. */
	@Test
	public void aSceneLosingItsMirrorDropsOutOfTheBudget() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("a");
		mirrors.mirror("b");
		AnimatorBudget budget = new AnimatorBudget();

		frame(mirrors, budget, used("a", "b"));
		assertEquals(2, budget.trackedScenes());

		mirrors.evict("b");                      // the real eviction path, not a test-only hook
		frame(mirrors, budget, used("a", "b"));
		assertEquals("b has no mirror now and must leave the budget", 1, budget.trackedScenes());
		assertEquals(30_000L, budget.lastFrameDemand());
	}

	/** A mirrored scene IS included — the mirror of every assertion above, so none is vacuous. */
	@Test
	public void mirroredScenesAreIncluded() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("a");
		mirrors.mirror("b");
		List<String> walk = new ArrayList<String>();

		SceneRenderer.resolveWalk(mirrors, used("a", "b"), walk);

		assertEquals(2, walk.size());
		assertTrue(walk.contains("a"));
		assertTrue(walk.contains("b"));
	}

	/** The output list is reused across frames, so it must be cleared rather than appended to. */
	@Test
	public void theWalkListIsClearedNotAppended() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		mirrors.mirror("a");
		List<String> walk = new ArrayList<String>();

		SceneRenderer.resolveWalk(mirrors, used("a"), walk);
		SceneRenderer.resolveWalk(mirrors, used("a"), walk);
		SceneRenderer.resolveWalk(mirrors, used("a"), walk);

		assertEquals("three frames must not leave three copies", 1, walk.size());
	}

	/** No scenes at all must not throw, and must leave the budget disengaged. */
	@Test
	public void anEmptyFrameIsHarmless() {
		MirrorClient mirrors = new MirrorClient(SILENT, 20);
		AnimatorBudget budget = new AnimatorBudget();
		for (int f = 0; f < 3; f++) {
			frame(mirrors, budget, used());
		}
		assertEquals(0, budget.trackedScenes());
		assertEquals(0L, budget.lastFrameSpend());
		assertFalse(budget.degrading());
	}
}
