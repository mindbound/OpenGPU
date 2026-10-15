package io.github.mindbound.opengpu.core.cmd.bench;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.LiveView;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.cmd.bench.ValidatorWorkloads.Workload;

/**
 * 28 §3.5: validation allocates nothing per record. Measured deterministically with HotSpot's per-thread allocation
 * counter ({@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}, through reflection, and skipped where the
 * JVM lacks it): after 500 warm-up runs, the least that one {@code validate} allocates over 20 runs, less the counter
 * call's own allocation, is at most 1 024 bytes. That holds on three loads, so that every opcode is measured: the
 * {@code records} load (2 047 {@code FILL_RECT}s, 2 047 {@code BLIT}s and two {@code TEXT}s), {@code clears} (4 096
 * {@code CLEAR}s) and {@code short-text} (2 976 {@code TEXT}s with 1- to 4-byte codepoints). Each opcode has 2 047
 * records or more on one of them, and the smallest object is 16 bytes, so one object per record of any opcode would
 * be at least 32 000 bytes there. Only the result objects remain.
 */
class ValidatorAllocationTest {

    /** Keeps the results reachable, so that no run is optimized away. */
    static volatile Validation sink;

    @Test
    void theRecordsLoadAllocatesNothingPerRecord() throws Exception {
        assertNothingPerRecord(ValidatorWorkloads.records(65536, false));
    }

    @Test
    void clearAllocatesNothingPerRecord() throws Exception {
        assertNothingPerRecord(ValidatorWorkloads.clears(4096));
    }

    @Test
    void textAllocatesNothingPerRecord() throws Exception {
        assertNothingPerRecord(ValidatorWorkloads.shortText());
    }

    private static void assertNothingPerRecord(Workload w) throws Exception {
        Object bean = ManagementFactory.getThreadMXBean();
        Method allocated;
        try {
            Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
            Assumptions.assumeTrue(type.isInstance(bean), "no com.sun.management.ThreadMXBean");
            allocated = type.getMethod("getThreadAllocatedBytes", long.class);
            Method supported = type.getMethod("isThreadAllocatedMemorySupported");
            Assumptions.assumeTrue((Boolean) supported.invoke(bean), "thread allocation is not measured");
            Method enabled = type.getMethod("isThreadAllocatedMemoryEnabled");
            if (!(Boolean) enabled.invoke(bean)) {
                type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class)
                    .invoke(bean, true);
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Assumptions.assumeTrue(false, "no thread allocation counter: " + e);
            return;
        }
        long thread = Thread.currentThread()
            .getId();
        byte[] s = w.segment;
        LiveView view = new ValidatorWorkloads.OneTextureView();
        for (int i = 0; i < 500; i++) {
            sink = Validator.validate(s, view);
        }
        Assertions.assertTrue(sink.isAccepted(), sink::toString);
        Assertions.assertEquals(w.expected, sink.totals(), w.name);
        int records = sink.totals().records;
        long overhead = Long.MAX_VALUE;
        long least = Long.MAX_VALUE;
        for (int run = 0; run < 20; run++) {
            long a = (Long) allocated.invoke(bean, thread);
            long b = (Long) allocated.invoke(bean, thread);
            overhead = Math.min(overhead, b - a);
            long before = (Long) allocated.invoke(bean, thread);
            sink = Validator.validate(s, view);
            long after = (Long) allocated.invoke(bean, thread);
            least = Math.min(least, after - before);
        }
        long perValidation = least - overhead;
        System.out.println(
            "ValidatorAllocationTest: one validate of " + w.name
                + ", "
                + records
                + " records, allocates "
                + perValidation
                + " B (the counter's own "
                + overhead
                + " B subtracted)");
        Assertions.assertTrue(
            perValidation <= 1024,
            "one validate of " + w.name + ", " + records + " records, allocated " + perValidation + " B, over 1 024");
    }
}
