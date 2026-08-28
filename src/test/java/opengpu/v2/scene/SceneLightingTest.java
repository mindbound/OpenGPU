package opengpu.v2.scene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * C1.3.2's selection and validation rules: which lights light, and when a light is refused.
 *
 * Written against the same standard as {@link ActiveCameraTest}, and for the same reason — every
 * rule here has a plausible wrong implementation that no other channel catches:
 *
 * <ul>
 * <li><b>The ceiling has two sides.</b> "At most two lights" is satisfied by an implementation
 *     that selects ZERO, or one, or the WRONG two. An upper-bound assertion alone cannot tell
 *     those apart, so the ceiling test below asserts the lower bound as well, by identity.</li>
 * <li><b>The visibility rule is one identifier from its opposite.</b> Lights use EFFECTIVE
 *     visibility and cameras use the raw own-flag; both readings are in scope and each looks
 *     obviously right. A discriminating test pins the disagreement directly.</li>
 * <li><b>Ambient not spending a hardware slot</b> is invisible until a scene has three lights,
 *     at which point the symptom is a missing light rather than an error.</li>
 * <li><b>"No lights authored" must stay null</b>, not become a helpful grey — CASEBOOK D11.</li>
 * </ul>
 *
 * <b>The file has two halves, and they enter through different doors on purpose.</b> Everything
 * up to "the host verb" writes {@code __light} DIRECTLY into the node's table — the shape a
 * MIRROR payload arrives in, having passed {@code DeltaApplier}/{@code SnapshotCodec}, which check
 * name legality and value count and never the band. Those malformed entries are exactly the ones
 * no host verb would ever produce, and {@code lightParams} is the only thing standing between
 * them and the renderer.
 *
 * The last section goes through {@link ServerScene#setLight} instead, exercising the ADMISSION
 * gate. Neither half substitutes for the other: the server refuses bad input at the door, and the
 * reader re-checks because the mirror never runs that door. The pair that matters most is
 * {@code theHostVerbWritesAnEntryTheRendererAccepts} — if the writer's output ever stops
 * satisfying the reader's validator, one of them is wrong and the symptom is a light that
 * silently never appears.
 */
public class SceneLightingTest {

	private static ServerScene scene() {
		return new ServerScene("lights");
	}

	private static SceneNode node(ServerScene s, int id) {
		return s.state().nodes.get(Integer.valueOf(id));
	}

	/** Writes __light straight into the table — the shape a mirror payload arrives in. */
	private static int light(ServerScene s, double kind, double r, double g, double b) {
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		node(s, id).uniforms.put(ServerScene.LIGHT_UNIFORM, new double[] { kind, r, g, b });
		return id;
	}

	private static int directional(ServerScene s) {
		return light(s, ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1);
	}

	// ------------------------------------------------------------------ validation

	@Test
	public void aLightWithNoUniformIsNotUsable() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		assertNull("a light node created and never configured has no params",
				s.state().lightParams(node(s, id)));
		assertTrue("and it must not be selected either", s.state().activeLights().isEmpty());
	}

	@Test
	public void aMalformedEntryIsRefusedWholeRatherThanPartly() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		node(s, id).uniforms.put(ServerScene.LIGHT_UNIFORM, new double[] { 1, 1, 1 });
		// The wrong answer written in: an implementation that read the components it HAS and
		// defaulted the rest would return non-null here and light the scene from a vec3.
		assertNull("a 3-component __light is refused, not padded",
				s.state().lightParams(node(s, id)));
	}

	@Test
	public void anUnrecognisedKindIsRefused() {
		ServerScene s = scene();
		int id = light(s, 4, 1, 1, 1);
		assertNull("kind 4 is not a light kind", s.state().lightParams(node(s, id)));
		int zero = light(s, 0, 1, 1, 1);
		assertNull("and neither is 0 — the absent-value default must not become a kind",
				s.state().lightParams(node(s, zero)));
	}

	@Test
	public void aNegativeComponentRefusesTheWholeLight() {
		ServerScene s = scene();
		int id = light(s, ServerScene.LIGHT_DIRECTIONAL, 1, -0.5, 1);
		// Refused rather than clamped: a negative component SUBTRACTS light, and a light that
		// darkens reads as an artist's choice rather than as a defect.
		assertNull("one negative component refuses the light", s.state().lightParams(node(s, id)));
	}

	@Test
	public void aNonFiniteComponentIsRefused() {
		ServerScene s = scene();
		// BOTH on DIRECTIONAL deliberately. An earlier draft put the infinity case on a POINT
		// light, which made one assertion carry two independent checks: delete kind 2 from the
		// whitelist and this line still passes, for the mutant's reason rather than its own.
		int nan = light(s, ServerScene.LIGHT_DIRECTIONAL, Double.NaN, 1, 1);
		int inf = light(s, ServerScene.LIGHT_DIRECTIONAL, 1, Double.POSITIVE_INFINITY, 1);
		int negInf = light(s, ServerScene.LIGHT_DIRECTIONAL, 1, 1, Double.NEGATIVE_INFINITY);
		assertNull("NaN is refused", s.state().lightParams(node(s, nan)));
		assertNull("and so is +infinity", s.state().lightParams(node(s, inf)));
		assertNull("and -infinity, which the >= 0 check would also catch",
				s.state().lightParams(node(s, negInf)));
	}

	@Test
	public void aNonFiniteKindIsRefused() {
		ServerScene s = scene();
		int id = light(s, Double.NaN, 1, 1, 1);
		// Not an accident of the != chain, but worth pinning BECAUSE it is one: NaN != x is true
		// for every x, so all three kind comparisons pass and the light is refused before the
		// component loop runs. An implementation that switched to a range check (kind >= 1 &&
		// kind <= 3) would silently start accepting NaN, since those comparisons are false and
		// a naive rewrite inverts them.
		assertNull("a NaN kind is refused", s.state().lightParams(node(s, id)));
	}

	@Test
	public void aPointLightIsAHardwareLightLikeADirectionalOne() {
		ServerScene s = scene();
		int id = light(s, ServerScene.LIGHT_POINT, 1, 1, 1);
		// THE POSITIVE KIND-2 CASE. Without it, deleting `kind != LIGHT_POINT` from the
		// whitelist leaves every other test in this file green: kind 2 would return null at the
		// gate, activeLights and ambientLight both skip on null, and nothing errors. The symptom
		// would be a point light that silently does nothing — the failure this file's own
		// preamble calls invisible without a test. ActiveCameraTest covers both projection modes
		// positively for the same reason; this is the sibling obligation.
		assertTrue("kind 2 is accepted", s.state().lightParams(node(s, id)) != null);
		assertSame("and it spends a hardware slot", node(s, id), s.state().activeLights().get(0));
		assertNull("while contributing nothing to the ambient term", s.state().ambientLight());
	}

	@Test
	public void aDirectionalLightIsAcceptedAndSpendsASlot() {
		ServerScene s = scene();
		int id = light(s, ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1);
		// The kind-1 mirror of the test above, so neither accepted kind rests on the other.
		assertTrue("kind 1 is accepted", s.state().lightParams(node(s, id)) != null);
		assertSame("and it spends a hardware slot", node(s, id), s.state().activeLights().get(0));
		assertNull("and contributes no ambient term", s.state().ambientLight());
	}

	@Test
	public void anAmbientLightIsAcceptedAndSpendsNoSlot() {
		ServerScene s = scene();
		int id = light(s, ServerScene.LIGHT_AMBIENT, 0.5, 0.5, 0.5);
		// And kind 3, completing the set: every accepted kind now has a positive test naming it,
		// so removing any one of the three from the whitelist fails a test that names that kind.
		assertTrue("kind 3 is accepted", s.state().lightParams(node(s, id)) != null);
		assertTrue("but spends NO hardware slot", s.state().activeLights().isEmpty());
		assertEquals("contributing to the ambient term instead",
				0.5, s.state().ambientLight()[0], 1e-12);
	}

	@Test
	public void zeroIsAValidColourAndNotAMalformedOne() {
		ServerScene s = scene();
		int id = light(s, ServerScene.LIGHT_DIRECTIONAL, 0, 0, 0);
		// The falsifier for an over-strict validator: a black light is legal and merely does
		// nothing. Refusing it would make "off" and "malformed" the same state.
		assertTrue("an all-zero colour is a valid light",
				s.state().lightParams(node(s, id)) != null);
	}

	@Test
	public void aLightUniformOnANonLightNodeIsIgnored() {
		ServerScene s = scene();
		int group = s.createNode(V2Wire.NODE_GROUP, 0);
		node(s, group).uniforms.put(ServerScene.LIGHT_UNIFORM,
				new double[] { ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1 });
		assertNull("the type gate runs before the uniform read",
				s.state().lightParams(node(s, group)));
		assertTrue("and no such node reaches the selection",
				s.state().activeLights().isEmpty());
	}

	// ------------------------------------------------------------------ the ceiling

	@Test
	public void aThirdLightIsRefusedAndTheFirstTwoStillArrive() {
		ServerScene s = scene();
		int a = directional(s);
		int b = directional(s);
		int c = directional(s);
		assertTrue("ids must ascend for this test to be about ORDER", a < b && b < c);

		List<SceneNode> active = s.state().activeLights();
		// THE UPPER BOUND — which alone is satisfied by selecting zero, one, or the wrong two.
		assertEquals("the ceiling drops the third light", 2, active.size());
		// THE LOWER BOUND, by identity, which is the half that makes the assertion above mean
		// anything. caps-have-two-sides: read what the enforcement site DOES on trip.
		assertSame("the first light still arrives", node(s, a), active.get(0));
		assertSame("and so does the second, in id order", node(s, b), active.get(1));
		assertTrue("the third is absent, not merely last",
				!active.contains(node(s, c)));
	}

	@Test
	public void hidingASelectedLightPromotesTheNextOne() {
		ServerScene s = scene();
		int a = directional(s);
		int b = directional(s);
		int c = directional(s);
		s.setVisible(a, false);

		List<SceneNode> active = s.state().activeLights();
		assertEquals("still two", 2, active.size());
		assertSame("the third is promoted into the freed slot", node(s, b), active.get(0));
		assertSame("and the fourth-in-line takes the second slot", node(s, c), active.get(1));

		s.setVisible(a, true);
		// Proving nothing was cached: the promotion must reverse.
		assertSame("un-hiding demotes them again", node(s, a),
				s.state().activeLights().get(0));
	}

	@Test
	public void anInvalidLightDoesNotConsumeASlot() {
		ServerScene s = scene();
		int broken = s.createNode(V2Wire.NODE_LIGHT, 0);   // no __light at all
		int a = directional(s);
		int b = directional(s);
		assertTrue("the broken light sorts FIRST, so it would take a slot if counted",
				broken < a);

		List<SceneNode> active = s.state().activeLights();
		assertEquals("two usable lights are found past the broken one", 2, active.size());
		assertSame(node(s, a), active.get(0));
		assertSame(node(s, b), active.get(1));
	}

	// ------------------------------------------------------------------ ambient

	@Test
	public void ambientDoesNotConsumeAHardwareSlot() {
		ServerScene s = scene();
		int amb = light(s, ServerScene.LIGHT_AMBIENT, 0.2, 0.2, 0.2);
		int a = directional(s);
		int b = directional(s);
		assertTrue("ambient sorts first, so a naive selector would spend a slot on it", amb < a);

		List<SceneNode> active = s.state().activeLights();
		assertEquals("ambient is not in the hardware list", 2, active.size());
		assertSame("both directionals survive", node(s, a), active.get(0));
		assertSame(node(s, b), active.get(1));
		assertTrue("and the ambient light is not among them", !active.contains(node(s, amb)));

		double[] ambient = s.state().ambientLight();
		assertTrue("while still contributing its term", ambient != null);
		assertEquals(0.2, ambient[0], 1e-12);
	}

	@Test
	public void ambientIsSummedAcrossEveryVisibleAmbientLight() {
		ServerScene s = scene();
		light(s, ServerScene.LIGHT_AMBIENT, 0.1, 0.2, 0.3);
		light(s, ServerScene.LIGHT_AMBIENT, 0.4, 0.0, 0.5);

		double[] ambient = s.state().ambientLight();
		assertTrue("two ambient lights produce a term", ambient != null);
		// The wrong answers written in: last-wins would read {0.4, 0.0, 0.5} and first-wins
		// {0.1, 0.2, 0.3}. Both depend on node id, which the author does not control.
		assertEquals("r summed", 0.5, ambient[0], 1e-12);
		assertEquals("g summed", 0.2, ambient[1], 1e-12);
		assertEquals("b summed", 0.8, ambient[2], 1e-12);
	}

	@Test
	public void noAmbientLightMeansNullNotASubstitutedGrey() {
		ServerScene s = scene();
		directional(s);
		// CASEBOOK D11. A "reasonable" default here renders a believable picture from numbers
		// nobody supplied, and no channel downstream can tell it from an authored value.
		assertNull("no ambient authored means no ambient term is written at all",
				s.state().ambientLight());
	}

	@Test
	public void anAmbientLightIsUnclampedBecauseIntensityRidesTheComponents() {
		ServerScene s = scene();
		light(s, ServerScene.LIGHT_AMBIENT, 3.0, 0, 0);
		assertEquals("over-driving is the author's call, not the model's",
				3.0, s.state().ambientLight()[0], 1e-12);
	}

	// ------------------------------------------------------------------ visibility

	@Test
	public void aHiddenLightIsNotSelected() {
		ServerScene s = scene();
		int a = directional(s);
		s.setVisible(a, false);
		assertTrue("its own flag is enough to switch it off",
				s.state().activeLights().isEmpty());
	}

	@Test
	public void aLightInAHiddenGroupIsNotSelected() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int lamp = s.createNode(V2Wire.NODE_LIGHT, 0, rig);
		node(s, lamp).uniforms.put(ServerScene.LIGHT_UNIFORM,
				new double[] { ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1 });
		assertEquals("the lamp lights while its rig is visible",
				1, s.state().activeLights().size());

		s.setVisible(rig, false);
		// THE RULE, stated as its own test: a light is CONTENT, so hiding the rig that carries
		// it turns it off — exactly as it hides the rig's canvases and meshes.
		assertTrue("hiding the rig turns the lamp off", s.state().activeLights().isEmpty());
	}

	@Test
	public void anAmbientLightInAHiddenGroupStopsContributing() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int amb = s.createNode(V2Wire.NODE_LIGHT, 0, rig);
		node(s, amb).uniforms.put(ServerScene.LIGHT_UNIFORM,
				new double[] { ServerScene.LIGHT_AMBIENT, 0.5, 0.5, 0.5 });
		assertTrue("visible to start with", s.state().ambientLight() != null);

		s.setVisible(rig, false);
		// The mirror of the test above. Applying the visibility rule to the hardware list and
		// forgetting the ambient sum is the one-sided fix this pins shut (CASEBOOK D3).
		assertNull("the ambient term follows the same rule as the hardware list",
				s.state().ambientLight());
	}

	@Test
	public void aCameraInAHiddenGroupIsSelectedButALightIsNot() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0, rig);
		int lamp = s.createNode(V2Wire.NODE_LIGHT, 0, rig);
		node(s, lamp).uniforms.put(ServerScene.LIGHT_UNIFORM,
				new double[] { ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1 });
		s.setVisible(rig, false);

		// THE DISCRIMINATING TEST. The two rules genuinely disagree, on exactly this scene, and
		// this is the only place that disagreement is asserted rather than described. Unifying
		// them — in either direction — fails here, which is the point: a reviewer who "tidies"
		// activeCamera into isEffectivelyVisible, or activeLights into the raw flag, is caught.
		assertSame("a camera in a hidden rig stays eligible — a camera is a VIEWPOINT",
				node(s, cam), s.state().activeCamera());
		assertTrue("a light in the same hidden rig does not — a light is CONTENT",
				s.state().activeLights().isEmpty());
	}

	@Test
	public void effectiveVisibilityIsTheSameQuestionThe2dLayerAsks() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int child = s.createNode(V2Wire.NODE_CANVAS, 0, rig);
		assertTrue("a visible child of a visible parent is drawn",
				s.state().isEffectivelyVisible(node(s, child)));

		s.setVisible(rig, false);
		assertTrue("and a visible child of a HIDDEN parent is not",
				!s.state().isEffectivelyVisible(node(s, child)));

		s.setVisible(rig, true);
		s.setVisible(child, false);
		assertTrue("nor is a hidden child of a visible parent",
				!s.state().isEffectivelyVisible(node(s, child)));
	}

	// ------------------------------------------------------------------ the empty scene

	@Test
	public void noLightsMeansAnEmptyListNeverNull() {
		ServerScene s = scene();
		s.createNode(V2Wire.NODE_MESH_INSTANCE, 0);
		List<SceneNode> active = s.state().activeLights();
		assertTrue("the contract is an empty list, so callers need no null check",
				active != null && active.isEmpty());
	}

	@Test
	public void aSceneWithOnlyAmbientHasNoHardwareLights() {
		ServerScene s = scene();
		light(s, ServerScene.LIGHT_AMBIENT, 0.3, 0.3, 0.3);
		assertTrue("ambient alone lights nothing through a hardware slot",
				s.state().activeLights().isEmpty());
		assertTrue("but the term is still there", s.state().ambientLight() != null);
	}

	// ------------------------------------------------------------- the host verb (group C)
	//
	// Everything above writes __light straight into the table, which is the MIRROR's shape. These
	// exercise the ADMISSION gate instead. Both matter and neither substitutes for the other: the
	// server refuses bad input at the door, and lightParams re-checks because the mirror path
	// never runs that door.

	@Test
	public void theHostVerbWritesAnEntryTheRendererAccepts() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		s.setLight(id, ServerScene.LIGHT_DIRECTIONAL, 1, 0.5, 0.25);

		double[] p = s.state().lightParams(node(s, id));
		assertTrue("the verb's output must satisfy the reader's validator — if these two ever"
				+ " disagree, one of them is wrong and the scene silently loses a light",
				p != null);
		assertEquals("kind", ServerScene.LIGHT_DIRECTIONAL, p[0], 0.0);
		assertEquals("r", 1.0, p[1], 1e-12);
		assertEquals("g", 0.5, p[2], 1e-12);
		assertEquals("b", 0.25, p[3], 1e-12);
		assertSame("and it is selected", node(s, id), s.state().activeLights().get(0));
	}

	@Test
	public void theHostVerbRefusesANodeThatIsNotALight() {
		ServerScene s = scene();
		int group = s.createNode(V2Wire.NODE_GROUP, 0);
		try {
			s.setLight(group, ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1);
			fail("a group is not a light");
		} catch (IllegalStateException expected) {
			assertTrue("the message should name the node, got: " + expected.getMessage(),
					expected.getMessage().contains(String.valueOf(group)));
		}
		// The wrong answer written in: a verb that wrote the entry anyway would leave a group
		// carrying __light, which lightParams refuses by type — so the scene would look correct
		// and the light would simply never appear.
		assertTrue("and nothing was written", node(s, group).uniforms.isEmpty());
	}

	@Test
	public void theHostVerbRefusesAnUnknownNode() {
		ServerScene s = scene();
		try {
			s.setLight(9999, ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1);
			fail("node 9999 does not exist");
		} catch (IllegalStateException expected) {
			assertTrue("the message names the id, got: " + expected.getMessage(),
					expected.getMessage().contains("9999"));
		}
	}

	@Test
	public void theHostVerbRefusesBadColoursAndLeavesThePreviousValue() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		s.setLight(id, ServerScene.LIGHT_DIRECTIONAL, 0.75, 0.75, 0.75);

		double[][] bad = {
			{ -0.1, 1, 1 },
			{ 1, Double.NaN, 1 },
			{ 1, 1, Double.POSITIVE_INFINITY },
		};
		for (double[] c : bad) {
			try {
				s.setLight(id, ServerScene.LIGHT_DIRECTIONAL, c[0], c[1], c[2]);
				fail("expected refusal for " + java.util.Arrays.toString(c));
			} catch (IllegalArgumentException expected) {
				// The pinned set-call rule: rejection by throw retains the previous value BY
				// CONSTRUCTION, because applyAndStage never ran. Asserted rather than assumed —
				// a verb that validated after staging would leave the light corrupted.
			}
		}
		assertEquals("the previous colour survived every refusal", 0.75,
				s.state().lightParams(node(s, id))[1], 1e-12);
	}

	@Test
	public void theHostVerbRefusesAKindItDoesNotDefine() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		try {
			s.setLight(id, 4, 1, 1, 1);
			fail("kind 4 is not defined");
		} catch (IllegalArgumentException expected) {
			// Reachable only from Java — the three Lua verbs each pass a fixed kind — so this is
			// defence-in-depth, priced honestly. It exists because the kind is a DOUBLE on the
			// wire and a mirror payload can carry any value at all.
		}
	}

	@Test
	public void anAuthorStillCannotWriteTheReservedNameDirectly() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		try {
			s.setUniform(id, ServerScene.LIGHT_UNIFORM, new double[] { 1, 1, 1, 1 }, false);
			fail("__light is reserved for the host");
		} catch (IllegalArgumentException expected) {
			// ONE STATE, ONE DOOR. If setUniform ever stopped refusing __ names, setLight's
			// validation would become optional rather than mandatory, and every band check above
			// would be bypassable by an author who guessed the name.
			assertTrue("the message should explain the reservation, got: "
					+ expected.getMessage(), expected.getMessage().contains("__"));
		}
	}

	@Test
	public void aLightIsWrittenAsONEDeltaSoItCannotTearAtTheUniformCap() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		s.setLight(id, ServerScene.LIGHT_POINT, 1, 2, 3);
		// The atomicity claim, checked where it is observable: one NAME holding four values, not
		// four names holding one each. A four-entry design could half-apply at a full table and
		// leave a light with a kind and no colour, which lightParams would refuse whole — a light
		// that vanishes because a DIFFERENT node filled the table.
		assertEquals("exactly one uniform entry", 1, node(s, id).uniforms.size());
		assertEquals("carrying all four components", 4,
				node(s, id).uniforms.get(ServerScene.LIGHT_UNIFORM).length);
	}

	// ------------------------------------------------------- the light survives the wire
	//
	// GROUP C IS WHAT MAKES THESE NECESSARY. Until an author could CREATE a light, no NODE_LIGHT
	// could reach a save or a batch, and the type's presence in isKnownNodeType was a dormant
	// branch. The moment createLight shipped, every light in every scene starts being encoded —
	// so the codecs are now on the path and were being trusted rather than tested. Before this,
	// NODE_LIGHT appeared in the persistence test suite only inside a javadoc COMMENT.

	@Test
	public void aLightSurvivesTheNETWORKSnapshotRoundTrip() throws Exception {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		s.setLight(id, ServerScene.LIGHT_POINT, 0.25, 0.5, 0.75);
		s.sealBatch();

		SceneSnapshot back = opengpu.v2.protocol.SnapshotCodec.decode(
				opengpu.v2.protocol.SnapshotCodec.encode(s.snapshot()));

		SceneNode light = back.state.nodes.get(Integer.valueOf(id));
		assertTrue("the light node itself survived", light != null);
		assertEquals("as a light, not as some other type", V2Wire.NODE_LIGHT, light.type);
		// The whole point: the far side must find it USABLE, not merely present. A node that
		// decodes with its uniform mangled is a light that silently never appears.
		double[] p = back.state.lightParams(light);
		assertTrue("and the decoded __light passes the reader's validator", p != null);
		assertEquals("kind", ServerScene.LIGHT_POINT, p[0], 0.0);
		assertEquals("r", 0.25, p[1], 1e-12);
		assertEquals("g", 0.5, p[2], 1e-12);
		assertEquals("b", 0.75, p[3], 1e-12);
		assertSame("and it is still selected on the far side", light,
				back.state.activeLights().get(0));
	}

	@Test
	public void aLightSurvivesThePERSISTEDRoundTrip() throws Exception {
		ServerScene s = scene();
		int amb = s.createNode(V2Wire.NODE_LIGHT, 0);
		s.setLight(amb, ServerScene.LIGHT_AMBIENT, 0.125, 0.25, 0.375);
		s.sealBatch();

		// decodePersisted, not decode: it is the arm whose failure DELETES the scene's bodies,
		// so a light that the network path tolerates and the save path refuses would cost a world
		// rather than a frame.
		SceneSnapshot back = opengpu.v2.protocol.SnapshotCodec.decodePersisted(
				opengpu.v2.protocol.SnapshotCodec.encode(s.snapshot()));

		SceneNode light = back.state.nodes.get(Integer.valueOf(amb));
		assertEquals("the light survives the save path as a light", V2Wire.NODE_LIGHT, light.type);
		assertEquals("and its ambient term is intact", 0.125, back.state.ambientLight()[0], 1e-12);
		assertTrue("spending no hardware slot, as before", back.state.activeLights().isEmpty());
	}

	@Test
	public void aParentedLightKeepsItsRigAcrossTheWire() throws Exception {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int lamp = s.createNode(V2Wire.NODE_LIGHT, 0, rig);
		s.setLight(lamp, ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1);
		s.setVisible(rig, false);
		s.sealBatch();

		SceneSnapshot back = opengpu.v2.protocol.SnapshotCodec.decode(
				opengpu.v2.protocol.SnapshotCodec.encode(s.snapshot()));

		// The EFFECTIVE-visibility rule depends on the parent link surviving. If `parent` were
		// dropped in transit the lamp would decode as unparented, its rig's hidden flag would
		// stop reaching it, and a light the author switched off would come back ON after a
		// reconnect — a defect visible only across a rejoin.
		assertEquals("the parent link survived", rig,
				back.state.nodes.get(Integer.valueOf(lamp)).parent);
		assertTrue("so the hidden rig still suppresses it on the far side",
				back.state.activeLights().isEmpty());
	}

	@Test
	public void changingAKindOverwritesRatherThanAccumulates() {
		ServerScene s = scene();
		int id = s.createNode(V2Wire.NODE_LIGHT, 0);
		s.setLight(id, ServerScene.LIGHT_DIRECTIONAL, 1, 1, 1);
		assertEquals("spends a slot as a directional", 1, s.state().activeLights().size());

		s.setLight(id, ServerScene.LIGHT_AMBIENT, 0.5, 0.5, 0.5);
		// The three verbs write the SAME reserved entry, so switching kind must move the light
		// between the hardware list and the ambient term rather than leaving it in both.
		assertTrue("and stops spending one as an ambient", s.state().activeLights().isEmpty());
		assertEquals("having moved into the ambient term", 0.5,
				s.state().ambientLight()[0], 1e-12);
		assertEquals("still one entry, overwritten", 1, node(s, id).uniforms.size());
	}
}
