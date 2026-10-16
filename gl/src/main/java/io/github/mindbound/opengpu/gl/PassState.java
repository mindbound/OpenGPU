package io.github.mindbound.opengpu.gl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Locale;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * The bracket of every pass and every other GL entry point (20:52-73; 24:197; 25 R-1, R-2, R-3, R-6; S8 plan §3.3 and
 * amendments B). {@link #enter} saves what the pass changes outside {@code glPushAttrib}, pushes one attribute level
 * with seven bits (R-6) and saves the scissor box (R-1), forces the state the pass needs, unbinds both pixel buffers
 * and binds sampler 0 on units 0 and 1. {@link #exit} restores in reverse, the scissor box just before
 * {@code glPopAttrib}. Both contexts take the same calls (24:187); under GLSM every one goes through its cache, so the
 * start state it saves is GLSM's.
 * <p>
 * The error check (20:240; H63) runs after the first pass, and after every pass in dev builds: {@link #exit} reads the
 * driver's error state, and {@link #enter} of the same pass first drains what other code left (R-3), so the check
 * reports the pass's own errors only. Both read it through {@link #DRIVER_GET_ERROR}, a method handle on
 * {@code GL11.glGetError} looked up at run time (D01), never through a call instruction: GLSMRedirector rewrites
 * {@code GL11.glGetError} to {@code GLStateManager.glGetError}, and from Angelica 2.2.16 {@code Util.checkGLError} to
 * {@code GLStateManager.checkGLError}, both of which answer no error under Angelica's release default (its
 * {@code disableErrorChecks}, on in an obfuscated client; {@code GLStateManager.java:5216-5219} at 2.2.8,
 * {@code :5979-5988} at 2.2.28). The redirector rewrites call instructions and the method handles of
 * {@code invokedynamic}, never a lookup at run time, and leaves LWJGL's own classes as they are, so on every leg and
 * every release the handle reaches the driver (S8 review HY-1). A pass that will not check drains nothing, since the
 * driver's {@code glGetError} can be a sync point. GL thread; nothing is allocated after construction.
 */
public final class PassState {

    /** 20 §1's seven bits (C04). */
    static final int ATTRIB_BITS = GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
        | GL11.GL_DEPTH_BUFFER_BIT
        | GL11.GL_STENCIL_BUFFER_BIT
        | GL11.GL_SCISSOR_BIT
        | GL11.GL_VIEWPORT_BIT
        | GL11.GL_POLYGON_BIT;
    /** R-3's bound: a lost context can report errors forever. */
    static final int MAX_DRAINED_ERRORS = 16;
    /** The units the pass touches and saves (H53). */
    private static final int UNITS = 2;
    private static final boolean DEV = PassState.class.desiredAssertionStatus();
    /**
     * D01: {@code GL11.glGetError}, looked up here rather than called, so that GLSMRedirector leaves it as it is (see
     * the class comment). A lookup, no GL work.
     */
    private static final MethodHandle DRIVER_GET_ERROR = lookUpDriverGetError();

    /** R-2: a direct buffer at position 0 large enough for LWJGL's 16-value check; read with absolute gets. */
    private final IntBuffer scissor = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer();
    private final int[] texture = new int[UNITS];
    private final int[] sampler = new int[UNITS];
    private int program;
    private int drawFramebuffer;
    private int readFramebuffer;
    private int vertexArray;
    private int arrayBuffer;
    private int packBuffer;
    private int unpackBuffer;
    private int activeTexture;
    private final int[] scissorBox = new int[4];
    private boolean rasterizerDiscard;
    private boolean entered;
    private boolean checkedOnce;
    /** Whether the open pass drained at its enter and checks at its exit: dev builds, or no check yet. */
    private boolean checking;
    private int errorsDrained;
    private long rasterizerDiscardSeen;

    /** No GL work. */
    public PassState() {}

    /**
     * Opens the bracket.
     *
     * @throws IllegalStateException if it is open
     */
    public void enter() {
        if (entered) {
            throw new IllegalStateException("the pass bracket is already open");
        }
        // 1. R-3: what other code left is not this pass's, drained from the driver (D01) by a pass that checks
        checking = DEV || !checkedOnce;
        errorsDrained = 0;
        while (checking && errorsDrained < MAX_DRAINED_ERRORS && driverError() != GL11.GL_NO_ERROR) {
            errorsDrained++;
        }
        // 2. Saves (C01): draw and read framebuffers separately (20:57); the VAO is GLSM's default one under GLSM
        // (20:58)
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        unpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int unit = 0; unit < UNITS; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            texture[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            sampler[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        // 3. One attribute level (R-6), then the box, which GL_SCISSOR_BIT restores only from GLSM 2.2.21 (R-1, R-2)
        GL11.glPushAttrib(ATTRIB_BITS);
        scissor.clear();
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, scissor);
        for (int i = 0; i < 4; i++) {
            scissorBox[i] = scissor.get(i);
        }
        entered = true;
        // 4. Forces (20:71-73; H41), each capability a constant in its call (25:239)
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_DITHER);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE2);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE3);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE4);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE5);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE6);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE7);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        // A discard another mod leaked would blank every draw and let untouched surfaces be confirmed (H70)
        rasterizerDiscard = GL11.glIsEnabled(GL30.GL_RASTERIZER_DISCARD);
        if (rasterizerDiscard) {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            rasterizerDiscardSeen++;
            if (DEV && rasterizerDiscardSeen == 1) {
                System.err.println("[opengpu-gl] GL_RASTERIZER_DISCARD was on at the pass's start; off for the pass");
            }
        }
        // 5. Both pixel buffers unbound, so LWJGL's buffer checks hold whatever the start (S8 amendments B; B1)
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        // 6. Sampler 0 on both units during the pass (C12; 28:459), unit 0 active
        GL33.glBindSampler(0, 0);
        GL33.glBindSampler(1, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Closes the bracket and then reads the driver's error state after the first pass and, in dev builds, after every
     * pass (D01).
     *
     * @throws IllegalStateException unless it is open, or naming the GL error the check found (after restoring)
     */
    public void exit() {
        restore();
        if (checking) {
            checking = false;
            checkedOnce = true;
            int error = driverError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException(
                    String.format(Locale.ROOT, "GL error 0x%04X after OpenGPU's pass (20:240)", error));
            }
        }
    }

    /**
     * Closes the bracket without the error check, for a pass that failed: the state is restored and the failure that
     * ended the pass is reported instead.
     *
     * @throws IllegalStateException unless it is open
     */
    public void abort() {
        restore();
    }

    public boolean entered() {
        return entered;
    }

    /**
     * How many errors {@link #enter} drained from the driver last time (R-3), for the hygiene tests: only a pass that
     * checks at its exit drains (the first, or every one in dev builds), so for any other pass it is 0 whatever is
     * pending.
     */
    public int errorsDrainedAtEnter() {
        return errorsDrained;
    }

    /** How many passes found {@code GL_RASTERIZER_DISCARD} on at their start (H70). */
    public long rasterizerDiscardSeen() {
        return rasterizerDiscardSeen;
    }

    private void restore() {
        if (!entered) {
            throw new IllegalStateException("the pass bracket is not open");
        }
        entered = false;
        for (int unit = UNITS - 1; unit >= 0; unit--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture[unit]);
            GL33.glBindSampler(unit, sampler[unit]);
        }
        GL13.glActiveTexture(activeTexture);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL30.glBindVertexArray(vertexArray);
        // Each target to its own binding, never GL_FRAMEBUFFER (20:57); before the pop, so a draw-buffer setting the
        // pop restores lands on the framebuffer it was saved from
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        // The exact id (20:56)
        GL20.glUseProgram(program);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        GL11.glPopAttrib();
        if (rasterizerDiscard) {
            GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        }
    }

    /** The driver's next error, {@code GL_NO_ERROR} for none: {@link #DRIVER_GET_ERROR} (D01). */
    private static int driverError() {
        try {
            return (int) DRIVER_GET_ERROR.invokeExact();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code GL11.glGetError()} as a method handle: GL11's class literal and the name, the only form D01 takes. */
    private static MethodHandle lookUpDriverGetError() {
        try {
            return MethodHandles.publicLookup()
                .findStatic(GL11.class, "glGetError", MethodType.methodType(int.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("no public static int glGetError() on LWJGL's GL11", e);
        }
    }
}
