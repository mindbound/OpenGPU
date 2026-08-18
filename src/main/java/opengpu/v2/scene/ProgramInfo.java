package opengpu.v2.scene;

import java.util.Arrays;

/**
 * A validated OCSL program stored on the scene: an id, the stage it was authored for, and the
 * blob.
 *
 * DELIBERATELY NOT A {@link ResourceInfo}. That was the alternative considered and rejected at
 * build time (DESIGN, "Program storage"), on three counts the code makes concrete:
 *
 * <ul>
 * <li>The reuse is not there. Resource persistence and client body serving are TEXTURE-ONLY
 *     filters ({@code ScenePersistence.writeBodies}/{@code restore}, {@code SceneHost}'s
 *     resource-request path), so a third resource type inherits nothing and widens three
 *     filters.</li>
 * <li>A program is IMMUTABLE — validated once at create, never written — while ResourceInfo's
 *     whole identity model (version / latestVersion / knownHash) exists to track MUTATION. Those
 *     fields would sit pinned at 1 forever, and a reader would reasonably wonder what a
 *     program's "version 3" meant.</li>
 * <li>Resources charge the VRAM budget, which means "what every client in subscribe range must
 *     hold". Code is not pixels; a program must not compete with textures for it.</li>
 * </ul>
 *
 * IMMUTABLE BY CONSTRUCTION: every field is final and {@link #bytes} is never handed out
 * unwrapped. That is what lets copies share the array instead of cloning it — the blob is the
 * largest thing here (up to 64 KiB, the codec ceiling; the OP cap does not bound bytes, because
 * the constant pool is uncharged), and a scene copy happens on every snapshot.
 */
public final class ProgramInfo {
	public final int id;
	/** The OCSL stage byte the blob declares; the attach path checks it against the target. */
	public final byte stage;
	/**
	 * The validated blob, exactly as it will be replayed. NOT exposed directly — see
	 * {@link #blobCopy()} — because a caller that mutated it would silently desynchronise every
	 * mirror holding the same array.
	 */
	private final byte[] bytes;
	/** Charged against the scene's program ledger; equals {@code bytes.length}. */
	public final int sizeBytes;
	/**
	 * The validator's post-unroll op charge, carried so diagnostics and the future per-stage cap
	 * review (ANIM-16) can report what real programs cost without re-validating.
	 */
	public final int structuralOps;

	public ProgramInfo(int id, byte stage, byte[] bytes, int structuralOps) {
		this.id = id;
		this.stage = stage;
		this.bytes = bytes;
		this.sizeBytes = bytes.length;
		this.structuralOps = structuralOps;
	}

	/**
	 * A defensive copy, and the ONLY way the bytes leave this object — including for the codec,
	 * which pays one array copy per program per snapshot rather than being handed the live
	 * array. Even at the 64 KiB blob ceiling that is not a cost worth a package-private back
	 * door: the immutability is what lets copy() share instances at all.
	 */
	public byte[] blobCopy() {
		return Arrays.copyOf(bytes, bytes.length);
	}

	/** Length without copying, for accounting and codec sizing. */
	public int blobLength() {
		return bytes.length;
	}

	/**
	 * Shares the byte array, which is safe precisely because this class is immutable. If a
	 * mutable field is ever added here, this must become a deep copy — stated at the point a
	 * future edit would break it rather than in a design doc nobody re-reads.
	 */
	public ProgramInfo copy() {
		return this;
	}

	public boolean contentEquals(ProgramInfo other) {
		return other != null
				&& id == other.id
				&& stage == other.stage
				&& structuralOps == other.structuralOps
				&& Arrays.equals(bytes, other.bytes);
	}
}
