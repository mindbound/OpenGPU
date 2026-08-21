package opengpu.v2.scene;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;

/**
 * The client-side scene mirror, GL-free by design so server/mirror convergence is testable
 * headlessly. Implements the v2 ordering rules — these rules (not any lock) are what close
 * the legacy snapshot/delta ordering bug class:
 *
 * - stale batch (seq <= last applied, wraparound-safe) → discarded;
 * - in-order batch (seq == last + 1) → applied; any apply failure (unknown id, canvas
 *   mismatch, cap breach) flags {@code needsResync};
 * - gap (seq > last + 1) → nothing applied, {@code needsResync} flagged;
 * - while {@code needsResync} is set nothing applies until a snapshot arrives;
 * - a snapshot replaces the state, stamps lastSeq with its seq, and clears the flag —
 *   the stale rule then discards any late batches the snapshot already covers.
 *
 * The transport layer polls {@link #needsResync()} to drive retried snapshot requests.
 * {@code dirty} is the renderer's re-render trigger; the renderer clears it. A failed batch
 * does not set dirty: the renderer keeps showing the last clean frame until the snapshot
 * replaces the state.
 *
 * Thread contract: every method — including {@link #state()} reads — runs under the caller's
 * mirror lock; the renderer must hold that lock across its whole read of a frame. Nothing
 * here is internally synchronized.
 */
public final class SceneMirror {
	public final String sceneId;
	private SceneState state = new SceneState();
	/** 0 = no incarnation adopted yet; set by the first epoch-bearing message. */
	private int knownEpoch;
	private int lastSeq;
	private long lastServerTick;
	/** serverTick - worldTime, captured at snapshot time; see applySnapshot. */
	private long sessionTickOffset;
	private boolean sessionTickOffsetKnown;
	private boolean needsResync;
	private boolean dirty;
	/** Nodes that must SNAP this frame rather than interpolate; cleared with dirty. */
	private final java.util.Set<Integer> teleported = new java.util.HashSet<Integer>();

	public SceneMirror(String sceneId) {
		this(sceneId, 0);
	}

	/** initialSeq is exposed for wraparound testing. */
	public SceneMirror(String sceneId, int initialSeq) {
		this.sceneId = sceneId;
		this.lastSeq = initialSeq;
	}

	public SceneState state() {
		return state;
	}

	public int lastSeq() {
		return lastSeq;
	}

	public long lastServerTick() {
		return lastServerTick;
	}

	public boolean needsResync() {
		return needsResync;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void clearDirty() {
		dirty = false;
		teleported.clear();
	}

	/**
	 * Node ids whose transform arrived flagged PROP_TELEPORT since the last clearDirty.
	 *
	 * Routed here rather than through DeltaApplier because the flag qualifies a TRANSITION,
	 * not node state: storing it on SceneNode would put it in snapshots and in contentEquals,
	 * where a teleport would read as divergence. The applier consumes the value to keep its
	 * cursor aligned and ignores it; this is the client's only interest in it.
	 */
	public java.util.Set<Integer> teleportedNodes() {
		return teleported;
	}

	public int knownEpoch() {
		return knownEpoch;
	}

	/**
	 * Epoch discipline: a mismatched incarnation stamp means the scene was destroyed and
	 * recreated (or restored divergently) — every seq/state assumption is void. Hard reset:
	 * empty state, lastSeq 0, adopt the new epoch. The normal ordering rules then bootstrap
	 * the new incarnation (an in-order batch applies; anything else gaps into a resync).
	 * A mirror that has adopted no epoch yet (0) adopts silently without resetting, so
	 * late-joiner construction with an initial seq keeps working.
	 */
	private void adoptEpoch(int epoch) {
		if (knownEpoch == epoch)
			return;
		if (knownEpoch != 0) {
			hardReset();
		}
		knownEpoch = epoch;
	}

	private void hardReset() {
		state = new SceneState();
		lastSeq = 0;
		needsResync = false;
		dirty = true;
	}

	/** @return true when the batch was applied cleanly. */
	public boolean applyBatch(SceneBatch batch) {
		if (!sceneId.equals(batch.sceneId))
			return false;
		adoptEpoch(batch.epoch);
		int delta = V2Wire.seqDelta(batch.seq, lastSeq);
		if (delta <= 0)
			return false; // stale — already covered by state or snapshot
		if (needsResync)
			return false; // unreliable state; wait for the snapshot
		if (delta > 1) {
			needsResync = true;
			return false;
		}
		if (batch.deltas.isEmpty()) {
			// The server never seals an empty batch; an in-order empty batch is a protocol
			// anomaly (e.g. a heartbeat wrongly encoded as a batch would silently swallow a
			// lost batch's seq here). Heartbeats must use observeSeq, never applyBatch.
			needsResync = true;
			return false;
		}
		boolean clean = true;
		for (Delta d : batch.deltas) {
			try {
				if (d instanceof Delta.NodeProps) {
					Delta.NodeProps np = (Delta.NodeProps) d;
					if ((np.mask & V2Wire.PROP_TELEPORT) != 0) {
						teleported.add(Integer.valueOf(np.nodeId));
					}
				}
				DeltaApplier.apply(state, d);
			} catch (Exception e) {
				// Unknown id / mismatch: state is unreliable from here — resync overwrites.
				needsResync = true;
				clean = false;
				break;
			}
		}
		lastSeq = batch.seq;
		lastServerTick = batch.serverTick;
		if (clean) {
			dirty = true;
		}
		return clean;
	}

	/**
	 * Seq-only probe for idle heartbeats and the "you are at seq N" re-entry check: flags
	 * resync when the server is ahead, applies nothing, never advances lastSeq. An epoch
	 * mismatch hard-resets and flags resync unconditionally (nothing of the old incarnation
	 * is trustworthy, and the new one must be fetched).
	 *
	 * A same-epoch heartbeat carrying a seq strictly BEHIND lastSeq is impossible in a
	 * healthy incarnation under the per-watcher FIFO transport contract (the mirror only
	 * ever learned seqs the server had already passed) — it is proof of a divergent restore
	 * (crash-without-save, live NBT rollback, epoch collision). Hard reset and resync, so
	 * the restored incarnation's snapshot installs instead of being stale-discarded forever.
	 */
	public void observeSeq(int epoch, int serverSeq) {
		boolean mismatch = knownEpoch != 0 && knownEpoch != epoch;
		adoptEpoch(epoch);
		if (mismatch) {
			needsResync = true;
			return;
		}
		int delta = V2Wire.seqDelta(serverSeq, lastSeq);
		if (delta > 0) {
			needsResync = true;
		} else if (delta < 0) {
			hardReset();
			needsResync = true;
		}
	}

	/**
	 * Validated delivery of a pending texture body — the only sanctioned way bytes reach a
	 * mirror. Rejects unknown ids (freed mid-transfer: the free cancels the transfer),
	 * non-textures, wrong lengths, and hash mismatches (caller should re-request).
	 */
	public boolean deliverResourceBody(int epoch, int resId, int version, long hash, byte[] bytes) {
		// I-6: never install while the state is unreliable. `latestVersion` is only a
		// trustworthy acceptance key on a mirror that has not missed deltas; a content hash
		// used to be self-certifying regardless of mirror health, and no longer is.
		if (needsResync || epoch != knownEpoch)
			return false;
		ResourceInfo res = state.resources.get(resId);
		if (res == null || res.type != V2Wire.RES_TEXTURE || bytes == null)
			return false;
		if (bytes.length != res.sizeBytes || V2Wire.contentHash(bytes) != hash)
			return false;
		// The body must be the version we believe is current. A body older than what the
		// delta stream already told us about would silently roll the texture back; a newer
		// one means we missed a write, which is a divergence we must not paper over.
		//
		// REJECTING IS THE WHOLE FIX — this deliberately does NOT flag needsResync, and an
		// audit has already read the missing flag as a stuck-mirror bug once, so the recovery
		// is worth naming here rather than leaving a reader to find it in another file.
		// MirrorClient.requestPendingBodies is LEVEL-triggered on ResourceInfo.needsBody()
		// (bytes == null || version != latestVersion) and re-sends on a retry cadence, so a
		// rejected body is simply re-fetched — no resync needed for either direction:
		//
		//   version < latestVersion — a late response to an earlier request. needsBody() stays
		//     true, the retry re-asks, and the mirror renders its stale content meanwhile.
		//   version > latestVersion — a body response that overtook the batch announcing it.
		//     Genuinely MISSING that write is impossible without a batch gap, and a gap latches
		//     needsResync, which the first check above already rejects on. So this is transient:
		//     the batch lands, latestVersion moves, needsBody() flips true, the body is re-asked.
		//
		// Escalating to a resync here would trade a cheap re-fetch for a full snapshot on what
		// is usually just packet ordering.
		if (version != res.latestVersion)
			return false;
		res.bytes = bytes.clone();
		res.version = version;
		res.knownHash = hash;
		res.knownHashVersion = version;
		res.markFullDirty();
		dirty = true;
		return true;
	}

	/**
	 * Stale snapshots (a delayed response to an earlier request) are discarded — but only
	 * within the same incarnation: across an epoch change the stale rule is void (the new
	 * incarnation's seq may legitimately be behind the old one's).
	 */
	public void applySnapshot(SceneSnapshot snapshot) {
		if (!sceneId.equals(snapshot.sceneId))
			return;
		boolean sameEpoch = knownEpoch == snapshot.epoch;
		adoptEpoch(snapshot.epoch);
		if (sameEpoch && V2Wire.seqDelta(snapshot.seq, lastSeq) < 0)
			return; // keep needsResync latched; the retry cadence fetches a fresh one
		SceneState fresh = snapshot.state.copy();
		if (sameEpoch) {
			// Carry over bytes we already hold within the same incarnation: resource ids are
			// never reused, so bytes for (id, version) are still valid content. They land as
			// STALE if the snapshot names a newer version, which schedules exactly one refetch
			// instead of re-downloading every texture on every resync.
			for (ResourceInfo old : state.resources.values()) {
				if (old.bytes == null || old.version == 0)
					continue;
				ResourceInfo now = fresh.resources.get(old.id);
				if (now != null && now.type == V2Wire.RES_TEXTURE
						&& now.sizeBytes == old.sizeBytes && old.version <= now.latestVersion) {
					now.bytes = old.bytes;
					now.version = old.version;
					now.markFullDirty();
				}
			}
		}
		state = fresh;
		lastSeq = snapshot.seq;
		lastServerTick = snapshot.serverTick;
		// THE ANIMATOR CLOCK'S DOMAIN OFFSET, captured HERE and nowhere else, because a snapshot
		// is the only payload carrying both halves of the pair at one instant: its header's
		// serverTick and its state's worldTimeAnchor were stamped from the same server tick.
		//
		// It must not be recomputed later from live fields. `lastServerTick` advances with every
		// batch while `worldTimeAnchor` does not — no delta carries one — so
		// `lastServerTick - state.worldTimeAnchor` drifts by one per tick after the snapshot and
		// would slide every animator clock steadily out of true. Capturing once is what makes the
		// subtraction correct.
		//
		// One value for the whole session: world time and the server's tick counter both advance
		// at 20/s, so their difference is constant until the server restarts — and a restart
		// changes the epoch, which forces a hardReset and a fresh snapshot, which re-captures it.
		//
		// Anchor 0 means "the server never stamped this scene" — a pre-v8 save, or a scene whose
		// owner has not ticked yet. There is no offset to know then, and saying so is better than
		// publishing a number that is wrong by the whole magnitude of world time.
		sessionTickOffsetKnown = snapshot.state.worldTimeAnchor != 0L;
		sessionTickOffset = sessionTickOffsetKnown
				? snapshot.serverTick - snapshot.state.worldTimeAnchor
				: 0L;
		needsResync = false;
		dirty = true;
	}

	/**
	 * Whether {@link #sessionTickOf} can answer. False until a snapshot carrying a stamped anchor
	 * has been applied — a caller must not silently substitute 0, which would place every scene's
	 * epoch at the start of the session and start every animator from the wrong phase.
	 */
	public boolean animatorClockKnown() {
		return sessionTickOffsetKnown;
	}

	/**
	 * Convert a persisted WORLD-time stamp into the server-tick domain the render clock counts.
	 *
	 * This is the second half of the split {@code SceneState.worldTimeAnchor} describes: stamps
	 * are stored and replicated as world time so that every copy of them is the same quantity,
	 * and the receiver converts once, here, rather than the server pre-deriving values that would
	 * mean different things on the two sides.
	 *
	 * The result is a TICK count, which is what {@code OcslTime.time} expects alongside
	 * {@code ServerTimeline.renderNanos} — that method does the scaling to nanoseconds itself.
	 */
	public long sessionTickOf(long worldTimeStamp) {
		return worldTimeStamp + sessionTickOffset;
	}

	/**
	 * The raw offset {@link #sessionTickOf} adds. For the render-side animator evaluator, which
	 * converts one attach stamp per attached node plus the scene epoch every frame, and takes
	 * primitives so it stays testable without a mirror. Meaningless while
	 * {@link #animatorClockKnown} is false — a caller must check, for the reason that method's
	 * javadoc gives.
	 */
	public long sessionTickOffset() {
		return sessionTickOffset;
	}
}
