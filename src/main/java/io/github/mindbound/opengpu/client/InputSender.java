package io.github.mindbound.opengpu.client;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.net.Channel;
import io.github.mindbound.opengpu.net.PointerEvent;
import io.github.mindbound.opengpu.net.PointerPacket;
import io.netty.buffer.Unpooled;

/**
 * Input's client half (13:139; 24:222; S11 plan N5, U5.13; S12 plan P12.6 with amendment I11 (b)): one display GUI's
 * sender, which turns each pointer event into S11's {@link PointerPacket} for the GUI's display block and sends it to
 * the server on the {@value Channel#NAME} channel, where S11's {@code InputListener} hands it to the session's
 * {@code InputHandler} on the server thread. A send happens on the client main thread only, where the GUI's input
 * runs, so no two threads share the channel's embedded channel (13:9); a call from any other thread is dropped. The
 * payload is a heap buffer, since FML's {@code toC17Packet} reads {@code payload.array()}
 * (FMLProxyPacket.java:127-130). With no connection FML drops the packet quietly (FMLOutboundHandler.java:216-228).
 * <p>
 * Only {@link #forBlock}, the production factory, reaches the channel and {@code Minecraft}, through a nested class
 * that only it loads; the package-private constructor takes the bytes' consumer and the thread test, so no test loads
 * {@code Channel} or touches {@code Minecraft} (S11 plan P15.1). Client code, outside jarTest check 8's common scope
 * (13:139). The client main thread, but the thread test is safe on any thread.
 */
public final class InputSender {

    private final int x;
    private final int y;
    private final int z;
    private final Consumer<byte[]> out;
    private final BooleanSupplier onMainThread;
    /** Calls dropped: off the main thread, or for an id the codec refuses. */
    private final AtomicInteger dropped = new AtomicInteger();

    /**
     * A sender for the display block at {@code (x, y, z)} that hands each packet to {@code out} when
     * {@code onMainThread} holds.
     *
     * @throws NullPointerException for a null {@code out} or {@code onMainThread}
     */
    InputSender(int x, int y, int z, Consumer<byte[]> out, BooleanSupplier onMainThread) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.out = Objects.requireNonNull(out, "out");
        this.onMainThread = Objects.requireNonNull(onMainThread, "onMainThread");
    }

    /**
     * The sender of the GUI opened on the display block at {@code (x, y, z)}: the channel's {@code sendToServer}, and
     * {@code Minecraft.func_152345_ab()} as the thread test (Minecraft.java:3012). In game only, after FML's preInit
     * has made the channel.
     */
    public static InputSender forBlock(int x, int y, int z) {
        return new InputSender(x, y, z, new ToServer(), Minecraft.getMinecraft()::func_152345_ab);
    }

    /**
     * Sends one pointer event for the display {@code displayId} at this sender's block, at surface pixel
     * {@code (px, py)}, unfloored, with its data byte (S11 plan U5.3). Dropped, and the first drop logged, when the
     * call comes from another thread than the client main thread (S12 plan P12.6), or for an id the codec refuses
     * (empty, or longer than 64 UTF-8 bytes), which only a server's description can give, so that it never throws in
     * the GUI [proposal; S12 build, U4]. A null id, which no description carries yet, is never encoded.
     *
     * @throws NullPointerException for a null kind
     */
    public void send(PointerEvent.Kind kind, String displayId, float px, float py, byte data) {
        Objects.requireNonNull(kind, "kind");
        if (!onMainThread.getAsBoolean()) {
            String thread = Thread.currentThread()
                .getName();
            drop("a call from thread " + thread + ", not the client main thread (13:139)");
            return;
        }
        if (displayId == null) {
            return;
        }
        byte[] bytes;
        try {
            bytes = PointerPacket.encode(new PointerEvent(kind, x, y, z, displayId, px, py, data));
        } catch (IllegalArgumentException e) {
            drop("display id \"" + displayId + "\": " + e.getMessage());
            return;
        }
        out.accept(bytes);
    }

    /** The calls dropped so far: off the main thread, or for an id the codec refuses. */
    int dropped() {
        return dropped.get();
    }

    /** For example "InputSender[1 64 -2, dropped 0]". */
    @Override
    public String toString() {
        return "InputSender[" + x + " " + y + " " + z + ", dropped " + dropped.get() + "]";
    }

    private void drop(String why) {
        if (dropped.getAndIncrement() == 0) {
            OpenGPU.LOG.warn(
                "OpenGPU input: dropped a pointer event for the display at {} {} {}: {}; later drops are not logged",
                x,
                y,
                z,
                why);
        }
    }

    /** The channel's send to the server; loaded only by {@link #forBlock}. */
    private static final class ToServer implements Consumer<byte[]> {

        ToServer() {}

        @Override
        public void accept(byte[] bytes) {
            Channel.get()
                .sendToServer(new FMLProxyPacket(Unpooled.wrappedBuffer(bytes), Channel.NAME));
        }
    }
}
