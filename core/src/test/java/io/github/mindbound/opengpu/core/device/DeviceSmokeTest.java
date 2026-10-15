package io.github.mindbound.opengpu.core.device;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * 28 §9.4's worked frame on T2 through {@link Device} on a zeros {@link FakeRenderHost}, from hand-built bytes and no
 * fixture: the four callbacks' results, the offered sequence {@code TEX_CREATE}, {@code TEX_UPDATE}, {@code FRAME(1)}
 * (28:840), a tail of {@code FRAME(1)} alone, and K = 1 after one step.
 */
class DeviceSmokeTest {

    /** 28:833-838, verbatim. */
    private static final String T2_FRAME = "4F 47 50 55 01 00 00 00  01 00 00 00 00  "
        + "30 0A 00 14 00 64 00 32 00 C4 00 00 00  31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01  "
        + "33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69  FF";

    private static final CallBudget FREE = new CallBudget() {

        @Override
        public void consume(double cost) {}

        @Override
        public void pause(double seconds) {}
    };

    @Test
    void theWorkedFrameRunsThroughTheDevice() {
        DeviceKey key = new DeviceKey(1, 1);
        FakeRenderHost host = new FakeRenderHost();
        Device device = Device.create(key, DeviceCaps.forTier(2), host, Runnable::run, FrameListener.NONE, () -> 0L);
        byte[] segment = hex(T2_FRAME);
        Assertions.assertEquals(64, segment.length);
        byte[] sprite = new byte[256];
        for (int i = 0; i < sprite.length; i++) {
            sprite[i] = (byte) (i % 5);
        }

        Assertions.assertEquals(CallResult.handle(0x08000000), device.createTexture(FREE, 16, 16, null));
        Assertions.assertEquals(CallResult.TRUE, device.updateTexture(FREE, 0x08000000, 0, 0, 16, 16, sprite));
        Assertions.assertEquals(CallResult.TRUE, device.submit(FREE, segment));
        Assertions.assertEquals(CallResult.frameId(1), device.present(FREE));

        List<Record> offered = host.offered(key);
        Assertions.assertArrayEquals(new int[] { Record.TEX_CREATE, Record.TEX_UPDATE, Record.FRAME }, kinds(offered));
        Assertions.assertSame(segment, ((FrameRecord) offered.get(2)).segment(0));

        DeviceSnapshot before = device.snapshot();
        Assertions.assertArrayEquals(new int[] { Record.FRAME }, kinds(before.tail));
        Assertions.assertEquals(0, before.confirmedFrameId);
        Assertions.assertEquals(1, before.masters.size());

        Assertions.assertEquals(3, host.step());
        DeviceSnapshot after = device.snapshot();
        Assertions.assertEquals(1, after.confirmedFrameId);
        Assertions.assertTrue(after.tail.isEmpty());
        CallResult pixel = device.readPixels(FREE, 0, 0, 1, 1);
        Assertions.assertEquals(1L, pixel.frameId());
    }

    private static int[] kinds(List<Record> records) {
        int[] kinds = new int[records.size()];
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = records.get(i)
                .kind();
        }
        return kinds;
    }

    private static byte[] hex(String text) {
        String digits = text.replace(" ", "");
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
