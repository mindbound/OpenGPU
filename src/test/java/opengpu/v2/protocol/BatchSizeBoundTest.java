package opengpu.v2.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The decoder's ceiling must be guaranteed by what the PRODUCER can admit, not merely checked
 * when a batch arrives.
 *
 * V2Wire's comment above the caps says an over-cap payload should be impossible to produce rather
 * than a decode-time surprise. That held for each cap on its own and failed on their product: the
 * delta count was capped and the payload bytes were capped, but nothing bounded
 * {@code MAX_DELTAS x widest delta} against {@link BatchCodec#MAX_INFLATED_BYTES}. At the old
 * 1&lt;&lt;16 the worst case reached 5.6 MB against a 4 MiB ceiling, so the server could produce a
 * batch that every client refuses whole.
 *
 * That failure mode is worth naming because it is not a crash. A refused batch is simply not
 * applied: the server advances, no mirror does, and the convergence tests cannot see it because
 * they only ever compare two states that both exist. In game it would look like every client's
 * scene quietly stopping.
 *
 * <h2>Why arithmetic and not a runtime check</h2>
 * A seal-time byte check has nothing useful to do when it trips — throwing crashes the server
 * pump, and sending anyway ships a batch already known to be undecodable. So the bound is
 * enforced by choosing caps whose product cannot exceed the ceiling, and this test is what keeps
 * that arithmetic from rotting. Raising MAX_DELTAS, adding a prop bit (which widens NodeProps),
 * or raising a per-batch payload allowance each fail here, next to the reasoning.
 *
 * <h2>What is excluded, and why that is safe</h2>
 * SceneProp carries a {@code byte[]} bounded only by MAX_SCENE_PROP_PAYLOAD (1 MiB) and is left
 * out of the budget below. That is sound only because nothing on the server constructs one — the
 * type exists purely as a decode target, built in a single place in BatchCodec's decoder. Adding
 * a producer for it invalidates this whole calculation; such a producer needs a per-batch
 * allowance of its own, the way TextureWrite and CanvasPublish have one.
 */
public class BatchSizeBoundTest {

	/**
	 * Encoded size of the widest delta a server can produce, derived rather than quoted.
	 *
	 * NodeProps: 1 type byte (written by encodeRaw) + 4 nodeId + 4 mask + 8 per set property.
	 * Every other producible delta is smaller at a fixed size (NodeCreate 14 since v5 appended
	 * `parent` to it — still far under the bound, which derives from NodeProps; ResourceCreate 30,
	 * the frees 5) or carries a payload already bounded by its own per-batch allowance —
	 * CanvasPublish and CanvasAppend by MAX_SUBMIT_BYTES_PER_BATCH, TextureWrite by
	 * MAX_WRITE_BYTES_PER_BATCH — both of which are added to the budget once, below.
	 */
	private static int widestProducibleDelta() {
		return 1 + 4 + 4 + 8 * Integer.bitCount(V2Wire.KNOWN_PROPS_MASK);
	}

	/**
	 * Batch header, from BatchCodec.encodeRaw: short version, UTF sceneId, int epoch, int seq,
	 * long tick, int count. sceneId is a UUID string in practice; it is budgeted at writeUTF's
	 * absolute maximum instead so this bound does not quietly depend on that.
	 */
	private static final int HEADER_BYTES = 2 + (2 + 65535) + 4 + 4 + 8 + 4;

	private static long worstCaseBatchBytes() {
		return (long) HEADER_BYTES
				+ (long) V2Wire.MAX_DELTAS * widestProducibleDelta()
				+ V2Wire.MAX_WRITE_BYTES_PER_BATCH
				+ V2Wire.MAX_SUBMIT_BYTES_PER_BATCH;
	}

	@Test
	public void theWidestDeltaIsWhatWeThinkItIs() {
		// Asserted as a shape, not just a number, so a new prop bit says what it broke.
		assertEquals("KNOWN_PROPS_MASK should be 9 bits (x, y, rot, sx, sy, z, visible, tint,"
				+ " teleport). A tenth widens NodeProps by 8 bytes — re-check the budget in"
				+ " aMaximalBatchCannotExceedTheDecoderCeiling before changing this number.",
				9, Integer.bitCount(V2Wire.KNOWN_PROPS_MASK));
		assertEquals("a full-mask NodeProps encodes to 81 bytes", 81, widestProducibleDelta());
	}

	@Test
	public void aMaximalBatchCannotExceedTheDecoderCeiling() {
		assertTrue("A batch of MAX_DELTAS widest deltas plus both payload allowances encodes to "
				+ worstCaseBatchBytes() + " B, over BatchCodec.MAX_INFLATED_BYTES of "
				+ BatchCodec.MAX_INFLATED_BYTES + " B. The server would produce a batch every"
				+ " client refuses whole — a silent desync, not a crash. Lower MAX_DELTAS, or"
				+ " raise the ceiling deliberately and record why here.",
				worstCaseBatchBytes() <= BatchCodec.MAX_INFLATED_BYTES);
	}

	/**
	 * The other side of the same bound, and the reason this cap cannot simply be driven down.
	 *
	 * ServerScene.sealBatch throws IllegalStateException when a tick stages more than MAX_DELTAS.
	 * So the cap is squeezed from both directions: too high and a batch encodes past the decoder
	 * ceiling (silent desync), too low and an ordinary busy tick crashes the pump. Only the upper
	 * bound was ever checked. This is the lower one.
	 *
	 * A node contributes at most a couple of deltas in a tick — a props update, or a create plus
	 * its initial props — so a pathological rewrite of every node in a scene lands near
	 * 2 x MAX_NODES. Requiring 4x that keeps the throw out of reach of anything a program can
	 * actually stage; for scale, the heaviest bench2 run moved 143 B/tick with a 24,662 B
	 * largest batch.
	 *
	 * Together with the upper bound this leaves a window of [32768, 47331], and 1&lt;&lt;15 is the
	 * only power of two inside it: 1&lt;&lt;14 crashes the pump on a busy tick, 1&lt;&lt;16 exceeds the
	 * decoder. The current value is derived, not chosen.
	 */
	@Test
	public void theCapStaysClearOfWhatABusyTickCanStage() {
		int pathologicalTick = 2 * opengpu.v2.scene.ServerScene.MAX_NODES;
		assertTrue("MAX_DELTAS is " + V2Wire.MAX_DELTAS + ", within 4x of the " + pathologicalTick
				+ " deltas a full-scene rewrite can stage (2 per node over MAX_NODES). Below that"
				+ " margin, ServerScene.sealBatch's IllegalStateException stops being unreachable"
				+ " and an ordinary busy tick can crash the pump.",
				V2Wire.MAX_DELTAS >= 4 * pathologicalTick);
	}

	/**
	 * Fails before the hard bound does. A budget sitting at 99% is one prop bit from breaking and
	 * says nothing until it has; this is the margin that makes the failure arrive early enough to
	 * be a design question rather than a field report.
	 */
	@Test
	public void theBoundKeepsItsHeadroom() {
		long pct = worstCaseBatchBytes() * 100 / BatchCodec.MAX_INFLATED_BYTES;
		assertTrue("the worst-case batch is " + pct + "% of the decoder ceiling; this bound is"
				+ " meant to carry margin, so keep it under 90%", pct < 90);
	}
}
