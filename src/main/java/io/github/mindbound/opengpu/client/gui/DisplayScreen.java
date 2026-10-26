package io.github.mindbound.opengpu.client.gui;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import org.lwjgl.input.Mouse;

import io.github.mindbound.opengpu.client.render.GlRenderHost;
import io.github.mindbound.opengpu.client.render.ScreenDraw;
import io.github.mindbound.opengpu.client.render.ScreenFace;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.display.DisplayTile;

/**
 * The display's GUI (24:222; 20:173; RM:34, :85; S12 plan P12.2-P12.5 with amendment F1; Q-S12-4 (a), Q-S12-6 (a)),
 * which a right-click opens through {@code DisplayOpener}: no container, and it does not pause the game, as OC's
 * screen GUI does not (InputBuffer.scala:38), so the integrated server keeps ticking and a frame's status and the
 * GUI's own packets do not wait (K4). Escape closes it, vanilla's {@code keyTyped}; every other key does nothing in M0,
 * since the keyboard is M1's (RM:85).
 * <p>
 * <b>The picture.</b> The client tile entity's description is read live, at every draw and every event, so a
 * description under a new id or size, which every attach and detach sends (28:1151), is followed, not closed on
 * (O-22). Unbound, a size of 0 × 0, it shows the label {@value #NO_SIGNAL} centred and maps no pixel. Bound, the
 * picture sits at {@link PointerMapping}'s integer magnification in window pixels, and shows what the display's face
 * shows ({@link ScreenFace#choose}): the present texture through {@link ScreenDraw#gui}; the "no graphics" icon of
 * the block atlas over a square in its middle, on black; or black, which a LAN guest's and a dedicated server's
 * player's client shows for every bound display in M0 (Q-S12-4 (a)), and which takes touches all the same. The black
 * is vanilla's {@code drawRect} at the picture's whole GUI units, rounded outward.
 * <p>
 * <b>Input.</b> {@link #handleMouseInput} reads each buffered LWJGL event raw, in window pixels, since
 * {@code GuiScreen}'s scaled integers lose the fraction (K9; 03:51), maps it with {@link PointerMapping}, and feeds a
 * {@link Gesture}, which {@link #updateScreen} ticks once per client tick and which sends to the sink it was given,
 * {@code client.InputSender} in game. It closes itself when its tile entity is no longer the one at its position.
 * <p>
 * It makes no GL call of its own: its GL is {@link ScreenDraw}'s, and vanilla's in {@code drawRect}, the background
 * and the font, so jarTest's client checks see every GL call of OpenGPU's in {@code ScreenDraw} (S12 plan P14.4). The
 * client main thread only.
 */
public final class DisplayScreen extends GuiScreen {

    /** The lang key of the label an unbound display's GUI shows (S12 plan P12.3). */
    public static final String NO_SIGNAL = "gui.opengpu.screen.noSignal";

    /** Opaque black, ARGB. */
    private static final int BLACK = 0xFF000000;
    /** The label's colour, white. */
    private static final int LABEL = 0xFFFFFF;

    private final DisplayTile tile;
    private final RenderHost host;
    /** The host when it is a {@link GlRenderHost}, the only one with present textures, else null. */
    private final GlRenderHost gl;
    private final Supplier<IIcon> noGraphics;
    private final Gesture gesture;

    /**
     * The GUI of {@code tile}, a client's display tile entity.
     *
     * @param host       the installed {@code Installation}'s host, as the TESR's
     * @param noGraphics the "no graphics" icon in the block atlas, asked for at each such draw; null while there is
     *                   none
     * @param sink       where the gestures go: the {@code InputSender} for the tile's block in game
     * @throws NullPointerException for a null argument
     */
    public DisplayScreen(DisplayTile tile, RenderHost host, Supplier<IIcon> noGraphics, Gesture.Sink sink) {
        this.tile = Objects.requireNonNull(tile, "tile");
        this.host = Objects.requireNonNull(host, "host");
        this.gl = host instanceof GlRenderHost ? (GlRenderHost) host : null;
        this.noGraphics = Objects.requireNonNull(noGraphics, "noGraphics");
        this.gesture = new Gesture(new TileTarget(tile), sink);
    }

    /**
     * False: the game keeps running while the GUI is open, so the integrated server ticks (K4; 20:173; 24:222).
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * Once per client tick: closes the GUI when its tile entity is gone ({@link #showsItsDisplay}), else sends the
     * gesture's coalesced drag, at most one a tick (15:186) and only when its pixel changed (S12 decisions D26).
     */
    @Override
    public void updateScreen() {
        if (!showsItsDisplay(tile)) {
            mc.displayGuiScreen(null);
            return;
        }
        gesture.tick();
    }

    /**
     * Each buffered mouse event, read raw: a button's press or release, else a move and a wheel turn, at the event's
     * window pixel, mapped with the description's size at that moment (NaN when nothing maps). Not
     * {@code super.handleMouseInput()}, whose scaled integers lose the fraction, and which serves buttons this GUI
     * does not have (GuiScreen.java:329-363).
     */
    @Override
    public void handleMouseInput() {
        PointerMapping mapping = mapping();
        int eventX = Mouse.getEventX();
        int eventY = Mouse.getEventY();
        double px = mapping != null ? mapping.surfaceX(eventX) : Double.NaN;
        double py = mapping != null ? mapping.surfaceY(eventY) : Double.NaN;
        int button = Mouse.getEventButton();
        if (button != -1) {
            if (Mouse.getEventButtonState()) {
                gesture.press(button, px, py);
            } else {
                gesture.release(button, px, py);
            }
            return;
        }
        gesture.move(px, py);
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            gesture.scroll(wheel, px, py);
        }
    }

    /**
     * The GUI closes, by Escape, its display gone or another screen opening (Minecraft.java:846-849): a gesture still
     * held ends with its drop at its last inside position ({@link Gesture#close}; S12 decisions D12).
     */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        gesture.close();
    }

    /** The world's darkened background, then the picture or the "no signal" label (S12 plan P12.3). */
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (tile.surfaceWidth() <= 0 || tile.surfaceHeight() <= 0) {
            drawCenteredString(
                fontRendererObj,
                I18n.format(NO_SIGNAL),
                width / 2,
                (height - fontRendererObj.FONT_HEIGHT) / 2,
                LABEL);
        } else {
            PointerMapping mapping = mapping();
            if (mapping != null) {
                drawPicture(mapping);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * Whether a GUI opened on {@code tile} still shows its display: the tile entity is valid, in a world, and the one
     * at its own position there. A display broken, unloaded or replaced closes its GUI (S12 plan P12.2).
     */
    static boolean showsItsDisplay(DisplayTile tile) {
        World world = tile.getWorldObj();
        return !tile.isInvalid() && world != null && world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    /** The GUI's gesture machine, for tests, which feed it as {@link #handleMouseInput} would. */
    Gesture gesture() {
        return gesture;
    }

    // ---- Internals

    /** The mapping for the description's size in the window as it is now, or null. */
    private PointerMapping mapping() {
        return PointerMapping.fit(mc.displayWidth, mc.displayHeight, tile.surfaceWidth(), tile.surfaceHeight());
    }

    private void drawPicture(PointerMapping mapping) {
        double[] rect = mapping.guiRect(new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor());
        int texture = gl != null ? gl.presentTexture(tile.displayId()) : 0;
        ScreenFace face = ScreenFace.choose(true, host.available(), texture);
        if (face == ScreenFace.PRESENT) {
            ScreenDraw.gui(texture, 0, 0, 1, 1, rect[0], rect[1], rect[2], rect[3]);
            return;
        }
        Gui.drawRect(
            (int) Math.floor(rect[0]),
            (int) Math.floor(rect[1]),
            (int) Math.ceil(rect[2]),
            (int) Math.ceil(rect[3]),
            BLACK);
        if (face == ScreenFace.NO_GRAPHICS) {
            drawNoGraphics(rect);
        }
    }

    /**
     * The "no graphics" icon over the largest square centred in the picture, as the TESR's fills the inner square
     * [proposal; S12 build, U4].
     */
    private void drawNoGraphics(double[] rect) {
        IIcon icon = noGraphics.get();
        ITextureObject atlas = mc.getTextureManager()
            .getTexture(TextureMap.locationBlocksTexture);
        if (icon == null || atlas == null) {
            return;
        }
        double side = Math.min(rect[2] - rect[0], rect[3] - rect[1]);
        double left = (rect[0] + rect[2] - side) / 2;
        double top = (rect[1] + rect[3] - side) / 2;
        ScreenDraw.gui(
            atlas.getGlTextureId(),
            icon.getMinU(),
            icon.getMinV(),
            icon.getMaxU(),
            icon.getMaxV(),
            left,
            top,
            left + side,
            top + side);
    }

    /** The client tile entity's description as the gesture reads it, at each send. */
    private static final class TileTarget implements Gesture.Target {

        private final DisplayTile tile;

        TileTarget(DisplayTile tile) {
            this.tile = tile;
        }

        @Override
        public String displayId() {
            return tile.displayId();
        }

        @Override
        public int surfaceWidth() {
            return tile.surfaceWidth();
        }

        @Override
        public int surfaceHeight() {
            return tile.surfaceHeight();
        }
    }
}
