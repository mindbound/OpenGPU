package opengpu.v2.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;

/**
 * The v3 -> v4 save migration: a world written before the font change must still load.
 *
 * WHY THIS IS NOT THE SAME SHAPE AS {@code LegacyMigrationTest}. v2 -> v3 changed the resource
 * record, so it needed its own decoder. v3 -> v4 changed NOTHING the structure codec reads: the
 * bump appended OP_SET_FONT to the op table and moved no field. So a v3 structure is the current
 * layout under an older version number, and the only thing rejecting it was the decoder's strict
 * version equality — which exists for the NETWORK, where a peer of the wrong vintage really is an
 * error and can be told to upgrade.
 *
 * The consequence of getting this wrong is not a crash. {@code restoreOrFresh} answers a
 * CodecException by calling {@code store.deleteScene}, so a missing migration silently DESTROYS
 * every pre-upgrade world's scenes and texture bodies on first chunk load, and the symptom is a
 * blank screen that any redraw hides. That is why {@link #restoreOrFreshMustNotDeleteAV3Scene}
 * drives the real chunk-load policy rather than the codec alone.
 *
 * The v3 STRUCTURE bytes are written by hand, deliberately: producing them with the current
 * encoder would make the test tautological — encode under v4 rules, decode under v4 rules, always
 * passes. The arities below are transcribed as v3 had them, so changing the arity of an existing
 * op (which would genuinely break old saves) fails here. Body FRAMING is not hand-written: it did
 * not change across this bump and is not what is under test, so the fixture uses the same
 * {@code frameBody} the production save path uses — which is why this test lives in this package.
 */
public class PersistedVersionMigrationTest {

	private static final short V3 = 3;
	/**
	 * Pinned to a literal, never to {@code V2Wire.PROTOCOL_VERSION}. A historic fixture that
	 * follows the constant stops describing the version it is named for the moment the constant
	 * moves — and v4 is what is on the disk of every world written before transform parenting.
	 */
	private static final short V4 = 4;

	/** v3's arities for the ops used below, transcribed rather than read back from V2Wire. */
	private static final int V3_ARGS_SET_COLOR = 4;
	private static final int V3_ARGS_FILL = 0;
	private static final int V3_ARGS_DRAW_TEXT = 2;

	private static final int TEX_ID = 2;
	private static final int TEX_DIM = 4;
	private static final int TEX_VERSION = 2;
	private static final int EPOCH = 0x0FACE;

	private File root;
	private DirectoryResourceStore store;

	@Before
	public void setUp() throws Exception {
		root = File.createTempFile("opengpu-v3-migration", "");
		assertTrue(root.delete());
		assertTrue(root.mkdirs());
		store = new DirectoryResourceStore(root);
	}

	@After
	public void tearDown() {
		store.close();
		deleteRecursively(root);
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}

	/** The texture bytes the fixture stores, and whose hash the structure must claim. */
	private static byte[] textureBody() {
		byte[] body = new byte[TEX_DIM * TEX_DIM * 4];
		for (int i = 0; i < body.length; i++) {
			body[i] = (byte) (i * 7);
		}
		return body;
	}

	/** Writes the v3 persisted-structure layout, field for field, under a chosen version short. */
	private static final class StructureWriter {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream(bytes);

		StructureWriter(short version, String sceneId, int epoch, int seq, long tick,
				int nextResourceId, int nextNodeId) throws IOException {
			out.writeShort(version);
			out.writeUTF(sceneId);
			out.writeInt(epoch);
			out.writeInt(seq);
			out.writeLong(tick);
			out.writeInt(nextResourceId);
			out.writeInt(nextNodeId);
		}

		StructureWriter resources(int count) throws IOException {
			out.writeInt(count);
			return this;
		}

		/** v3 resource record: three version/hash fields where v2 carried a single hash. */
		StructureWriter texture(int id, int w, int h, int latestVersion, int knownHashVersion,
				long hash) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_TEXTURE);
			out.writeInt(w);
			out.writeInt(h);
			out.writeInt(w * h * 4);
			out.writeInt(latestVersion);
			out.writeInt(knownHashVersion);
			out.writeLong(hash);
			return this;
		}

		/**
		 * A canvas holding ops a v3 world could actually contain. Text is included on purpose:
		 * DRAW_TEXT is what the font change was about, and a v3 save carries one with no
		 * OP_SET_FONT in front of it — the case the renderer's per-canvas reset to FONT_DEFAULT
		 * is what makes well-defined.
		 */
		StructureWriter canvasWithContent(int id, int w, int h, int cap) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_CANVAS);
			out.writeInt(w);
			out.writeInt(h);
			out.writeInt(0);
			out.writeInt(1);   // canvases are written as version 1
			out.writeInt(0);   // no hash version
			out.writeLong(0L); // no hash
			out.writeInt(cap);
			out.writeInt(3);   // command count
			op(V2Wire.OP_SET_COLOR, V3_ARGS_SET_COLOR, 10, 20, 30, 255);
			op(V2Wire.OP_FILL, V3_ARGS_FILL);
			out.writeByte(V2Wire.OP_DRAW_TEXT);
			for (int i = 0; i < V3_ARGS_DRAW_TEXT; i++) {
				out.writeDouble(i == 0 ? 4 : 6);
			}
			out.writeUTF("pre-font world");
			return this;
		}

		private void op(byte code, int argc, double... args) throws IOException {
			assertEquals("transcribed arity disagrees with the args given", argc, args.length);
			out.writeByte(code);
			for (double a : args) {
				out.writeDouble(a);
			}
		}

		StructureWriter nodes(int count) throws IOException {
			out.writeInt(count);
			return this;
		}

		/**
		 * The node record as v3 and v4 wrote it: 58 bytes, ending at tint. FROZEN — do not add
		 * fields here. A historic fixture that tracks the current layout stops describing the
		 * version it is named for, and this one is the only executable record of what a v4 world
		 * has on disk. New fields go in a new writer, as {@link #nodeV5} does.
		 */
		StructureWriter node(int id, byte type, int ref, double x, double y, int z, int tint)
				throws IOException {
			out.writeInt(id);
			out.writeByte(type);
			out.writeInt(ref);
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(0); // rot
			out.writeDouble(1); // sx
			out.writeDouble(1); // sy
			out.writeInt(z);
			out.writeBoolean(true);
			out.writeInt(tint);
			return this;
		}

		/** The v5 node record: everything v4 wrote, then `parent`. 62 bytes. */
		StructureWriter nodeV5(int id, byte type, int ref, double x, double y, int z, int tint,
				int parent) throws IOException {
			node(id, type, ref, x, y, z, tint);
			out.writeInt(parent);
			return this;
		}

		/**
		 * The v7 node record: everything v5 wrote, then `animator`. 66 bytes. A NEW writer rather
		 * than a parameter on nodeV5, for the reason stated on {@link #node}: nodeV5 is the only
		 * executable record of what a v5 world holds, and widening it would erase that.
		 */
		StructureWriter nodeV7(int id, byte type, int ref, double x, double y, int z, int tint,
				int parent, int animator) throws IOException {
			nodeV5(id, type, ref, x, y, z, tint, parent);
			out.writeInt(animator);
			return this;
		}

		/** The v7 scene tail: the animator epoch, written AFTER the v6 program section. */
		StructureWriter creationWorldTimeV7(long worldTime) throws IOException {
			out.writeLong(worldTime);
			return this;
		}

		/**
		 * The same node record with its LAST field absent — a stand-in for any bump that
		 * changed the record's width. Used only to show what a mislabelled version does.
		 */
		StructureWriter nodeMissingTint(int id, byte type, int ref, double x, double y, int z)
				throws IOException {
			out.writeInt(id);
			out.writeByte(type);
			out.writeInt(ref);
			out.writeDouble(x);
			out.writeDouble(y);
			out.writeDouble(0); // rot
			out.writeDouble(1); // sx
			out.writeDouble(1); // sy
			out.writeInt(z);
			out.writeBoolean(true);
			return this;
		}

		/**
		 * The v6 program SECTION: next-id, then a count, then that many records. A NEW writer
		 * rather than an edit to any above, for the reason {@link #node} states — a fixture that
		 * tracks the current layout stops describing the version it is named for, and these
		 * fixtures are the only executable record of what each old world holds on disk.
		 *
		 * Empty is the interesting case for the version-gate tests: a v6 scene that has no
		 * programs still writes the two ints, and a decoder that skipped them would land on the
		 * trailing-data guard rather than EOF. (The first draft wrote only the header while its
		 * comment claimed to be "parameterised so the first program-carrying fixture does not
		 * need a second writer" — with count > 0 it produced a truncated fixture. Review caught
		 * the sentence; now the records exist.)
		 */
		StructureWriter programsV6(int nextProgramId, int count) throws IOException {
			out.writeInt(nextProgramId);
			out.writeInt(count);
			for (int i = 0; i < count; i++) {
				programRecordV6(1 + i, (byte) 1, 1, new byte[] { (byte) (0x40 + i) });
			}
			return this;
		}

		/** One hand-written program record, field order the layout comment's: id, stage, charge, length, blob. */
		StructureWriter programRecordV6(int id, byte stage, int structuralOps, byte[] blob)
				throws IOException {
			out.writeInt(id);
			out.writeByte(stage);
			out.writeInt(structuralOps);
			out.writeInt(blob.length);
			out.write(blob);
			return this;
		}

		byte[] done() throws IOException {
			out.flush();
			return bytes.toByteArray();
		}
	}

	/** A representative pre-font world: a drawn canvas, a texture, and a node for each. */
	private static byte[] sample(short version) throws IOException {
		StructureWriter w = new StructureWriter(version, "gpu-addr", EPOCH, 7, 900L, 3, 3);
		w.resources(2)
				.canvasWithContent(1, 512, 288, 4096)
				.texture(TEX_ID, TEX_DIM, TEX_DIM, TEX_VERSION, TEX_VERSION,
						V2Wire.contentHash(textureBody()));
		w.nodes(2);
		// The node record is the one thing that differs across the versions this fixture covers,
		// so it is chosen by version rather than baked in. Everything else — header, resource
		// record, canvas commands — is identical from v3 up, which is exactly why v3 and v4 are
		// both readable and why only ONE of them needed a gate.
		if (version >= 7) {
			w.nodeV7(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF, 0, 0)
					.nodeV7(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2, 0x80FF00FF, 0, 0);
		} else if (version >= 5) {
			w.nodeV5(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF, 0)
					.nodeV5(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2, 0x80FF00FF, 0);
		} else {
			w.node(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF)
					.node(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2, 0x80FF00FF);
		}
		// The v6 TAIL SECTION, chosen by version for the same reason the node record is: a
		// fixture must carry exactly what its version defines, no more and no less. A v5 sample
		// with this section would not be a v5 save, and appendedBytesAreRejectedNeverIgnored
		// would then be proving the guard against a fixture that is already malformed.
		if (version >= 6) {
			w.programsV6(1, 0);
		}
		if (version >= 7) {
			w.creationWorldTimeV7(0L);
		}
		return w.done();
	}

	@Test
	public void aV3StructureDecodesThroughThePersistencePath() throws Exception {
		SceneSnapshot snap = SnapshotCodec.decodePersisted(sample(V3));

		assertEquals("gpu-addr", snap.sceneId);
		assertEquals("the incarnation must CONTINUE — nothing here is degraded", EPOCH, snap.epoch);
		assertEquals(7, snap.seq);
		assertEquals(900L, snap.serverTick);
		assertEquals(3, snap.state.nextResourceId);
		assertEquals(3, snap.state.nextNodeId);

		ResourceInfo canvas = snap.state.resources.get(1);
		assertNotNull("the canvas must survive", canvas);
		assertNotNull("with its command list", canvas.canvas);
		assertEquals("every v3 command decodes under the v4 arity table",
				3, canvas.canvas.visibleCommands().size());
		assertEquals("the text op keeps its string", "pre-font world",
				canvas.canvas.visibleCommands().get(2).text);

		ResourceInfo tex = snap.state.resources.get(TEX_ID);
		assertEquals(V2Wire.contentHash(textureBody()), tex.knownHash);
		assertEquals(TEX_VERSION, tex.latestVersion);
		assertEquals(TEX_VERSION, tex.knownHashVersion);

		SceneNode sprite = snap.state.nodes.get(2);
		assertEquals(3.5, sprite.x, 1e-9);
		assertEquals(0x80FF00FF, sprite.tint);
	}

	@Test
	public void theNetworkDecoderStillRefusesAV3Payload() throws Exception {
		// The asymmetry IS the design. A peer of the wrong vintage disagrees about the op table,
		// so decoding its payload risks reading one op's argument as another's — and it can be
		// told to upgrade. A save on disk can do neither, and refusing it destroys it.
		try {
			SnapshotCodec.decode(sample(V3));
			fail("the network decoder must stay strict at PROTOCOL_VERSION");
		} catch (CodecException expected) {
			assertTrue("the error should name the version, got: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains("version"));
		}
	}

	@Test
	public void anUnknownVersionIsRefusedOnBOTHPaths() throws Exception {
		// Tolerating v3 must not become tolerating anything. An unvetted version could have a
		// different layout, and misreading one yields a plausible scene assembled from misaligned
		// bytes — strictly worse than starting fresh, which is at least visible.
		byte[] alien = sample((short) 99);
		for (boolean persisted : new boolean[] { false, true }) {
			try {
				if (persisted) {
					SnapshotCodec.decodePersisted(alien);
				} else {
					SnapshotCodec.decode(alien);
				}
				fail("version 99 must be refused on the " + (persisted ? "persistence" : "network")
						+ " path");
			} catch (CodecException expected) {
				assertTrue(expected.getMessage().toLowerCase().contains("version"));
			}
		}
	}

	/**
	 * The v4 seam, and the reason a fixture has to be pinned to a LITERAL version.
	 *
	 * Write {@code version >= 4} instead of {@code >= 5} in SnapshotCodec's node loop and only the
	 * two v4 tests fail — this one and {@link #restoreOrFreshMustNotDeleteAV4Scene}. Nothing else
	 * can: v3 is below both gates, v5 satisfies both, and every fixture built from
	 * PROTOCOL_VERSION is by definition testing v5. Only a fixture pinned to 4 puts a 58-byte
	 * record in front of a decoder told to read 62, after which each node swallows the next one's
	 * id as its parent. On the real path that is every pre-upgrade world deleted on first chunk
	 * load, which is why the pair matters: this test catches it at the codec, and the other
	 * catches it where the deletion actually happens.
	 */
	@Test
	public void aV4StructureDecodesThroughThePersistencePath() throws Exception {
		SceneSnapshot snap = SnapshotCodec.decodePersisted(sample(V4));

		assertEquals("gpu-addr", snap.sceneId);
		assertEquals("the incarnation must CONTINUE — nothing here is degraded", EPOCH, snap.epoch);
		assertEquals("both nodes must survive at the v4 record width", 2, snap.state.nodes.size());

		SceneNode canvasNode = snap.state.nodes.get(1);
		SceneNode sprite = snap.state.nodes.get(2);
		assertEquals("a v4 node has no parent field, so it must read as unparented",
				0, canvasNode.parent);
		assertEquals(0, sprite.parent);
		// Proof the records stayed ALIGNED rather than merely producing two node objects: a
		// misread would have shifted these, and they are the last fields before the new one.
		assertEquals(3.5, sprite.x, 1e-9);
		assertEquals(-1.25, sprite.y, 1e-9);
		assertEquals(2, sprite.z);
		assertEquals(0x80FF00FF, sprite.tint);
		assertEquals(TEX_ID, sprite.ref);
	}

	/**
	 * A legal v5 parent survives, and every illegal one degrades to "unparented" rather than
	 * throwing. The degradation IS the requirement: on this path a CodecException is
	 * {@code store.deleteScene}, so validation that throws would answer "one node has a bad
	 * parent id" with "delete the world and its textures".
	 *
	 * None of these four shapes is hypothetical. A self-parent and a forward reference are what
	 * misaligned bytes produce; a second nesting level is what a save written by a future build
	 * that lifted the one-level limit would contain, and that build's worlds must still open here;
	 * an absent parent is a node freed before the save.
	 */
	@Test
	public void anIllegalPersistedParentDegradesInsteadOfDeletingTheScene() throws Exception {
		StructureWriter w =
				new StructureWriter(V2Wire.PROTOCOL_VERSION, "gpu-addr", EPOCH, 7, 900L, 1, 11);
		w.resources(0);
		// Node 9 is written BEFORE node 4, and that ordering is the point rather than an
		// accident. In an ascending blob a self-parent and a forward reference are ALSO absent at
		// read time, so both would degrade even with the id check deleted, and the check would sit
		// fully masked. Writing 9 first makes node 4's parent PRESENT and unparented, so only the
		// "must be a lower id" rule can refuse it — the one shape that tells the two apart.
		w.nodes(7)
				.nodeV7(1, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0)   // a legal parent
				.nodeV7(2, V2Wire.NODE_GROUP, 0, 1, 1, 0, 0xFFFFFFFF, 1, 0)   // a legal child of it
				.nodeV7(3, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 3, 0)   // parents itself
				.nodeV7(9, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0)   // present, unparented
				.nodeV7(4, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 9, 0)   // ...but 9 is above 4
				.nodeV7(5, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 2, 0)   // 2 is already a child
				.nodeV7(10, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 7, 0); // 7 was never written
		w.programsV6(1, 0); // v6 section: this fixture is pinned to PROTOCOL_VERSION
		w.creationWorldTimeV7(0L); // ...and therefore the v7 tail too

		SceneSnapshot snap = SnapshotCodec.decodePersisted(w.done());

		assertEquals("only the bad FIELD may degrade — no node may be dropped",
				7, snap.state.nodes.size());
		assertEquals("a legal parent must survive untouched", 1, snap.state.nodes.get(2).parent);
		assertEquals("a node parented to itself", 0, snap.state.nodes.get(3).parent);
		assertEquals("a parent that exists and is unparented, but is not a LOWER id",
				0, snap.state.nodes.get(4).parent);
		assertEquals("a second nesting level", 0, snap.state.nodes.get(5).parent);
		assertEquals("a parent id that is absent", 0, snap.state.nodes.get(10).parent);
	}

	/**
	 * The same bytes, two answers, on purpose — asserted here so the split reads as a decision
	 * rather than as one path having been forgotten.
	 *
	 *   save    — sanitise. A CodecException reaches restoreOrFresh, which deletes the scene and
	 *             its texture bodies, so refusing is the more destructive option.
	 *   network — throw. Refusing costs a resync and nothing else, because the mirror already
	 *             answers a failed apply with needsResync and the retry fetches a clean snapshot.
	 *             It is also the ONLY path where the disagreement is detectable: silently zeroing
	 *             the parent would leave the mirror rendering an ungrouped scene against a server
	 *             that has the grouping, with no seq gap and no apply failure to reveal it.
	 */
	@Test
	public void theNetworkPathRefusesAParentThatTheSaveToleratesQuietly() throws Exception {
		StructureWriter w =
				new StructureWriter(V2Wire.PROTOCOL_VERSION, "gpu-addr", EPOCH, 7, 900L, 1, 3);
		w.resources(0);
		w.nodes(2)
				.nodeV7(1, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0)
				.nodeV7(2, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 7, 0); // 7 is above 2
		w.programsV6(1, 0); // v6 section: this fixture is pinned to PROTOCOL_VERSION
		w.creationWorldTimeV7(0L); // ...and therefore the v7 tail too
		byte[] blob = w.done();

		assertEquals("the save must degrade rather than refuse",
				0, SnapshotCodec.decodePersisted(blob).state.nodes.get(2).parent);

		try {
			SnapshotCodec.decode(blob);
			fail("the network path must refuse a parent the server could not have produced");
		} catch (CodecException expected) {
			assertTrue("expected the parent to be named, got: " + expected.getMessage(),
					expected.getMessage().contains("parent"));
		}
	}

	@Test
	public void restoreOrFreshMustNotDeleteAV4Scene() throws Exception {
		// The v4 half of the deletion path, driven through the real chunk-load policy. v4 is the
		// version on every world written before transform parenting, so this is the one that
		// stands between an upgrade and a wiped save.
		String sceneId = "gpu-addr";
		byte[] body = textureBody();
		store.save(sceneId, TEX_ID,
				ScenePersistence.frameBody(TEX_VERSION, V2Wire.contentHash(body), body));
		store.flush();

		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh(sceneId, sample(V4), store);

		assertTrue("a clean v4 restore must report no warnings, got: " + result.warnings,
				result.warnings.isEmpty());
		assertEquals("the scene must keep its identity, not be replaced by a fresh one",
				EPOCH, result.scene.epoch());
		assertEquals("both resources must survive", 2, result.scene.state().resources.size());
		assertNotNull("the stored texture body must still be on disk", store.load(sceneId, TEX_ID));
	}

	@Test
	public void restoreOrFreshMustNotDeleteAV3Scene() throws Exception {
		// The actual data-loss path, driven through the real chunk-load policy rather than the
		// codec alone: before the fix this returned a fresh scene AND wiped the stored bodies.
		String sceneId = "gpu-addr";
		byte[] body = textureBody();
		store.save(sceneId, TEX_ID,
				ScenePersistence.frameBody(TEX_VERSION, V2Wire.contentHash(body), body));
		store.flush();

		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh(sceneId, sample(V3), store);

		assertTrue("a clean v3 restore must report no warnings, got: " + result.warnings,
				result.warnings.isEmpty());
		assertEquals("the scene must keep its identity, not be replaced by a fresh one",
				EPOCH, result.scene.epoch());
		assertEquals("both resources must survive", 2, result.scene.state().resources.size());
		assertNotNull("the stored texture body must still be on disk", store.load(sceneId, TEX_ID));
		assertEquals("and it must be the same bytes, not a blank degrade",
				body.length, result.scene.state().resources.get(TEX_ID).bytes.length);
		assertEquals("byte 3 survived", body[3],
				result.scene.state().resources.get(TEX_ID).bytes[3]);
	}

	/**
	 * The v2 seam, end to end: the one dispatch line whose failure mode is deletion.
	 *
	 * {@code LegacyMigrationTest} proves decodeV2 works and {@code restoreOrFreshMustNotDeleteAV3Scene}
	 * proves the v3 path works, but nothing drove v2 bytes through {@code restoreOrFresh} itself —
	 * and that seam is where the stakes live: route a v2 structure to the strict decoder instead of
	 * the legacy one and the CodecException answer is {@code store.deleteScene}. The two halves
	 * being individually correct proves nothing about the line that chooses between them.
	 *
	 * The body is stored RAW, not framed, because that is what a genuine pre-upgrade world has on
	 * disk: v2 wrote bare payload validated by the manifest hash. This therefore also exercises the
	 * legacy-raw-body shim (accept, attach, leave persistedVersion 0 so the next save re-frames),
	 * which no other test reaches.
	 */
	@Test
	public void restoreOrFreshRoutesAV2WorldThroughTheLegacyDecoder() throws Exception {
		String sceneId = "gpu-addr";
		byte[] body = textureBody();

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		// v2 header: same fields as v3, older version short.
		out.writeShort(opengpu.v2.protocol.LegacyStructureCodec.V2_VERSION);
		out.writeUTF(sceneId);
		out.writeInt(0x5EED);   // epoch — must CONTINUE through the migration, not be reminted
		out.writeInt(7);        // seq
		out.writeLong(99L);     // tick
		out.writeInt(TEX_ID + 1);
		out.writeInt(2);
		// v2 resource record: ONE hash, no version fields — the layout decodeV2 exists for.
		out.writeInt(1);
		out.writeInt(TEX_ID);
		out.writeByte(V2Wire.RES_TEXTURE);
		out.writeInt(TEX_DIM);
		out.writeInt(TEX_DIM);
		out.writeInt(body.length);
		out.writeLong(V2Wire.contentHash(body));
		// One sprite showing it, so node migration rides along.
		out.writeInt(1);
		out.writeInt(1);
		out.writeByte(V2Wire.NODE_SPRITE);
		out.writeInt(TEX_ID);
		out.writeDouble(3.5);
		out.writeDouble(-2.0);
		out.writeDouble(0);
		out.writeDouble(1);
		out.writeDouble(1);
		out.writeInt(0);
		out.writeBoolean(true);
		out.writeInt(0xFFFFFFFF);
		out.flush();

		store.save(sceneId, TEX_ID, body);   // RAW: exactly what a v2-era save left behind
		store.flush();

		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh(sceneId, bytes.toByteArray(), store);

		assertTrue("a clean v2 migration must warn about nothing, got: " + result.warnings,
				result.warnings.isEmpty());
		assertEquals("the epoch must continue through the migration", 0x5EED, result.scene.epoch());
		ResourceInfo tex = result.scene.state().resources.get(TEX_ID);
		assertNotNull("the texture must survive the seam", tex);
		assertEquals("the raw legacy body must attach, not degrade to blank",
				body[3], tex.bytes[3]);
		assertEquals("an attached legacy body is version-1 content", 1, tex.version);
		assertEquals("a raw body must NOT count as persisted, or the re-framing shim dies"
				+ " and the format freeze can never delete it", 0, tex.persistedVersion);
		assertNotNull("the stored body must still be on disk — the delete path must not run",
				store.load(sceneId, TEX_ID));
		SceneNode sprite = result.scene.state().nodes.get(1);
		assertNotNull("the node must survive the seam", sprite);
		assertEquals(TEX_ID, sprite.ref);
	}

	@Test
	public void aTrulyUnreadableStructureStillDegradesToFresh() throws Exception {
		// Accepting v3 must not turn a corrupt structure into an exception escaping through chunk
		// load. Version 3 in the header, garbage after it.
		ScenePersistence.RestoreResult result =
				ScenePersistence.restoreOrFresh("gpu-addr", new byte[] { 0, 3, 9, 9 }, store);
		assertNotNull(result.scene);
		assertTrue("the failure must be reported", !result.warnings.isEmpty());
	}

	/**
	 * The tail is NOT silently extensible, and a future bump may not pretend otherwise.
	 *
	 * The tempting shortcut for adding a field is "append it and let old decoders ignore the
	 * extra bytes". They do not ignore them — the trailing-data guard rejects, and on the
	 * persistence path a rejection is {@code store.deleteScene}.
	 *
	 * SCOPE, precisely: this covers an append to the STREAM TAIL only. Widening a per-node or
	 * per-resource RECORD is a different mechanism with a different symptom — the drift is
	 * consumed by the reads that follow, and what surfaces is whatever guard the misaligned
	 * bytes happen to trip first. {@link #aWronglyWhitelistedVersionCostsTheSceneEvenWhenItRejectsCleanly}
	 * is the record-width case. Both shapes reject; neither is ignorable; the reasons differ.
	 *
	 * This test is what makes the tail case a fact rather than an assumption. If it ever starts
	 * failing, someone made the format lenient, and every claim in the surrounding javadocs
	 * about clean rejection needs re-reading.
	 */
	@Test
	public void appendedBytesAreRejectedNeverIgnored() throws Exception {
		byte[] valid = sample(V2Wire.PROTOCOL_VERSION);
		assertNotNull("the fixture must decode before we corrupt it",
				SnapshotCodec.decodePersisted(valid));

		byte[] extended = new byte[valid.length + 1];
		System.arraycopy(valid, 0, extended, 0, valid.length);

		try {
			SnapshotCodec.decodePersisted(extended);
			fail("one appended byte decoded cleanly — the format is now silently extensible,"
					+ " which means a truncated or misaligned save can also read as valid");
		} catch (CodecException expected) {
			assertTrue("expected the trailing-data guard, got: " + expected.getMessage(),
					expected.getMessage().contains("Trailing data"));
		}
	}

	/**
	 * Why {@code LAYOUT_COMPATIBLE_PERSISTED_VERSIONS} cannot be a judgement call.
	 *
	 * Nothing verifies that a version listed there really is byte-identical; the list is a
	 * maintainer's ASSERTION, checked by no code. This drives the case where that assertion is
	 * wrong — v3 is whitelisted, but here the v3-stamped bytes carry a narrower node record, as
	 * a bump that changed the record's width would have left them.
	 *
	 * The loss is that a rejection here is not a safe outcome. restoreOrFresh answers a
	 * CodecException with {@code store.deleteScene}, so a CLEAN rejection still destroys the
	 * world. "It will fail safe" is not a defence when the safe failure is deletion — which is
	 * why the version → layout mapping has to be right by construction, and why the extension
	 * policy is written down rather than left to a reviewer's memory.
	 *
	 * WHAT THIS TEST DOES NOT SHOW. It is not evidence that the structure format cannot be
	 * silently misread. For the NODE array it genuinely cannot: the node loop is the last thing
	 * before the trailing-data guard, so uniform per-record drift over N records must land the
	 * stream either short (EOF) or long (leftover) — there is no slack to absorb it, and the
	 * outcome is always a throw. Silence needs a record whose drift is swallowed by reads that
	 * come after it, which means the RESOURCE record — and {@code resources(0)} below removes
	 * that possibility on purpose, to keep this test about one mechanism. Read the claim as
	 * scoped to the node array.
	 *
	 * The route to the throw is the instructive part, because every guard that could have
	 * caught it sits DOWNSTREAM of the damage. Node 1 eats node 2's id as its tint. Node 2 then
	 * reads its own id out of the middle of its record, and its type out of the last byte of
	 * {@code ref} — which is TEX_ID, 2, which is NODE_SPRITE, so {@code isKnownNodeType} waves
	 * it through. The decode dies eight bytes later at {@code z}. The counter-consistency check
	 * would have rejected node 2's bogus id too, and never runs, because EOF fires first. The
	 * guards are not missing; they are simply behind the point where the bytes stopped meaning
	 * what the decoder thinks they mean.
	 */
	@Test
	public void aWronglyWhitelistedVersionCostsTheSceneEvenWhenItRejectsCleanly() throws Exception {
		// Keeps nodeMissingTint honest. node() is pinned against the real encoder by
		// aV3StructureDecodesThroughThePersistencePath, so if a field is ever added to the node
		// record, node() must follow it and this delta moves off 4 — failing here, loudly,
		// instead of leaving the fixture below quietly modelling nothing.
		assertEquals("nodeMissingTint has drifted from node(): the fixture no longer models a"
				+ " one-field record-width change", 4, nodeRecordWidthDelta());

		StructureWriter w = new StructureWriter(V3, "gpu-addr", EPOCH, 7, 900L, 3, 3);
		w.resources(0);
		w.nodes(2)
				.nodeMissingTint(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0)
				.nodeMissingTint(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2);

		try {
			SnapshotCodec.decodePersisted(w.done());
			fail("a node record of the wrong width decoded cleanly. For the node array that"
					+ " should be impossible — either the layout moved, or the trailing-data"
					+ " guard is gone and misaligned saves now read as valid scenes");
		} catch (CodecException expected) {
			// Pinning the exact mechanism, not merely "it threw". The weaker check this
			// replaced was satisfied by every other message the codec can emit, so it would
			// have stayed green if the fixture stopped reaching the node loop at all — and
			// if v3 ever leaves the whitelist it stops reaching it, rejected at the header.
			assertEquals("the documented route is EOF partway through the last node; another"
					+ " message means the trace in this javadoc no longer describes the code",
					"Truncated snapshot", expected.getMessage());
			assertTrue("expected the EOF underneath it, got: " + expected.getCause(),
					expected.getCause() instanceof java.io.EOFException);
		}
	}

	/**
	 * A HAND-WRITTEN v6 fixture carrying a full program record, decoded and checked field by
	 * field. The ProgramStorageTest round trip is encoder-against-its-own-decoder, so a
	 * symmetric mistake — both sides writing length before structuralOps, say — round-trips
	 * clean there and only a fixture whose bytes were laid down independently can catch it.
	 */
	@Test
	public void aHandWrittenV6ProgramRecordDecodesFieldForField() throws Exception {
		byte[] blob = new byte[] { 0x11, 0x22, 0x33, 0x44, 0x55 };
		StructureWriter w = new StructureWriter((short) 6, "gpu-addr", EPOCH, 7, 900L, 2, 2);
		w.resources(0);
		w.nodes(0);
		w.out.writeInt(9);   // nextProgramId — FIRST field of the section, before the count
		w.out.writeInt(1);   // one record
		w.programRecordV6(4, opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST, 37, blob);

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(w.done());
		assertEquals(9, decoded.state.nextProgramId);
		assertEquals(1, decoded.state.programs.size());
		opengpu.v2.scene.ProgramInfo p = decoded.state.programs.get(Integer.valueOf(4));
		assertEquals("id read from the record, not the map key alone", 4, p.id);
		assertEquals("stage and charge must land in their own fields — a swapped pair is the"
				+ " defect only an independent fixture can see",
				opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST, p.stage);
		assertEquals(37, p.structuralOps);
		assertTrue(java.util.Arrays.equals(blob, p.blobCopy()));
	}

	/**
	 * The persisted decoder's per-record refusals, driven with records no OpenGPU encoder can
	 * write. These bounds were added to match the batch decoder's, and the fix-round sweep proved
	 * the valid-record test above cannot see them (removing the stage check survived it): only a
	 * fixture carrying the ILLEGAL value exercises a refusal.
	 */
	@Test
	public void corruptV6ProgramRecordsAreRefusedNotAbsorbed() throws Exception {
		// [description, id, stage, structuralOps, blobLen] — blob bytes are filler of blobLen.
		Object[][] bad = {
				{ "unknown stage", 1, (byte) 99, 1, 4, "Unknown program stage" },
				{ "id zero", 0, opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST, 1, 4,
						"Program id" },
				{ "id MAX_VALUE", Integer.MAX_VALUE, opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST,
						1, 4, "Program id" },
				{ "negative charge", 1, opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST, -1, 4,
						"structural charge" },
				{ "charge over the acceptance cap", 1, opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST,
						opengpu.v2.ocsl.IrValidator.MAX_STRUCTURAL_OPS + 1, 4,
						"structural charge" },
				{ "zero-length blob", 1, opengpu.v2.ocsl.OcslWire.STAGE_PIXEL_POST, 1, 0,
						"blob length" },
		};
		for (Object[] row : bad) {
			StructureWriter w = new StructureWriter((short) 6, "gpu-addr", EPOCH, 7, 900L, 2, 2);
			w.resources(0);
			w.nodes(0);
			w.out.writeInt(Integer.MAX_VALUE - 1); // a counter above any of the crafted ids
			w.out.writeInt(1);
			w.programRecordV6((Integer) row[1], (Byte) row[2], (Integer) row[3],
					new byte[(Integer) row[4]]);
			try {
				SnapshotCodec.decodePersisted(w.done());
				fail("a corrupt program record (" + row[0] + ") decoded anyway");
			} catch (CodecException expected) {
				assertTrue(row[0] + ": expected a refusal naming '" + row[5] + "', got: "
						+ expected.getMessage(),
						expected.getMessage().contains((String) row[5]));
			}
		}
	}

	/** Width of the real node record minus the deliberately-narrowed one. Must stay 4. */
	private static int nodeRecordWidthDelta() throws IOException {
		StructureWriter full = new StructureWriter(V3, "s", EPOCH, 1, 0L, 2, 2);
		full.resources(0).nodes(1).node(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0);
		StructureWriter narrow = new StructureWriter(V3, "s", EPOCH, 1, 0L, 2, 2);
		narrow.resources(0).nodes(1).nodeMissingTint(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0);
		return full.done().length - narrow.done().length;
	}

	/**
	 * The guard that makes the NEXT bump decide, instead of silently repeating this bug.
	 *
	 * Nothing else can catch the omission. A persisted-version gap is invisible on the build that
	 * introduces it — every save that build writes is current-version, so every test is green and
	 * every in-game load works. It only appears on a world written by the PREVIOUS build, which
	 * nothing in CI has. This fails the moment PROTOCOL_VERSION moves, next to the instructions.
	 */
	/**
	 * A v5 structure: everything v4 wrote plus `parent`, and NO program section — which is
	 * exactly what every world saved before the 5 -> 6 bump holds on disk. Pinned to a LITERAL
	 * 5, never to PROTOCOL_VERSION, for the reason this file states throughout: a fixture that
	 * tracks the current version stops describing the version it is named for.
	 */
	private static byte[] v5Structure() throws IOException {
		final int canvasId = 1;
		StructureWriter w = new StructureWriter((short) 5, "gpu-addr", EPOCH, 7, 900L, 2, 3);
		// canvasWithContent writes its own three-command list; there is no separate commands()
		// step and no canvas() overload. Read from the writer above rather than assumed.
		w.resources(1)
				.canvasWithContent(canvasId, 64, 32, 16);
		w.nodes(2)
				.nodeV5(1, V2Wire.NODE_CANVAS, canvasId, 0, 0, 0, 0xFFFFFFFF, 0)
				.nodeV5(2, V2Wire.NODE_GROUP, 0, 1.5, -2.5, 3, 0x80FF00FF, 1);
		return w.done();
	}

	/**
	 * A v6 structure: v5's node record plus the program section, and NO v7 additions — what every
	 * world saved between the 5 → 6 and 6 → 7 bumps holds on disk. Carries a REAL program, because
	 * the 6 → 7 bump appended a field to the NODE record: a v6 fixture whose program section were
	 * empty would still exercise the node gate, but not the interaction between a widened node
	 * record and a section that follows it, which is where an off-by-one lands.
	 */
	private static byte[] v6Structure() throws IOException {
		final int canvasId = 1;
		StructureWriter w = new StructureWriter((short) 6, "gpu-addr", EPOCH, 7, 900L, 2, 3);
		w.resources(1)
				.canvasWithContent(canvasId, 64, 32, 16);
		w.nodes(2)
				.nodeV5(1, V2Wire.NODE_CANVAS, canvasId, 0, 0, 0, 0xFFFFFFFF, 0)
				.nodeV5(2, V2Wire.NODE_GROUP, 0, 1.5, -2.5, 3, 0x80FF00FF, 1);
		w.programsV6(5, 1); // nextProgramId 5, one record (id 1)
		return w.done();
	}

	/**
	 * THE 6 → 7 BUMP'S OBLIGATION, discharged: a v6 save must still load after `animator` was
	 * appended to the node record AND the scene epoch was appended after the program section.
	 *
	 * Two gates in one bump is the case worth a test of its own. The node gate reads at the wrong
	 * width if it says >= 6 instead of >= 7, which would take each node's animator out of the next
	 * node's id; the tail gate consumes the trailing-data guard's bytes if it is ungated at all.
	 * Both failures land on the SAME path — restoreOrFresh answering a CodecException by deleting
	 * the scene's stored bodies — so this drives the restore policy as well as the codec.
	 */
	@Test
	public void aV6StructureStillDecodesAfterTheNodeRecordAndSceneEpochGrew() throws Exception {
		byte[] structure = v6Structure();

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		assertEquals("a v6 scene restores its nodes", 2, decoded.state.nodes.size());
		assertEquals("and its resources", 1, decoded.state.resources.size());
		assertEquals("and its programs", 1, decoded.state.programs.size());
		assertEquals("the program counter is the v6 one, read at its own offset",
				5, decoded.state.nextProgramId);
		// The node record must have been read at 62 bytes, not 66. If the gate said >= 6, node 1
		// would have consumed node 2's id as its animator and the loop would have desynced — so
		// the parent relation surviving is the discriminating assertion, not the count.
		assertEquals("node 2's parent came through, so the record width was right",
				1, decoded.state.nodes.get(Integer.valueOf(2)).parent);
		assertEquals("a v6 world has no attachments, and must not acquire phantom ones",
				0, decoded.state.nodes.get(Integer.valueOf(1)).animator);
		assertEquals(0, decoded.state.nodes.get(Integer.valueOf(2)).animator);
		assertEquals("a v6 world has no epoch on disk and must restore as unstamped",
				0L, decoded.state.creationWorldTime);

		// And the restore path, which is the one that deletes bodies on failure — driven the way
		// the v5 obligation test drives it, against the store this class sets up in @Before.
		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals("restore agrees with the codec on node count",
				2, result.scene.state().nodes.size());
		assertEquals("and carries the v6 program through",
				1, result.scene.state().programs.size());
		assertEquals("with no attachment invented on the way",
				0, result.scene.state().nodes.get(Integer.valueOf(2)).animator);
	}

	/**
	 * THE 5 -> 6 BUMP'S OBLIGATION, discharged: a v5 save must still load after the program
	 * section was appended. Driven through BOTH paths the checklist names, because they fail
	 * differently — the codec throws, while restoreOrFresh answers a throw by DELETING the
	 * scene's stored bodies, so a decoder regression here costs worlds their textures rather
	 * than raising an error anyone sees.
	 */
	@Test
	public void aV5StructureStillDecodesAndRestoresAfterTheProgramSectionWasAppended()
			throws Exception {
		byte[] structure = v5Structure();

		// (a) the codec path: read at its own width by the version >= 6 gate, landing flush on
		// the trailing-data guard rather than consuming it.
		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		assertEquals("a v5 scene restores its nodes", 2, decoded.state.nodes.size());
		assertEquals("and its resources", 1, decoded.state.resources.size());
		assertTrue("a v5 world has no programs, and must not acquire phantom ones",
				decoded.state.programs.isEmpty());
		// The wrong answer written in: a decoder that skipped the gate would either throw
		// (truncation) or invent a program table from the trailing-guard bytes. Both are
		// excluded by asserting the table is EMPTY rather than merely small. (The counter line
		// below is honest about its weight: SceneState initialises nextProgramId to 1, so it
		// passes via the field initialiser and only the isEmpty assertion above discriminates.)
		assertEquals("the program counter starts fresh for a pre-program world",
				1, decoded.state.nextProgramId);

		// (b) the restore path, which is the one that deletes bodies on failure.
		// The store this class already sets up in @Before, not a hand-rolled one: the restore
		// path's failure mode is deleting stored bodies, so it must be exercised against the
		// same kind of store the real path uses.
		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals("restore agrees with the codec on node count",
				2, result.scene.state().nodes.size());
		assertTrue("restore must not fabricate programs either",
				result.scene.state().programs.isEmpty());
	}

	private static final short VERSION_THIS_TEST_WAS_WRITTEN_FOR = 7;

	@Test
	public void aProtocolBumpMustDecideWhatHappensToTheOutgoingFormat() {
		assertEquals("PROTOCOL_VERSION moved to " + V2Wire.PROTOCOL_VERSION + ". Decide, in the"
				+ " SAME edit, what happens to saves written as "
				+ VERSION_THIS_TEST_WAS_WRITTEN_FOR + ". Answer BOTH questions the extension"
				+ " policy asks (DESIGN-RENDERER-V2, Persistence & legacy migration), because one"
				+ " version number covers two formats. (1) THE RECORDS: unchanged -> add the old"
				+ " version to SnapshotCodec.LAYOUT_COMPATIBLE_PERSISTED_VERSIONS with its reason;"
				+ " a field APPENDED -> add it there too AND gate the new read on the version, as"
				+ " v5 does for `parent`; a field MOVED, RESIZED or REORDERED -> no gate helps,"
				+ " write a decoder for the old layout as LegacyStructureCodec does for v2."
				+ " (2) THE OP TABLE: an op APPENDED is harmless, but changing an existing op's"
				+ " arity or reusing a retired id silently misreads every old canvas and rules the"
				+ " old version out entirely. Doing NEITHER deletes every existing world's scenes"
				+ " on first chunk load. Then add a golden fixture for "
				+ VERSION_THIS_TEST_WAS_WRITTEN_FOR + " pinned to a LITERAL version, drive it"
				+ " through restoreOrFresh as well as the codec, and update this constant last.",
				VERSION_THIS_TEST_WAS_WRITTEN_FOR, V2Wire.PROTOCOL_VERSION);
	}
}
