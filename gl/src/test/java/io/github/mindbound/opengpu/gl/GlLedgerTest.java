package io.github.mindbound.opengpu.gl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link GlLedger} (G10, 26:114-117; S8 plan §3.11): rows keep count and bytes per (session, device, kind, label), go
 * at count 0, refuse a double delete and a size that does not match the add, sum by kind and by device, keep host rows
 * apart from sessions (H34) and list themselves sorted; two threads adding and removing leave it empty.
 */
class GlLedgerTest {

    private final GlLedger ledger = new GlLedger();

    @Test
    void addAndRemoveKeepCountAndBytes() {
        ledger.add(1, 2, GlLedger.TEXTURE, "0x08000001", 256);
        ledger.add(1, 2, GlLedger.TEXTURE, "0x08000001", 256);
        Assertions.assertEquals(512, ledger.bytes(1, 2, GlLedger.TEXTURE));
        Assertions.assertEquals(2, ledger.count(1, 2, GlLedger.TEXTURE));
        ledger.remove(1, 2, GlLedger.TEXTURE, "0x08000001", 256);
        Assertions.assertEquals(256, ledger.bytes(1, 2, GlLedger.TEXTURE));
        Assertions.assertEquals(1, ledger.count(1, 2, GlLedger.TEXTURE));
        ledger.remove(1, 2, GlLedger.TEXTURE, "0x08000001", 256);
        Assertions.assertTrue(ledger.isEmpty(), ledger.rows()::toString);
        Assertions.assertEquals(0, ledger.count(1, 2, GlLedger.TEXTURE));
    }

    @Test
    void aZeroByteRowStillCounts() {
        ledger.add(1, 2, GlLedger.FBO, "surface", 0);
        Assertions.assertEquals(1, ledger.count(1, 2, GlLedger.FBO));
        Assertions.assertFalse(ledger.isEmpty());
        ledger.remove(1, 2, GlLedger.FBO, "surface", 0);
        Assertions.assertTrue(ledger.isEmpty());
    }

    @Test
    void aDoubleRemoveThrows() {
        ledger.add(1, 2, GlLedger.TEXTURE, "0x08000001", 16);
        ledger.remove(1, 2, GlLedger.TEXTURE, "0x08000001", 16);
        IllegalStateException e = Assertions
            .assertThrows(IllegalStateException.class, () -> ledger.remove(1, 2, GlLedger.TEXTURE, "0x08000001", 16));
        Assertions.assertTrue(
            e.getMessage()
                .contains("1:2 texture 0x08000001"),
            e.getMessage());
        Assertions
            .assertThrows(IllegalStateException.class, () -> ledger.remove(1, 2, GlLedger.TEXTURE, "0x08000002", 0));
    }

    @Test
    void aSizeThatDoesNotMatchTheAddThrowsAndChangesNothing() {
        ledger.add(1, 2, GlLedger.PBO, "pbo0", 1000);
        // More than the row holds
        Assertions.assertThrows(IllegalStateException.class, () -> ledger.remove(1, 2, GlLedger.PBO, "pbo0", 1001));
        // The last object, but bytes would remain
        Assertions.assertThrows(IllegalStateException.class, () -> ledger.remove(1, 2, GlLedger.PBO, "pbo0", 999));
        Assertions.assertEquals(1000, ledger.bytes(1, 2, GlLedger.PBO));
        Assertions.assertEquals(1, ledger.count(1, 2, GlLedger.PBO));
    }

    @Test
    void badArgumentsThrow() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ledger.add(1, 2, GlLedger.VBO, "quads", -1));
        Assertions.assertThrows(NullPointerException.class, () -> ledger.add(1, 2, null, "quads", 1));
        Assertions.assertThrows(NullPointerException.class, () -> ledger.add(1, 2, GlLedger.VBO, null, 1));
        ledger.add(1, 2, GlLedger.VBO, "quads", 1);
        Assertions.assertThrows(IllegalArgumentException.class, () -> ledger.remove(1, 2, GlLedger.VBO, "quads", -1));
        Assertions.assertEquals(
            1,
            ledger.rows()
                .size());
    }

    @Test
    void totalsByKindSumOverLabels() {
        ledger.add(1, 2, GlLedger.TEXTURE, "0x08000001", 100);
        ledger.add(1, 2, GlLedger.TEXTURE, "0x08000002", 20);
        ledger.add(1, 2, GlLedger.PBO, "pbo0", 3);
        ledger.add(1, 3, GlLedger.TEXTURE, "0x08000001", 4000);
        Assertions.assertEquals(120, ledger.bytes(1, 2, GlLedger.TEXTURE));
        Assertions.assertEquals(2, ledger.count(1, 2, GlLedger.TEXTURE));
        Assertions.assertEquals(4000, ledger.bytes(1, 3, GlLedger.TEXTURE));
        Assertions.assertEquals(0, ledger.bytes(2, 2, GlLedger.TEXTURE));
        Assertions.assertEquals(0, ledger.count(1, 2, GlLedger.QUERY));
    }

    @Test
    void deviceBytesSumOverKinds() {
        // 20 §1's formula at T2 (S8 plan §3.11): surface + present + 3 PBOs = 512 000 B
        int surface = 320 * 200;
        ledger.add(5, 1, GlLedger.SURFACE, "surface", surface);
        ledger.add(5, 1, GlLedger.PRESENT, "present", 4L * surface);
        for (int i = 0; i < 3; i++) {
            ledger.add(5, 1, GlLedger.PBO, "pbo" + i, surface);
            ledger.add(5, 1, GlLedger.QUERY, "readback" + i, 0);
        }
        ledger.add(5, 1, GlLedger.FBO, "surface", 0);
        ledger.add(5, 1, GlLedger.FBO, "present", 0);
        ledger.add(5, 2, GlLedger.SURFACE, "surface", 7);
        Assertions.assertEquals(512_000, ledger.deviceBytes(5, 1));
        Assertions.assertEquals(7, ledger.deviceBytes(5, 2));
        Assertions.assertEquals(
            10,
            ledger.rows(5, 1)
                .size());
    }

    @Test
    void isEmptyOfASessionIgnoresHostRows() {
        ledger.add(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.PROGRAM, "fill", 0);
        ledger.add(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.GLYPH_ATLAS, "font0", 26_520);
        ledger.add(9, 1, GlLedger.SURFACE, "surface", 10);
        Assertions.assertFalse(ledger.isEmpty(9));
        Assertions.assertTrue(ledger.isEmpty(8));
        ledger.remove(9, 1, GlLedger.SURFACE, "surface", 10);
        Assertions.assertTrue(ledger.isEmpty(9));
        Assertions.assertFalse(ledger.isEmpty());
        Assertions.assertFalse(ledger.isEmpty(GlLedger.HOST_SESSION));
    }

    @Test
    void rowsAreSortedBySessionDeviceKindAndLabel() {
        ledger.add(2, 1, GlLedger.TEXTURE, "0x08000002", 1);
        ledger.add(1, 3, GlLedger.FBO, "surface", 0);
        ledger.add(1, 2, GlLedger.TEXTURE, "0x08000001", 1);
        ledger.add(1, 2, GlLedger.PBO, "pbo1", 1);
        ledger.add(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.VAO, "expanded", 0);
        ledger.add(1, 2, GlLedger.PBO, "pbo0", 1);
        List<String> keys = new ArrayList<>();
        for (GlLedger.Row row : ledger.rows()) {
            keys.add(row.key.toString());
        }
        Assertions.assertEquals(
            Arrays.asList(
                "host vao expanded",
                "1:2 pbo pbo0",
                "1:2 pbo pbo1",
                "1:2 texture 0x08000001",
                "1:3 fbo surface",
                "2:1 texture 0x08000002"),
            keys);
        // A copy: changes after the call do not show in it
        List<GlLedger.Row> rows = ledger.rows();
        ledger.add(3, 3, GlLedger.FBO, "present", 0);
        Assertions.assertEquals(6, rows.size());
        Assertions.assertThrows(UnsupportedOperationException.class, () -> rows.remove(0));
    }

    @Test
    void keysCompareAndHashOnAllFourFields() {
        GlLedger.Key a = new GlLedger.Key(1, 2, GlLedger.TEXTURE, "x");
        Assertions.assertEquals(a, new GlLedger.Key(1, 2, GlLedger.TEXTURE, "x"));
        Assertions.assertEquals(a.hashCode(), new GlLedger.Key(1, 2, GlLedger.TEXTURE, "x").hashCode());
        Assertions.assertNotEquals(a, new GlLedger.Key(2, 2, GlLedger.TEXTURE, "x"));
        Assertions.assertNotEquals(a, new GlLedger.Key(1, 3, GlLedger.TEXTURE, "x"));
        Assertions.assertNotEquals(a, new GlLedger.Key(1, 2, GlLedger.PBO, "x"));
        Assertions.assertNotEquals(a, new GlLedger.Key(1, 2, GlLedger.TEXTURE, "y"));
        Assertions.assertEquals(0, a.compareTo(new GlLedger.Key(1, 2, GlLedger.TEXTURE, "x")));
        Assertions.assertTrue(a.compareTo(new GlLedger.Key(1, 2, GlLedger.TEXTURE, "y")) < 0);
        Assertions.assertTrue(a.compareTo(new GlLedger.Key(0, 9, GlLedger.TEXTURE, "a")) > 0);
    }

    @Test
    void textureLabelsAreTheFullHandleInHex() {
        Assertions.assertEquals("0x08000000", GlLedger.textureLabel(0x08000000));
        Assertions.assertEquals("0x0FFFFFFF", GlLedger.textureLabel(0x0FFFFFFF));
        Assertions.assertEquals("0x00000001", GlLedger.textureLabel(1));
        Assertions.assertEquals(
            "1:2 texture 0x08000000 = 256 B in 1",
            row(1, 2, GlLedger.TEXTURE, GlLedger.textureLabel(0x08000000), 256).toString());
    }

    /**
     * Two threads, each adding and removing 10 000 rows of its own and of one shared key, leave the ledger empty, and
     * neither throws (plan §0's thread rules: bounded loops, latches released in finally, a timeout on every wait).
     */
    @Test
    @Timeout(30)
    void twoThreadsLeaveItEmpty() throws InterruptedException {
        final int rows = 10_000;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        for (int t = 0; t < threads.length; t++) {
            final int device = t;
            threads[t] = new Thread(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("the start latch was not released within 10 s");
                    }
                    for (int i = 0; i < rows; i++) {
                        ledger.add(1, device, GlLedger.TEXTURE, GlLedger.textureLabel(i), i);
                        ledger.add(1, 99, GlLedger.QUERY, "shared", 2);
                        ledger.bytes(1, device, GlLedger.TEXTURE);
                        ledger.rows();
                    }
                    for (int i = 0; i < rows; i++) {
                        ledger.remove(1, device, GlLedger.TEXTURE, GlLedger.textureLabel(i), i);
                        ledger.remove(1, 99, GlLedger.QUERY, "shared", 2);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "ledger-" + t);
            threads[t].start();
        }
        try {
            start.countDown();
        } finally {
            for (Thread thread : threads) {
                thread.join(20_000);
                Assertions.assertFalse(thread.isAlive(), thread.getName() + " did not finish within 20 s");
            }
        }
        if (failure.get() != null) {
            throw new AssertionError("a ledger thread failed", failure.get());
        }
        Assertions.assertTrue(
            ledger.isEmpty(),
            () -> ledger.rows()
                .size() + " rows left");
    }

    private static GlLedger.Row row(long session, int device, String kind, String label, long bytes) {
        GlLedger ledger = new GlLedger();
        ledger.add(session, device, kind, label, bytes);
        return ledger.rows()
            .get(0);
    }
}
