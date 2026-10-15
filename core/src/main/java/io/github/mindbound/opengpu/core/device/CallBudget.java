package io.github.mindbound.opengpu.core.device;

/**
 * OC's {@code Context} as a device's callbacks need it (28 §8.2; H4): the root's {@code @Callback} layer and S10's
 * harness forward these to their {@code Context}. The device runs the order (checks, charge, effect, 28 §2.1), the
 * amounts and {@code bitblt}'s two-phase state; it never calls these under its monitor.
 */
public interface CallBudget {

    /**
     * {@code Context.consumeCallBudget(cost)} ({@code OCT/java/li/cil/oc/api/machine/Context.java:156}): may
     * throw (OC's {@code LimitReachedException}, unchecked through Scala), and does nothing in a synchronized call.
     */
    void consume(double cost);

    /** {@code Context.pause(seconds)} ({@code Context.java:117}). */
    void pause(double seconds);
}
