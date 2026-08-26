package opengpu.v2.ocsl;

import java.util.Arrays;

/**
 * Where the animator surface reports a rejected write — ANIM-9(d).
 *
 * <h2>The unit was wrong, and the wrong unit under-reports</h2>
 *
 * The design said "logged once per program". <b>A curated preset is one program id across many
 * nodes</b>, so the first failing node consumed the only line and every other node's failure was
 * invisible for the rest of the session — which is the opposite of what a diagnostic is for. The
 * unit is now <b>once per (program, node)</b>, with a global rate cap so the finer key cannot flood
 * a 200-node scene's log at frame rate.
 *
 * <h2>A sink, because this package is Minecraft-free</h2>
 *
 * Copied deliberately from {@code opengpu.v2.font.FontDiagnostics}, which exists for exactly this
 * reason and learned it the hard way: reaching for {@code OpenGPU.logger} directly makes the "no
 * Minecraft" claim false, and that static is assigned during preInit, so any path reached before
 * then throws NullPointerException from inside the degraded route meant to keep things running. The
 * OCSL package is tested headless and must stay that way. Deliberately tiny — this is not a logging
 * framework.
 *
 * <h2>{@link Reporter#rejectedWrite} returns void, and that is load-bearing</h2>
 *
 * Every other value decision in ANIM-9 is stateless precisely so two clients cannot disagree. This
 * one cannot be: deduplication is memory of what has already been said. Returning {@code void} is
 * what keeps that state out of the frame — <b>no displayed value can depend on whether a line was
 * printed</b>, because there is nothing to branch on. A {@code boolean} return would be one
 * refactor away from reintroducing the per-client divergence ANIM-9(a) removed.
 */
public final class OcslDiagnostics {

	/** Somewhere to send a message. */
	public interface Sink {
		void warn(String message);
	}

	/** Named rather than inline so {@link #setSink} can actually restore it. */
	private static final Sink DEFAULT = new Sink() {
		@Override
		public void warn(String message) {
			System.err.println("[OpenGPU:ocsl] WARN " + message);
		}
	};

	private static volatile Sink sink = DEFAULT;

	private OcslDiagnostics() {}

	/**
	 * Install the real logger; passing null restores the stderr default rather than silencing.
	 *
	 * The restore matters for the same reason it does in the font package: the suite runs in ONE JVM
	 * (no {@code forkEvery} in build.gradle.kts), so a capturing sink installed by one test class
	 * would otherwise leak into every class that ran after it.
	 */
	public static void setSink(Sink replacement) {
		sink = replacement != null ? replacement : DEFAULT;
	}

	static void warn(String message) {
		sink.warn(message);
	}

	/**
	 * A program declared uniforms that nothing can bind — the SILENT half of the uniform gap.
	 *
	 * <h2>Why this is a diagnostic and not a refusal</h2>
	 *
	 * Uniforms are a designed surface: {@code OcslIngress} specifies a replicated table, and
	 * DESIGN promises "uniform sets by name per attachment". The table and its set-call now
	 * exist (C1.1/C1.2) — what is still not built is the client BINDING, so a declared uniform
	 * still reads 0.0 on every evaluation, with nothing said, until C1.3 lands. The tempting
	 * fix — have the validator refuse such programs — is wrong twice over: it is a TIGHTENING,
	 * so it would reject blobs already persisted in a save, and it would have to be deleted the
	 * day the binding lands. A line of text has neither cost. It also carries no format impact
	 * and no acceptance change, which is the property that made it safe to ship when it did.
	 *
	 * <h2>Stateless, unlike {@link Reporter}</h2>
	 *
	 * No dedup table and no rate cap here, because this one is not per-node and not per-frame:
	 * the caller emits it on the 0 → 1 transition of
	 * {@code RenderStats.animatorProgramsWithUniforms}, so it is once per COUNTER WINDOW — NOT
	 * once per client session, which is what this javadoc claimed when it shipped. The stats
	 * overlay's SHIFT+toggle calls {@code RenderStats.reset()}, which clears the latch along
	 * with the count and re-arms this line for the next program compiled after it. That is the
	 * same window every other row on that overlay reports over, so it is consistent rather than
	 * anomalous; it is simply not "per session".
	 *
	 * Putting a second latch in this class would mean two pieces of state that must be reset
	 * together and will eventually not be — but note the house has priced that trade the other
	 * way before, in {@code SceneRenderer}'s two told-flags, whose comment records that reusing
	 * shared state made "the second failure anywhere in a session silent forever". If a genuine
	 * once-per-session line is ever wanted, that is the shape, not a reworded comment.
	 *
	 * @param programId     the scene's program id, so the author can find the blob
	 * @param uniformCount  the DECLARED count. Declared rather than read: the validator counts
	 *        declarations and does not track which uniforms an op reads, so "declares" is the
	 *        strongest true statement available here.
	 */
	public static void uniformsWithNothingToBindThem(int programId, int uniformCount) {
		warn("animator program " + programId + " declares " + uniformCount + " uniform"
				+ (uniformCount == 1 ? "" : "s") + " and nothing can bind one yet, so"
				+ " every read of them evaluates to 0.0. This is a KNOWN GAP in OpenGPU -- the\n"
				+ "  uniform table and its setUniform call exist (API 9), but nothing binds a\n"
				+ "  table entry to a program register yet; the client half lands at C1.3 -- and\n"
				+ "  it is NOT a fault in the program. Further such programs are counted on the\n"
				+ "  OpenGPU stats overlay's animator line -- bind \"Toggle render stats overlay\"\n"
				+ "  under Controls -> OpenGPU -- rather than logged here.");
	}

	/**
	 * The per-client dedup and rate state. One instance per client runtime; NOT thread-safe by
	 * contract, because it is touched from the render thread only and a lock there would cost more
	 * than the diagnostic is worth.
	 */
	public static final class Reporter {

		/** Lines admitted before the rate cap bites — enough to see a scene-load storm's shape. */
		public static final int BURST = 16;

		/** One further line per this interval, so a permanently broken scene costs ~6/minute. */
		public static final long REFILL_NANOS = 10L * 1_000_000_000L;

		/** Distinct (program, node) pairs remembered at once. */
		public static final int MAX_TRACKED = 256;

		/** Power of two and 2x MAX_TRACKED, so the table stays half-empty and probes stay short. */
		private static final int SLOTS = 512;
		private static final int MASK = SLOTS - 1;

		private final long[] programs = new long[SLOTS];
		private final int[] nodes = new int[SLOTS];
		private final boolean[] used = new boolean[SLOTS];
		private int tracked;

		private int tokens = BURST;
		private long creditNanos;
		private long lastNanos;
		private boolean started;

		/**
		 * Report that an animator write was rejected, at most once per (program, node).
		 *
		 * The message is BUILT ONLY IF IT IS EMITTED. Taking a pre-formatted string would allocate on
		 * the suppressed path too — which is every frame, for every failing node, on the render
		 * thread — and suppression is the common case by design.
		 *
		 * @param nowNanos a monotonic reading, passed in rather than read here, so the policy is a
		 *        pure function of its inputs and testable without a clock — the same discipline
		 *        {@link OcslTime#time} follows.
		 */
		public void rejectedWrite(long programHash, int nodeId, int propertyId, float offending,
				long nowNanos) {
			refill(nowNanos);
			if (alreadyReported(programHash, nodeId)) {
				return;
			}
			// THE RATE CHECK COMES BEFORE THE RECORD, and the order is the rule. Recording first
			// would let a scene-load burst consume dedup slots for pairs that never produced a line,
			// so those failures would be silent forever -- the exact under-reporting this amendment
			// exists to fix, reintroduced by a different mechanism.
			if (tokens <= 0) {
				return;
			}
			tokens--;
			record(programHash, nodeId);

			String property = SurfaceTable.propertyName(OcslWire.STAGE_ANIMATOR, propertyId);
			OcslDiagnostics.warn("animator write rejected: " + (property != null ? property
					: "property " + propertyId) + " produced " + offending
					+ ", so this frame shows the server base (program "
					+ Long.toHexString(programHash) + ", node " + nodeId + ")");
		}

		/** Forget everything — a scene reload, so a player who fixed their program hears about it. */
		public void reset() {
			Arrays.fill(used, false);
			tracked = 0;
			tokens = BURST;
			creditNanos = 0L;
			started = false;
		}

		/** Tokens currently available. Exposed for tests; nothing displayed may read it. */
		public int availableTokens() {
			return tokens;
		}

		private void refill(long nowNanos) {
			if (!started) {
				started = true;
				lastNanos = nowNanos;
				return;
			}
			if (nowNanos <= lastNanos) {
				// BACKWARD OR STALLED, and clamping the delta to zero was NOT enough -- the test
				// caught it. After a rewind, moving lastNanos back means catching up to a reading
				// already counted credits that span a second time: a clock nudged 100s -> 50s -> 99s
				// minted four tokens for fifty seconds that had already been spent. lastNanos is a
				// HIGH-WATER MARK, so a correction mints nothing until the clock passes where it had
				// already been -- the same re-base ServerTimeline applies to a backward tick.
				return;
			}
			long delta = nowNanos - lastNanos;
			lastNanos = nowNanos;
			creditNanos += delta;
			while (creditNanos >= REFILL_NANOS && tokens < BURST) {
				creditNanos -= REFILL_NANOS;
				tokens++;
			}
			if (tokens >= BURST) {
				// Banking credit at a full bucket would let a quiet hour mint a burst the moment
				// something goes wrong -- the cap would hold on paper and not in the log.
				creditNanos = 0L;
			}
		}

		private boolean alreadyReported(long programHash, int nodeId) {
			int i = slot(programHash, nodeId);
			// BOUNDED, because an unbounded linear probe over a full table does not degrade -- it
			// spins forever, on the render thread. `tracked` cannot exceed MAX_TRACKED and the table
			// is twice that, so this can only fire if that invariant is ever broken; a hang is far
			// too expensive a way to find out.
			for (int probes = 0; probes < SLOTS && used[i]; probes++) {
				if (programs[i] == programHash && nodes[i] == nodeId) {
					return true;
				}
				i = (i + 1) & MASK;
			}
			return false;
		}

		private void record(long programHash, int nodeId) {
			if (tracked >= MAX_TRACKED) {
				// CLEARED rather than frozen. Node ids churn over a session, so "remember every pair
				// forever" is unbounded; refusing new keys instead would make a genuinely new failure
				// silent for the rest of the session, which is the under-reporting being fixed here.
				// Repeats after a clear are bounded by the rate cap, which is the real flood control.
				Arrays.fill(used, false);
				tracked = 0;
			}
			int i = slot(programHash, nodeId);
			for (int probes = 0; probes < SLOTS && used[i]; probes++) {
				i = (i + 1) & MASK;
			}
			if (used[i]) {
				// Only reachable if `tracked` ever stopped matching the table. Clearing is the same
				// remedy as a full table and keeps the invariant true from here on, where writing
				// over an occupied slot would silently lose a pair's dedup record.
				Arrays.fill(used, false);
				tracked = 0;
				i = slot(programHash, nodeId);
			}
			used[i] = true;
			programs[i] = programHash;
			nodes[i] = nodeId;
			tracked++;
		}

		/**
		 * Both halves of the key participate, and the pair is compared EXACTLY at every probe.
		 *
		 * Folding the pair into one number would let a collision suppress a distinct node's failure
		 * while looking exactly like correct deduplication — a diagnostic that lies about coverage is
		 * worse than one that repeats itself.
		 *
		 * PACKAGE-PRIVATE ON PURPOSE. Weakening the probe to {@code ||} is invisible to any test that
		 * does not force two keys into the same slot, and a mutation that says so survived a sweep
		 * proving it. The test finds a colliding pair by searching this function, and asserts it
		 * found one — a vector that silently stopped colliding would otherwise pass while testing
		 * nothing.
		 */
		static int slot(long programHash, int nodeId) {
			int h = (int) (programHash ^ (programHash >>> 32)) * 31 + nodeId;
			h ^= h >>> 16;
			return h & MASK;
		}
	}
}
