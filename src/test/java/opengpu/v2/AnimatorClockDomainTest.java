package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.ServerScene;

/**
 * Phase 3.5: turning a persisted WORLD-time stamp into the domain the render clock counts.
 *
 * This is the half of the animator clock that can be tested at all. The other half —
 * {@code NodeInterpolator.renderInstant} feeding {@code OcslTime.time} — is inside the render
 * package and needs GL and Forge, so the arithmetic is proven here and the wiring is proven in
 * game once 3.3 gives it a consumer.
 *
 * The offset exists because two clocks are involved and neither can be dropped: stamps must
 * PERSIST as world time (a session tick is meaningless after a restart) and must be COMPARED
 * against a render clock anchored to the session tick counter (which resets at server stop). One
 * subtraction reconciles them, and this file is about getting that subtraction right.
 */
public class AnimatorClockDomainTest {

	private static final String SCENE = "gpu-node-address";

	/** A server whose scene has been stamped, sealed and snapshot-ready. */
	private static ServerScene stampedServer(long worldTime, long serverTick) {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.setCurrentTick(serverTick);
		server.stampWorldTime(worldTime);
		server.sealBatch();
		return server;
	}

	private static SceneMirror mirrorOf(ServerScene server) throws Exception {
		SceneMirror mirror = new SceneMirror(SCENE);
		mirror.applySnapshot(SnapshotCodec.decode(SnapshotCodec.encode(server.snapshot())));
		return mirror;
	}

	/**
	 * The offset reconciles the two clocks: a stamp taken at the same instant as the anchor must
	 * convert to the tick the server was on.
	 *
	 * World time is deliberately FAR from the session tick — that gap is the whole problem, and
	 * numbers near each other would let an implementation that ignored the offset entirely pass.
	 */
	@Test
	public void aStampAtTheAnchorConvertsToTheAnchorsTick() throws Exception {
		SceneMirror mirror = mirrorOf(stampedServer(9_000_000L, 500L));
		assertTrue("the anchor was stamped, so the clock must be known",
				mirror.animatorClockKnown());
		assertEquals("a stamp equal to the anchor is the anchor's tick",
				500L, mirror.sessionTickOf(9_000_000L));
	}

	/** An OLDER stamp converts to an EARLIER tick, by exactly the elapsed world time. */
	@Test
	public void anOlderStampConvertsToAnEarlierTick() throws Exception {
		SceneMirror mirror = mirrorOf(stampedServer(9_000_000L, 500L));
		// Created 200 ticks of world time before the anchor.
		assertEquals(300L, mirror.sessionTickOf(9_000_000L - 200L));
		// And a scene older than the whole session converts NEGATIVE, which is correct rather
		// than a bug: OcslTime differences it against renderNanos, so a negative start simply
		// means a large positive elapsed, and the period wrap handles the rest.
		assertEquals(-500L, mirror.sessionTickOf(9_000_000L - 1000L));
	}

	/**
	 * THE OFFSET MUST NOT BE RECOMPUTED FROM LIVE FIELDS. `lastServerTick` advances with every
	 * batch while `worldTimeAnchor` does not — no delta carries it — so an implementation that
	 * subtracted them on demand drifts by one tick per tick after the snapshot, sliding every
	 * animator clock steadily out of true. Shipping batches after the snapshot must change
	 * nothing.
	 */
	@Test
	public void theOffsetSurvivesBatchesArrivingAfterTheSnapshot() throws Exception {
		ServerScene server = stampedServer(9_000_000L, 500L);
		SceneMirror mirror = mirrorOf(server);
		long before = mirror.sessionTickOf(9_000_000L);

		// Advance the server by 50 ticks of real activity and ship the batches.
		for (int i = 1; i <= 50; i++) {
			server.setCurrentTick(500L + i);
			server.stampWorldTime(9_000_000L + i);
			server.createNode(V2Wire.NODE_GROUP, 0);
			SceneBatch batch = server.sealBatch();
			assertTrue("the mirror rejected a batch",
					mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
		}
		assertEquals("50 batches moved lastServerTick", 550L, mirror.lastServerTick());
		assertEquals("...but the offset is captured, not derived, so conversion is unchanged",
				before, mirror.sessionTickOf(9_000_000L));
	}

	/** A later snapshot RE-captures the offset, which is how a server restart is absorbed. */
	@Test
	public void aFreshSnapshotRecapturesTheOffset() throws Exception {
		SceneMirror mirror = mirrorOf(stampedServer(9_000_000L, 500L));
		assertEquals(500L, mirror.sessionTickOf(9_000_000L));

		// A restarted server: the tick counter is back near zero while world time kept climbing.
		ServerScene restarted = new ServerScene(SCENE, 0, mirror.knownEpoch(),
				new opengpu.v2.scene.SceneState());
		int canvas = restarted.createCanvas(64, 32, 256);
		restarted.createNode(V2Wire.NODE_CANVAS, canvas);
		restarted.setCurrentTick(7L);
		restarted.stampWorldTime(9_100_000L);
		restarted.sealBatch();
		mirror.applySnapshot(SnapshotCodec.decode(SnapshotCodec.encode(restarted.snapshot())));

		assertEquals("the new session's offset must replace the old one, or every animator on"
				+ " this scene runs against a clock from the previous run",
				7L, mirror.sessionTickOf(9_100_000L));
	}

	/**
	 * An UNSTAMPED anchor is reported as unknown rather than silently converting.
	 *
	 * A pre-v8 save restores with anchor 0, and so does a scene whose owner has not ticked yet.
	 * Returning an offset of 0 there would place the scene's epoch at the start of the session —
	 * a plausible-looking number that is wrong by the whole magnitude of world time, and exactly
	 * the kind of silent wrongness the domain split exists to prevent.
	 */
	@Test
	public void anUnstampedSceneReportsItsClockUnknown() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.setCurrentTick(500L);
		server.sealBatch(); // never stamped
		assertEquals("precondition: the anchor really is unset", 0L,
				server.state().worldTimeAnchor);

		SceneMirror mirror = mirrorOf(server);
		assertFalse("an unstamped scene must not claim to know its clock",
				mirror.animatorClockKnown());
	}

	/** A mirror that has seen no snapshot at all is unknown too — the bootstrap case. */
	@Test
	public void aMirrorWithNoSnapshotReportsItsClockUnknown() {
		assertFalse(new SceneMirror(SCENE).animatorClockKnown());
	}

	/**
	 * The epoch a scene actually carries converts to a sane tick end to end — the value 3.3 will
	 * hand to {@code OcslTime.time}. Uses the scene's OWN creationWorldTime rather than a literal,
	 * so a change to how the epoch is stamped shows up here.
	 */
	@Test
	public void theScenesOwnEpochConvertsToTheTickItWasStampedOn() throws Exception {
		ServerScene server = stampedServer(9_000_000L, 500L);
		assertEquals("precondition: first stamp sets the epoch too",
				9_000_000L, server.state().creationWorldTime);
		SceneMirror mirror = mirrorOf(server);
		assertEquals("the scene was created at the anchor instant, so its epoch is that tick",
				500L, mirror.sessionTickOf(mirror.state().creationWorldTime));
	}
}
