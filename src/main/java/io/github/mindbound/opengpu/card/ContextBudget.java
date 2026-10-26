package io.github.mindbound.opengpu.card;

import io.github.mindbound.opengpu.core.device.CallBudget;
import li.cil.oc.api.machine.Context;

/**
 * :core's {@link CallBudget} on OC's {@link Context}, built per call (28:1077; S11 plan P9): {@code consume} is
 * {@code consumeCallBudget} and {@code pause} is {@code Context.pause}, nothing more, as the harness's OcelotBudget
 * does on ocelot's. OC's {@code consumeCallBudget} declares no exception in Java yet throws the checked
 * {@code LimitReachedException} from Scala (Machine.scala:286-294), which passes out of the callback untouched, so OC
 * retries the call synchronized on the next tick (28:710-713). Device never calls it under its monitor
 * (CallBudget.java), and {@code pause} only from inside a callback, as {@code Context.pause} requires
 * (Context.java:82-117).
 */
final class ContextBudget implements CallBudget {

    private final Context context;

    ContextBudget(Context context) {
        this.context = context;
    }

    @Override
    public void consume(double cost) {
        context.consumeCallBudget(cost);
    }

    @Override
    public void pause(double seconds) {
        context.pause(seconds);
    }
}
