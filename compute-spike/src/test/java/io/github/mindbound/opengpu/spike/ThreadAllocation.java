package io.github.mindbound.opengpu.spike;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Assertions;

/**
 * The bytes this thread allocates, from HotSpot's per-thread counter
 * ({@code com.sun.management.ThreadMXBean#getThreadAllocatedBytes}, through reflection), as
 * {@code :core}'s ValidatorAllocationTest reads it. Every JVM the legs run on has it, so its absence fails the test
 * rather than skipping it.
 */
final class ThreadAllocation {

    private final Object bean = ManagementFactory.getThreadMXBean();
    private final Method allocated;
    private final long thread = Thread.currentThread()
        .getId();

    ThreadAllocation() throws ReflectiveOperationException {
        Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
        Assertions.assertTrue(type.isInstance(bean), "no com.sun.management.ThreadMXBean");
        allocated = type.getMethod("getThreadAllocatedBytes", long.class);
        Assertions.assertTrue(
            (Boolean) type.getMethod("isThreadAllocatedMemorySupported")
                .invoke(bean),
            "thread allocation is not measured");
        if (!(Boolean) type.getMethod("isThreadAllocatedMemoryEnabled")
            .invoke(bean)) {
            type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class)
                .invoke(bean, true);
        }
    }

    /** What {@code work} allocates on this thread, less the counter call's own allocation. */
    long during(Runnable work) throws ReflectiveOperationException {
        long overhead = Long.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long a = (Long) allocated.invoke(bean, thread);
            long b = (Long) allocated.invoke(bean, thread);
            overhead = Math.min(overhead, b - a);
        }
        long before = (Long) allocated.invoke(bean, thread);
        work.run();
        long after = (Long) allocated.invoke(bean, thread);
        return after - before - overhead;
    }
}
