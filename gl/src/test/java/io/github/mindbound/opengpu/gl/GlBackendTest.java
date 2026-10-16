package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;

/**
 * What {@link GlBackend} does without a context: the production defaults of its constructor chain, the staging
 * buffer, allocated at construction at the largest texture's size so that a call under a device's lock never
 * allocates (S8 review HO-4; amendment D's lock-hold bound), and which renderers' readback copies wait for the
 * surfaces drawn before them (llvmpipe's; {@code finishBeforeCopy}).
 */
class GlBackendTest {

    private static GlBackend backend() {
        return new GlBackend(SyntheticGlyphs.TABLE, QuadMode.EXPANDED, new GlLedger(), UploadProbe.NONE);
    }

    @Test
    void theShortConstructorsTakeProductionsPolicies() {
        GlBackend four = backend();
        Assertions.assertEquals(QueryPolicy.DRIVER, four.queryPolicy());
        Assertions.assertEquals(ReadbackFinish.AUTO, four.readbackFinishPolicy());
        GlBackend five = new GlBackend(
            SyntheticGlyphs.TABLE,
            QuadMode.INSTANCED,
            new GlLedger(),
            UploadProbe.NONE,
            QueryPolicy.NEVER_READY);
        Assertions.assertEquals(ReadbackFinish.AUTO, five.readbackFinishPolicy());
        GlBackend six = new GlBackend(
            SyntheticGlyphs.TABLE,
            QuadMode.INSTANCED,
            new GlLedger(),
            UploadProbe.NONE,
            QueryPolicy.DRIVER,
            ReadbackFinish.NEVER);
        Assertions.assertEquals(ReadbackFinish.NEVER, six.readbackFinishPolicy());
        // Resolved by the first pass, from GL_VENDOR; nothing before it
        Assertions.assertFalse(four.readbackFinish());
        Assertions.assertNull(four.vendor());
        Assertions.assertThrows(
            NullPointerException.class,
            () -> new GlBackend(
                SyntheticGlyphs.TABLE,
                QuadMode.EXPANDED,
                new GlLedger(),
                UploadProbe.NONE,
                QueryPolicy.DRIVER,
                null));
    }

    @Test
    void stagingIsAllocatedAtConstructionAndOnlyReslicedUpToTheLargestTexture() {
        // 28 §6.2: textures up to 1 024², rgb565's 2 bytes the widest format
        Assertions.assertEquals(1024, DeviceGl.MAX_TEXTURE_SIDE);
        Assertions.assertEquals(2 * 1024 * 1024, GlBackend.STAGING_BYTES);
        GlBackend backend = backend();
        ByteBuffer first = backend.staging(0);
        Assertions.assertTrue(first.isDirect());
        Assertions.assertEquals(ByteOrder.nativeOrder(), first.order());
        Assertions.assertEquals(GlBackend.STAGING_BYTES, first.capacity(), "not allocated at its full size at once");
        // Every size up to it, in an order that grows: the same buffer each time, resliced
        for (int bytes = 0; bytes <= GlBackend.STAGING_BYTES; bytes++) {
            ByteBuffer b = backend.staging(bytes);
            if (b != first || b.position() != 0 || b.limit() != bytes) {
                Assertions.fail(
                    "staging(" + bytes
                        + ") gave "
                        + (b == first ? "the buffer" : "a new buffer")
                        + " at position "
                        + b.position()
                        + ", limit "
                        + b.limit()
                        + ", capacity "
                        + b.capacity());
            }
        }
        // And down again
        Assertions.assertSame(first, backend.staging(1));
        Assertions.assertEquals(1, first.limit());
        // Only a larger size, a RESET or readNow of an untiered test surface staged outside every lock, grows it
        ByteBuffer larger = backend.staging(GlBackend.STAGING_BYTES + 1);
        Assertions.assertNotSame(first, larger);
        Assertions.assertEquals(GlBackend.STAGING_BYTES + 1, larger.limit());
        Assertions.assertSame(larger, backend.staging(GlBackend.STAGING_BYTES));
    }

    @Test
    void aNegativeStagingSizeIsRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> backend().staging(-1));
    }

    @Test
    void onlyARendererOnLlvmpipeWaitsBeforeACopy() {
        // llvmpipe's own string, Mesa's before 2017, and zink on lavapipe, which runs on llvmpipe's rasterizer
        for (String renderer : new String[] { "llvmpipe (LLVM 21.1.8, 256 bits)", "llvmpipe (LLVM 20.1.2, 128 bits)",
            "Gallium 0.4 on llvmpipe (LLVM 3.8, 256 bits)", "zink Vulkan 1.3(llvmpipe (LLVM 21.1.8, 256 bits))" }) {
            Assertions.assertTrue(GlBackend.finishesBeforeCopy(renderer), renderer);
        }
        // Hardware drivers, Mesa's others (radeonsi names LLVM, not llvmpipe), and none
        for (String renderer : new String[] { "NVIDIA GeForce RTX 5060/PCIe/SSE2", "Intel(R) Graphics",
            "D3D12 (NVIDIA GeForce RTX 5060)", "D3D12 (Intel(R) Graphics)", "Mesa Intel(R) Graphics (ADL GT2)",
            "AMD Radeon RX 7600 (radeonsi, navi33, LLVM 19.1.1, DRM 3.59, 6.8.0)", "softpipe",
            "zink Vulkan 1.3(NVIDIA GeForce RTX 5060 (NVIDIA_PROPRIETARY))", "", null }) {
            Assertions.assertFalse(GlBackend.finishesBeforeCopy(renderer), String.valueOf(renderer));
        }
        // Resolved by the first pass, from GL_RENDERER; nothing before it
        GlBackend backend = backend();
        Assertions.assertFalse(backend.copyFinish());
        Assertions.assertNull(backend.renderer());
        Assertions.assertEquals(0, backend.copyFinishes());
    }
}
