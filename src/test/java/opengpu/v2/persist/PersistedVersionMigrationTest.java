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

		/**
		 * The v8 node record: everything v7 wrote, then ANIM-6's attach stamp. 74 bytes. A new
		 * writer again rather than a parameter, for the reason stated on {@link #node}.
		 */
		StructureWriter nodeV8(int id, byte type, int ref, double x, double y, int z, int tint,
				int parent, int animator, long attachedWorldTime) throws IOException {
			nodeV7(id, type, ref, x, y, z, tint, parent, animator);
			out.writeLong(attachedWorldTime);
			return this;
		}

		/** The v8 scene tail: the world-time anchor, written after the v7 epoch. */
		StructureWriter worldTimeAnchorV8(long anchor) throws IOException {
			out.writeLong(anchor);
			return this;
		}

		/**
		 * The v10 node record: everything v8 wrote, then the six 3D TRS doubles in the pinned
		 * order tz, sz, qx, qy, qz, qw. 122 bytes. A new writer again, per {@link #node}'s
		 * freeze rule — nodeV8 is the only executable record of what v8 AND v9 worlds hold.
		 */
		StructureWriter nodeV10(int id, byte type, int ref, double x, double y, int z, int tint,
				int parent, int animator, long attachedWorldTime,
				double tz, double sz, double qx, double qy, double qz, double qw)
				throws IOException {
			nodeV8(id, type, ref, x, y, z, tint, parent, animator, attachedWorldTime);
			out.writeDouble(tz);
			out.writeDouble(sz);
			out.writeDouble(qx);
			out.writeDouble(qy);
			out.writeDouble(qz);
			out.writeDouble(qw);
			return this;
		}

		/**
		 * The v10 mesh resource record: the frozen type-3 header convention (width =
		 * vertexCount, height = 1, sizeBytes = combined length, versions pinned 1/1, hash of
		 * the concatenation) plus the inline two-blob tail.
		 */
		StructureWriter meshV10(int id, byte[] vertexBytes, byte[] indexBytes) throws IOException {
			out.writeInt(id);
			out.writeByte(V2Wire.RES_MESH);
			out.writeInt(vertexBytes.length / V2Wire.MESH_VERTEX_STRIDE);
			out.writeInt(1);
			out.writeInt(vertexBytes.length + indexBytes.length);
			out.writeInt(1);
			out.writeInt(1);
			byte[] combined = new byte[vertexBytes.length + indexBytes.length];
			System.arraycopy(vertexBytes, 0, combined, 0, vertexBytes.length);
			System.arraycopy(indexBytes, 0, combined, vertexBytes.length, indexBytes.length);
			out.writeLong(V2Wire.contentHash(combined));
			out.writeInt(vertexBytes.length);
			out.write(vertexBytes);
			out.writeInt(indexBytes.length);
			out.write(indexBytes);
			return this;
		}

		/**
		 * The v10 uniform tail section header. WRITTEN EVEN WHEN EMPTY — the count int is
		 * load-bearing for the trailing-data guard (the programsV6 lesson, one bump later).
		 */
		StructureWriter uniformSectionV10(int nodeCount) throws IOException {
			out.writeInt(nodeCount);
			return this;
		}

		StructureWriter uniformGroupV10(int nodeId, int entryCount) throws IOException {
			out.writeInt(nodeId);
			out.writeInt(entryCount);
			return this;
		}

		StructureWriter uniformEntryV10(String name, double... values) throws IOException {
			out.writeUTF(name);
			out.writeByte(values.length);
			for (double v : values) {
				out.writeDouble(v);
			}
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
		if (version >= 10) {
			w.nodeV10(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L, 0, 1, 0, 0, 0, 1)
					.nodeV10(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2, 0x80FF00FF, 0, 0, 0L,
							0, 1, 0, 0, 0, 1);
		} else if (version >= 8) {
			w.nodeV8(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L)
					.nodeV8(2, V2Wire.NODE_SPRITE, TEX_ID, 3.5, -1.25, 2, 0x80FF00FF, 0, 0, 0L);
		} else if (version >= 7) {
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
		if (version >= 8) {
			w.worldTimeAnchorV8(0L);
		}
		if (version >= 10) {
			// EVEN WHEN EMPTY — omitting the count int would make every test built from
			// PROTOCOL_VERSION fail its own precondition at the trailing-data guard.
			w.uniformSectionV10(0);
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
				.nodeV10(1, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L, 0, 1, 0, 0, 0, 1)   // a legal parent
				.nodeV10(2, V2Wire.NODE_GROUP, 0, 1, 1, 0, 0xFFFFFFFF, 1, 0, 0L, 0, 1, 0, 0, 0, 1)   // a legal child of it
				.nodeV10(3, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 3, 0, 0L, 0, 1, 0, 0, 0, 1)   // parents itself
				.nodeV10(9, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L, 0, 1, 0, 0, 0, 1)   // present, unparented
				.nodeV10(4, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 9, 0, 0L, 0, 1, 0, 0, 0, 1)   // ...but 9 is above 4
				.nodeV10(5, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 2, 0, 0L, 0, 1, 0, 0, 0, 1)   // 2 is already a child
				.nodeV10(10, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 7, 0, 0L, 0, 1, 0, 0, 0, 1); // 7 was never written
		w.programsV6(1, 0); // v6 section: this fixture is pinned to PROTOCOL_VERSION
		w.creationWorldTimeV7(0L); // ...and therefore the v7 tail too
		w.worldTimeAnchorV8(0L); // ...and the v8 anchor
		w.uniformSectionV10(0); // ...and the v10 uniform section, even empty

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
				.nodeV10(1, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L, 0, 1, 0, 0, 0, 1)
				.nodeV10(2, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 7, 0, 0L, 0, 1, 0, 0, 0, 1); // 7 is above 2
		w.programsV6(1, 0); // v6 section: this fixture is pinned to PROTOCOL_VERSION
		w.creationWorldTimeV7(0L); // ...and therefore the v7 tail too
		w.worldTimeAnchorV8(0L); // ...and the v8 anchor
		w.uniformSectionV10(0); // ...and the v10 uniform section, even empty
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
	 * THE v8 NODE RECORD AND SCENE TAIL, pinned with values no field swap survives.
	 *
	 * Every other v8 fixture in this file writes {@code animator=0, attachedWorldTime=0L} and
	 * {@code worldTimeAnchor=0L}, which are INVARIANT under swapping the two node fields or
	 * word-swapping the long — so the whole v8 layout rested on an encoder-symmetric round trip
	 * (encode with the codec, decode with the same codec) that a paired bug passes cleanly. The
	 * v7 test below already learned this lesson for {@code animator}; the v8 bump did not repeat
	 * it, and a review caught that.
	 *
	 * The values are chosen so each 32-bit HALF differs from the other: a decoder reading the long
	 * as two ints in the wrong order, or reading the int and long in the wrong order, produces a
	 * different number rather than the same one.
	 *
	 * RENAMED 2026-08-22 at the 8 -> 9 bump. It builds from the LIVE {@code PROTOCOL_VERSION}, so
	 * it tests whatever the current record is and stopped being about v8 the moment the constant
	 * moved -- exactly the drift the literal-pinning rule exists to prevent. The v8 obligation is
	 * discharged by {@code aV8StructureStillDecodesAfterTheHeartbeatGainedATick} below, which
	 * pins its literal.
	 */
	@Test
	public void theCurrentNodeRecordAndTailSurviveWithValuesNoSwapCouldFake() throws Exception {
		final long stamp = 0x0000000100000002L;   // halves differ: 1 and 2
		final long epoch = 0x0000000300000004L;
		final long anchor = 0x0000000500000006L;
		// Six MUTUALLY-DISTINCT 3D values, none equal to a default (0 or 1) and none equal to
		// each other or to any 2D field — a swapped pair or an off-by-one read cannot fake all
		// six landing in the right fields.
		final double tz = 11.5, sz = 12.5, qx = 13.5, qy = 14.5, qz = 15.5, qw = 16.5;
		StructureWriter w = new StructureWriter(V2Wire.PROTOCOL_VERSION, "gpu-addr", EPOCH, 7, 900L, 2, 3);
		w.resources(1).canvasWithContent(1, 64, 32, 16);
		w.nodes(1).nodeV10(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF, 0, 31, stamp,
				tz, sz, qx, qy, qz, qw);
		w.programsV6(1, 0);
		w.creationWorldTimeV7(epoch);
		w.worldTimeAnchorV8(anchor);
		w.uniformSectionV10(0);

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(w.done());
		SceneNode node = decoded.state.nodes.get(Integer.valueOf(1));
		assertEquals("the animator id must not be read out of the stamp's bytes", 31, node.animator);
		assertEquals("the stamp must survive whole, in the right word order",
				stamp, node.attachedWorldTime);
		assertEquals("tz reads at its own offset", tz, node.tz, 0.0);
		assertEquals("sz reads at its own offset", sz, node.sz, 0.0);
		assertEquals("qx reads at its own offset", qx, node.qx, 0.0);
		assertEquals("qy reads at its own offset", qy, node.qy, 0.0);
		assertEquals("qz reads at its own offset", qz, node.qz, 0.0);
		assertEquals("qw reads at its own offset", qw, node.qw, 0.0);
		assertEquals("the epoch is its own field", epoch, decoded.state.creationWorldTime);
		assertEquals("and the anchor is its own field, distinct from the epoch",
				anchor, decoded.state.worldTimeAnchor);
	}

	/**
	 * A v8 structure, pinned to the LITERAL 8 — what every world saved between the 7 → 8 and
	 * 8 → 9 bumps holds on disk.
	 */
	private static byte[] v8Structure() throws IOException {
		final long stamp = 0x0000000700000009L;
		final int canvasId = 1;
		StructureWriter w = new StructureWriter((short) 8, "gpu-addr", EPOCH, 7, 900L, 2, 3);
		w.resources(1).canvasWithContent(canvasId, 64, 32, 16);
		w.nodes(2)
				.nodeV8(1, V2Wire.NODE_CANVAS, canvasId, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L)
				.nodeV8(2, V2Wire.NODE_GROUP, 0, 1.5, -2.5, 3, 0x80FF00FF, 1, 31, stamp);
		w.programsV6(5, 1);
		w.creationWorldTimeV7(4242L);
		w.worldTimeAnchorV8(0x0000000500000006L);
		return w.done();
	}

	/**
	 * THE 8 → 9 BUMP'S OBLIGATION, discharged: a v8 save must still load after the heartbeat
	 * gained ANIM-13(b)'s server tick.
	 *
	 * THE EASIEST OBLIGATION IN THIS FILE, AND THE ONE MOST LIKELY TO BE SKIPPED FOR THAT REASON.
	 * The 8 → 9 bump touched a TRANSIENT message and no persisted record at all, so v8's layout
	 * IS v9's layout and this fixture differs from a current one only in its version short. The
	 * scoping note for this increment said "no new golden fixture needed" on exactly that
	 * reasoning, and the guardian refused it — correctly. What needs proving is not that the
	 * layout survived (it did not change) but that the v9 READER still ACCEPTS an 8: the whole
	 * risk of this bump lives in the version gate, where forgetting to whitelist 8 answers every
	 * existing world by deleting its scenes on first chunk load. A fixture that cost ten minutes
	 * covers a failure that costs a save.
	 *
	 * Driven through restore as well as the codec because those are different policies:
	 * {@code restoreOrFresh} turns a CodecException into deletion of the stored bodies, so a
	 * codec-only pass would not see the expensive half of the failure.
	 */
	@Test
	public void aV8StructureStillDecodesAfterTheHeartbeatGainedATick() throws Exception {
		byte[] structure = v8Structure();

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		assertEquals("a v8 scene restores its nodes", 2, decoded.state.nodes.size());
		assertEquals("and its programs", 1, decoded.state.programs.size());
		assertEquals("the scene epoch is read at its own offset", 4242L,
				decoded.state.creationWorldTime);
		assertEquals("and the v8 anchor with it", 0x0000000500000006L,
				decoded.state.worldTimeAnchor);
		SceneNode two = decoded.state.nodes.get(Integer.valueOf(2));
		assertEquals("node 2's parent came through", 1, two.parent);
		assertEquals("node 2's animator came through", 31, two.animator);
		assertEquals("and its attach stamp survived whole, in the right word order",
				0x0000000700000009L, two.attachedWorldTime);

		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals("restore agrees with the codec rather than deleting the scene",
				2, result.scene.state().nodes.size());
		assertEquals("and keeps the attachment", 31,
				result.scene.state().nodes.get(Integer.valueOf(2)).animator);
	}

	/**
	 * A v9 structure, pinned to the LITERAL 9 — what every world saved between the 8 → 9 and
	 * 9 → 10 bumps holds on disk. v9's LAYOUT IS v8's (the 8 → 9 bump touched only the
	 * transient heartbeat), so this reuses the frozen v8 writers and differs from v8Structure
	 * only in its version short — the same shape as the 8 → 9 obligation, one bump later.
	 */
	private static byte[] v9Structure() throws IOException {
		final long stamp = 0x0000000B0000000DL;
		final int canvasId = 1;
		StructureWriter w = new StructureWriter((short) 9, "gpu-addr", EPOCH, 7, 900L, 2, 3);
		w.resources(1).canvasWithContent(canvasId, 64, 32, 16);
		w.nodes(2)
				.nodeV8(1, V2Wire.NODE_CANVAS, canvasId, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L)
				.nodeV8(2, V2Wire.NODE_GROUP, 0, 1.5, -2.5, 3, 0x80FF00FF, 1, 31, stamp);
		w.programsV6(5, 1);
		w.creationWorldTimeV7(4242L);
		w.worldTimeAnchorV8(0x0000000500000006L);
		return w.done();
	}

	/**
	 * THE 9 → 10 BUMP'S BACKWARD OBLIGATION: a v9 save must still load after the node record,
	 * the resource record and the scene tail all grew.
	 *
	 * The assertions to care about are the IDENTITY DEFAULTS: the file's gate pattern defaults
	 * appended fields to 0/0L, and a copy-pasted 0 for sz or qw would silently collapse every
	 * pre-v10 node's scale and rotation the moment C1.3 starts consuming them — invisible until
	 * then, wrong forever after. Driven through restore as well as the codec, because
	 * restoreOrFresh turns a CodecException into deletion of the stored bodies.
	 */
	@Test
	public void aV9StructureStillDecodesAfterTheFormatGrewIn3D() throws Exception {
		byte[] structure = v9Structure();

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		assertEquals("a v9 scene restores its nodes", 2, decoded.state.nodes.size());
		assertEquals("and its programs", 1, decoded.state.programs.size());
		SceneNode two = decoded.state.nodes.get(Integer.valueOf(2));
		assertEquals("node 2's animator came through", 31, two.animator);
		assertEquals("and its stamp", 0x0000000B0000000DL, two.attachedWorldTime);
		assertEquals("a pre-v10 node restores with IDENTITY scale-z, not the zero default",
				1.0, two.sz, 0.0);
		assertEquals("and an IDENTITY quaternion w, not the zero default", 1.0, two.qw, 0.0);
		assertEquals("tz defaults to 0", 0.0, two.tz, 0.0);
		assertEquals("qx defaults to 0", 0.0, two.qx, 0.0);
		assertEquals("qy defaults to 0", 0.0, two.qy, 0.0);
		assertEquals("qz defaults to 0", 0.0, two.qz, 0.0);
		assertTrue("a v9 node restores with an empty uniform table", two.uniforms.isEmpty());

		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals("restore agrees with the codec rather than deleting the scene",
				2, result.scene.state().nodes.size());
		assertEquals("and keeps the identity scale-z", 1.0,
				result.scene.state().nodes.get(Integer.valueOf(2)).sz, 0.0);
	}

	/** A deterministic, valid v10 mesh: {@code vertexCount} stride-36 records, values non-zero. */
	private static byte[] testVertexBlob(int vertexCount) {
		byte[] blob = new byte[vertexCount * V2Wire.MESH_VERTEX_STRIDE];
		for (int i = 0; i < blob.length; i++) {
			blob[i] = (byte) (0x11 + i);
		}
		return blob;
	}

	/** One triangle 0,1,2 as u16 LITTLE-endian — the blob-interior convention. */
	private static byte[] testIndexBlob() {
		return new byte[] { 0, 0, 1, 0, 2, 0 };
	}

	/**
	 * A hand-written CURRENT-version structure carrying everything v10 added: a type-3 mesh
	 * record with its inline tail, a 3D-transformed node, and a populated uniform section —
	 * decoded field-for-field, the {@code aHandWrittenV6ProgramRecordDecodesFieldForField}
	 * discipline for the new format surface.
	 */
	@Test
	public void aHandWrittenV10MeshAndUniformSectionDecodesFieldForField() throws Exception {
		byte[] vertexBlob = testVertexBlob(3);
		byte[] indexBlob = testIndexBlob();
		StructureWriter w = new StructureWriter(V2Wire.PROTOCOL_VERSION, "gpu-addr", EPOCH, 7,
				900L, 3, 3);
		w.resources(2)
				.canvasWithContent(1, 64, 32, 16)
				.meshV10(2, vertexBlob, indexBlob);
		w.nodes(2)
				.nodeV10(1, V2Wire.NODE_CANVAS, 1, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L, 0, 1, 0, 0, 0, 1)
				.nodeV10(2, V2Wire.NODE_MESH_INSTANCE, 2, 3.5, -1.25, 2, 0x80FF00FF, 0, 0, 0L,
						7.5, 2.25, 0.5, -0.5, 0.5, 0.5);
		w.programsV6(1, 0);
		w.creationWorldTimeV7(0L);
		w.worldTimeAnchorV8(0L);
		w.uniformSectionV10(1);
		w.uniformGroupV10(2, 2);
		w.uniformEntryV10("speed", 2.5);
		w.uniformEntryV10("tint4", 0.1, 0.2, 0.3, 0.4);
		byte[] structure = w.done();

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		opengpu.v2.scene.ResourceInfo mesh = decoded.state.resources.get(Integer.valueOf(2));
		assertEquals("mesh type", V2Wire.RES_MESH, mesh.type);
		assertEquals("width carries the vertex count", 3, mesh.width);
		assertEquals("height is pinned 1", 1, mesh.height);
		assertEquals("mesh bytes arrive inline, both blobs combined",
				vertexBlob.length + indexBlob.length, mesh.bytes.length);
		assertEquals("a decoded mesh HOLDS its bytes (version = 1, not the pending 0)",
				1, mesh.version);
		SceneNode two = decoded.state.nodes.get(Integer.valueOf(2));
		assertEquals("a MESH_INSTANCE node decodes", V2Wire.NODE_MESH_INSTANCE, two.type);
		assertEquals("tz", 7.5, two.tz, 0.0);
		assertEquals("sz", 2.25, two.sz, 0.0);
		assertEquals("qw", 0.5, two.qw, 0.0);
		assertEquals("two uniforms restored", 2, two.uniforms.size());
		org.junit.Assert.assertArrayEquals("a float uniform round-trips",
				new double[] { 2.5 }, two.uniforms.get("speed"), 0.0);
		org.junit.Assert.assertArrayEquals("a vec4 uniform round-trips",
				new double[] { 0.1, 0.2, 0.3, 0.4 }, two.uniforms.get("tint4"), 0.0);
		// The NETWORK path accepts the same current-version payload — the strict decoder, so
		// the new sections are proven on both policies, not just the forgiving one.
		assertEquals(2, SnapshotCodec.decode(structure).state.nodes.size());
	}

	/**
	 * A mesh record whose fixed-width header disagrees with its own tail was written by no
	 * encoder — the cross-check must refuse it on the persisted path too (the throwing
	 * dims-check precedent governs impossible records), not absorb one half as truth.
	 */
	@Test
	public void aMeshHeaderDisagreeingWithItsTailIsRefusedNotAbsorbed() throws Exception {
		byte[] vertexBlob = testVertexBlob(3);
		byte[] indexBlob = testIndexBlob();
		StructureWriter w = new StructureWriter(V2Wire.PROTOCOL_VERSION, "gpu-addr", EPOCH, 7,
				900L, 3, 1);
		// Hand-write the record with width = 4 against a 3-vertex tail.
		w.resources(1);
		w.out.writeInt(2);
		w.out.writeByte(V2Wire.RES_MESH);
		w.out.writeInt(4);   // WRONG vertexCount
		w.out.writeInt(1);
		w.out.writeInt(vertexBlob.length + indexBlob.length);
		w.out.writeInt(1);
		w.out.writeInt(1);
		byte[] combined = new byte[vertexBlob.length + indexBlob.length];
		System.arraycopy(vertexBlob, 0, combined, 0, vertexBlob.length);
		System.arraycopy(indexBlob, 0, combined, vertexBlob.length, indexBlob.length);
		w.out.writeLong(V2Wire.contentHash(combined));
		w.out.writeInt(vertexBlob.length);
		w.out.write(vertexBlob);
		w.out.writeInt(indexBlob.length);
		w.out.write(indexBlob);
		w.nodes(0);
		w.programsV6(1, 0);
		w.creationWorldTimeV7(0L);
		w.worldTimeAnchorV8(0L);
		w.uniformSectionV10(0);
		try {
			SnapshotCodec.decodePersisted(w.done());
			fail("a header/tail disagreement decoded cleanly");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("disagrees"));
		}
	}

	/**
	 * The THIRD leg of the RES_MESH refusal (constraint 21): a type-3 record under a pre-v10
	 * version short is refused outright — never skipped, never absorbed as a data-less mesh.
	 * No v9 encoder could write one, so this payload is corruption by definition, and the
	 * misread (fabricating state from bytes that mean something else) is the failure this
	 * file's whitelist doctrine ranks worse than deletion.
	 */
	@Test
	public void aPreV10PayloadClaimingAMeshIsRefusedNotSkipped() throws Exception {
		StructureWriter w = new StructureWriter((short) 9, "gpu-addr", EPOCH, 7, 900L, 3, 1);
		w.resources(1).meshV10(2, testVertexBlob(3), testIndexBlob());
		w.nodes(0);
		w.programsV6(1, 0);
		w.creationWorldTimeV7(0L);
		w.worldTimeAnchorV8(0L);
		try {
			SnapshotCodec.decodePersisted(w.done());
			fail("a v9 payload with a type-3 record decoded cleanly");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("meshes exist only from v10"));
		}
	}

	/** A crafted 65-entry uniform group: no encoder writes one (the apply cap is 64). */
	@Test
	public void anOverCapUniformGroupIsRefusedAtItsCount() throws Exception {
		StructureWriter w = new StructureWriter(V2Wire.PROTOCOL_VERSION, "gpu-addr", EPOCH, 7,
				900L, 1, 2);
		w.resources(0);
		w.nodes(1).nodeV10(1, V2Wire.NODE_GROUP, 0, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L,
				0, 1, 0, 0, 0, 1);
		w.programsV6(1, 0);
		w.creationWorldTimeV7(0L);
		w.worldTimeAnchorV8(0L);
		w.uniformSectionV10(1);
		w.uniformGroupV10(1, opengpu.v2.scene.ServerScene.MAX_NODE_UNIFORMS + 1);
		for (int i = 0; i <= opengpu.v2.scene.ServerScene.MAX_NODE_UNIFORMS; i++) {
			w.uniformEntryV10("u" + i, 1.0);
		}
		try {
			SnapshotCodec.decodePersisted(w.done());
			fail("an over-cap group decoded cleanly — producer-max stopped bounding the decoder");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("entry count out of range"));
		}
	}

	/**
	 * A v7 structure: v7's node record (through {@code animator}) and the scene epoch, with NO v8
	 * additions — what a world saved between the 6 → 7 and 7 → 8 bumps holds on disk.
	 *
	 * Node 2 carries a NON-ZERO animator on purpose. A zero would be indistinguishable from the
	 * value a decoder that never read the field produces, which is the assertion-passes-on-the-
	 * default trap; a real id also means a v8 gate reading one version early would consume the
	 * NEXT node's id as this one's stamp and desync the loop visibly.
	 */
	private static byte[] v7Structure() throws IOException {
		final int canvasId = 1;
		StructureWriter w = new StructureWriter((short) 7, "gpu-addr", EPOCH, 7, 900L, 2, 3);
		w.resources(1)
				.canvasWithContent(canvasId, 64, 32, 16);
		w.nodes(2)
				.nodeV7(1, V2Wire.NODE_CANVAS, canvasId, 0, 0, 0, 0xFFFFFFFF, 0, 0)
				.nodeV7(2, V2Wire.NODE_GROUP, 0, 1.5, -2.5, 3, 0x80FF00FF, 1, 31);
		w.programsV6(5, 1);
		w.creationWorldTimeV7(4242L);
		return w.done();
	}

	/**
	 * THE 7 → 8 BUMP'S OBLIGATION, discharged: a v7 save must still load after ANIM-6's attach
	 * stamp was appended to the node record and the world-time anchor after the scene epoch.
	 *
	 * Same two-gates-in-one-bump shape as the v6 test below, and the same two failure modes: a
	 * node gate saying >= 7 misreads every record, and an ungated tail read eats the
	 * trailing-data guard's bytes. Both land on restoreOrFresh deleting the scene's bodies.
	 */
	@Test
	public void aV7StructureStillDecodesAfterTheAttachStampAndAnchorGrew() throws Exception {
		byte[] structure = v7Structure();

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		assertEquals("a v7 scene restores its nodes", 2, decoded.state.nodes.size());
		assertEquals("and its programs", 1, decoded.state.programs.size());
		assertEquals("the v7 epoch is read at its own offset", 4242L, decoded.state.creationWorldTime);
		// The node record must have been read at 66 bytes, not 74. Node 2's parent AND its
		// animator surviving together is what proves the width — either alone could survive a
		// desync by coincidence.
		SceneNode two = decoded.state.nodes.get(Integer.valueOf(2));
		assertEquals("node 2's parent came through", 1, two.parent);
		assertEquals("node 2's animator came through, so the record width was right", 31, two.animator);
		assertEquals("a v7 world has no attach stamp and must not acquire one",
				0L, two.attachedWorldTime);
		assertEquals("nor an anchor", 0L, decoded.state.worldTimeAnchor);

		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals("restore agrees with the codec", 2, result.scene.state().nodes.size());
		assertEquals("and keeps the attachment", 31,
				result.scene.state().nodes.get(Integer.valueOf(2)).animator);
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

	/**
	 * A v10 structure, pinned to the LITERAL 10 — what every world saved between the 9 → 10 and
	 * 10 → 11 bumps holds on disk.
	 *
	 * v10's layout IS v11's: the 10 → 11 bump (C1.3.2, lighting) claimed one node-type id and
	 * changed no record. So this differs from a current fixture only in its version short, which
	 * is the 8 → 9 shape again — and the reason that shape still needs a fixture is unchanged.
	 * What is being proved is not that the layout survived (it did not change) but that the v11
	 * READER ACCEPTS A 10. The whole risk of this bump lives in the version gate, where
	 * forgetting to whitelist 10 answers every existing world by deleting its scenes.
	 *
	 * Unlike the v9 fixture this one carries a MESH, a non-identity 3D TRS and a UNIFORM ENTRY —
	 * everything the 9 → 10 bump appended. A v10 fixture that omitted them would still exercise
	 * the version gate, but it would not exercise the gate ON THE SECTIONS v10 ADDED, and those
	 * are the reads that a mis-gated v11 would misalign. The uniform name is __proj because a
	 * reserved name is the one entry whose meaning the renderer acts on.
	 */
	private static byte[] v10Structure() throws IOException {
		final int canvasId = 1;
		final int meshId = 2;
		// Three vertices at the frozen stride, and a three-index triangle. The CONTENT is
		// irrelevant to the version gate; the LENGTHS are not, since the record declares both a
		// vertex count and a byte size and the decoder checks them against each other.
		byte[] vertexBytes = new byte[3 * V2Wire.MESH_VERTEX_STRIDE];
		for (int i = 0; i < vertexBytes.length; i++) {
			vertexBytes[i] = (byte) (i & 0x7F);
		}
		byte[] indexBytes = new byte[3 * V2Wire.MESH_INDEX_BYTES];
		indexBytes[0] = 0; indexBytes[2] = 1; indexBytes[4] = 2;

		StructureWriter w = new StructureWriter((short) 10, "gpu-addr", EPOCH, 7, 900L, 3, 3);
		w.resources(2)
				.canvasWithContent(canvasId, 64, 32, 16)
				.meshV10(meshId, vertexBytes, indexBytes);
		// Values no field swap survives, the v8 fixture's rule: tz, sz and each quaternion
		// component differ from one another, so a decoder reading the six doubles in the wrong
		// order produces different numbers rather than the same ones.
		w.nodes(2)
				.nodeV10(1, V2Wire.NODE_CANVAS, canvasId, 0, 0, 0, 0xFFFFFFFF, 0, 0, 0L,
						0, 1, 0, 0, 0, 1)
				.nodeV10(2, V2Wire.NODE_MESH_INSTANCE, meshId, 1.5, -2.5, 3, 0x80FF00FF, 1, 0, 0L,
						7.25, 2.5, 0.125, 0.25, 0.375, 0.5);
		w.programsV6(5, 1);
		w.creationWorldTimeV7(4242L);
		w.worldTimeAnchorV8(0x0000000500000006L);
		w.uniformSectionV10(1)
				.uniformGroupV10(2, 1)
				.uniformEntryV10("__proj", 1.0, 0.75, 0.1, 100.0);
		return w.done();
	}

	/**
	 * THE 10 → 11 BUMP'S OBLIGATION, discharged: a v10 save must still load after NODE_LIGHT
	 * joined the node-type table.
	 *
	 * This is the "easiest obligation in the file" shape for the third time, and the reasoning
	 * that makes it skippable is the reasoning that makes it necessary. The 10 → 11 bump changed
	 * NO layout — it widened the legal VALUE SPACE of an existing field — so every test built
	 * from PROTOCOL_VERSION stayed green on the build that introduced it, and every save that
	 * build writes is a v11. The gap would appear only on a world written by the previous build,
	 * which nothing in CI has. That is exactly the invisibility the guard below describes.
	 *
	 * Driven through restore as well as the codec, because those are different policies:
	 * restoreOrFresh turns a CodecException into deletion of the stored bodies, so a codec-only
	 * pass would not see the expensive half of the failure.
	 */
	@Test
	public void aV10StructureStillDecodesAfterNodeLightJoinedTheTypeTable() throws Exception {
		byte[] structure = v10Structure();

		SceneSnapshot decoded = SnapshotCodec.decodePersisted(structure);
		assertEquals("a v10 scene restores its nodes", 2, decoded.state.nodes.size());
		assertEquals("and both resources, the mesh included", 2, decoded.state.resources.size());
		assertEquals("and its programs", 1, decoded.state.programs.size());

		SceneNode two = decoded.state.nodes.get(Integer.valueOf(2));
		assertEquals("the mesh instance kept its ref", 2, two.ref);
		// The six v10 doubles, each distinct, read back at their own offsets. A reader that
		// swapped any pair returns a different number here rather than the same one.
		assertEquals("tz", 7.25, two.tz, 0.0);
		assertEquals("sz", 2.5, two.sz, 0.0);
		assertEquals("qx", 0.125, two.qx, 0.0);
		assertEquals("qy", 0.25, two.qy, 0.0);
		assertEquals("qz", 0.375, two.qz, 0.0);
		assertEquals("qw", 0.5, two.qw, 0.0);

		assertEquals("the v10 uniform section survived the gate", 1, two.uniforms.size());
		double[] proj = two.uniforms.get("__proj");
		assertTrue("__proj is present under its reserved name", proj != null);
		assertEquals("and carries all four components", 4, proj.length);
		assertEquals("mode", 1.0, proj[0], 0.0);
		assertEquals("far", 100.0, proj[3], 0.0);

		ScenePersistence.RestoreResult result = ScenePersistence.restore(structure, store);
		assertEquals("restore agrees with the codec rather than deleting the scene",
				2, result.scene.state().nodes.size());
		assertEquals("and keeps the mesh resource", 2, result.scene.state().resources.size());

		// AND THROUGH restoreOrFresh, which is the leg this file's own bump instruction asks for
		// and which no fixture here had ever run on a VALID structure — every one of them stops
		// at restore(). The distinction is the whole point of the instruction: restore() throws
		// on a bad structure and deletes nothing, while restoreOrFresh() is the caller that turns
		// a CodecException into deleteScene. Only this leg exercises the policy that costs a save.
		//
		// Worth being precise about what it adds and what it does not, so the next bump does not
		// over-trust it: restoreOrFresh's version gate is DERIVED (version >= V2_VERSION &&
		// version <= PROTOCOL_VERSION), so unlike SnapshotCodec's hand-maintained list it picked
		// up v10 automatically and cannot suffer the forgot-to-whitelist failure. The whitelist
		// obligation is covered by the decodePersisted leg above, which goes red if 10 is dropped
		// from LAYOUT_COMPATIBLE_PERSISTED_VERSIONS. What THIS leg covers is the end-to-end claim
		// the instruction actually makes: a v10 world loads under v11 without being archived.
		ScenePersistence.RestoreResult viaOrFresh =
				ScenePersistence.restoreOrFresh("gpu-addr", structure, store);
		assertEquals("a v10 world survives the path that is allowed to delete it",
				2, viaOrFresh.scene.state().nodes.size());
		assertEquals("with its mesh resource intact", 2, viaOrFresh.scene.state().resources.size());
		// The wrong answer written in: a fresh scene ALSO has a ServerScene and would pass a
		// null check, so the failure mode being excluded is "archived and started blank", which
		// announces itself in warnings and nowhere else.
		assertTrue("and with NO warnings — an archived or degraded load says so here, and a"
				+ " silently blank scene is exactly what this leg exists to catch, got: "
				+ viaOrFresh.warnings, viaOrFresh.warnings.isEmpty());
	}

	private static final short VERSION_THIS_TEST_WAS_WRITTEN_FOR = 11;

	/**
	 * The guard that makes the NEXT bump decide, instead of silently repeating this bug.
	 *
	 * Nothing else can catch the omission. A persisted-version gap is invisible on the build that
	 * introduces it — every save that build writes is current-version, so every test is green and
	 * every in-game load works. It only appears on a world written by the PREVIOUS build, which
	 * nothing in CI has. This fails the moment PROTOCOL_VERSION moves, next to the instructions.
	 *
	 * (RE-HOMED 2026-08-28. This javadoc had drifted ~500 lines away from the test it describes
	 * and sat immediately above v5Structure's own javadoc, where javac keeps only the second and
	 * discards this one. It documented nothing, and the guard it explains is the one thing in
	 * this file that a future bump reads first.)
	 */
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
