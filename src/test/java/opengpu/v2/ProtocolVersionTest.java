package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;

/**
 * Guards the coupling that makes "unknown ops cannot arrive" true.
 *
 * The decoder tests PROTOCOL_VERSION for strict equality and rejects unknown ops outright, so
 * a client of the wrong vintage discards a whole batch rather than misreading it — which is
 * what lets the renderer's default case assert that an unknown op is unreachable, and what
 * removed the need for any mixed-version fallback when fonts were added.
 *
 * That guarantee holds only while the version and the op table move together. Add an op
 * without bumping the version and it inverts: two builds share a version number, disagree
 * about the op table, and an old client decodes the new op's argument as some other op's
 * payload. Silent corruption instead of a clean rejection.
 *
 * This test cannot detect a future omission by itself — nothing can compare against a build
 * that does not exist yet. What it does is pin the current pairing so that adding an op
 * without touching the version fails HERE, next to a comment explaining what to do about it.
 *
 * <b>WIDENED 2026-08-28 (C1.3.2), because it was guarding one table out of three.</b> Every
 * word above was written about the canvas OP table, and the sweep below only ever walked
 * canvasOpArgCount. But the same argument holds verbatim for the other two id spaces the wire
 * validates against a whitelist — NODE TYPES and RESOURCE TYPES — and neither was coupled to
 * anything. A new node type or resource type could ship with PROTOCOL_VERSION unmoved and this
 * file stayed green, which is the failure this file exists to make impossible.
 *
 * That is worse than the op case rather than merely equal to it. An unknown op is refused by a
 * decoder that then discards a batch — recoverable, and network-only. An unknown node or
 * resource type throws from SnapshotCodec's SHARED decode, so it lands on the PERSISTED path
 * too, where ScenePersistence.restoreOrFresh answers a CodecException by deleting the scene's
 * stored bodies. The uncoupled tables were the ones whose failure destroys data.
 *
 * The version↔table coupling this file names is therefore now asserted for ALL FOUR id spaces
 * — ops, node types, resource types and delta types — by
 * {@code everyWireIdTableIsPinnedToThisVersion}, which reads the constants off V2Wire by
 * reflection instead of walking a predicate. That is deliberate and is the stronger test: a
 * whitelist sweep only sees an id once it has been WIRED IN, while reflection sees it the
 * moment it is DECLARED. It also covers delta types, which have no whitelist predicate to
 * walk at all — only a switch with a default arm.
 *
 * (Reflection here is over OUR OWN class and costs nothing at runtime. It is not the
 * reflection-into-Angelica question that C1.3.2's plan defers separately.)
 */
public class ProtocolVersionTest {

	/**
	 * The highest op id this version defines, and the version that defines it. Bump BOTH when
	 * adding an op — that is the entire point of this test.
	 */
	private static final int HIGHEST_OP_AT_THIS_VERSION = V2Wire.OP_SET_FONT;
	/**
	 * 8, while the highest op is still the one v4 defined. That gap is intentional and is the
	 * answer to this test's own instruction, now four times over: 4 -> 5 appended `parent` to the
	 * node record, 5 -> 6 appended the whole PROGRAM SECTION after the node loop, 6 -> 7 appended
	 * `animator` to the node record plus the scene's creation epoch after that section, and
	 * 7 -> 8 appended ANIM-6's attach stamp to the node record plus the world-time anchor after
	 * the epoch. None touched the op table, so only this constant moves. The gap is also what lets
	 * SnapshotCodec list v4 through v7 as readable — a bump that HAD changed an arity could not.
	 */
	// 8 -> 9 added ANIM-13(b)'s server tick to the HEARTBEAT — a transient message, so unlike
	// every bump before it this one touched no persisted record and no op table: v8's snapshot
	// layout IS v9's, which is why v8 could join LAYOUT_COMPATIBLE_PERSISTED_VERSIONS unchanged
	// It still owed a v8 golden fixture: an unchanged layout does not excuse one, because what
	// needs pinning is that the v9 reader ACCEPTS an 8. See PersistedVersionMigrationTest.
	// 9 -> 10 is Stage C's C1.1: meshes (RES_MESH + DELTA_MESH_CREATE), the 3D TRS prop bits and
	// node-record append, and the per-attachment uniform table (DELTA_UNIFORM_SET + a nested
	// snapshot section). No new canvas op, so HIGHEST_OP_AT_THIS_VERSION stays OP_SET_FONT and
	// only this constant moves — the 4 -> 9 precedent, sixth time over.
	// 10 -> 11 is Stage C's C1.3.2: lighting, claiming ONE node-type id (NODE_LIGHT = 6) and
	// nothing else. No new canvas op again, so HIGHEST_OP_AT_THIS_VERSION is still OP_SET_FONT.
	// This is the first bump whose REASON is a table this file was not guarding — see the class
	// javadoc's 2026-08-28 note, and HIGHEST_NODE_TYPE_AT_THIS_VERSION below.
	private static final short VERSION_THAT_DEFINES_IT = 11;

	/**
	 * The highest node-type and resource-type ids this version defines. Same contract as
	 * HIGHEST_OP_AT_THIS_VERSION: bump the version in the SAME edit that raises either.
	 *
	 * These are whitelist tables (V2Wire.isKnownNodeType / isKnownResType), not arity tables,
	 * so the sweep asks a boolean rather than a count — but the coupling being pinned is the
	 * same one, and the cost of getting it wrong is higher. See the class javadoc.
	 */
	private static final byte HIGHEST_NODE_TYPE_AT_THIS_VERSION = V2Wire.NODE_LIGHT;
	private static final byte HIGHEST_RES_TYPE_AT_THIS_VERSION = V2Wire.RES_MESH;
	private static final byte HIGHEST_DELTA_TYPE_AT_THIS_VERSION = V2Wire.DELTA_UNIFORM_SET;

	@Test
	public void versionAndOpTableMoveTogether() {
		assertEquals("PROTOCOL_VERSION changed without updating this test — if you added an op,"
				+ " update BOTH constants here; if you bumped the version for another reason,"
				+ " update VERSION_THAT_DEFINES_IT only",
				VERSION_THAT_DEFINES_IT, V2Wire.PROTOCOL_VERSION);

		// Nothing may be defined above the recorded highest op without the version moving.
		for (int op = HIGHEST_OP_AT_THIS_VERSION + 1; op < HIGHEST_OP_AT_THIS_VERSION + 16; op++) {
			assertEquals("op " + op + " is defined but PROTOCOL_VERSION is still "
					+ V2Wire.PROTOCOL_VERSION + " — bump the version and this test together",
					-1, V2Wire.canvasOpArgCount(op));
		}
	}

	@Test
	public void everyDefinedOpHasAnArity() {
		for (int op = 1; op <= HIGHEST_OP_AT_THIS_VERSION; op++) {
			assertTrue("op " + op + " has no arity entry, so the decoder would reject it",
					V2Wire.canvasOpArgCount(op) >= 0);
		}
	}

	/**
	 * THE GUARD THAT WAS MISSING. Reads the constants of FOUR id tables off V2Wire — {@code OP_},
	 * {@code NODE_}, {@code RES_}, {@code DELTA_} — and pins each table's shape to this version,
	 * so declaring a new id in any of them without bumping fails here.
	 *
	 * <b>Four named tables, NOT "every id on V2Wire".</b> The prefix list is hardcoded and
	 * nothing asserts it is exhaustive; the per-prefix "swept nothing" guard catches a MISTYPED
	 * prefix, never a MISSING one. Deliberately outside the sweep, each for a reason:
	 * <ul>
	 * <li>{@code FONT_} ids — dense from ZERO with {@code FONT_COUNT} as a non-id sentinel, so
	 *     they cannot satisfy this helper's 1..N contract; and an unknown font degrades to
	 *     {@code FONT_DEFAULT} in the renderer rather than throwing, so the failure is a wrong
	 *     glyph, not a lost scene. A font id IS persisted format (it rides OP_SET_FONT's
	 *     argument inside a snapshot's command lists), so this is a priced gap, not a safe one.</li>
	 * <li>{@code PROP_} mask BITS — powers of two, not a 1..N table. Bounded incidentally by
	 *     BatchSizeBoundTest's bit-count pin, which exists for a byte budget rather than for
	 *     the version.</li>
	 * <li>{@code ServerScene.LIGHT_*} kinds — {@code double}, and on a different class, so
	 *     neither this sweep's prefix match nor its integral-type filter would see them even
	 *     if they moved here.</li>
	 * </ul>
	 * Widening the sweep to any of those FAILS ON A CORRECT TABLE, which is why the honest move
	 * is naming the scope rather than growing it.
	 *
	 * Why reflection rather than four more sweeps of the whitelist predicates: a predicate sweep
	 * can only see an id after it has been wired into the predicate, and the defect this catches
	 * is the id being DECLARED and used while some table still disagrees. Delta types have no
	 * predicate at all — only BatchCodec's switch and its default arm — so nothing else could
	 * cover them without hand-computing a byte offset into a payload the encoder may compress.
	 *
	 * Each table is asserted to be EXACTLY the set {1..N}, not merely bounded above. All four are
	 * today, and that is the property worth pinning: a bound alone passes a table that has grown
	 * a hole where an id was retired.
	 *
	 * <b>THE THREE ASSERTIONS COVER DIFFERENT DEFECTS, and none is redundant.</b> The
	 * HIGHEST_*_AT_THIS_VERSION constants are SYMBOLIC (house style, following
	 * HIGHEST_OP_AT_THIS_VERSION) rather than literals, so they FOLLOW the constant they check.
	 * That makes the maximum assertion structurally unable to catch a change to an existing id's
	 * VALUE: mutate RES_MESH from 3 to 4 and the expectation moves to 4 with it. The SET sees
	 * that one, via the hole it opens. Conversely a newly ADDED id is caught by the maximum and
	 * sails past the set, which stays satisfied at 1..N+1. And because the set collapses
	 * duplicates, two constants sharing one value are invisible to both — the COUNT catches
	 * that, and only that.
	 *
	 * <b>WHAT THIS TEST DOES NOT CATCH, stated so nobody trusts it further than it goes.</b>
	 * A PERMUTATION is not caught and is not meant to be: swap NODE_SPRITE and NODE_GROUP and
	 * the set is still exactly {1..6}, so the table satisfies the contract as written. Catching
	 * that needs per-constant literal pins, which is a different test.
	 * A COMPENSATED REUSE IN ONE EDIT is not caught either: retire NODE_SPRITE and declare
	 * NODE_BILLBOARD = 2 in the same commit and the table is genuinely contiguous. Reuse is
	 * refused by the append-only-by-id rule written at V2Wire's burned-id comment, not here —
	 * a shape check cannot see the difference between "2 has always meant sprite" and "2 means
	 * something new now", because the shape is identical.
	 *
	 * Verified rather than argued, 2026-08-28. Two mutants were injected and each died to the
	 * assertion predicted for it, at different lines: a new node type (NODE_FOO = 7) at the
	 * maximum, and a retired-id hole (RES_MESH 3 -> 4) at the set. That pairing is what
	 * distinguishes "the test works" from "some assertion happened to fire".
	 *
	 * <b>And the first version of this method FAILED that standard, which is why the paragraph
	 * above is worth its space.</b> It asserted {@code max == count} and called it contiguity.
	 * That is a PROXY, true of {1..N} but also of a hole compensated by a duplicate
	 * (NODE_SPRITE = 6 gives {1,6,3,4,5,6}: count 6, max 6, green) and of a value out of range
	 * (NODE_GROUP = 0 gives {1,2,0,4,5,6}: count 6, max 6, green). The distinct-value set was
	 * computed, and appeared only inside a failure message — built, never asserted on. Both
	 * mutants above land inside the region the proxy does cover, so the exercise that "verified"
	 * the guard could not have found this. An independent panel did. The lesson is the file's
	 * own subject matter: a guard can assert something ADJACENT to its claim and read as green
	 * for the right reason on today's data, which is exactly D9.
	 */
	@Test
	public void everyWireIdTableIsPinnedToThisVersion() throws Exception {
		assertIdTable("OP_", HIGHEST_OP_AT_THIS_VERSION);
		assertIdTable("NODE_", HIGHEST_NODE_TYPE_AT_THIS_VERSION);
		assertIdTable("RES_", HIGHEST_RES_TYPE_AT_THIS_VERSION);
		assertIdTable("DELTA_", HIGHEST_DELTA_TYPE_AT_THIS_VERSION);
	}

	/**
	 * Asserts that the V2Wire constants named {@code prefix}* are exactly the ids 1..expectedMax.
	 *
	 * Deliberately fails when the table is EMPTY. A prefix typo would otherwise make this whole
	 * guard vacuous while reporting success — the test would sweep nothing and pass, which is the
	 * defect class this file was just widened to fix.
	 */
	private static void assertIdTable(String prefix, int expectedMax) throws Exception {
		int max = 0;
		int count = 0;
		java.util.TreeSet<Integer> seen = new java.util.TreeSet<Integer>();
		for (java.lang.reflect.Field f : V2Wire.class.getDeclaredFields()) {
			if (!f.getName().startsWith(prefix) || !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
				continue;
			Class<?> t = f.getType();
			if (t != byte.class && t != short.class && t != int.class)
				continue;
			int value = ((Number) f.get(null)).intValue();
			count++;
			seen.add(Integer.valueOf(value));
			if (value > max)
				max = value;
		}
		assertTrue("no V2Wire constants start with \"" + prefix + "\" — this guard swept nothing"
				+ " and would have passed vacuously; fix the prefix", count > 0);
		assertEquals("the highest " + prefix + " id is " + max + " but this test records "
				+ expectedMax + " for PROTOCOL_VERSION " + V2Wire.PROTOCOL_VERSION
				+ " — if you added an id, bump the version and update this test together",
				expectedMax, max);
		// THE SET, asserted directly. An earlier version of this method asserted max == count and
		// called that contiguity in its javadoc; it is only a PROXY for contiguity, and it holds
		// under two edits it was written to refuse — a hole compensated by a duplicate
		// (NODE_SPRITE = 6 gives {1,6,3,4,5,6}: count 6, max 6, green) and a value out of range
		// (NODE_GROUP = 0 gives {1,2,0,4,5,6}: count 6, max 6, green). `seen` existed and was
		// built, but appeared only inside this message — a set computed and never asserted on.
		java.util.TreeSet<Integer> expected = new java.util.TreeSet<Integer>();
		for (int i = 1; i <= expectedMax; i++) {
			expected.add(Integer.valueOf(i));
		}
		assertEquals("the " + prefix + " table is not exactly 1.." + expectedMax + " — it is "
				+ seen + ". A HOLE means an id was retired; a value <= 0 or a gap is not a table"
				+ " this guard can reason about. Decide explicitly before pinning it.",
				expected, seen);
		// STILL NEEDED alongside the set: `seen` is a SET, so two fields sharing one value
		// collapse into a single element and leave it exactly 1..N. Only the count sees that.
		assertEquals("the " + prefix + " table has " + count + " constants but only " + seen.size()
				+ " distinct ids, so two constants share a value — one of them is a silent alias",
				count, seen.size());
	}

	/** A batch from a different protocol version must be refused whole, not partially read. */
	@Test
	public void aBatchFromAnotherVersionIsRejected() throws Exception {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(1, V2Wire.RES_CANVAS, 64, 64, 0, 0, 256));
		byte[] encoded = BatchCodec.encode(new SceneBatch("s", 1, 1, 1L, deltas));

		// The version is the first field; corrupt only it.
		encoded[0] = (byte) 0;
		encoded[1] = (byte) 99;
		try {
			BatchCodec.decode(encoded);
			fail("a batch claiming version 99 must be rejected");
		} catch (CodecException expected) {
			assertTrue("the error should name the version, got: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains("version"));
		}
	}

	/**
	 * The new op survives the real server-to-client path with its argument intact, carried
	 * inside a batch rather than through a standalone list — the standalone form is only ever
	 * produced by the Lua library, so the codec has no encoder for it to test against.
	 */
	@Test
	public void setFontRoundTripsInsideABatch() throws Exception {
		ArrayList<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_UNSCII8));
		cmds.add(CanvasCommand.text(1, 2, "hi"));
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_FONT, V2Wire.FONT_DEFAULT));

		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(1, V2Wire.RES_CANVAS, 64, 64, 0, 0, 256));
		deltas.add(new Delta.CanvasPublish(1, cmds));

		SceneBatch back = BatchCodec.decode(
				BatchCodec.encode(new SceneBatch("s", 1, 1, 1L, deltas)));

		Delta.CanvasPublish pub = null;
		for (Delta d : back.deltas) {
			if (d instanceof Delta.CanvasPublish) {
				pub = (Delta.CanvasPublish) d;
			}
		}
		assertTrue("the publish survived", pub != null);
		assertEquals(3, pub.commands.size());
		assertEquals("op preserved", V2Wire.OP_SET_FONT, pub.commands.get(0).op);
		assertEquals("font id preserved", V2Wire.FONT_UNSCII8,
				(int) pub.commands.get(0).args[0]);
		assertEquals("the text between them is untouched", "hi", pub.commands.get(1).text);
		assertEquals("and the reset back to default", V2Wire.FONT_DEFAULT,
				(int) pub.commands.get(2).args[0]);
	}

	@Test
	public void fontIdValidityMatchesTheDefinedFonts() {
		assertTrue("default is valid", V2Wire.isValidFont(V2Wire.FONT_DEFAULT));
		assertTrue("unscii8 is valid", V2Wire.isValidFont(V2Wire.FONT_UNSCII8));
		assertTrue("negative is not", !V2Wire.isValidFont(-1));
		assertTrue("past the end is not", !V2Wire.isValidFont(V2Wire.FONT_COUNT));
	}
}
