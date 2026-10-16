package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * What the scenarios that drive a {@code Device} on {@link GlTestHost} share (S8 plan §4.3 rows 12-17): pumping until
 * the device's confirmed frame K reaches a frame (28:99, :112), until the host and the device are both quiet, G10's
 * equation (28:1083; S8 plan §3.11), and seeded byte patterns. No GL call of its own.
 */
final class DeviceSteps {

    /** Pumps any one wait may take: a frame, its readback and the forced fetch fit many times over (24:197). */
    static final int MAX_PUMPS = GlTestHost.MAX_PUMPS;

    private DeviceSteps() {}

    /**
     * Pumps until K is at least frameId, at most {@link #MAX_PUMPS} times.
     *
     * @return the pumps taken
     * @throws AssertionError naming the cap when K does not get there
     */
    static int pumpUntilConfirmed(GlTestHost host, final DeviceRig rig, final long frameId, String what) {
        return host.pumpUntil(
            () -> rig.device.snapshot().confirmedFrameId >= frameId,
            MAX_PUMPS,
            what + ": K reaching frame " + frameId);
    }

    /**
     * Pumps until the host owes the key nothing and the device's server tail is empty (S8 plan §3.11's condition):
     * every presented frame read back and folded.
     *
     * @return the device's snapshot then
     * @throws AssertionError naming the cap when it does not get there
     */
    static DeviceSnapshot settle(final GlTestHost host, final DeviceRig rig, String what) {
        host.pumpUntil(
            () -> host.idle(rig.key) && rig.device.snapshot().tail.isEmpty(),
            MAX_PUMPS,
            what + ": the host idle and the server tail empty");
        return rig.device.snapshot();
    }

    /**
     * G10 against {@code :core} (28:1083; 28:568, :602; S8 plan §3.11): with the host idle for the key and the server
     * tail empty, the ledger's texture bytes of the device equal {@code vramUsed} plus the open frame's deferred bytes,
     * and its texture rows the live textures plus the deferred frees; the host's own count agrees.
     *
     * @return whether it held
     */
    static boolean ledgerEquation(ScenarioRun run, GlTestHost host, DeviceKey key, DeviceSnapshot s, String where) {
        GlLedger ledger = host.ledger();
        long bytes = ledger.bytes(key.session, key.deviceId, GlLedger.TEXTURE);
        int count = ledger.count(key.session, key.deviceId, GlLedger.TEXTURE);
        boolean ok = run.check(
            bytes == s.vramUsed + s.deferredBytes && count == s.live.size() + s.deferredCount,
            String.format(
                Locale.ROOT,
                "%s: G10: the ledger holds %d texture(s) of %d B, :core %d live of %d B plus %d deferred of %d B"
                    + " (28:1083)",
                where,
                count,
                bytes,
                s.live.size(),
                s.vramUsed,
                s.deferredCount,
                s.deferredBytes));
        int gpu = host.stats(key).gpuTextures;
        long gpuBytes = host.stats(key).gpuTextureBytes;
        return ok & run.check(
            gpu == count && gpuBytes == bytes,
            where + ": the host counts "
                + gpu
                + " GPU textures of "
                + gpuBytes
                + " B, the ledger "
                + count
                + " of "
                + bytes
                + " B");
    }

    /**
     * 20 §1's formula for a device's own objects (26:117; S8 plan §3.11): surface W·H·bpp, present 4·W·H and three PBOs
     * of W·H·bpp (512 000 B at T2, 3 072 000 B at T3), two FBOs and three queries; plus the textures.
     */
    static void formula(ScenarioRun run, GlTestHost host, DeviceKey key, SurfaceDesc surface, long textures,
        String where) {
        HostLifecycleScenario.deviceRows(run, host.ledger(), key, surface, textures, where);
    }

    /** n bytes of a seeded pattern without a period short enough to hide a misplaced row: texels of either format. */
    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        int state = seed * 0x9E3779B9 + 0x6A09E667;
        for (int i = 0; i < n; i++) {
            state = state * 1664525 + 1013904223;
            b[i] = (byte) (state >>> 24);
        }
        return b;
    }
}
