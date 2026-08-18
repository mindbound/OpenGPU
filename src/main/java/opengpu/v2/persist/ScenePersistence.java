package opengpu.v2.persist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.LegacyStructureCodec;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.ServerScene;

/**
 * Scene persistence: structure through the snapshot codec, bodies through the
 * {@link ResourceStore}.
 *
 * The persisted STRUCTURE format IS the snapshot wire format ({@link SnapshotCodec}): one
 * codec, two duties — epoch, seq, tick, id counters, resource manifests, canvas command
 * lists (restored through the canonical publish() path), nodes, and (v6) the OCSL program
 * table, blobs inline; texture bytes deliberately absent. Consequence: the persistence
 * format is versioned by PROTOCOL_VERSION; a protocol bump is also a save-migration point
 * (the appended-section policy in SnapshotCodec is what keeps bumps cheap; note the OCSL
 * blob format froze separately at 1 when v6 began persisting programs).
 *
 * Save flow (component layer, under the scene lock): call {@code SceneHost.saveBoundary()}
 * first — seal AND broadcast, never seal-and-discard (staged deltas are already applied to
 * server state; discarding the sealed batch silently desyncs every mirror) — then
 * {@link #persistStructure} (returns inline bytes for TE NBT, or spills to the store when
 * over {@link #STRUCTURE_NBT_CEILING}) and {@link #writeBodies}. Restore flow:
 * {@link #resolveStructure} + {@link #restore} (or the recommended
 * {@link #restoreOrFresh}), which re-attaches bodies with hash validation, degrades
 * missing/corrupt bodies to blank (warning, never a crash), prunes orphaned store entries —
 * and, when anything degraded, MINTS A FRESH EPOCH: a degraded restore is by definition a
 * divergent one, and surviving mirrors must hard-reset rather than silently keep the old
 * bytes.
 */
public final class ScenePersistence {
	private ScenePersistence() {}

	/** Structure above this must spill out-of-band (the S-02 chunk-NBT ceiling). */
	public static final int STRUCTURE_NBT_CEILING = 64 * 1024;

	public static final class RestoreResult {
		public final ServerScene scene;
		public final List<String> warnings;

		RestoreResult(ServerScene scene, List<String> warnings) {
			this.scene = scene;
			this.warnings = warnings;
		}
	}

	/** Encode structure (no bodies). Requires a batch boundary, like snapshot(). */
	public static byte[] encodeStructure(ServerScene scene) {
		return SnapshotCodec.encode(scene.snapshot());
	}

	/**
	 * Encode and place the structure: returns the bytes to inline into TE NBT when they fit
	 * under {@link #STRUCTURE_NBT_CEILING}, else spills them to the store and returns null
	 * (the TE NBT then records only a spill marker).
	 */
	public static byte[] persistStructure(ServerScene scene, ResourceStore store) {
		byte[] structure = encodeStructure(scene);
		if (structure.length <= STRUCTURE_NBT_CEILING) {
			return structure;
		}
		store.saveStructure(scene.sceneId, structure);
		return null;
	}

	/** Resolves the structure bytes from inline NBT bytes or the store's spill slot. */
	public static byte[] resolveStructure(String sceneId, byte[] inlineOrNull, ResourceStore store) {
		return inlineOrNull != null ? inlineOrNull : store.loadStructure(sceneId);
	}

	/**
	 * Persists texture bodies whose content has changed since the last save. Bodies are NO
	 * LONGER immutable (writeRegion mutates them in place), so "already stored" is not a
	 * reason to skip — {@code persistedVersion} is. Degraded bodies are always rewritten so
	 * the on-disk corrupt bytes converge to the blank body the scene actually holds.
	 */
	/**
	 * Persist changed texture bodies as self-describing blobs.
	 *
	 * Bodies are no longer immutable, so "already stored" is not a reason to skip — the
	 * version is. {@code persistedVersion} means an unchanged texture still costs nothing,
	 * while a written one is rewritten exactly once per save.
	 */
	public static void writeBodies(ServerScene scene, ResourceStore store) {
		for (ResourceInfo res : scene.state().resources.values()) {
			if (res.type != V2Wire.RES_TEXTURE || res.bytes == null)
				continue;
			boolean stale = res.persistedVersion != res.version
					|| !store.contains(scene.sceneId, res.id);
			if (!stale && !res.degraded)
				continue;
			// Reuse the hash if a body serve already computed it for this version.
			long hash = res.knownHashVersion == res.version
					? res.knownHash : V2Wire.contentHash(res.bytes);
			if (res.knownHashVersion != res.version) {
				res.knownHash = hash;
				res.knownHashVersion = res.version;
				scene.bumpManifestGen();
			}
			store.save(scene.sceneId, res.id, frameBody(res.version, hash, res.bytes));
			res.persistedVersion = res.version;
		}
	}

	/**
	 * Self-describing body blob: magic + format + version + hash + length + payload.
	 *
	 * The structure and the bodies live in different files written at different times — the
	 * TE's NBT is deferred by Minecraft's threaded IO while bodies are fsync'd immediately —
	 * so after a hard kill they can disagree. A blob that validates against ITSELF stays
	 * usable regardless, which is what stops a stale manifest hash from blanking every
	 * texture the player had written.
	 */
	static byte[] frameBody(int version, long hash, byte[] payload) {
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(22 + payload.length);
		buf.putInt(V2Wire.PERSIST_BODY_MAGIC);
		buf.putShort(V2Wire.PERSIST_BODY_FORMAT);
		buf.putInt(version);
		buf.putLong(hash);
		buf.putInt(payload.length);
		buf.put(payload);
		return buf.array();
	}

	/** Decoded blob, or null when the bytes are not a valid self-describing body. */
	static ParsedBody parseBody(byte[] blob) {
		if (blob == null || blob.length < 22)
			return null;
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(blob);
		if (buf.getInt() != V2Wire.PERSIST_BODY_MAGIC)
			return null;
		if (buf.getShort() != V2Wire.PERSIST_BODY_FORMAT)
			return null;
		int version = buf.getInt();
		long hash = buf.getLong();
		int len = buf.getInt();
		if (version < 1 || len < 0 || len != blob.length - 22)
			return null;
		byte[] payload = new byte[len];
		buf.get(payload);
		if (V2Wire.contentHash(payload) != hash)
			return null;
		return new ParsedBody(version, hash, payload);
	}

	static final class ParsedBody {
		final int version;
		final long hash;
		final byte[] payload;

		ParsedBody(int version, long hash, byte[] payload) {
			this.version = version;
			this.hash = hash;
			this.payload = payload;
		}
	}

	/**
	 * Rebuilds a scene from persisted structure + store bodies. Never throws for damaged
	 * BODIES — missing or hash-mismatched bytes become blank bodies with a recomputed hash
	 * and the degraded flag, reported in warnings, and the rebuilt scene gets a FRESH epoch
	 * (divergent restore). A clean restore continues the persisted epoch (the incarnation
	 * continues; surviving mirrors keep their seq discipline). Orphaned store entries are
	 * deleted.
	 *
	 * @throws CodecException when the STRUCTURE itself is unreadable — the caller decides
	 *         whether that means a fresh scene or a hard error ({@link #restoreOrFresh} is
	 *         the recommended chunk-load policy).
	 */
	public static RestoreResult restore(byte[] structure, ResourceStore store) throws CodecException {
		// Dispatch on the persisted version BEFORE attempting a current-version decode. Doing
		// this from inside a catch would be catastrophic: restoreOrFresh answers a
		// CodecException by deleting the scene's stored bodies, so every pre-upgrade world
		// would lose its textures the first time it loaded.
		//
		// THREE KINDS OF OLD SAVE, and they need different handling. Which kind a future bump
		// produces is the question the extension policy makes it answer; this is the call site
		// that acts on the answer:
		//   v2  — a genuinely different layout (one content hash where v3 carries three
		//         fields), so it needs its own decoder. That is what a MOVED field costs.
		//   v3  — the same layout under an older version number, so decodePersisted reads it
		//         directly. The 3 -> 4 bump appended an op and moved no field.
		//   v4  — the same layout MINUS a field appended after it. decodePersisted reads it too,
		//         but only because its node loop gates the v5 `parent` read on the version, so a
		//         v4 record is consumed at its own 58-byte width. Nothing here dispatches on that
		//         — the gate is inside the decoder — which is exactly why it is worth naming at
		//         the one place that chooses a decoder.
		// decodePersisted, not decode: the strict check belongs on the NETWORK path, where the
		// peer can be told to upgrade. Here, refusing means deleting the world's scenes.
		SceneSnapshot decoded =
				LegacyStructureCodec.peekVersion(structure) == LegacyStructureCodec.V2_VERSION
						? LegacyStructureCodec.decodeV2(structure)
						: SnapshotCodec.decodePersisted(structure);
		List<String> warnings = new ArrayList<String>();
		boolean degradedAny = false;

		for (Map.Entry<Integer, ResourceInfo> e : decoded.state.resources.entrySet()) {
			ResourceInfo res = e.getValue();
			if (res.type != V2Wire.RES_TEXTURE)
				continue;
			byte[] blob = store.load(decoded.sceneId, res.id);
			ParsedBody parsed = parseBody(blob);
			boolean legacyRaw = false;
			if (parsed == null && blob != null && blob.length == res.sizeBytes
					&& V2Wire.contentHash(blob) == res.knownHash) {
				legacyRaw = true;
				// Legacy (pre-v3) raw body: unframed, and validated against the manifest hash
				// exactly as v2 did. A framed blob is 22 + sizeBytes bytes and so can never
				// be mistaken for one. Re-framed on the next save.
				parsed = new ParsedBody(res.latestVersion, res.knownHash, blob);
			}
			if (parsed != null && parsed.payload.length == res.sizeBytes) {
				res.bytes = parsed.payload;
				res.version = parsed.version;
				res.knownHash = parsed.hash;
				res.knownHashVersion = parsed.version;
				// A legacy raw body is deliberately NOT marked persisted, so the next save
				// rewrites it in the self-describing frame. Marking it would mean the
				// migration shim could never be deleted.
				res.persistedVersion = legacyRaw ? 0 : parsed.version;
				// The blob is self-validating, so IT is the authority on which version the
				// bytes are. If the structure was written before the last body flush (or
				// after it, post-crash), reconcile rather than blanking usable content.
				if (parsed.version > res.latestVersion) {
					// The body is ahead of the structure: a crash between the (immediate)
					// body fsync and the (deferred) NBT write. The content is real, but
					// surviving mirrors were told about an older version, so this is a
					// divergent restore and must mint a fresh epoch like the rewind case.
					warnings.add("Resource " + res.id + " body is version " + parsed.version
							+ ", structure expected " + res.latestVersion
							+ "; adopting the newer body");
					res.latestVersion = parsed.version;
					degradedAny = true;
				} else if (parsed.version < res.latestVersion) {
					warnings.add("Resource " + res.id + " body is version " + parsed.version
							+ ", structure expected " + res.latestVersion
							+ "; keeping the body and rewinding");
					res.latestVersion = parsed.version;
					degradedAny = true; // mirrors must hard-reset: this is a divergent restore
				}
			} else {
				byte[] blank = new byte[res.sizeBytes];
				ResourceInfo replacement = new ResourceInfo(res.id, res.type, res.width,
						res.height, res.sizeBytes);
				replacement.bytes = blank;
				replacement.version = 1;
				replacement.latestVersion = 1;
				replacement.knownHash = V2Wire.contentHash(blank);
				replacement.knownHashVersion = 1;
				replacement.degraded = true;
				e.setValue(replacement);
				degradedAny = true;
				warnings.add("Resource " + res.id + " body "
						+ (blob == null ? "missing" : "failed validation")
						+ "; restored blank");
			}
		}

		for (int storedId : store.listResources(decoded.sceneId)) {
			if (!decoded.state.resources.containsKey(storedId)) {
				store.delete(decoded.sceneId, storedId);
				warnings.add("Deleted orphaned body " + storedId);
			}
		}

		int epoch = degradedAny ? ServerScene.mintEpoch() : decoded.epoch;
		ServerScene scene = new ServerScene(decoded.sceneId, decoded.seq, epoch, decoded.state);
		scene.setCurrentTick(decoded.serverTick);
		return new RestoreResult(scene, warnings);
	}

	/**
	 * The recommended chunk-load policy: restore, and on an unreadable structure fall back
	 * to a FRESH scene, with the failure recorded as a warning. Mirrors hard-reset correctly
	 * via the fresh scene's new epoch.
	 *
	 * THE DELETION RULE, tightened 2026-08-11 — read this before changing any branch below.
	 * Stored bodies are deleted ONLY on POSITIVE EVIDENCE that the structure is corrupt. Never
	 * because we merely failed to obtain one, and never because we are too old to read one.
	 * Leaking bodies wastes disk and is recoverable by hand; deleting them destroys the player's
	 * pictures and is not. Where the two compete, leak.
	 *
	 * What the old code did wrong, both halves reachable in normal play:
	 *
	 *   ABSENCE. A null structure went straight to deleteScene with an EMPTY warnings list — so a
	 *   brand-new GPU and a scene whose spilled structure had vanished were indistinguishable,
	 *   and the destructive one logged NOTHING. A spill is written out-of-band; a disk-full save,
	 *   an antivirus lock, or a restored-from-backup world can leave the bodies present and the
	 *   structure gone. That is the case where the bodies are most worth keeping, and it was the
	 *   case that deleted them silently. {@code structureExpected} is how the caller says "NBT
	 *   told me a structure exists" — see TileEntityGpu2.initScene, which had that bit and
	 *   discarded it.
	 *
	 *   THE FUTURE. SnapshotCodec throws for any version it does not know, which includes every
	 *   version ABOVE the current one. Rolling a jar back one release — an ordinary modpack
	 *   action after a crash — therefore deleted every scene's textures on first chunk load. The
	 *   backward direction has a compatible-versions array, a gated read and a bump checklist;
	 *   the forward direction had nothing at all. It is now peeked BEFORE the decode, using the
	 *   same peek-don't-catch discipline restore() already documents.
	 *
	 * KEPT MEANS ARCHIVED, NOT LEFT IN PLACE — this distinction is the whole correctness of the
	 * rule and the first version of this change got it wrong. A fresh scene restarts its resource
	 * ids at 1; body blobs carry no scene-incarnation marker; and {@link #restore} accepts a
	 * framed body on a LENGTH match alone (the hash cross-check exists only on the legacy-raw
	 * path). Bodies left in the live directory therefore attach silently to whatever the next
	 * incarnation creates at the same id with the same dimensions — the player's new texture
	 * comes back holding the old one's pixels, not degraded, not warned. Archiving moves them out
	 * of the id namespace, so "kept" cannot become "silently inherited".
	 *
	 * The claimed-version range is [2, PROTOCOL_VERSION] — v2 has its own decoder, 3 and 4 are
	 * layout-compatible, and PROTOCOL_VERSION is current. Anything OUTSIDE that range is a
	 * structure we do not claim to read, which includes both genuinely-newer saves and garbage:
	 * `peekVersion` returns a SIGNED short, so any first byte >= 0x80 peeks negative, and a
	 * zero-length file (the classic post-crash artifact) peeks -1. Testing only "above current"
	 * would have sent all of those to the delete branch while the javadoc claimed they leaked.
	 * We cannot distinguish corruption-that-looks-versioned from a real version — the structure
	 * format carries no magic number, unlike bodies — so everything unclaimed is archived.
	 */
	public static RestoreResult restoreOrFresh(String sceneId, byte[] structureOrNull, ResourceStore store) {
		return restoreOrFresh(sceneId, structureOrNull, false, store);
	}

	/** @param structureExpected the caller has positive reason to believe a structure exists. */
	public static RestoreResult restoreOrFresh(String sceneId, byte[] structureOrNull,
			boolean structureExpected, ResourceStore store) {
		List<String> warnings = new ArrayList<String>();
		if (structureOrNull == null) {
			// `structureExpected` alone is NOT sufficient: the caller derives it from the spill
			// marker, which is only set for structures over the 64 KiB inline ceiling — and
			// typical scenes are far under it. Losing an INLINE structure is the common case and
			// would have been silent. Stored bodies with no structure to reference them is the
			// signal that covers both, and it needs nothing from the caller.
			boolean somethingWasHere = structureExpected || !store.listResources(sceneId).isEmpty();
			if (somethingWasHere) {
				warnings.add(archived(sceneId, store, "a persisted structure was EXPECTED for this"
						+ " scene and none could be read"));
			}
			return new RestoreResult(new ServerScene(sceneId), warnings);
		}
		short version = LegacyStructureCodec.peekVersion(structureOrNull);
		boolean weClaimToReadIt = version >= LegacyStructureCodec.V2_VERSION
				&& version <= V2Wire.PROTOCOL_VERSION;
		if (!weClaimToReadIt) {
			warnings.add(archived(sceneId, store, "structure declares format v" + version
					+ ", which this build does not read (it reads v"
					+ LegacyStructureCodec.V2_VERSION + "-v" + V2Wire.PROTOCOL_VERSION
					+ "). A newer save, or a corrupt header"));
			return new RestoreResult(new ServerScene(sceneId), warnings);
		}
		try {
			return restore(structureOrNull, store);
		} catch (CodecException e) {
			// Positive evidence of corruption: a version we DO claim to read, that then does not
			// decode. This is the one branch that may delete.
			warnings.add("Structure unreadable (" + e.getMessage() + "); starting fresh");
			store.deleteScene(sceneId);
			return new RestoreResult(new ServerScene(sceneId), warnings);
		}
	}

	/**
	 * Archive the scene's bytes and phrase the warning around what actually happened.
	 *
	 * The message deliberately does NOT say "upgrade again and it will come back". It will not:
	 * the fresh scene's next chunk save rewrites the structure unconditionally, and vanilla
	 * autosaves loaded chunks every 900 ticks — so the window is under a minute of play, and any
	 * Lua draw marks the chunk dirty sooner. Telling a player to reinstall the newer jar later
	 * would be advice that quietly expires.
	 */
	private static String archived(String sceneId, ResourceStore store, String cause) {
		String where = store.archiveScene(sceneId);
		if (where == null) {
			return cause + "; starting fresh. Nothing was stored for this scene, so nothing"
					+ " was lost";
		}
		return cause + "; starting fresh. Its stored textures were MOVED ASIDE to \"" + where
				+ "\" rather than deleted — they are not referenced by anything now, and the"
				+ " next chunk save will overwrite this scene's structure, so recover them from"
				+ " there BEFORE continuing to play if they matter";
	}
}
