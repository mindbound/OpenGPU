package io.github.mindbound.opengpu.client.render;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.display.DisplayShape;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.display.Displays;
import io.github.mindbound.opengpu.display.ScreenSurface;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.GlTest;
import io.github.mindbound.opengpu.gltestkit.GlThread;
import io.github.mindbound.opengpu.gltestkit.host.GlHygiene;
import io.github.mindbound.opengpu.gltestkit.host.GlReads;

/**
 * {@link ScreenDraw}'s two brackets and the TESR that calls one of them, {@link DisplayRenderer}, on a real context
 * with no Minecraft running (S12 plan P14.3 with amendments I1 and I2; S12 review strength-2), in
 * {@code clientGlTest}'s JVM, every GL call on the {@link GlThread}, {@code OpenGlHelper.initializeTextures()} first,
 * since X01 needs {@code lightmapTexUnit} (Minecraft.java:497):
 * <ul>
 * <li>the image cases draw a production {@link GlRenderHost}'s present texture of one T2 frame into an RGBA8
 * framebuffer, with {@code GL_CULL_FACE} on, {@code GL_BACK} and {@code GL_CCW} as the world pass leaves them
 * (EntityRenderer.java:1192), through the test's own unmirrored projection, and find the expanded picture upright and
 * not mirrored: {@code world} over {@code ScreenSurface.picture}'s corners on a north wall, the floor at rotation 1 and
 * the ceiling at rotation 2, seen head-on from the front, from a TESR-like entry with lighting on and a dark lightmap,
 * which only the bracket's F09 and X01 turn into the picture's own colours, with the face's outward normal on every
 * vertex (S12 review gl-1); and {@code gui} at magnification 2 under a GUI projection, from a tinted current colour,
 * which its white vertices override;</li>
 * <li>the TESR cases hand {@code renderTileEntityAt} a client tile entity as a server's description leaves it, bound
 * under the host's display id, and find the same picture on a north wall, an east wall and the floor, and nothing from
 * behind, for an unbound display or for a display whose id no device is bound to;</li>
 * <li>the hygiene case starts from I1's TESR entry built on {@link GlHygiene#mcLikeStart()}, with a live texture on
 * unit 0 other than the drawn one and a third on unit 1, and finds that each bracket leaves every item
 * {@link GlHygiene#compare} reads, and lighting, the active unit and units 0's and 1's enables and unit 1's binding,
 * read directly, as it found them, and unit 0 bound to 0 (S12 decisions D23).</li>
 * </ul>
 * The test code uses any GL; only shipped code is held to 25 §4.1's rows. No test loads FML, and none installs
 * anything in {@code RenderHosts} (S12 plan P14.1).
 */
@GlTest
class ScreenDrawTest {

    private static final String DISPLAY = "display-1";
    private static final int BACKGROUND = 0x0F;
    private static final int BOX = 0x01;
    private static final int BOX_W = 16, BOX_H = 8;
    private static final int MAX_PUMPS = 64;
    /** The GUI case: magnification 2, a GUI scale factor of 3 (ScaledResolution's), an 8-pixel margin (P12.3). */
    private static final int MAGNIFICATION = 2;
    private static final int GUI_SCALE = 3;
    private static final int MARGIN = 8;
    /** Vanilla's lightmap is 16 x 16 (EntityRenderer.java:829-848); here only its brightest texel, (15, 15), is lit. */
    private static final int LIGHTMAP = 16;

    private final ManualClock clock = new ManualClock();
    // The GL thread writes them, the test thread reads them once its GlThread.call has returned
    private final List<String> warn = new CopyOnWriteArrayList<>();
    private final List<String> info = new CopyOnWriteArrayList<>();

    /** One T2 frame presented through a production host: the host, the surface, its present texture and picture. */
    private static final class Shown {

        final GlRenderHost host;
        final SurfaceDesc surface;
        final int texture;
        /** The confirmed image expanded to RGBA8, row 0 the surface's top row. */
        final byte[] rgba;

        Shown(GlRenderHost host, SurfaceDesc surface, int texture, byte[] rgba) {
            this.host = host;
            this.surface = surface;
            this.texture = texture;
            this.rgba = rgba;
        }
    }

    /**
     * What a draw left: the framebuffer, row 0 its top row, what the bracket did not put back, and the normals the
     * {@code Tessellator} holds for the world quad's four vertices (empty for the GUI's, or when no quad was drawn).
     */
    private static final class Drawn {

        final byte[] image;
        final List<String> problems;
        final List<Integer> normals;

        Drawn(byte[] image, List<String> problems, List<Integer> normals) {
            this.image = image;
            this.problems = problems;
            this.normals = normals;
        }
    }

    /**
     * A box of {@link #BOX} at the top left of a {@link #BACKGROUND} surface, presented and confirmed, on a production
     * host whose display {@value #DISPLAY} is bound: the box shows any flip or mirror.
     */
    private Shown show(GlThread gl) throws Exception {
        GlRenderHost host = GlRenderHost.production(warn::add, info::add, gl.info().renderer, clock);
        DeviceRig rig = DeviceRig
            .on(DeviceCaps.forTier(2), host, () -> on(gl, () -> host.pass(PassBudget.unlimited())));
        host.bindDisplay(DISPLAY, rig.key);
        rig.submit(TestEncoder.segment(TestEncoder.clear(BACKGROUND), TestEncoder.fill(0, 0, BOX_W, BOX_H, BOX)));
        long frame = rig.present();
        for (int pumps = 0; pumps < MAX_PUMPS && rig.listener.events()
            .isEmpty(); pumps++) {
            rig.step();
        }
        Assertions.assertEquals(Collections.singletonList("done " + frame), rig.listener.events());
        SurfaceDesc surface = rig.device.surface();
        int texture = gl.call(() -> host.presentTexture(DISPLAY));
        Assertions.assertNotEquals(0, texture, "the bound display's present texture");
        return new Shown(host, surface, texture, Colours.expandToRgba(surface, rig.screen(), Palette.DEFAULT));
    }

    /** Closes the host on the GL thread: the ledger is then empty (24:218), and no warning was logged. */
    private void close(GlThread gl, Shown shown) {
        on(gl, shown.host::close);
        Assertions.assertTrue(
            shown.host.ledger()
                .isEmpty(),
            () -> "ledger after close: " + shown.host.ledger()
                .rows());
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    /**
     * {@code world} on {@code surface} from the camera offset (x, y, z): the picture upright and not mirrored, and the
     * face's outward normal on each of the quad's vertices.
     */
    private void worldShowsThePictureUpright(GlThread gl, ScreenSurface surface, double x, double y, double z)
        throws Exception {
        Shown shown = show(gl);
        int w = shown.surface.width;
        int h = shown.surface.height;
        double[][] corners = surface.picture(w, h);
        Runnable draw = () -> ScreenDraw.world(shown.texture, 0, 0, 1, 1, corners, x, y, z);
        Drawn drawn = gl.call(() -> drawWorld(surface, w, h, x, y, z, draw));
        assertImage(shown.rgba, drawn.image, w, h, surface.toString());
        Assertions.assertEquals(Collections.emptyList(), drawn.problems, surface.toString());
        int normal = packedNormal(surface.normal());
        Assertions.assertEquals(Arrays.asList(normal, normal, normal, normal), drawn.normals, surface.toString());
        close(gl, shown);
    }

    // P14.3, I2: a north wall, seen from the north; the corners, counter-clockwise from the front, survive the cull
    @Test
    void theWorldQuadShowsThePictureUprightOnANorthWall(GlThread gl) throws Exception {
        worldShowsThePictureUpright(gl, ScreenSurface.of(DisplayShape.placed(ForgeDirection.NORTH, 0)), -2.5, 1.25, 3);
    }

    // P14.3, I2: the floor at rotation 1, seen from above, pixel (0, 0) at the top-left for its placer
    @Test
    void theWorldQuadShowsThePictureUprightOnTheFloorAtRotation1(GlThread gl) throws Exception {
        worldShowsThePictureUpright(gl, ScreenSurface.of(DisplayShape.placed(ForgeDirection.UP, 1)), 0.5, -3, -1.75);
    }

    // P14.3, I2: the ceiling at rotation 2, seen from below
    @Test
    void theWorldQuadShowsThePictureUprightOnTheCeilingAtRotation2(GlThread gl) throws Exception {
        worldShowsThePictureUpright(gl, ScreenSurface.of(DisplayShape.placed(ForgeDirection.DOWN, 2)), 4, 2.5, 0);
    }

    // P8.3, P8.5, P8.7; review strength-2: the TESR reads a bound client tile entity's description, picks PRESENT, and
    // draws the bound device's picture upright over the letterboxed corners, from each face's front, through its own
    // camera offset
    @Test
    void theTesrDrawsTheBoundDisplaysPictureUprightOnAWallAnotherWallAndTheFloor(GlThread gl) throws Exception {
        Shown shown = show(gl);
        int w = shown.surface.width;
        int h = shown.surface.height;
        DisplayRenderer renderer = new DisplayRenderer(shown.host, () -> null);
        Object[][] views = { { DisplayShape.placed(ForgeDirection.NORTH, 0), new double[] { -2.5, 1.25, 3 } },
            { DisplayShape.placed(ForgeDirection.EAST, 0), new double[] { -3, -0.5, 1.75 } },
            { DisplayShape.placed(ForgeDirection.UP, 1), new double[] { 0.5, -3, -1.75 } } };
        for (Object[] view : views) {
            DisplayShape shape = (DisplayShape) view[0];
            double[] at = (double[]) view[1];
            ScreenSurface surface = ScreenSurface.of(shape);
            DisplayTile tile = described(DISPLAY, shape, w, h);
            Runnable draw = () -> renderer.renderTileEntityAt(tile, at[0], at[1], at[2], 0f);
            Drawn drawn = gl.call(() -> drawWorld(surface, w, h, at[0], at[1], at[2], draw));
            assertImage(shown.rgba, drawn.image, w, h, "the TESR, " + surface);
            Assertions.assertEquals(Collections.emptyList(), drawn.problems, surface.toString());
        }
        close(gl, shown);
    }

    // P8.5, P8.7; review strength-2: nothing is drawn for a camera behind the face, for an unbound display (a size of
    // 0 x 0), or for a bound display whose id no device of this host is bound to (no present texture: NONE)
    @Test
    void theTesrDrawsNothingFromBehindUnboundOrWithNoPresentTexture(GlThread gl) throws Exception {
        Shown shown = show(gl);
        int w = shown.surface.width;
        int h = shown.surface.height;
        DisplayRenderer renderer = new DisplayRenderer(shown.host, () -> null);
        DisplayShape north = DisplayShape.placed(ForgeDirection.NORTH, 0);
        ScreenSurface surface = ScreenSurface.of(north);
        Object[][] cases = { { "from behind", described(DISPLAY, north, w, h), -4.0 },
            { "unbound", described(DISPLAY, north, 0, 0), 3.0 },
            { "no device bound to its id", described("display-2", north, w, h), 3.0 } };
        for (Object[] c : cases) {
            DisplayTile tile = (DisplayTile) c[1];
            double z = (Double) c[2];
            Runnable draw = () -> renderer.renderTileEntityAt(tile, -2.5, 1.25, z, 0f);
            Drawn drawn = gl.call(() -> drawWorld(surface, w, h, -2.5, 1.25, z, draw));
            assertImage(new byte[w * h * 4], drawn.image, w, h, (String) c[0]);
            Assertions.assertEquals(Collections.emptyList(), drawn.problems, (String) c[0]);
        }
        close(gl, shown);
    }

    // P14.3, I2: the GUI's quad at magnification 2 under a GUI projection: each pixel of the picture a 2 x 2 block
    // inside an 8-pixel margin, row 0 at the top, white vertices over a tinted current colour
    @Test
    void theGuiQuadShowsThePictureUprightAtMagnification2(GlThread gl) throws Exception {
        Shown shown = show(gl);
        int w = shown.surface.width;
        int h = shown.surface.height;
        int fw = MAGNIFICATION * w + 2 * MARGIN;
        int fh = MAGNIFICATION * h + 2 * MARGIN;
        Drawn drawn = gl.call(() -> drawGui(shown.texture, w, h, fw, fh));
        byte[] expected = new byte[fw * fh * 4];
        for (int py = MARGIN; py < MARGIN + MAGNIFICATION * h; py++) {
            for (int px = MARGIN; px < MARGIN + MAGNIFICATION * w; px++) {
                int texel = ((py - MARGIN) / MAGNIFICATION * w + (px - MARGIN) / MAGNIFICATION) * 4;
                System.arraycopy(shown.rgba, texel, expected, (py * fw + px) * 4, 4);
            }
        }
        assertImage(expected, drawn.image, fw, fh, "the GUI at magnification 2");
        Assertions.assertEquals(Collections.emptyList(), drawn.problems);
        close(gl, shown);
    }

    // I1, D23: from a TESR entry on GlHygiene's MC-like start, each bracket leaves every compared item, lighting, the
    // active unit, units 0's and 1's enables and unit 1's binding as it found them, and unit 0 bound to 0
    @Test
    void theWorldAndGuiBracketsLeaveTheTesrEntryAsTheyFoundItButUnit0BoundTo0(GlThread gl) throws Exception {
        List<String> problems = gl.call(ScreenDrawTest::hygiene);
        Assertions.assertEquals(Collections.emptyList(), problems);
    }

    // ---- The GL thread's

    /**
     * {@code draw}, {@code world} or the TESR, into a W × H framebuffer under {@link #faceOn}'s projection, from a
     * TESR-like entry: lighting on, unit 0's texturing on, the lightmap on unit 1 with vanilla's texture matrix and a
     * dark coordinate, the cull as the world pass has it, white. Everything is put back after.
     */
    private static Drawn drawWorld(ScreenSurface surface, int w, int h, double x, double y, double z, Runnable draw) {
        OpenGlHelper.initializeTextures();
        int[] viewport = viewport();
        int lightmap = lightmap();
        int target = rgbaTarget(w, h);
        int framebuffer = framebuffer(target);
        List<String> problems = new ArrayList<>();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadMatrix(floats(faceOn(surface, w, h, x, y, z)));
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        try {
            GL11.glViewport(0, 0, w, h);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            tesrEntry(lightmap);
            draw.run();
            List<Integer> normals = tessellatedNormals();
            expect(problems, "GL_LIGHTING after world", GL11.glIsEnabled(GL11.GL_LIGHTING), true);
            expect(problems, "active unit after world", GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), GL13.GL_TEXTURE0);
            expect(problems, "unit 0 texture after world", GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), 0);
            leaveTesrEntry();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            return new Drawn(
                topRowFirst(GlReads.texture(target, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, w * h * 4), w, h),
                problems,
                normals);
        } finally {
            leaveTesrEntry();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(target);
            GL11.glDeleteTextures(lightmap);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * {@code gui} into an fw × fh framebuffer under vanilla's GUI projection at {@link #GUI_SCALE}
     * (EntityRenderer.java:1739-1749): the picture's rectangle at window pixels {@link #MARGIN} to
     * {@code MARGIN + 2 W} divided by the scale factor, so each vertex lands on a window pixel. A GUI's state: lighting
     * and the lightmap unit off, unit 0's texturing on, the cull on, and a tinted current colour.
     */
    private static Drawn drawGui(int texture, int w, int h, int fw, int fh) {
        OpenGlHelper.initializeTextures();
        int[] viewport = viewport();
        int target = rgbaTarget(fw, fh);
        int framebuffer = framebuffer(target);
        List<String> problems = new ArrayList<>();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0, fw / (double) GUI_SCALE, fh / (double) GUI_SCALE, 0, 1000, 3000);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0f, 0f, -2000f);
        try {
            GL11.glViewport(0, 0, fw, fh);
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glColor4f(0.5f, 0.25f, 0.75f, 1f);
            double scale = GUI_SCALE;
            ScreenDraw.gui(
                texture,
                0,
                0,
                1,
                1,
                MARGIN / scale,
                MARGIN / scale,
                (MARGIN + MAGNIFICATION * w) / scale,
                (MARGIN + MAGNIFICATION * h) / scale);
            expect(problems, "unit 0 texture after gui", GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), 0);
            expect(problems, "GL_TEXTURE_2D after gui", GL11.glIsEnabled(GL11.GL_TEXTURE_2D), true);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            return new Drawn(
                topRowFirst(GlReads.texture(target, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, fw * fh * 4), fw, fh),
                problems,
                Collections.<Integer>emptyList());
        } finally {
            GL11.glColor4f(1f, 1f, 1f, 1f);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(target);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /**
     * I1's TESR entry on {@link GlHygiene#mcLikeStart()}: the array buffer, program and VAO 0, since the
     * {@code Tessellator}'s client arrays need them (LWJGL's array-buffer check), unit 0 active with a texture of its
     * own and its texturing on, the lightmap bound and enabled on {@code lightmapTexUnit}, lighting on, the cull
     * {@code GL_BACK} and {@code GL_CCW}; then each bracket against a snapshot whose unit 0 binding is 0 (S12
     * decisions D23), the entry's texture bound on unit 0 again before {@code gui}, so that a bracket that leaves the
     * drawn texture bound, puts the entry's back, or binds 0 on another unit fails. Its own additions are undone before
     * GlHygiene's undo.
     */
    private static List<String> hygiene() {
        OpenGlHelper.initializeTextures();
        // Before the start, whose unpack buffer and stores would take an upload
        int entry = rgbaTarget(2, 2);
        int picture = rgbaTarget(4, 4);
        int lightmap = lightmap();
        List<String> problems = new ArrayList<>();
        GlHygiene start = null;
        try {
            start = GlHygiene.mcLikeStart();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(0);
            GL30.glBindVertexArray(0);
            GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmap);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(GL11.GL_BACK);
            GL11.glFrontFace(GL11.GL_CCW);
            Map<String, String> entryState = direct();
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put("GL_LIGHTING", "true");
            expected.put("active unit", "GL_TEXTURE0");
            expected.put("unit 0 texture", String.valueOf(entry));
            expected.put("unit 0 GL_TEXTURE_2D", "true");
            expected.put("unit 1 texture", String.valueOf(lightmap));
            expected.put("unit 1 GL_TEXTURE_2D", "true");
            problems.addAll(differences("the entry", expected, entryState));
            // D23: each bracket leaves unit 0 bound to 0 and every other item as it found it
            Map<String, String> snapshot = new LinkedHashMap<>(GlHygiene.snapshot());
            snapshot.put("unit 0 texture", "0");
            Map<String, String> after = new LinkedHashMap<>(entryState);
            after.put("unit 0 texture", "0");
            double[][] corners = ScreenSurface.of(DisplayShape.DEFAULT)
                .picture(320, 200);
            ScreenDraw.world(picture, 0, 0, 1, 1, corners, 0.5, -1, -2);
            for (String item : GlHygiene.compare(snapshot)) {
                problems.add("world: " + item);
            }
            problems.addAll(differences("world", after, direct()));
            // The entry's texture again, so that gui too must replace a live binding with 0
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, entry);
            ScreenDraw.gui(picture, 0, 0, 1, 1, 4, 4, 324, 204);
            for (String item : GlHygiene.compare(snapshot)) {
                problems.add("gui: " + item);
            }
            problems.addAll(differences("gui", after, direct()));
        } finally {
            GL11.glDisable(GL11.GL_LIGHTING);
            GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDeleteTextures(entry);
            GL11.glDeleteTextures(picture);
            GL11.glDeleteTextures(lightmap);
            if (start != null) {
                start.undo();
            }
        }
        return problems;
    }

    /**
     * The items GlHygiene does not compare and the hygiene case reads itself (I1): lighting, the active unit, and
     * units 0's and 1's 2D bindings and enables; the active unit is put back after the reads.
     */
    private static Map<String, String> direct() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("GL_LIGHTING", String.valueOf(GL11.glIsEnabled(GL11.GL_LIGHTING)));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        s.put("active unit", "GL_TEXTURE" + (active - GL13.GL_TEXTURE0));
        for (int unit = 0; unit < 2; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            s.put("unit " + unit + " texture", String.valueOf(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)));
            s.put("unit " + unit + " GL_TEXTURE_2D", String.valueOf(GL11.glIsEnabled(GL11.GL_TEXTURE_2D)));
        }
        GL13.glActiveTexture(active);
        return s;
    }

    /**
     * The world pass's state at a TESR, as far as it bears on the quad: unit 1 holds the lightmap, enabled, under
     * vanilla's texture matrix (scale 1/256, move 8; EntityRenderer.java:829-848) at the dark coordinate (0, 0), so
     * only X01's 240 reaches the lit texel; lighting on with no light enabled, so only F09 keeps the picture's colours;
     * unit 0's texturing on; the cull on, {@code GL_BACK} and {@code GL_CCW}; the dispatcher's white
     * (TileEntityRendererDispatcher.java:121-125).
     */
    private static void tesrEntry(int lightmap) {
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightmap);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glScalef(1f / 256, 1f / 256, 1f / 256);
        GL11.glTranslatef(8f, 8f, 8f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL13.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, 0f, 0f);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    /** {@link #tesrEntry} undone, back to the context's defaults; idempotent. */
    private static void leaveTesrEntry() {
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glMatrixMode(GL11.GL_TEXTURE);
        GL11.glLoadIdentity();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL13.glMultiTexCoord2f(OpenGlHelper.lightmapTexUnit, 0f, 0f);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    /**
     * The projection, column-major, that shows {@code surface}'s W × H picture head-on from its front, filling clip
     * space: x along the face's right, y along its up, z against its normal, so the view is a rotation, not mirrored.
     * Its centre and size come from the face and the docs' rule, not from {@code picture}'s corners: the face's centre
     * {@code OFFSET} in front of it, and the inner square's side, 11.5/16, along the picture's longer side (15:175).
     */
    private static float[] faceOn(ScreenSurface surface, int w, int h, double x, double y, double z) {
        double inner = 1 - 2 * ScreenSurface.BEZEL;
        double halfWidth = (w >= h ? inner : inner * w / h) / 2;
        double halfHeight = (w >= h ? inner * h / w : inner) / 2;
        ForgeDirection r = surface.right();
        ForgeDirection u = surface.up();
        ForgeDirection n = surface.normal();
        double[] mid = surface.point(0.5, 0.5);
        double cx = x + mid[0] + ScreenSurface.OFFSET * n.offsetX;
        double cy = y + mid[1] + ScreenSurface.OFFSET * n.offsetY;
        double cz = z + mid[2] + ScreenSurface.OFFSET * n.offsetZ;
        double rc = r.offsetX * cx + r.offsetY * cy + r.offsetZ * cz;
        double uc = u.offsetX * cx + u.offsetY * cy + u.offsetZ * cz;
        double nc = n.offsetX * cx + n.offsetY * cy + n.offsetZ * cz;
        return new float[] { (float) (r.offsetX / halfWidth), (float) (u.offsetX / halfHeight), -n.offsetX, 0,
            (float) (r.offsetY / halfWidth), (float) (u.offsetY / halfHeight), -n.offsetY, 0,
            (float) (r.offsetZ / halfWidth), (float) (u.offsetZ / halfHeight), -n.offsetZ, 0, (float) (-rc / halfWidth),
            (float) (-uc / halfHeight), (float) nc, 1 };
    }

    /** A 16 × 16 RGBA8 lightmap, black but for its brightest texel (15, 15), nearest and clamped. */
    private static int lightmap() {
        ByteBuffer texels = ByteBuffer.allocateDirect(LIGHTMAP * LIGHTMAP * 4)
            .order(ByteOrder.nativeOrder());
        for (int i = 0; i < LIGHTMAP * LIGHTMAP; i++) {
            byte lit = (byte) (i == LIGHTMAP * LIGHTMAP - 1 ? 255 : 0);
            texels.put(lit)
                .put(lit)
                .put(lit)
                .put((byte) 255);
        }
        texels.flip();
        int name = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            LIGHTMAP,
            LIGHTMAP,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            texels);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return name;
    }

    /** An empty RGBA8 texture, nearest, for a framebuffer's target or a texture to bind. */
    private static int rgbaTarget(int w, int h) {
        int name = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            w,
            h,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return name;
    }

    /** A framebuffer with {@code target} as its colour, bound. */
    private static int framebuffer(int target) {
        int name = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, name);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("framebuffer status 0x" + Integer.toHexString(status));
        }
        return name;
    }

    private static int[] viewport() {
        IntBuffer buffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();
        GL11.glGetInteger(GL11.GL_VIEWPORT, buffer);
        return new int[] { buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3) };
    }

    private static FloatBuffer floats(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        buffer.put(values);
        buffer.flip();
        return buffer;
    }

    // ---- Plain helpers

    /** A client's display tile entity described bound under {@code id}, or unbound for a 0 × 0 size. */
    private static DisplayTile described(String id, DisplayShape shape, int width, int height) {
        DisplayTile tile = Displays.described(id, shape, width, height);
        Assertions.assertEquals(
            Arrays.asList(id, width, height),
            Arrays.asList(tile.displayId(), tile.surfaceWidth(), tile.surfaceHeight()));
        return tile;
    }

    /**
     * The normals of the last quad's four vertices, as vanilla's {@code Tessellator} keeps them after its
     * {@code draw()} (its eight ints a vertex, the sixth the packed normal; Tessellator.java:410-432), read by
     * reflection, since the fields are private; empty when the quad carried none.
     */
    private static List<Integer> tessellatedNormals() {
        try {
            Field hasNormals = Tessellator.class.getDeclaredField("hasNormals");
            Field rawBuffer = Tessellator.class.getDeclaredField("rawBuffer");
            hasNormals.setAccessible(true);
            rawBuffer.setAccessible(true);
            if (!hasNormals.getBoolean(Tessellator.instance)) {
                return Collections.emptyList();
            }
            int[] raw = (int[]) rawBuffer.get(Tessellator.instance);
            List<Integer> normals = new ArrayList<>();
            for (int vertex = 0; vertex < 4; vertex++) {
                normals.add(raw[vertex * 8 + 6]);
            }
            return normals;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The face's outward normal packed as {@code Tessellator.setNormal} packs it (Tessellator.java:468-475). */
    private static int packedNormal(ForgeDirection normal) {
        int x = (byte) (int) (normal.offsetX * 127f);
        int y = (byte) (int) (normal.offsetY * 127f);
        int z = (byte) (int) (normal.offsetZ * 127f);
        return x & 255 | (y & 255) << 8 | (z & 255) << 16;
    }

    /** glGetTexImage's rows start at the framebuffer's bottom; the pictures' at their top. */
    private static byte[] topRowFirst(byte[] bottomFirst, int w, int h) {
        int row = w * 4;
        byte[] out = new byte[bottomFirst.length];
        for (int y = 0; y < h; y++) {
            System.arraycopy(bottomFirst, (h - 1 - y) * row, out, y * row, row);
        }
        return out;
    }

    private static void expect(List<String> problems, String what, Object now, Object expected) {
        if (!expected.equals(now)) {
            problems.add(what + ": " + now + ", expected " + expected);
        }
    }

    private static List<String> differences(String when, Map<String, String> expected, Map<String, String> now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> item : expected.entrySet()) {
            if (!item.getValue()
                .equals(now.get(item.getKey()))) {
                out.add(
                    when + ": " + item.getKey() + " is " + now.get(item.getKey()) + ", expected " + item.getValue());
            }
        }
        return out;
    }

    /** Every pixel compared; a failure names how many differ and the first, (x, y) from the top-left. */
    private static void assertImage(byte[] expected, byte[] actual, int w, int h, String what) {
        Assertions.assertEquals(expected.length, actual.length, what);
        int differing = 0;
        int first = -1;
        for (int p = 0; p < w * h; p++) {
            for (int c = 0; c < 4; c++) {
                if (expected[p * 4 + c] != actual[p * 4 + c]) {
                    differing++;
                    if (first < 0) {
                        first = p;
                    }
                    break;
                }
            }
        }
        if (differing > 0) {
            Assertions.fail(
                String.format(
                    Locale.ROOT,
                    "%s: %d of %d pixels differ; the first, (%d, %d), is %s, expected %s",
                    what,
                    differing,
                    w * h,
                    first % w,
                    first / w,
                    pixel(actual, first),
                    pixel(expected, first)));
        }
    }

    private static String pixel(byte[] rgba, int p) {
        return String.format(
            Locale.ROOT,
            "(%d, %d, %d, %d)",
            rgba[p * 4] & 0xFF,
            rgba[p * 4 + 1] & 0xFF,
            rgba[p * 4 + 2] & 0xFF,
            rgba[p * 4 + 3] & 0xFF);
    }

    /** Work with no result on the GL thread, checked exceptions wrapped, for DeviceRig's Runnable pump. */
    private static void on(GlThread gl, Runnable work) {
        try {
            gl.call((Callable<Void>) () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
