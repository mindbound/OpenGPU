package opengpu.v2.mc.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import opengpu.v2.mc.FontMetrics;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Replays a scene's node list into the currently bound FBO (a FramebufferPass must be active
 * AND retargeted to this scene immediately before the call: ortho projection sized for it,
 * logical y-down, blend on, freshly cleared). Canvas transform ops run on a
 * CPU-side affine stack — vertices are transformed before submission, so no GL matrix
 * state is touched during replay (the design's record-time-capped matrix stack).
 *
 * Command semantics mirrored from the server-side normative rules: FILL ignores the CANVAS
 * transform but not the NODE one (whole-canvas raster fill, the compaction anchor);
 * CLEAR_RECT is a hard set (blend off); pending textures draw nothing (the defined
 * transparent placeholder).
 */
public final class Canvas2dRenderer {
	private static final int OVAL_SEGMENTS = 48;

	// The affine and the parent-to-child fold live in NodeFold, which touches no GL and is therefore
	// unit-testable -- see its class note. They were here, untested, deciding where every child of
	// every group draws. ANIM-10.

	// Replay state (single-threaded render use).
	private final NodeFold.Affine node = new NodeFold.Affine();
	private final NodeFold.Affine local = new NodeFold.Affine();
	private final NodeFold.Affine effective = new NodeFold.Affine();
	private final List<double[]> stack = new ArrayList<double[]>();
	private double colR, colG, colB, colA;
	/** The current node's tint, as a 0..1 multiplier. Set by beginNode, applied by color(). */
	private double tintR = 1, tintG = 1, tintB = 1, tintA = 1;
	private boolean texturing;

	public void renderScene(SceneState state, int width, int height, Map<Integer, Integer> glTextures) {
		renderScene(state, width, height, glTextures, null, 0L);
	}

	/**
	 * As above, but with node transforms smoothed by {@code interp} as of {@code nowNanos}.
	 * A null interpolator draws nodes at their raw transforms (the pre-interpolation path,
	 * kept for tests and for any caller that wants the settled state).
	 */
	public void renderScene(SceneState state, int width, int height, Map<Integer, Integer> glTextures,
			NodeInterpolator interp, long nowNanos) {
		renderScene(state, width, height, glTextures, interp, nowNanos, null);
	}

	/**
	 * As above, with animator output substituted from {@code overlay} — Phase 3.3b's form, the
	 * one {@code SceneRenderer} calls. The overlay must have been evaluated THIS frame, against
	 * the same {@code interp} and {@code nowNanos}, or the substituted values compose over a base
	 * this call is not displaying. A null overlay draws server values only.
	 */
	public void renderScene(SceneState state, int width, int height, Map<Integer, Integer> glTextures,
			NodeInterpolator interp, long nowNanos, AnimatorOverlay overlay) {
		this.interp = interp;
		this.interpNanos = nowNanos;
		this.overlay = overlay;
		// This renderer is shared across passes, so the texturing shadow must be re-synced
		// with real GL at every entry — never inherited from the previous scene's tail.
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		texturing = false;
		// The framebuffer is cleared by FramebufferPass.retarget(), which owns the ordering
		// between the clear and the alpha mask that keeps the attachment opaque.
		//
		// Note the precondition this call carries is NARROWER than "a pass is open": it is
		// "retarget() ran for THIS scene, with nothing drawn since". One pass can now serve
		// several targets, so a caller that retargets, draws elsewhere, and comes back would
		// hand this method a framebuffer that is neither freshly cleared nor the one it thinks.

		List<SceneNode> ordered = new ArrayList<SceneNode>(state.nodes.values());
		// SORTED BY THE ANCHOR FIRST — the parent when there is one, the node itself otherwise —
		// then by the node's own z and id. That places a parent and its children in one
		// contiguous run at the parent's slot in the global order, and it is what makes a group's
		// z mean anything at all.
		//
		// Without it a group's setZ converged perfectly across the wire and moved no pixel: the
		// group draws nothing and its children kept sorting at their own z among strangers. That
		// is the same "property that looks like it works and does not" this file rejects for tint
		// (beginNode) and for visibility (below), and DESIGN lists z among a Group's properties,
		// so leaving it flat would have been the one axis where a group silently lied.
		//
		// It also stops a child sorting UNDER its own parent: a hat parented to a head at z=10
		// used to draw beneath it, because the hat's default z of 0 sorted first globally. Within
		// a run the child's own z still orders it against its siblings AND against the parent, so
		// deliberately putting a child behind its parent still works — it is only the accidental
		// case that is fixed.
		//
		// Cost: one map lookup per comparison, and only for PARENTED nodes — an unparented node
		// short-circuits on `parent == 0` with no lookup at all. Scenes that use no groups pay
		// nothing, and the ones that pay are the ones that wanted the ordering.
		Collections.sort(ordered, new Comparator<SceneNode>() {
			@Override
			public int compare(SceneNode n1, SceneNode n2) {
				SceneNode a1 = anchorOf(n1, state);
				SceneNode a2 = anchorOf(n2, state);
				if (a1.z != a2.z) {
					return a1.z < a2.z ? -1 : 1;
				}
				if (a1.id != a2.id) {
					return a1.id < a2.id ? -1 : 1;
				}
				// Same run: siblings against each other, and the parent against its own children.
				if (n1.z != n2.z) {
					return n1.z < n2.z ? -1 : 1;
				}
				return n1.id < n2.id ? -1 : n1.id == n2.id ? 0 : 1;
			}
		});
		for (SceneNode sceneNode : ordered) {
			// A HIDDEN GROUP HIDES ITS CHILDREN. Without this a group's own `visible` would mean
			// nothing at all — a NODE_GROUP draws nothing itself — so hiding one would converge
			// across the wire and change no pixel, which is the same defect the tint comment in
			// beginNode describes. One nesting level means one lookup rather than a walk.
			if (!isDrawn(sceneNode, state)) {
				continue;
			}
			if (sceneNode.type == V2Wire.NODE_CANVAS) {
				ResourceInfo res = state.resources.get(sceneNode.ref);
				if (res != null && res.type == V2Wire.RES_CANVAS && res.canvas != null) {
					replayCanvas(res.canvas, sceneNode, state, glTextures);
				}
			} else if (sceneNode.type == V2Wire.NODE_SPRITE) {
				ResourceInfo res = state.resources.get(sceneNode.ref);
				if (res != null && res.type == V2Wire.RES_TEXTURE) {
					drawSprite(sceneNode, res, state, glTextures);
				}
			}
			// NODE_GROUP itself draws nothing: it exists to carry a transform, a tint and a
			// visibility flag for its children, all of which are applied where they are consumed
			// (beginNode for the first two, the check above for the third).
		}
	}

	/** Interpolation source for the current renderScene call; null = draw raw transforms. */
	private NodeInterpolator interp;
	private long interpNanos;
	/** Animator output for the current renderScene call; null = draw server values only. */
	private AnimatorOverlay overlay;
	private final double[] xform = new double[NodeFold.TRS_WIDTH];
	private final double[] parentXform = new double[NodeFold.TRS_WIDTH];
	private final double[] tintRgba = new double[4];
	private final double[] childTintFactor = new double[4];
	private final double[] parentTintFactor = new double[4];

	private void beginNode(SceneNode sceneNode, SceneState state) {
		// PARENT FIRST, then the child, onto the same matrix -- NodeFold.foldTransform owns that
		// order and the reasoning behind it. The parent is interpolated exactly like the child,
		// which is what makes a moving group carry its children smoothly rather than in tick steps.
		//
		// One nesting level means one lookup and no loop. If that limit is ever lifted this
		// becomes a walk up to the root, and `parent < id` is what guarantees it terminates.
		//
		// READ THEN FOLD, rather than applying each node's ops as it is read: the fold is the part
		// ANIM-10 pins and the part a JVM test can reach, so it may not be spread across two call
		// sites that only a GL context can execute.
		SceneNode parent = parentOf(sceneNode, state);
		if (parent != null) {
			readTransform(parent, parentXform);
		}
		readTransform(sceneNode, xform);
		NodeFold.foldTransform(parent != null ? parentXform : null, xform, node);
		local.identity();
		stack.clear();
		colR = 1; colG = 1; colB = 1; colA = 1;
		// THE NODE TINT IS A MULTIPLIER, applied for every node type rather than only sprites.
		//
		// It used to be read solely in drawSprite, which meant setNodeTint on a canvas node or on
		// the display node converged perfectly across the wire and then rendered nothing at all —
		// the server and every client agreed on a property none of them drew. A feature that
		// looks like it works and does not is the thing this codebase refuses elsewhere (no
		// createGroup, no canvas-backed sprites), so it is now honoured instead of removed:
		// tinting a whole canvas is how a program fades or flashes one, and there was no other
		// way to do it.
		//
		// Multiplied in color() rather than assigned here, because a canvas sets its own colour
		// per command; assigning would be overwritten by the first OP_SET_COLOR. Default tint is
		// 0xFFFFFFFF, so an untinted node multiplies by exactly 1 and nothing changes.
		//
		// ONE EXCEPTION, AND IT IS NOT FIXABLE HERE: OP_CLEAR_RECT draws with GL_BLEND disabled
		// (see its case below) because a clear is a hard set, not a paint. With blending off the
		// source alpha participates in nothing, so a tint's RGB multiplies a cleared region while
		// its ALPHA is inert over exactly that region. Gating the blend on tintA would be the
		// obvious fix and is the wrong one: SceneCanvas compacts on a full-canvas CLEAR_RECT at
		// ANY alpha precisely because it hard-sets, and a canvas resource cannot know which nodes
		// reference it or with what tint — the truncation proof would stop holding. Documented as
		// an exception instead, in the callback doc, the Lua wrapper and API-V2.
		//
		// LOAD-BEARING ORDERING: every path that draws must call this method first. That was
		// already true for the transform and the font; it is now true for COLOUR as well, because
		// these fields persist between nodes. A draw placed above a beginNode would render in the
		// PREVIOUS node's tint — a defect that follows z-order and reads as nondeterministic,
		// exactly like the font leak the comment below describes.
		// A GROUP'S TINT MULTIPLIES ITS CHILDREN'S, for the same reason the tint above is honoured
		// for every node type rather than only sprites: a NODE_GROUP draws nothing itself, so a
		// tint that did not reach its children would be a property that converges perfectly across
		// the wire and renders nothing — the defect described at length just above. Fading a whole
		// group with one call is most of why a group is worth having.
		//
		// THE `parent.tint != 0xFFFFFFFF` SHORT-CIRCUIT IS GONE, and NodeFold explains why: it
		// tested the parent's RAW field, so a group whose server tint is white and whose animator
		// fades it would take the early exit and reach no child at all -- the very defect this
		// comment block exists to refuse, reintroduced one level up. That hypothetical became
		// concrete in 3.3b: tintFactorOf below is exactly the displayed-vs-raw split the removed
		// short-circuit would have broken.
		//
		// UNPACKED FACTORS, not the packed fold: an animator's composed tint is continuous, so it
		// has no packed form to hand the int overload -- see NodeFold.foldTint(double[],...).
		tintFactorOf(sceneNode, childTintFactor);
		if (parent != null) {
			tintFactorOf(parent, parentTintFactor);
		} else {
			NodeFold.unpack(NodeFold.WHITE, parentTintFactor);
		}
		NodeFold.foldTint(childTintFactor, parentTintFactor, tintRgba);
		tintR = tintRgba[NodeFold.TINT_R];
		tintG = tintRgba[NodeFold.TINT_G];
		tintB = tintRgba[NodeFold.TINT_B];
		tintA = tintRgba[NodeFold.TINT_A];
		// Font resets with the colour, and must: it is ambient state in the same command
		// stream, so a canvas that selected unscii would otherwise leak it into whichever
		// canvas replayed next. That leak would follow node draw order, making text change
		// font depending on z-order — a symptom that reads as nondeterministic.
		currentFont = V2Wire.FONT_DEFAULT;
		setTexturing(false);
		updateEffective();
	}

	/**
	 * Reads one node's DISPLAYED TRS into {@code out} — interpolated when a source is available,
	 * then with the animator's composed output substituted per written property (Phase 3.3b).
	 * The parent and the child alike go through here, which is what makes an animated group
	 * carry its children. It reads and does not apply: the applying is
	 * {@link NodeFold#foldTransform}'s job, so that the order is stated in one testable place.
	 */
	private void readTransform(SceneNode n, double[] out) {
		if (interp != null) {
			interp.transformOf(n, interpNanos, out);
		} else {
			out[NodeFold.TRS_X] = n.x;
			out[NodeFold.TRS_Y] = n.y;
			out[NodeFold.TRS_ROT] = n.rot;
			out[NodeFold.TRS_SX] = n.sx;
			out[NodeFold.TRS_SY] = n.sy;
		}
		if (overlay != null) {
			overlay.overlayTransform(n.id, out);
		}
	}

	/**
	 * A node's DISPLAYED tint factor: the animator's composed tint when one is written, else the
	 * raw packed field unpacked. The overlay owns that choice; this helper only covers the
	 * overlay-less path, so both readTransform and the tint read fail back to server values the
	 * same way.
	 */
	private void tintFactorOf(SceneNode n, double[] out) {
		if (overlay != null) {
			overlay.tintFactor(n.id, n.tint, out);
		} else {
			NodeFold.unpack(n.tint, out);
		}
	}

	/**
	 * A node's transform parent, or null.
	 *
	 * Null covers both "unparented" and "the parent id does not resolve", and the second should be
	 * unreachable: the network codec refuses a dangling parent, the persisted codec sanitises one
	 * to 0, and a parent cannot be freed while a child still points at it. It is tolerated rather
	 * than asserted on because the renderer must not be the thing that takes a client down over
	 * scene data — the same reason a dangling {@code ref} draws nothing instead of throwing.
	 */
	private static SceneNode parentOf(SceneNode n, SceneState state) {
		return n.parent == 0 ? null : state.nodes.get(n.parent);
	}

	/**
	 * Whether this node will actually be drawn: visible itself AND not hidden by its parent.
	 *
	 * Package-private because {@code SceneRenderer.countCommands} must ask the SAME question when
	 * it builds the per-command timing denominator. Asking a different one is how that denominator
	 * went wrong before — see its javadoc — and a hidden group's children are exactly the case
	 * where "visible" and "drawn" part company.
	 */
	static boolean isDrawn(SceneNode n, SceneState state) {
		if (!n.visible) {
			return false;
		}
		SceneNode parent = parentOf(n, state);
		return parent == null || parent.visible;
	}

	/**
	 * The node whose slot in the global z-order this node occupies: its parent, or itself.
	 *
	 * Public to the package because {@code SceneRenderer} needs the same notion of "is this node
	 * actually going to be drawn" for its per-command timing denominator.
	 */
	static SceneNode anchorOf(SceneNode n, SceneState state) {
		SceneNode parent = parentOf(n, state);
		return parent == null ? n : parent;
	}

	private void updateEffective() {
		// effective = node ∘ local
		effective.a = node.a * local.a + node.c * local.b;
		effective.b = node.b * local.a + node.d * local.b;
		effective.c = node.a * local.c + node.c * local.d;
		effective.d = node.b * local.c + node.d * local.d;
		effective.e = node.a * local.e + node.c * local.f + node.e;
		effective.f = node.b * local.e + node.d * local.f + node.f;
	}

	private void replayCanvas(SceneCanvas canvas, SceneNode sceneNode, SceneState state,
			Map<Integer, Integer> glTextures) {
		beginNode(sceneNode, state);
		for (CanvasCommand cmd : canvas.visibleCommands()) {
			double[] a = cmd.args;
			switch (cmd.op) {
				case V2Wire.OP_SET_COLOR:
					colR = a[0] / 255.0; colG = a[1] / 255.0; colB = a[2] / 255.0; colA = a[3] / 255.0;
					break;
				case V2Wire.OP_SET_FONT: {
					// Clamped rather than rejected, uniquely on this path. The decoder already
					// refuses out-of-range ids, so reaching here with one means a save written
					// by a build that knew more fonts than this one — and rendering the default
					// beats refusing to draw the canvas at all. The server rejects bad ids at
					// the callback, which is where a program's mistake is actually catchable.
					int requested = (int) a[0];
					currentFont = V2Wire.isValidFont(requested) ? requested : V2Wire.FONT_DEFAULT;
					break;
				}
				case V2Wire.OP_FILL:
					fillWholeCanvas(canvas.width, canvas.height, false);
					break;
				case V2Wire.OP_PLOT:
					quad(a[0], a[1], 1, 1);
					break;
				case V2Wire.OP_LINE:
					line(a[0], a[1], a[2], a[3]);
					break;
				case V2Wire.OP_RECT:
					rectOutline(a[0], a[1], a[2], a[3]);
					break;
				case V2Wire.OP_FILL_RECT:
					quad(a[0], a[1], a[2], a[3]);
					break;
				case V2Wire.OP_TRIANGLE:
					triangle(a, false);
					break;
				case V2Wire.OP_FILL_TRIANGLE:
					triangle(a, true);
					break;
				case V2Wire.OP_OVAL:
					oval(a[0], a[1], a[2], a[3], false);
					break;
				case V2Wire.OP_FILL_OVAL:
					oval(a[0], a[1], a[2], a[3], true);
					break;
				case V2Wire.OP_CLEAR_RECT:
					GL11.glDisable(GL11.GL_BLEND);
					quad(a[0], a[1], a[2], a[3]);
					GL11.glEnable(GL11.GL_BLEND);
					break;
				case V2Wire.OP_DRAW_TEXT:
					drawText(cmd.text, a[0], a[1]);
					break;
				case V2Wire.OP_DRAW_TEXTURE: {
					ResourceInfo res = state.resources.get((int) a[0]);
					Integer glId = glTextures.get((int) a[0]);
					if (res != null && glId != null) {
						untintedQuad(glId, a[1], a[2], res.width, res.height, 0, 0, 1, 1);
					}
					break;
				}
				case V2Wire.OP_DRAW_TEXTURE_SUB: {
					ResourceInfo res = state.resources.get((int) a[0]);
					Integer glId = glTextures.get((int) a[0]);
					if (res != null && glId != null && res.width > 0 && res.height > 0) {
						double u0 = a[3] / res.width, v0 = a[4] / res.height;
						double u1 = (a[3] + a[5]) / res.width, v1 = (a[4] + a[6]) / res.height;
						untintedQuad(glId, a[1], a[2], a[5], a[6], u0, v0, u1, v1);
					}
					break;
				}
				case V2Wire.OP_TRANSLATE:
					local.translate(a[0], a[1]);
					updateEffective();
					break;
				case V2Wire.OP_ROTATE:
					local.rotate(a[0]);
					updateEffective();
					break;
				case V2Wire.OP_ROTATE_AROUND:
					local.translate(a[1], a[2]);
					local.rotate(a[0]);
					local.translate(-a[1], -a[2]);
					updateEffective();
					break;
				case V2Wire.OP_SCALE:
					local.scale(a[0], a[1]);
					updateEffective();
					break;
				case V2Wire.OP_PUSH:
					stack.add(new double[] { local.a, local.b, local.c, local.d, local.e, local.f });
					break;
				case V2Wire.OP_POP:
					if (!stack.isEmpty()) {
						double[] m = stack.remove(stack.size() - 1);
						local.a = m[0]; local.b = m[1]; local.c = m[2];
						local.d = m[3]; local.e = m[4]; local.f = m[5];
						updateEffective();
					}
					break;
				case V2Wire.OP_ORIGIN:
					local.identity();
					updateEffective();
					break;
				default:
					// Unknown ops cannot arrive: the codec rejects them at decode time.
					break;
			}
		}
	}

	private void drawSprite(SceneNode sceneNode, ResourceInfo res, SceneState state,
			Map<Integer, Integer> glTextures) {
		Integer glId = glTextures.get(res.id);
		if (glId == null) {
			return; // pending
		}
		// The tint is NOT applied here any more: beginNode has already loaded it as the node
		// multiplier and color() applies it. Assigning it into colR..colA as well would square
		// it — a 50% tint would render at 25%. This is the special case the multiplier removed,
		// and removing it is what let canvas nodes have a tint at all.
		beginNode(sceneNode, state);
		texturedQuad(glId, 0, 0, res.width, res.height, 0, 0, 1, 1);
	}

	// ------------------------------------------------------------------
	// Primitives (all vertices go through the effective affine)

	private void setTexturing(boolean on) {
		if (texturing != on) {
			texturing = on;
			if (on) {
				GL11.glEnable(GL11.GL_TEXTURE_2D);
			} else {
				GL11.glDisable(GL11.GL_TEXTURE_2D);
			}
		}
	}

	/**
	 * The single point where colour reaches GL, which is what makes the node tint one multiply
	 * rather than a change at every draw site. Verified: this is the only glColor call in the
	 * renderer.
	 */
	private void color() {
		GL11.glColor4d(colR * tintR, colG * tintG, colB * tintB, colA * tintA);
	}

	private void vertex(double x, double y) {
		GL11.glVertex2d(effective.tx(x, y), effective.ty(x, y));
	}

	/**
	 * A vertex placed by the NODE transform only, skipping the canvas-local stack.
	 *
	 * Exists for FILL, which is defined to ignore the canvas transform but has no business
	 * ignoring where its node was put. See fillWholeCanvas.
	 */
	private void nodeVertex(double x, double y) {
		GL11.glVertex2d(node.tx(x, y), node.ty(x, y));
	}

	/**
	 * FILL ignores the CANVAS transform: a raster fill of the whole canvas, and the compaction
	 * anchor. It does NOT ignore the NODE transform.
	 *
	 * Fixed 2026-08-09. This emitted raw glVertex2d, which skipped `local` (correct, documented,
	 * and load-bearing — SceneCanvas truncates on a full-canvas fill precisely because it is a
	 * raster operation in canvas space) and ALSO skipped `node` (not correct, not documented, and
	 * not intended). A canvas node moved to (100, 50) whose list contained OP_FILL painted the
	 * scene rect (0,0)-(w,h) rather than (100,50)-(100+w,50+h): the fill landed at the scene
	 * origin regardless of where its node was.
	 *
	 * Reachable from the shipped library — `c:fill()` on any node that has been moved, and
	 * `canvasSubmit` accepts `fill` against any canvas id. Only the DISPLAY node refuses
	 * transforms, and the display node sits at identity, which is why this survived: on the one
	 * canvas most programs use, node-space and scene-space coincide and the defect is invisible.
	 *
	 * Fixed HERE and on its own, before the static-layer FBO (ROADMAP P1), deliberately. Under a
	 * static layer the fill would be emitted into the layer's own ortho and the defect would
	 * vanish as a side effect of an optimisation — and an optimisation whose engagement moves
	 * pixels cannot be shipped or reviewed. The change has to be visible as a fix.
	 *
	 * NOT unit-testable: Canvas2dRenderer needs a GL context, so no JVM test can load it. Verified
	 * by reading and in-game.
	 */
	private void fillWholeCanvas(int width, int height, boolean unused) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_QUADS);
		nodeVertex(0, 0);
		nodeVertex(0, height);
		nodeVertex(width, height);
		nodeVertex(width, 0);
		GL11.glEnd();
	}

	private void quad(double x, double y, double w, double h) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_QUADS);
		vertex(x, y);
		vertex(x, y + h);
		vertex(x + w, y + h);
		vertex(x + w, y);
		GL11.glEnd();
	}

	private void line(double x1, double y1, double x2, double y2) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_LINES);
		vertex(x1, y1);
		vertex(x2, y2);
		GL11.glEnd();
	}

	private void rectOutline(double x, double y, double w, double h) {
		setTexturing(false);
		color();
		GL11.glBegin(GL11.GL_LINE_LOOP);
		vertex(x, y);
		vertex(x + w, y);
		vertex(x + w, y + h);
		vertex(x, y + h);
		GL11.glEnd();
	}

	private void triangle(double[] a, boolean filled) {
		setTexturing(false);
		color();
		GL11.glBegin(filled ? GL11.GL_TRIANGLES : GL11.GL_LINE_LOOP);
		vertex(a[0], a[1]);
		vertex(a[2], a[3]);
		vertex(a[4], a[5]);
		GL11.glEnd();
	}

	/** Center-anchored: (cx, cy) with full width w and height h. */
	private void oval(double cx, double cy, double w, double h, boolean filled) {
		setTexturing(false);
		color();
		double rx = w / 2.0, ry = h / 2.0;
		if (filled) {
			GL11.glBegin(GL11.GL_TRIANGLE_FAN);
			vertex(cx, cy);
			for (int i = 0; i <= OVAL_SEGMENTS; i++) {
				double t = 2 * Math.PI * i / OVAL_SEGMENTS;
				vertex(cx + rx * Math.cos(t), cy + ry * Math.sin(t));
			}
			GL11.glEnd();
		} else {
			GL11.glBegin(GL11.GL_LINE_LOOP);
			for (int i = 0; i < OVAL_SEGMENTS; i++) {
				double t = 2 * Math.PI * i / OVAL_SEGMENTS;
				vertex(cx + rx * Math.cos(t), cy + ry * Math.sin(t));
			}
			GL11.glEnd();
		}
	}

	/**
	 * Draw a string from the runtime Unifont atlas.
	 *
	 * Two things differ from the old PNG-atlas path and both are load-bearing. Glyphs are
	 * batched PER PAGE rather than in one GL_QUADS block: the atlas can spill onto several
	 * textures, and a bind cannot happen inside glBegin/glEnd. And the pen advances by
	 * {@code metrics.charAdvance}, which the SERVER also used to answer getTextWidth for this
	 * very string — the two read the same font records, so the client's pen and the server's
	 * measurement cannot drift.
	 *
	 * A codepoint with no glyph still advances its cell. Skipping the gap would reflow the
	 * rest of the line past the width the server already reported.
	 *
	 * THE RASTERIZE PASS IS SEPARATE FROM THE DRAW PASS, and that is not an optimisation.
	 * {@code atlas.get} rasterizes on a cache miss, which issues glBindTexture and
	 * glTexSubImage2D — and possibly glGenTextures/glTexImage2D for a new page. Those are
	 * GL_INVALID_OPERATION between glBegin and glEnd, so calling get() from inside the batch
	 * drops the upload on a conformant driver while the atlas still caches the entry: the cell
	 * stays blank permanently, and only the first codepoint of the first string ever renders.
	 * The batching above was already written to keep BINDS out of the batch; the allocation
	 * hiding inside get() is the same rule and was missed.
	 *
	 * Worth knowing why this never showed up in testing: the development client runs Angelica,
	 * which emulates the fixed-function pipeline in software, so it intercepts the interleaved
	 * calls rather than rejecting them. In-game verification structurally could not see this.
	 */
	private void drawText(String text, double x, double y) {
		opengpu.v2.font.GlyphSource font = FontMetrics.fontWithGlyphs(currentFont);
		GlyphAtlas atlas = atlasFor(currentFont, font);
		int glyphHeight = font.cellHeight();
		int cellWidth = font.cellWidth();

		// Pass 1: rasterize every glyph this string needs, before any batch is open. Entries are
		// cached, so pass 2 is guaranteed to be pure drawing. Cheap in the steady state — every
		// get() after the first frame is a map hit.
		for (int j = 0; j < text.length(); ) {
			int cp = text.codePointAt(j);
			j += Character.charCount(cp);
			atlas.get(cp);
		}

		setTexturing(true);
		color();
		double pen = x;
		int boundTexture = -1;
		boolean drawing = false;
		int i = 0;
		// Pass 2: pure drawing. Every get() here is a map hit or a null — a codepoint the font
		// has no bitmap for returns null without touching GL, so nothing in this loop can issue
		// a texture call. The atlas is told the batch is open so it can say so if that ever
		// stops being true.
		atlas.setBatchOpen(true);
		while (i < text.length()) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			GlyphAtlas.Entry g = atlas.get(cp);
			if (g != null) {
				if (g.texture != boundTexture) {
					if (drawing) {
						GL11.glEnd();
						drawing = false;
					}
					GL11.glBindTexture(GL11.GL_TEXTURE_2D, g.texture);
					boundTexture = g.texture;
				}
				if (!drawing) {
					GL11.glBegin(GL11.GL_QUADS);
					drawing = true;
				}
				double w = g.cells * cellWidth;
				GL11.glTexCoord2f(g.u0, g.v0);
				vertex(pen, y);
				GL11.glTexCoord2f(g.u0, g.v1);
				vertex(pen, y + glyphHeight);
				GL11.glTexCoord2f(g.u1, g.v1);
				vertex(pen + w, y + glyphHeight);
				GL11.glTexCoord2f(g.u1, g.v0);
				vertex(pen + w, y);
			}
			// Advance even when the glyph is missing: the server measured this string with the
			// same font and reported a width that included this cell, so skipping the gap
			// would put every later glyph left of where the layout expects it.
			pen += font.advanceCells(cp) * cellWidth;
		}
		if (drawing) {
			GL11.glEnd();
		}
		atlas.setBatchOpen(false);
	}

	/**
	 * One atlas per font, built on first use.
	 *
	 * Per font rather than one shared: cell geometry differs (Unifont 8x16, unscii-8 8x8), so
	 * a single atlas would need per-entry sizes and could not pack rows uniformly. Needs a GL
	 * context, hence lazy rather than constructed with the renderer.
	 */
	private GlyphAtlas atlasFor(int fontId, opengpu.v2.font.GlyphSource font) {
		GlyphAtlas atlas = glyphAtlases[fontId];
		if (atlas == null) {
			atlas = new GlyphAtlas(font);
			glyphAtlases[fontId] = atlas;
		}
		return atlas;
	}

	private final GlyphAtlas[] glyphAtlases = new GlyphAtlas[V2Wire.FONT_COUNT];

	/**
	 * Font for subsequent text, reset per canvas by {@link #beginNode}. See OP_SET_FONT: this
	 * has OP_SET_COLOR's lifecycle exactly, including being unscoped by PUSH/POP.
	 */
	private int currentFont = V2Wire.FONT_DEFAULT;

	/**
	 * Draw a texture at its own colours, ignoring the canvas draw colour.
	 *
	 * The draw colour is ambient state meant for shapes and text; letting it modulate blits
	 * too means a fill colour set several commands earlier silently darkens or recolours
	 * every later texture — a footgun that costs an image and gives no error. Per-object
	 * tinting is the NODE's {@code tint} property, set with setNodeTint.
	 *
	 * Note what this does and does not clear. It zeroes the COMMAND colour, so the canvas's own
	 * setColor cannot modulate a blit — but the node tint lives in a separate multiplier applied
	 * by {@link #color()}, so a tinted node still tints its textures. That is the intended
	 * reading of "tint this node": it applies to everything the node draws, exactly as it does
	 * for a sprite.
	 */
	private void untintedQuad(int glId, double x, double y, double w, double h,
			double u0, double v0, double u1, double v1) {
		double r = colR, g = colG, b = colB, a = colA;
		colR = 1; colG = 1; colB = 1; colA = 1;
		texturedQuad(glId, x, y, w, h, u0, v0, u1, v1);
		colR = r; colG = g; colB = b; colA = a;
	}

	private void texturedQuad(int glId, double x, double y, double w, double h,
			double u0, double v0, double u1, double v1) {
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		setTexturing(true);
		color();
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2d(u0, v0);
		vertex(x, y);
		GL11.glTexCoord2d(u0, v1);
		vertex(x, y + h);
		GL11.glTexCoord2d(u1, v1);
		vertex(x + w, y + h);
		GL11.glTexCoord2d(u1, v0);
		vertex(x + w, y);
		GL11.glEnd();
	}
}
