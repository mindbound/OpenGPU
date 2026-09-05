package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import opengpu.v2.ocsl.IrValidator;
import opengpu.v2.ocsl.OcslTime;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.ocsl.SurfaceTable;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ServerScene;
import opengpu.v2.stats.RenderStats;

/**
 * THE CAPS INVENTORY — rendered from the constants, pinned against {@code docs/dev/CAPS.md}.
 *
 * The standing reference the 2026-08-21 cap review asked for, built the only way a reference
 * over two dozen constants stays honest: derived, not written. {@link #render} reads every
 * loadable constant; the pinning test fails the moment the doc and the code disagree, pointing
 * at whichever side moved. Regenerate = run the suite, copy
 * {@code build/reports/caps-inventory.md} over {@code docs/dev/CAPS.md} (the same pattern
 * {@code AnimatorDemoBlobsTest} uses for the field-test blobs).
 *
 * LIVES IN THIS PACKAGE for {@link AnimatorOverlay#SINCE_ATTACH_CAP_SECONDS} and
 * {@link ServerTimeline#INTERPOLATION_DELAY_TICKS}, which are package-private. Forge-bound
 * classes LOAD in a JVM test (the :dev artifact serves the interfaces — the 2026-08-24 probe,
 * recorded on ApiSurfacePinTest, refuted the older "cannot load" lore this comment used to
 * repeat); what fails is INSTANTIATION, so instance- and callback-derived values
 * (SceneRenderer's upload budget, TileEntityGpu2's canvas/VRAM caps) stay in a hand-maintained
 * section with file pointers, clearly marked.
 */
public class CapsInventoryTest {

	static String render() {
		StringBuilder s = new StringBuilder();
		s.append("# OpenGPU caps & ceilings — GENERATED, do not hand-edit\n\n");
		s.append("Rendered by `CapsInventoryTest.render()` from the constants themselves; the\n");
		s.append("suite pins this file against the code. To refresh after a cap change: run the\n");
		s.append("suite, copy `build/reports/caps-inventory.md` over `docs/dev/CAPS.md`.\n\n");

		s.append("## OCSL acceptance (validator's — raiseable under monotonicity)\n\n");
		s.append("| Cap | Value |\n|---|---|\n");
		s.append("| op-cap CEILING (codec bound) `MAX_STRUCTURAL_OPS` | ")
				.append(IrValidator.MAX_STRUCTURAL_OPS).append(" |\n");
		String[] stageNames = { "", "pixel-material", "pixel-effect", "pixel-post", "bake",
				"vertex", "animator", "compute" };
		for (byte st = OcslWire.STAGE_PIXEL_MATERIAL; st <= OcslWire.STAGE_COMPUTE; st++) {
			s.append("| ops @ stage ").append(st).append(" (").append(stageNames[st])
					.append(") | ").append(IrValidator.maxStructuralOps(st)).append(" |\n");
			}
		s.append("| fetches (pixel family) `MAX_FETCHES` | ").append(IrValidator.MAX_FETCHES)
				.append(" |\n");
		s.append("| fetches @ animator | ")
				.append(IrValidator.maxFetches(OcslWire.STAGE_ANIMATOR)).append(" |\n");
		s.append("| uniform components `MAX_UNIFORM_COMPONENTS` | ")
				.append(IrValidator.MAX_UNIFORM_COMPONENTS).append(" |\n");
		s.append("| unroll product `MAX_UNROLL_PRODUCT` | ")
				.append(IrValidator.MAX_UNROLL_PRODUCT).append(" |\n\n");

		s.append("## OCSL format identity (frozen with FORMAT_VERSION ")
				.append(OcslWire.FORMAT_VERSION).append(")\n\n");
		s.append("| Bound | Value |\n|---|---|\n");
		s.append("| blob op records `MAX_OPS` | ").append(OcslWire.MAX_OPS).append(" |\n");
		s.append("| constant pool `MAX_CONSTANTS` | ").append(OcslWire.MAX_CONSTANTS)
				.append(" |\n");
		s.append("| decoder registers `OcslWire.MAX_REGISTERS` | ")
				.append(OcslWire.MAX_REGISTERS).append(" |\n");
		s.append("| loop trips / depth | ").append(OcslWire.MAX_LOOP_TRIPS).append(" / ")
				.append(OcslWire.MAX_LOOP_DEPTH).append(" |\n");
		s.append("| names / name length | ").append(OcslWire.MAX_NAMES).append(" / ")
				.append(OcslWire.MAX_NAME_LENGTH).append(" |\n");
		s.append("| blob bytes `MAX_BLOB_BYTES` | ").append(OcslWire.MAX_BLOB_BYTES)
				.append(" |\n");
		s.append("| frame registers `SurfaceTable.MAX_REGISTERS` | ")
				.append(SurfaceTable.MAX_REGISTERS).append(" |\n");
		s.append("| frame floats `MAX_FRAME_WIDTH` | ").append(SurfaceTable.MAX_FRAME_WIDTH)
				.append(" |\n");
		s.append("| sampler slots / uniforms | ").append(SurfaceTable.MAX_SLOTS).append(" / ")
				.append(SurfaceTable.MAX_UNIFORMS).append(" |\n\n");

		s.append("## Scene & wire (protocol v").append(V2Wire.PROTOCOL_VERSION).append(")\n\n");
		s.append("| Bound | Value |\n|---|---|\n");
		s.append("| program ledger `MAX_PROGRAM_BYTES` | ").append(ServerScene.MAX_PROGRAM_BYTES)
				.append(" |\n");
		s.append("| nodes `MAX_NODES` | ").append(ServerScene.MAX_NODES).append(" |\n");
		s.append("| deltas / commands per batch | ").append(V2Wire.MAX_DELTAS).append(" / ")
				.append(V2Wire.MAX_COMMANDS).append(" |\n");
		s.append("| write bytes per tick / batch | ").append(V2Wire.MAX_WRITE_BYTES_PER_TICK)
				.append(" / ").append(V2Wire.MAX_WRITE_BYTES_PER_BATCH).append(" |\n");
		s.append("| submit bytes call / tick / batch | ").append(V2Wire.MAX_SUBMIT_BYTES)
				.append(" / ").append(V2Wire.MAX_SUBMIT_BYTES_PER_TICK).append(" / ")
				.append(V2Wire.MAX_SUBMIT_BYTES_PER_BATCH).append(" |\n");
		s.append("| program bytes per batch | ").append(V2Wire.MAX_PROGRAM_BYTES_PER_BATCH)
				.append(" |\n");
		s.append("| mesh ledger `MAX_MESH_BYTES` | ").append(ServerScene.MAX_MESH_BYTES)
				.append(" |\n");
		s.append("| mesh vertex / index bytes per mesh | ").append(V2Wire.MAX_MESH_VERTEX_BYTES)
				.append(" / ").append(V2Wire.MAX_MESH_INDEX_BYTES).append(" |\n");
		s.append("| mesh bytes per batch | ").append(V2Wire.MAX_MESH_BYTES_PER_BATCH)
				.append(" |\n");
		s.append("| uniforms per node `MAX_NODE_UNIFORMS` | ").append(ServerScene.MAX_NODE_UNIFORMS)
				.append(" |\n");
		s.append("| standing command bytes | ").append(V2Wire.MAX_STANDING_COMMAND_BYTES)
				.append(" |\n");
		s.append("| text chars / texture dim | ").append(V2Wire.MAX_TEXT_CHARS).append(" / ")
				.append(V2Wire.MAX_TEXTURE_DIM).append(" |\n");
		s.append("| write region bytes | ").append(V2Wire.MAX_WRITE_REGION_BYTES).append(" |\n\n");

		s.append("## Runtime (client)\n\n");
		s.append("| Bound | Value |\n|---|---|\n");
		s.append("| sinceAttach saturation (s) | ")
				.append((long) AnimatorOverlay.SINCE_ATTACH_CAP_SECONDS).append(" |\n");
		s.append("| interpolation delay (ticks) | ")
				.append(ServerTimeline.INTERPOLATION_DELAY_TICKS).append(" |\n");
		s.append("| glide ceiling (ticks) `GLIDE_MAX_GAP_TICKS` — DERIVED | ")
				.append(NodeInterpolator.GLIDE_MAX_GAP_TICKS).append(" |\n");
		s.append("| glide-share budget `MIN_GLIDED_SPAN_NUM/DEN` — PROVISIONAL | ")
				.append(NodeInterpolator.MIN_GLIDED_SPAN_NUM).append("/")
				.append(NodeInterpolator.MIN_GLIDED_SPAN_DEN).append(" |\n");
		s.append("| time wrap period (ticks) | ").append(OcslTime.PERIOD_TICKS).append(" |\n");
		s.append("| stall threshold (ns) | ").append(RenderStats.STALL_NANOS).append(" |\n\n");

		// The two rows above are the first entries in this inventory that are not simply "a number
		// someone chose". One is derived from another cap and must never be set directly; the other
		// is an open question wearing a value. Both facts belong in the GENERATED doc rather than
		// only in a javadoc, because this file is what a reader consults to find out what the caps
		// are -- and the glide budget is the one cap here that a measurement is expected to change.
		s.append("**The glide ceiling is DERIVED, not chosen:** ")
				.append("`GLIDE_MAX_GAP_TICKS = INTERPOLATION_DELAY_TICKS * DEN / NUM`")
				.append(", in integer arithmetic. It moves whenever the interpolation delay moves,")
				.append(" which is the point — the glided share of a span is `D/G`, so a ceiling")
				.append(" written directly in ticks would silently change its own meaning the day")
				.append(" the delay changed. Do not set it directly.\n\n");
		s.append("**The glide-share budget is PROVISIONAL, shipped 2026-08-30.** It is the one cap")
				.append(" in this inventory that no measurement supports. At ")
				.append(NodeInterpolator.MIN_GLIDED_SPAN_NUM).append("/")
				.append(NodeInterpolator.MIN_GLIDED_SPAN_DEN)
				.append(" the glide carries ")
				.append(Math.round(100.0 * NodeInterpolator.MIN_GLIDED_SPAN_NUM
						/ NodeInterpolator.MIN_GLIDED_SPAN_DEN))
				.append("% of the travel and the remainder arrives as a jump, so the interpolator")
				.append(" is already the minority partner in its own picture; the crossover where")
				.append(" glide and jump are a dead heat is a budget of 1/2 (ceiling 4). The A/B")
				.append(" that would settle it is specified in `PLAN-INTERPOLATION.md` and has not")
				.append(" been run. Until it does, this row is a bet rather than a result.\n\n");

		s.append("## Forge-bound (hand-maintained — classes load in a JVM test but cannot be"
				+ " instantiated, so instance-derived values are not derivable)\n\n");
		s.append("- `SceneRenderer.UPLOAD_BUDGET_PER_FRAME` — 2 MiB (see the file)\n");
		s.append("- `TileEntityGpu2`: canvas command cap, VRAM budget — see the file\n");
		return s.toString();
	}

	/** Always writes the fresh render where a regeneration can pick it up. */
	@Test
	public void rendersTheInventoryReport() throws Exception {
		File dir = new File("build/reports");
		dir.mkdirs();
		FileOutputStream out = new FileOutputStream(new File(dir, "caps-inventory.md"));
		try {
			out.write(render().getBytes(StandardCharsets.UTF_8));
		} finally {
			out.close();
		}
	}

	/** The mutual pin: docs/dev/CAPS.md must be exactly this render. Skipped on clean clones
	 *  (docs/ is gitignored), same as the animtest blob pin. */
	@Test
	public void theDocMatchesTheConstants() throws Exception {
		File doc = new File("docs/dev/CAPS.md");
		org.junit.Assume.assumeTrue("docs/dev/CAPS.md absent (clean clone) — nothing to pin",
				doc.exists());
		assertEquals("docs/dev/CAPS.md has drifted from the constants — regenerate from"
				+ " build/reports/caps-inventory.md",
				render(), new String(Files.readAllBytes(doc.toPath()), StandardCharsets.UTF_8));
	}
}
