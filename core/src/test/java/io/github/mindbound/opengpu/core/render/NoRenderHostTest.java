package io.github.mindbound.opengpu.core.render;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;

/**
 * The host of a dedicated server (28:204): never available and never stalled, and every SPI call a no-op for any
 * argument, nulls included, so {@code present} returns {@code "no renderer"} while submits and texture calls validate
 * as on a GL host (24:228).
 */
class NoRenderHostTest {

    @Test
    void itIsNeverAvailableAndNeverStalled() {
        Assertions.assertFalse(NoRenderHost.INSTANCE.available());
        Assertions.assertFalse(NoRenderHost.INSTANCE.stalled());
        // the same after any calls
        NoRenderHost.INSTANCE.open(new DeviceKey(1, 1), SurfaceDesc.forTier(2), (s, d, e, f, p) -> {});
        Assertions.assertFalse(NoRenderHost.INSTANCE.available());
        Assertions.assertFalse(NoRenderHost.INSTANCE.stalled());
    }

    @Test
    void everyCallIsANoOpForAnyArgument() {
        RenderHost host = NoRenderHost.INSTANCE;
        final int[] readbacks = { 0 };
        ReadbackSink sink = (s, d, e, f, p) -> readbacks[0]++;
        DeviceKey key = new DeviceKey(1, 1);
        host.open(key, SurfaceDesc.forTier(3), sink);
        // a second open of the same key, an offer of every kind, a release, and the root's calls
        host.open(key, SurfaceDesc.forTier(3), sink);
        host.offer(key, new FrameRecord(1, 1, new byte[0][], FrameTotals.EMPTY));
        host.offer(key, new TexCreateRecord(1, 0x08000000, 1, 1, 2));
        host.offer(key, new TexUpdateRecord(1, 0x08000000, 0, 0, 1, 1, new byte[2]));
        host.offer(key, new TexFreeRecord(1, 0x08000000));
        host.offer(key, new ResetRecord(2, new byte[1]));
        host.offer(new DeviceKey(9, 9), new TexFreeRecord(1, 0x08000000));
        host.release(key);
        host.release(key);
        host.offer(key, new TexFreeRecord(1, 0x08000000));
        host.bindDisplay("display", key);
        host.bindDisplay("display", null);
        host.releaseSession(1);
        host.releaseSession(Long.MIN_VALUE);
        // nulls, which a FakeRenderHost rejects
        host.open(null, null, null);
        host.offer(null, null);
        host.release(null);
        host.bindDisplay(null, null);
        Assertions.assertEquals(0, readbacks[0]);
        Assertions.assertEquals("NoRenderHost", host.toString());
    }

    @Test
    void thereIsOneInstance() {
        Assertions.assertSame(NoRenderHost.INSTANCE, NoRenderHost.INSTANCE);
        Assertions.assertTrue(Modifier.isFinal(NoRenderHost.class.getModifiers()));
        Constructor<?>[] constructors = NoRenderHost.class.getDeclaredConstructors();
        Assertions.assertEquals(1, constructors.length);
        Assertions.assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }
}
