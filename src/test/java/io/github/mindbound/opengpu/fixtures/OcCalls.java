package io.github.mindbound.opengpu.fixtures;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.LimitReachedException;

/**
 * OC's own callback scan, dispatch and argument reader, from OC's dev jar on the root's test runtime classpath, by
 * reflection, since only OC's API jar is on the test compile classpath (S11 plan P15.1; spike §4, rows 4 and 5):
 * <ul>
 * <li>{@code li.cil.oc.server.machine.Callbacks$}'s {@code apply(environment)}, the callbacks OC's machine finds:
 * public methods taking {@code (Context, Arguments)} and returning {@code Object[]}, on the class or a superclass,
 * named by {@code value} or the method (Callbacks.scala:87-117);</li>
 * <li>a callback's {@code apply(instance, context, args)}, which calls the method through OC's generated wrapper
 * (CallbackWrapper.scala), as {@code Component.invoke} does, without its {@code Registry.convert} of the results
 * (Component.scala:104-118);</li>
 * <li>{@code li.cil.oc.server.machine.ArgumentsImpl} over a Scala {@code Seq}, OC's real {@code checkInteger},
 * {@code checkByteArray} and {@code optString}, with OC's argument errors (ArgumentsImpl.scala).</li>
 * </ul>
 * These are OC's internals, not its API; a later OC may move them, and only tests use them (P22). Neither reaches
 * {@code SideTracker} or FML.
 */
public final class OcCalls {

    private OcCalls() {}

    /** The callbacks OC finds on {@code environment}, by Lua name, with their annotations. */
    public static Map<String, Callback> callbacks(Object environment) throws Exception {
        Map<String, Callback> out = new TreeMap<>();
        for (Map.Entry<String, Object> e : scan(environment).entrySet()) {
            Object callback = e.getValue();
            out.put(
                e.getKey(),
                (Callback) callback.getClass()
                    .getMethod("annotation")
                    .invoke(callback));
        }
        return out;
    }

    /** OC's ArgumentsImpl over the values, as OC hands them to a callback. */
    public static Arguments args(Object... values) throws Exception {
        List<Object> list = new ArrayList<>(Arrays.asList(values));
        Object seq = Class.forName("scala.collection.JavaConversions")
            .getMethod("asScalaBuffer", List.class)
            .invoke(null, list);
        return (Arguments) Class.forName("li.cil.oc.server.machine.ArgumentsImpl")
            .getConstructor(Class.forName("scala.collection.Seq"))
            .newInstance(seq);
    }

    /**
     * Calls the callback OC finds under {@code name} on {@code environment}, through OC's dispatch, with OC's
     * arguments over the values; whatever the callback throws is thrown as it is.
     *
     * @throws IllegalStateException when OC finds no such callback
     */
    public static Object[] call(Object environment, String name, Context context, Object... values) throws Exception {
        Object callback = scan(environment).get(name);
        if (callback == null) {
            throw new IllegalStateException("OC finds no callback " + name + " on " + environment);
        }
        Method apply = callback.getClass()
            .getMethod("apply", Object.class, Context.class, Arguments.class);
        try {
            return (Object[]) apply.invoke(callback, environment, context, args(values));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw (Error) cause;
        }
    }

    /**
     * OC's direct call and its retry: {@link #call}; on OC's LimitReachedException, {@code "limit"} on the context, and
     * the same call again as a synchronized one, in which {@code consumeCallBudget} charges nothing, as machine.lua and
     * {@code Machine.update} repeat it on the next tick (machine.lua:1079-1102; Machine.scala:597-630). A second
     * LimitReachedException propagates.
     */
    public static Object[] callWithRetry(Object environment, String name, RecordingContext context, Object... values)
        throws Exception {
        try {
            return call(environment, name, context, values);
        } catch (LimitReachedException first) {
            context.limitReached();
            boolean was = context.setSynchronized(true);
            try {
                return call(environment, name, context, values);
            } finally {
                context.setSynchronized(was);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scan(Object environment) throws Exception {
        Object callbacks = Class.forName("li.cil.oc.server.machine.Callbacks$")
            .getField("MODULE$")
            .get(null);
        Object scalaMap = callbacks.getClass()
            .getMethod("apply", Object.class)
            .invoke(callbacks, environment);
        return (Map<String, Object>) Class.forName("scala.collection.JavaConversions")
            .getMethod("mapAsJavaMap", Class.forName("scala.collection.Map"))
            .invoke(null, scalaMap);
    }
}
