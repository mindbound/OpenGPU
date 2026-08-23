package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;

import org.junit.Test;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.SceneState;

public class MirrorOrderingTest {

	private static final String SCENE = "scene-address";
	private static final int EPOCH = 7;

	private static SceneBatch batch(int seq, Delta... deltas) {
		ArrayList<Delta> list = new ArrayList<Delta>();
		Collections.addAll(list, deltas);
		return new SceneBatch(SCENE, EPOCH, seq, seq * 10L, list);
	}

	private static SceneBatch batchWithEpoch(int epoch, int seq, Delta... deltas) {
		ArrayList<Delta> list = new ArrayList<Delta>();
		Collections.addAll(list, deltas);
		return new SceneBatch(SCENE, epoch, seq, seq * 10L, list);
	}

	// ------------------------------------------------------------------
	// ANIM-13(b): the heartbeat's clock reading

	/**
	 * A healthy heartbeat records the tick and disturbs NOTHING else.
	 *
	 * Four separate assertions because the invariant has four halves and they fail
	 * independently: advancing seq would swallow a lost batch, marking dirty would make a silent
	 * scene re-render forever, moving lastServerTick would re-date the interpolator's keyframes
	 * against a tick no batch delivered, and not recording the tick leaves the defect in place.
	 */
	@Test
	public void aHealthyHeartbeatRecordsTheTickAndTouchesNothingElse() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		mirror.clearDirty();
		long batchTick = mirror.lastServerTick();

		mirror.observeHeartbeat(EPOCH, 1, batchTick + 40L);

		assertEquals("seq must not advance -- that is what would swallow a lost batch",
				1, mirror.lastSeq());
		assertFalse("a heartbeat is not state; marking dirty would re-render a silent scene"
				+ " every frame forever", mirror.isDirty());
		assertEquals("the keyframe x-axis belongs to applied batches only",
				batchTick, mirror.lastServerTick());
		assertTrue(mirror.hasObservedTick());
		assertEquals("and the clock reading IS recorded, or the free-run stays",
				batchTick + 40L, mirror.lastObservedTick());
	}

	/**
	 * A heartbeat from ANOTHER incarnation must not feed the clock.
	 *
	 * Its tick space is unrelated to ours -- a restored or recreated scene counts from its own
	 * origin -- so the sample would be arbitrary. It would also arrive through a door that
	 * bypasses the renderer's epoch reset, which is the one place the estimate is supposed to be
	 * dropped rather than averaged across.
	 */
	@Test
	public void aHeartbeatFromAnotherEpochDoesNotFeedTheClock() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		long known = mirror.lastObservedTick();

		mirror.observeHeartbeat(EPOCH + 1, 1, 999_999L);

		assertTrue("an epoch mismatch still demands a resync", mirror.needsResync());
		assertFalse("and the hard reset must forget the old incarnation's reading",
				mirror.hasObservedTick());
		assertTrue("so nothing reads 999999 as this timeline's clock",
				mirror.lastObservedTick() != 999_999L || known == 999_999L);
	}

	/**
	 * A heartbeat announcing a seq we never received is a GAP: resync follows, and the tick that
	 * rode in with it must not be trusted either, because the snapshot that heals the gap
	 * carries the authoritative stamp anyway.
	 */
	@Test
	public void aHeartbeatThatDetectsAGapDoesNotFeedTheClock() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		long beforeTick = mirror.lastObservedTick();

		mirror.observeHeartbeat(EPOCH, 5, 4242L);          // server is ahead: batches 2..5 lost

		assertTrue("a seq ahead of us is a gap", mirror.needsResync());
		assertEquals("the gap's tick must not be recorded", beforeTick, mirror.lastObservedTick());
	}

	/**
	 * A mirror that has seen nothing has no clock reading, and says so rather than reporting 0 --
	 * tick 0 is a legal reading, so the flag is the only way to tell "start of the world" from
	 * "never told". The renderer feeds the timeline off this flag.
	 */
	@Test
	public void aFreshMirrorHasNoObservedTick() {
		assertFalse(new SceneMirror(SCENE).hasObservedTick());
	}

	@Test
	public void inOrderBatchesApply() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertTrue(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 5 }))));
		assertEquals(2, mirror.lastSeq());
		assertEquals(5, mirror.state().nodes.get(1).x, 0);
		assertFalse(mirror.needsResync());
	}

	@Test
	public void staleBatchIsDiscarded() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertTrue(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 5 }))));
		// Replay of seq 2 with different content must be ignored entirely.
		assertFalse(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 99 }))));
		assertEquals(5, mirror.state().nodes.get(1).x, 0);
	}

	@Test
	public void gapTriggersResyncAndBlocksApplication() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		// Seq 3 skips 2: nothing applies, resync flagged.
		assertFalse(mirror.applyBatch(batch(3, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 7 }))));
		assertTrue(mirror.needsResync());
		assertEquals(0, mirror.state().nodes.get(1).x, 0);
		// Even an in-order successor is refused while resync is pending.
		assertFalse(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 6 }))));
		assertEquals(0, mirror.state().nodes.get(1).x, 0);
	}

	@Test
	public void unknownIdTriggersResync() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertFalse(mirror.applyBatch(batch(1, new Delta.NodeProps(42, V2Wire.PROP_X, new double[] { 1 }))));
		assertTrue(mirror.needsResync());
	}

	@Test
	public void snapshotRecoversAndStaleRuleCoversLateBatches() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertFalse(mirror.applyBatch(batch(4, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 9 }))));
		assertTrue(mirror.needsResync());

		// Snapshot at seq 4: state with x already 9.
		SceneState snapState = new SceneState();
		opengpu.v2.scene.SceneNode node = new opengpu.v2.scene.SceneNode(1, V2Wire.NODE_GROUP, 0);
		node.x = 9;
		snapState.nodes.put(1, node);
		mirror.applySnapshot(new SceneSnapshot(SCENE, EPOCH, 4, 40L, snapState));
		assertFalse(mirror.needsResync());
		assertEquals(4, mirror.lastSeq());

		// Late batches 2..4 (already covered) are discarded by the stale rule.
		assertFalse(mirror.applyBatch(batch(3, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 777 }))));
		assertEquals(9, mirror.state().nodes.get(1).x, 0);

		// Seq 5 resumes cleanly.
		assertTrue(mirror.applyBatch(batch(5, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 10 }))));
		assertEquals(10, mirror.state().nodes.get(1).x, 0);
	}

	@Test
	public void sequenceWraparoundIsHandled() {
		int nearMax = Integer.MAX_VALUE;
		SceneMirror mirror = new SceneMirror(SCENE, nearMax);
		// MAX_VALUE + 1 wraps to MIN_VALUE; seqDelta must see it as "next".
		assertTrue(mirror.applyBatch(batch(nearMax + 1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertEquals(Integer.MIN_VALUE, mirror.lastSeq());
		// The pre-wrap seq is now stale.
		assertFalse(mirror.applyBatch(batch(nearMax, new Delta.NodeFree(1))));
		assertTrue(mirror.state().nodes.containsKey(1));
	}

	@Test
	public void wrongSceneIdIsIgnored() {
		SceneMirror mirror = new SceneMirror(SCENE);
		SceneBatch other = new SceneBatch("other-scene", EPOCH, 1,
				0L, Collections.<Delta>singletonList(new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0)));
		assertFalse(mirror.applyBatch(other));
		assertEquals(0, mirror.lastSeq());
		assertFalse(mirror.needsResync());
	}

	@Test
	public void dirtyFlagTracksApplication() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertFalse(mirror.isDirty());
		mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0)));
		assertTrue(mirror.isDirty());
		mirror.clearDirty();
		assertFalse(mirror.isDirty());
	}

	@Test
	public void emptyInOrderBatchIsAProtocolAnomaly() {
		// The server never seals an empty batch; a heartbeat wrongly encoded as one must not
		// advance lastSeq (it would silently swallow a lost batch's deltas forever).
		SceneMirror mirror = new SceneMirror(SCENE);
		assertFalse(mirror.applyBatch(batch(1)));
		assertTrue(mirror.needsResync());
		assertEquals(0, mirror.lastSeq());
	}

	@Test
	public void observeSeqDetectsMissedBatches() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		mirror.observeSeq(EPOCH, 1); // heartbeat at current seq: no-op
		assertFalse(mirror.needsResync());
		mirror.observeSeq(EPOCH, 3); // server is ahead: batches were lost
		assertTrue(mirror.needsResync());
		assertEquals(1, mirror.lastSeq());
	}

	@Test
	public void staleSnapshotIsDiscarded() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertTrue(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 5 }))));
		// A delayed response to an old snapshot request must not rewind the mirror.
		SceneState old = new SceneState();
		mirror.applySnapshot(new SceneSnapshot(SCENE, EPOCH, 1, 10L, old));
		assertEquals(2, mirror.lastSeq());
		assertEquals(5, mirror.state().nodes.get(1).x, 0);
	}

	@Test
	public void epochChangeOnBatchHardResets() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertTrue(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 5 }))));
		// The scene was destroyed and recreated: new epoch, seq restarts. Without the epoch,
		// seq 1 would be discarded as stale forever (the sceneId-reuse hole).
		assertTrue(mirror.applyBatch(batchWithEpoch(99, 1, new Delta.NodeCreate(1, V2Wire.NODE_SPRITE, 0))));
		assertEquals(99, mirror.knownEpoch());
		assertEquals(1, mirror.lastSeq());
		assertEquals(V2Wire.NODE_SPRITE, mirror.state().nodes.get(1).type);
	}

	@Test
	public void epochChangeOnHeartbeatForcesResync() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		// New incarnation announced by heartbeat, even at a LOWER seq than ours.
		mirror.observeSeq(99, 0);
		assertTrue(mirror.needsResync());
		assertEquals(99, mirror.knownEpoch());
		assertTrue(mirror.state().nodes.isEmpty());
	}

	@Test
	public void sameEpochSeqRegressionOnHeartbeatHardResets() {
		// A same-epoch heartbeat BEHIND lastSeq is impossible in a healthy incarnation under
		// FIFO delivery — it proves a divergent restore (crash-without-save, NBT rollback).
		// Without this rule the mirror stale-discards the restored scene's traffic forever.
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertTrue(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 5 }))));
		mirror.observeSeq(EPOCH, 1); // server restored from an older save
		assertTrue(mirror.needsResync());
		assertTrue(mirror.state().nodes.isEmpty());
		assertEquals(0, mirror.lastSeq());
		// The restored incarnation's snapshot (same epoch, lower seq) now installs cleanly.
		SceneState snapState = new SceneState();
		mirror.applySnapshot(new SceneSnapshot(SCENE, EPOCH, 1, 10L, snapState));
		assertFalse(mirror.needsResync());
		assertEquals(1, mirror.lastSeq());
	}

	@Test
	public void snapshotAcrossEpochBypassesTheStaleGuard() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		assertTrue(mirror.applyBatch(batch(2, new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 5 }))));
		// A new incarnation's snapshot may carry a lower seq; it must install regardless.
		SceneState fresh = new SceneState();
		mirror.applySnapshot(new SceneSnapshot(SCENE, 99, 1, 10L, fresh));
		assertEquals(99, mirror.knownEpoch());
		assertEquals(1, mirror.lastSeq());
		assertTrue(mirror.state().nodes.isEmpty());
		assertFalse(mirror.needsResync());
	}

	@Test
	public void failedBatchDoesNotSetDirty() {
		SceneMirror mirror = new SceneMirror(SCENE);
		assertTrue(mirror.applyBatch(batch(1, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0))));
		mirror.clearDirty();
		// Unknown node id mid-batch: state unreliable — the renderer keeps the last clean frame.
		assertFalse(mirror.applyBatch(batch(2,
				new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 1 }),
				new Delta.NodeProps(42, V2Wire.PROP_X, new double[] { 2 }))));
		assertTrue(mirror.needsResync());
		assertFalse(mirror.isDirty());
	}
}
