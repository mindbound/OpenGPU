package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

import li.cil.oc.api.machine.Callback;

/**
 * THE CONTROL {@code API_LEVEL} NEVER HAD.
 *
 * <h2>Why this exists</h2>
 *
 * {@code TileEntityGpu2.API_LEVEL} is the number a Lua program feature-detects on. Its javadoc
 * records the bump nearly being missed at levels 4 and 5 and actually being missed at level 3, and
 * diagnoses why nothing catches it: <i>"on the build you are developing every check passes and
 * every test is green. Only reading this line does."</i> It concludes, of itself, that <b>"a
 * warning in a javadoc is not a control"</b> — and then remained the only control through levels 6
 * and 7, which happened to be bumped correctly. On 2026-08-24 a grep of {@code src/test} for
 * {@code API_LEVEL} returned nothing at all. This is the control; the FIFTH miss is the first one
 * it can catch.
 *
 * <h2>Reflection for the callbacks — and the false premise that nearly stopped it</h2>
 *
 * The first version of this file parsed the source as TEXT, on the stated grounds that <i>"the OC
 * API is compileOnly and no JVM test can load this class"</i> — copied from
 * {@code SceneGraphTest}'s comment without being checked. <b>It is false.</b> A probe on
 * 2026-08-24 loaded {@code TileEntityGpu2}, {@code li.cil.oc.api.machine.Callback} and
 * {@code net.minecraft.tileentity.TileEntity}, and reflection saw all 68 annotated methods;
 * {@code build.gradle.kts} explains why (the {@code :dev} artifact serves the interfaces at test
 * runtime). The text parser it justified was defeated nine different ways by a review — four legal
 * declaration spellings shipped a working 69th callback green, a commented-out annotation withdrew
 * one green, and a javadoc mention of the word was parsed as an annotation.
 *
 * Reflection has none of those holes, and reaches something no regex could: the <b>Lua-visible</b>
 * name. OpenComputers resolves it as {@code if (a.value != null && a.value.trim != "") a.value else
 * m.getName} ({@code Callbacks.scala}), so {@code @Callback(value = "resolution")} renames the API
 * without touching the Java method — invisible to a parser reading method names. {@code getter} and
 * {@code setter} likewise turn a method into a Lua FIELD. All three are pinned below.
 *
 * <h2>Text for the getLimits keys, because invocation does NOT work</h2>
 *
 * The same probe tried to instantiate the tile entity and call {@code getLimits} directly — the
 * ideal pin, since it would compare real published VALUES against the constants. It throws NPE from
 * the constructor. So the keys and their value expressions are still read from the source, with the
 * two defects a review found in that reading fixed: the key regex now tolerates the wrapped
 * {@code out.put(} that is house style (two of the six new puts already wrap), and a
 * count cross-check makes a key the parser cannot see go red instead of silently vanishing.
 *
 * <h2>What makes the pin bite rather than describe</h2>
 *
 * Keyed by API level. Adding a callback or a key means the current level's row no longer matches,
 * and the only way back to green is to bump {@code API_LEVEL} and add the new level's row — the two
 * halves that must move together, forced to. Bumping without a row fails on the missing key.
 */
public class ApiSurfacePinTest {

	private static final String SOURCE =
			"src/main/java/opengpu/v2/mc/server/TileEntityGpu2.java";

	/**
	 * The surface AT EACH LEVEL. Add a row when you bump; never edit a row that has shipped.
	 *
	 * That last sentence is an obligation nothing checks — the pin cannot tell a corrected row from
	 * a falsified one. It is recorded rather than enforced, and it is the one thing here a reviewer
	 * still has to do by eye.
	 *
	 * Levels below 7 are absent because this pin was written at 7→8 and the earlier sets were never
	 * recorded anywhere; reconstructing them from git would be inventing history the pin cannot
	 * check.
	 */
	private static final Map<Integer, String> CALLBACKS_AT = new LinkedHashMap<Integer, String>();
	private static final Map<Integer, String> LIMIT_KEYS_AT = new LinkedHashMap<Integer, String>();

	/** Which constant each semantic key must be published FROM — every symbol must appear. */
	private static final Map<String, String[]> PUBLISHED_FROM =
			new LinkedHashMap<String, String[]>();

	static {
		// LUA-VISIBLE names, sorted. Sixty-eight, taken from reflection rather than recalled: a
		// first draft of this pin listed 33 from memory and silently omitted the whole
		// immediate-mode surface. A pin built from a model of the API instead of the API is worse
		// than no pin, because it certifies.
		String callbacks7 = "autopresent,bind,canvasOps,canvasSubmit,clear,clearNodes,"
				+ "clearRectangle,createCanvas,createCanvasNode,createGroup,createProgram,"
				+ "createSprite,createTexture,createTextureFrom,drawText,drawTexture,fill,"
				+ "filledOval,filledRectangle,filledTriangle,freeCanvas,freeNode,freeProgram,"
				+ "freeTexture,getColor,getEpoch,getFontMetrics,getFreeMemory,getLimits,"
				+ "getProgramBudget,getResolution,getScreen,getSize,getStats,getSubmitBudget,"
				+ "getTextWidth,getTotalMemory,getUsedMemory,getVersion,getWriteBudget,line,"
				+ "maxResolution,nodes,origin,oval,plot,pop,present,programs,push,rectangle,"
				+ "resetStats,rotate,rotateAround,scale,setAnimator,setColor,setFont,setNodeTint,"
				+ "setNodeTransform,setNodeVisible,setNodeZ,setResolution,swapVisibility,translate,"
				+ "triangle,unbind,writeRegion";
		String keys7 = "submitBytes,submitBytesPerTick,commandCap,textChars,writeBytes,"
				+ "writeBytesPerTick,textureDim,standingCommandBytes,programBytes,programBlobBytes";
		CALLBACKS_AT.put(Integer.valueOf(7), callbacks7);
		LIMIT_KEYS_AT.put(Integer.valueOf(7), keys7);

		// Level 8 (2026-08-24) — no new callback; six OCSL semantic caps published.
		// This row's expression is SHIPPED and untouched (the never-edit-a-shipped-row rule);
		// level 9 writes its own fresh literals rather than chaining off this one.
		CALLBACKS_AT.put(Integer.valueOf(8), callbacks7);
		LIMIT_KEYS_AT.put(Integer.valueOf(8), keys7 + ",animatorOps,animatorFetches,"
				+ "programRegisters,programFrameFloats,programUnrollProduct,programUniforms");

		// Level 9 (2026-08-26, Stage C C1.2) — the twelve 3D-surface verbs and four mesh/uniform
		// caps. A FRESH sorted literal, per the never-edit-a-shipped-row rule above.
		String callbacks9 = "autopresent,bind,canvasOps,canvasSubmit,clear,clearNodes,"
				+ "clearRectangle,createCamera,createCanvas,createCanvasNode,createGroup,"
				+ "createMesh,createMeshNode,createProgram,createSprite,createTexture,"
				+ "createTextureFrom,drawText,drawTexture,fill,"
				+ "filledOval,filledRectangle,filledTriangle,freeCanvas,freeMesh,freeNode,"
				+ "freeProgram,freeTexture,getColor,getEpoch,getFontMetrics,getFreeMemory,"
				+ "getLimits,getMeshBudget,getProgramBudget,getResolution,getScreen,getSize,"
				+ "getStats,getSubmitBudget,getTextWidth,getTotalMemory,getUsedMemory,getVersion,"
				+ "getWriteBudget,line,lookAt,maxResolution,meshes,nodes,origin,oval,plot,pop,"
				+ "present,programs,push,rectangle,resetStats,rotate,rotateAround,scale,"
				+ "setAnimator,setColor,setFont,setNodeTint,setNodeTransform,setNodeTransform3d,"
				+ "setNodeVisible,setNodeZ,setOrtho,setPerspective,setResolution,setUniform,"
				+ "setUniformImmediate,swapVisibility,translate,triangle,unbind,writeRegion";
		CALLBACKS_AT.put(Integer.valueOf(9), callbacks9);
		LIMIT_KEYS_AT.put(Integer.valueOf(9), "submitBytes,submitBytesPerTick,commandCap,"
				+ "textChars,writeBytes,writeBytesPerTick,textureDim,standingCommandBytes,"
				+ "programBytes,programBlobBytes,animatorOps,animatorFetches,programRegisters,"
				+ "programFrameFloats,programUnrollProduct,programUniforms,"
				+ "meshVertexBytes,meshIndexBytes,meshBytes,nodeUniforms");

		// Level 10 (2026-08-28, Stage C C1.3.2) — the four light verbs: createLight,
		// setDirectionalLight, setPointLight, setAmbientLight. 80 callbacks become 84.
		// A FRESH sorted literal, per the never-edit-a-shipped-row rule, and SORTED BY MACHINE
		// rather than by eye: three of the four insertions land at adjacencies that are easy to
		// get wrong (setAmbientLight before setAnimator; setPerspective before setPointLight;
		// createGroup, createLight, createMesh), and a mis-sorted row fails as a diff nobody
		// reads rather than as a clear message.
		//
		// NO NEW LIMIT KEY, and that is a decision rather than an omission — see API_LEVEL's
		// javadoc. The two-light ceiling is a client rendering limit the server does not
		// enforce; publishing it among server admission bounds would assert a gate that does not
		// exist. Level 10's key row is therefore level 9's set, rewritten fresh rather than
		// chained, because that rule applies to unchanged rows too.
		String callbacks10 = "autopresent,bind,canvasOps,canvasSubmit,clear,clearNodes,"
				+ "clearRectangle,createCamera,createCanvas,createCanvasNode,createGroup,"
				+ "createLight,createMesh,createMeshNode,createProgram,createSprite,"
				+ "createTexture,createTextureFrom,drawText,drawTexture,fill,filledOval,"
				+ "filledRectangle,filledTriangle,freeCanvas,freeMesh,freeNode,freeProgram,"
				+ "freeTexture,getColor,getEpoch,getFontMetrics,getFreeMemory,getLimits,"
				+ "getMeshBudget,getProgramBudget,getResolution,getScreen,getSize,getStats,"
				+ "getSubmitBudget,getTextWidth,getTotalMemory,getUsedMemory,getVersion,"
				+ "getWriteBudget,line,lookAt,maxResolution,meshes,nodes,origin,oval,plot,pop,"
				+ "present,programs,push,rectangle,resetStats,rotate,rotateAround,scale,"
				+ "setAmbientLight,setAnimator,setColor,setDirectionalLight,setFont,"
				+ "setNodeTint,setNodeTransform,setNodeTransform3d,setNodeVisible,setNodeZ,"
				+ "setOrtho,setPerspective,setPointLight,setResolution,setUniform,"
				+ "setUniformImmediate,swapVisibility,translate,triangle,unbind,writeRegion";
		CALLBACKS_AT.put(Integer.valueOf(10), callbacks10);
		LIMIT_KEYS_AT.put(Integer.valueOf(10), "submitBytes,submitBytesPerTick,commandCap,"
				+ "textChars,writeBytes,writeBytesPerTick,textureDim,standingCommandBytes,"
				+ "programBytes,programBlobBytes,animatorOps,animatorFetches,programRegisters,"
				+ "programFrameFloats,programUnrollProduct,programUniforms,"
				+ "meshVertexBytes,meshIndexBytes,meshBytes,nodeUniforms");

		// THE STAGE ARGUMENT IS PART OF THE VALUE. Checking only that the expression names some
		// constant let STAGE_ANIMATOR -> STAGE_PIXEL_MATERIAL through, which publishes 16 for
		// animatorFetches where the comment three lines above insists on 0 — the exact lie the
		// animator prefix was invented to prevent.
		PUBLISHED_FROM.put("animatorOps", new String[] { "maxStructuralOps", "STAGE_ANIMATOR" });
		PUBLISHED_FROM.put("animatorFetches", new String[] { "maxFetches", "STAGE_ANIMATOR" });
		PUBLISHED_FROM.put("programRegisters", new String[] { "SurfaceTable.MAX_REGISTERS" });
		PUBLISHED_FROM.put("programFrameFloats", new String[] { "SurfaceTable.MAX_FRAME_WIDTH" });
		PUBLISHED_FROM.put("programUnrollProduct",
				new String[] { "IrValidator.MAX_UNROLL_PRODUCT" });
		PUBLISHED_FROM.put("programUniforms", new String[] { "SurfaceTable.MAX_UNIFORMS" });
		// The level-9 mesh/uniform caps, CLASS-QUALIFIED deliberately: an unqualified
		// "MAX_MESH_BYTES" would be a contains-match substring of MAX_MESH_BYTES_PER_BATCH —
		// the exact wrong-constant edit this map exists to make red.
		PUBLISHED_FROM.put("meshVertexBytes", new String[] { "V2Wire.MAX_MESH_VERTEX_BYTES" });
		PUBLISHED_FROM.put("meshIndexBytes", new String[] { "V2Wire.MAX_MESH_INDEX_BYTES" });
		PUBLISHED_FROM.put("meshBytes", new String[] { "ServerScene.MAX_MESH_BYTES" });
		PUBLISHED_FROM.put("nodeUniforms", new String[] { "ServerScene.MAX_NODE_UNIFORMS" });
	}

	private static String source() throws Exception {
		File f = new File(SOURCE);
		// NOT an Assume: src/ is tracked, unlike the gitignored docs/ that CapsInventoryTest has to
		// tolerate the absence of. A missing source file means the harness is broken.
		assertTrue(SOURCE + " must be readable for this pin to mean anything", f.isFile());
		return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
	}

	/**
	 * The LUA-VISIBLE surface, from the annotations themselves.
	 *
	 * A getter or setter is recorded with its shape, because those are not callable methods in Lua
	 * at all — they present as a field, and turning a function into a field is a breaking change no
	 * name comparison would notice.
	 */
	private static List<String> luaSurface() throws Exception {
		Class<?> c = Class.forName("opengpu.v2.mc.server.TileEntityGpu2");
		List<String> out = new ArrayList<String>();
		for (Method m : c.getDeclaredMethods()) {
			Callback a = m.getAnnotation(Callback.class);
			if (a == null) {
				continue;
			}
			String name = a.value() != null && a.value().trim().length() > 0
					? a.value().trim() : m.getName();
			if (a.getter()) {
				name = name + ":getter";
			}
			if (a.setter()) {
				name = name + ":setter";
			}
			out.add(name);
		}
		Collections.sort(out);
		return out;
	}

	/** The body of getLimits, as text. */
	private static String limitsBody(String src) {
		int start = src.indexOf("public Object[] getLimits(");
		assertTrue("getLimits must still exist under that name", start > 0);
		int end = src.indexOf("\n\t}", start);
		assertTrue("getLimits must have a closing brace", end > start);
		return src.substring(start, end);
	}

	/**
	 * The keys getLimits emits, in emission order.
	 *
	 * {@code \s*} after the paren is not cosmetic: the wrapped {@code out.put(} is house style and
	 * two of the six semantic puts already wrap, so the un-tolerant regex made a real, ordinary
	 * formatting choice hide a key.
	 */
	private static List<String> limitKeys(String body) {
		List<String> keys = new ArrayList<String>();
		Matcher m = Pattern.compile("out\\.put\\(\\s*\"(\\w+)\"").matcher(body);
		while (m.find()) {
			keys.add(m.group(1));
		}
		return keys;
	}

	@Test
	public void theApiLevelIsTheOneThisPinWasWrittenAgainst() {
		Integer level = Integer.valueOf(opengpu.v2.mc.server.TileEntityGpu2.API_LEVEL);
		assertTrue("API_LEVEL is " + level + " and this pin has no row for it. If you just bumped"
				+ " it, add the new level's callback and getLimits rows to CALLBACKS_AT and"
				+ " LIMIT_KEYS_AT — that is the other half of the bump.",
				CALLBACKS_AT.containsKey(level) && LIMIT_KEYS_AT.containsKey(level));
	}

	/** ADDING, RENAMING OR WITHDRAWING A CALLBACK WITHOUT BUMPING IS RED. */
	@Test
	public void theLuaVisibleCallbackSurfaceIsExactlyWhatThisApiLevelPromises() throws Exception {
		int level = opengpu.v2.mc.server.TileEntityGpu2.API_LEVEL;
		List<String> found = luaSurface();
		StringBuilder joined = new StringBuilder();
		for (String n : found) {
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append(n);
		}
		assertEquals("the Lua-visible callback surface changed but API_LEVEL is still " + level
				+ ". A callback is a feature — and so is a RENAME, since @Callback(value=...) sets"
				+ " the Lua name, and so is getter/setter, which turns a function into a field."
				+ " Bump API_LEVEL and add the new level's row here, in the SAME edit.",
				CALLBACKS_AT.get(Integer.valueOf(level)), joined.toString());
	}

	/**
	 * ADDING A getLimits KEY WITHOUT BUMPING IS RED — the key-only case, which has no new callback
	 * to remind anyone. Order is part of the pin: getLimits emits into a LinkedHashMap, so emission
	 * order is what a Lua caller sees when iterating.
	 */
	@Test
	public void theGetLimitsKeysAreExactlyWhatThisApiLevelPromises() throws Exception {
		int level = opengpu.v2.mc.server.TileEntityGpu2.API_LEVEL;
		String body = limitsBody(source());
		List<String> found = limitKeys(body);

		// THE PARSER MUST NOT BE ABLE TO PASS BY SEEING LESS. Counting the puts independently of
		// the key regex is what turns "a key the parser cannot read" from silence into failure.
		int puts = body.split("out\\.put\\(", -1).length - 1;
		assertEquals("getLimits emits " + puts + " keys but this pin can read " + found.size()
				+ ". A key the parser cannot see is a key that ships unpinned.",
				puts, found.size());

		StringBuilder joined = new StringBuilder();
		for (String k : found) {
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append(k);
		}
		assertEquals("getLimits's published keys changed but API_LEVEL is still " + level
				+ ". A key is a feature — without a bump a program cannot tell an older server from"
				+ " a removed key. Bump and add the new level's row here, in the SAME edit.",
				LIMIT_KEYS_AT.get(Integer.valueOf(level)), joined.toString());
	}

	/**
	 * EACH SEMANTIC CAP IS PUBLISHED FROM THE CONSTANT THAT ENFORCES IT — named, not merely
	 * class-prefixed.
	 *
	 * A literal here is a duplicated wire constant that drifts the day the cap moves, and the
	 * shipped Lua library has already fallen into that hole once with MAX_SUBMIT_BYTES. The earlier
	 * version of this test only checked that "IrValidator." or "SurfaceTable." appeared somewhere
	 * in the expression, which let four wrong-value edits through — including reading
	 * MAX_FRAME_WIDTH for programRegisters, and dividing a constant by two.
	 */
	@Test
	public void eachSemanticCapNamesTheConstantThatEnforcesIt() throws Exception {
		String body = limitsBody(source());
		for (Map.Entry<String, String[]> e : PUBLISHED_FROM.entrySet()) {
			String key = e.getKey();
			Matcher m = Pattern.compile(
					"out\\.put\\(\\s*\"" + key + "\"\\s*,([^;]+);").matcher(body);
			assertTrue("no emission found for " + key, m.find());
			String expr = m.group(1);
			for (String symbol : e.getValue()) {
				assertTrue(key + " must be published from " + symbol + ", not from anything else."
						+ " Found: " + expr.trim(), expr.contains(symbol));
			}
			// Remove every identifier; a digit that survives is a numeric literal or arithmetic
			// on the constant, and both defeat the point of reading it from the source.
			String stripped = expr.replaceAll("[A-Za-z_][A-Za-z0-9_.]*", "");
			assertTrue(key + " publishes a literal or arithmetic rather than the constant"
					+ " itself: " + expr.trim(),
					!Pattern.compile("[0-9]").matcher(stripped).find());
		}
	}

	/**
	 * THE RELAY FAMILIES: each twin verb must pass its OWN discriminator to the shared relay.
	 *
	 * Seven Lua verbs are thin wrappers that differ only in one constant handed to a private
	 * relay — {@code setPerspective}/{@code setOrtho} (a boolean into {@code projectionRelay}),
	 * {@code setUniform}/{@code setUniformImmediate} (a boolean into {@code uniformSetRelay}),
	 * and the three light setters (a {@code double} kind into {@code lightRelay}). For those
	 * verbs the constant IS the entire semantics, and NOTHING else in the suite reads it:
	 *
	 * <ul>
	 * <li>{@link #luaSurface} reflects annotation NAMES, so a swapped discriminator leaves the
	 *     surface list byte-identical.</li>
	 * <li>{@code limitsBody} slices only {@code getLimits}, nowhere near these methods.</li>
	 * <li>Every behavioural test enters through {@code ServerScene} with the value supplied by
	 *     the test, so all of them pass under a mis-bound verb.</li>
	 * <li>The kinds are all {@code double} and the flags all {@code boolean}, so every swap
	 *     COMPILES.</li>
	 * </ul>
	 *
	 * The worst variant is not subtle: give {@code setAmbientLight} the POINT kind and an ambient
	 * light validates, is collected as a hardware light, spends one of only two slots, and drops
	 * out of the ambient sum — a scene lit wrongly with no error anywhere.
	 *
	 * ASSERTED PER ENTRY, not "all three constants appear somewhere in the file": each body must
	 * name its own discriminator AND none of its siblings', so a straight swap fails twice.
	 * That is the lesson CASEBOOK recorded earlier in this same increment, when a light kind
	 * could be deleted from a whitelist with every test still green because no test named it.
	 *
	 * Written as a SOURCE-TEXT pin because {@code TileEntityGpu2} cannot be instantiated in a JVM
	 * test — the same constraint, and the same idiom, as
	 * {@link #eachSemanticCapNamesTheConstantThatEnforcesIt} above.
	 */
	@Test
	public void eachTwinVerbPassesItsOwnDiscriminatorToTheSharedRelay() throws Exception {
		String src = source();
		// verb -> { the token its body MUST contain, then the sibling tokens it must NOT }
		Map<String, String[]> expected = new LinkedHashMap<String, String[]>();
		expected.put("setDirectionalLight", new String[] {
				"LIGHT_DIRECTIONAL", "LIGHT_POINT", "LIGHT_AMBIENT" });
		expected.put("setPointLight", new String[] {
				"LIGHT_POINT", "LIGHT_DIRECTIONAL", "LIGHT_AMBIENT" });
		expected.put("setAmbientLight", new String[] {
				"LIGHT_AMBIENT", "LIGHT_DIRECTIONAL", "LIGHT_POINT" });
		expected.put("setPerspective", new String[] { "projectionRelay(args, false)" });
		expected.put("setOrtho", new String[] { "projectionRelay(args, true)" });
		expected.put("setUniform", new String[] { "uniformSetRelay(args, false)" });
		expected.put("setUniformImmediate", new String[] { "uniformSetRelay(args, true)" });

		for (Map.Entry<String, String[]> e : expected.entrySet()) {
			String body = methodBody(src, e.getKey());
			String[] tokens = e.getValue();
			assertTrue(e.getKey() + " must pass " + tokens[0] + " to its relay; its body is:\n"
					+ body, body.contains(tokens[0]));
			for (int i = 1; i < tokens.length; i++) {
				assertTrue(e.getKey() + " must NOT name " + tokens[i] + " — that is a SIBLING"
						+ " verb's kind, and a swap between them compiles and passes every"
						+ " behavioural test. Body:\n" + body, !body.contains(tokens[i]));
			}
		}
	}

	/**
	 * The source text of one callback method, declaration to its closing brace.
	 *
	 * Slices to the first {@code "\n\t}"} the way {@code limitsBody} does — these are all
	 * one-line relay bodies, so nothing nested can end the slice early. It FAILS rather than
	 * returning empty when the method is absent, because a rename that silently emptied every
	 * body would make the caller's assertions vacuous instead of red.
	 */
	private static String methodBody(String src, String name) {
		String decl = "public Object[] " + name + "(";
		int start = src.indexOf(decl);
		assertTrue("no method named " + name + " in " + SOURCE + " — if it was renamed, this pin"
				+ " must be updated in the same edit", start >= 0);
		int end = src.indexOf("\n\t}", start);
		assertTrue("could not find the end of " + name, end > start);
		return src.substring(start, end);
	}

	/**
	 * THE WITHHELD CAP IS GUARDED BY ITS VALUE, NOT BY A KEY SPELLING.
	 *
	 * The earlier version rejected keys whose NAME contained "component", which a review defeated
	 * by publishing {@code MAX_UNIFORM_COMPONENTS} under the existing {@code programUniforms} key.
	 * That is invisible today because both constants are 64, and becomes a lie on the wire exactly
	 * when typed uniforms make them diverge — the future the decision names as its own revisit
	 * point. So the assertion is on the CONSTANT: it may not reach the published table under any
	 * name at all.
	 */
	@Test
	public void theUniformComponentCapReachesTheWireUnderNoNameAtAll() throws Exception {
		String body = limitsBody(source());
		// EVERY PUBLISHED VALUE EXPRESSION, not the body text. The first version of this test
		// asserted on the whole body and went red immediately -- on the COMMENT that explains why
		// the cap is withheld, which names the constant on purpose. Excluding comments by hand
		// would be brittle; asserting on exactly the thing that reaches the wire is not.
		Matcher m = Pattern.compile("out\\.put\\(\\s*\"(\\w+)\"\\s*,([^;]+);").matcher(body);
		int checked = 0;
		while (m.find()) {
			checked++;
			assertTrue("getLimits publishes MAX_UNIFORM_COMPONENTS under the key \"" + m.group(1)
					+ "\". Uniform COMPONENTS are deliberately withheld: they equal MAX_UNIFORMS in"
					+ " v1 by accident of the float-only type system, so the slot cap always refuses"
					+ " first and a component number can never fire. Note the key NAME is not the"
					+ " guard -- publishing it under programUniforms would be just as wrong, and"
					+ " invisible until typed uniforms make the two constants diverge. Publish it"
					+ " then, and delete this test in that same edit.",
					!m.group(2).contains("MAX_UNIFORM_COMPONENTS"));
		}
		// A regex that matched nothing would pass this test while checking nothing at all.
		assertEquals("every published key must have been checked", limitKeys(body).size(), checked);
	}
}
