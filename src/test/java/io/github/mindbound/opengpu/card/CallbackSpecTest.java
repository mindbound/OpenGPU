package io.github.mindbound.opengpu.card;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.fixtures.device.M0Callbacks;
import io.github.mindbound.opengpu.core.fixtures.device.M0Callbacks.Read;
import io.github.mindbound.opengpu.core.fixtures.device.M0Callbacks.Spec;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;

/**
 * The card's callbacks are {@code :core}'s M0Callbacks, the spec {@code :ocelot-harness}'s OpenGpuEntity is held to as
 * well (S11 plan P15.4; amendments F-1; Q-S11-11): the eight M0 callbacks, {@code bind} the card's alone, each with
 * the spec's {@code direct}, {@code limit} and {@code doc}, a blank {@code value} and no getter or setter, by OC's
 * discovery rules and by OC's own scan, and each reading its arguments in the spec's order and kinds, as OC's argument
 * errors show.
 */
@Timeout(60)
class CallbackSpecTest {

    // OC's rules, Callbacks.scala:87-117 (S11 api.md §1.5, §6.5): public, exactly (Context, Arguments), Object[], found
    // on the class or a superclass but not an interface, named by value when it is not blank, else by the method
    @Test
    void theCardsAnnotationsAreM0CallbacksByOcsDiscoveryRules() {
        Map<String, Callback> found = new TreeMap<>();
        for (Class<?> c = CardEnvironment.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Callback a = m.getAnnotation(Callback.class);
                if (a == null) {
                    continue;
                }
                String where = c.getSimpleName() + "." + m.getName();
                Assertions.assertTrue(Modifier.isPublic(m.getModifiers()), where + " is public");
                Assertions.assertEquals(
                    Arrays.asList(Context.class, Arguments.class),
                    Arrays.asList(m.getParameterTypes()),
                    where + "'s parameters");
                Assertions.assertSame(Object[].class, m.getReturnType(), where + " returns Object[]");
                String name = a.value()
                    .trim()
                    .isEmpty() ? m.getName() : a.value();
                Assertions.assertNull(found.put(name, a), "two callbacks named " + name);
            }
        }
        List<String> names = new ArrayList<>();
        for (Spec s : M0Callbacks.all()) {
            names.add(s.name);
        }
        names.sort(null);
        Assertions.assertEquals(names, new ArrayList<>(found.keySet()), "the callbacks");
        for (Spec s : M0Callbacks.all()) {
            assertSpec(s, found.get(s.name));
        }
    }

    // OC's machine finds exactly these, with the same annotations (Callbacks.scala:20-27, :87-117)
    @Test
    void ocsOwnScanFindsTheEightWithTheirFlagsAndDocs() throws Exception {
        Map<String, Callback> found = OcCalls.callbacks(new CardRig().card(3));
        Assertions.assertEquals(8, found.size(), found::toString);
        for (Spec s : M0Callbacks.all()) {
            Assertions.assertTrue(found.containsKey(s.name), "OC finds " + s.name);
            assertSpec(s, found.get(s.name));
        }
    }

    // Each argument is read in the spec's order: with the first k given, the call fails on argument k + 1 with the
    // type that read expects (ArgumentsImpl.scala:310-321), and a value of no Lua type the read takes names it; an
    // optional read takes nil and no value. No call gets as far as the device
    @Test
    void eachCallbackReadsItsArgumentsInTheSpecsOrderAndKinds() throws Exception {
        CardEnvironment card = new CardRig().card(2);
        for (Spec s : M0Callbacks.all()) {
            List<Object> given = new ArrayList<>();
            for (int k = 0; k < s.reads.size(); k++) {
                Read read = s.reads.get(k);
                int n = k + 1;
                if (!read.optional) {
                    Assertions.assertEquals(
                        "bad arguments #" + n + " (" + read.expected + " expected, got no value)",
                        argumentError(card, s.name, given.toArray()),
                        s.name + " with " + k + " arguments");
                }
                List<Object> wrong = new ArrayList<>(given);
                wrong.add(Boolean.TRUE);
                Assertions.assertEquals(
                    "bad argument #" + n + " (" + read.expected + " expected, got boolean)",
                    argumentError(card, s.name, wrong.toArray()),
                    s.name + "'s argument #" + n);
                given.add(valid(read));
            }
        }
        Assertions.assertTrue(
            card.device()
                .snapshot().live.isEmpty(),
            "no call reached the device");
    }

    private static void assertSpec(Spec s, Callback a) {
        Assertions.assertNotNull(a, s.name);
        Assertions.assertEquals(s.direct, a.direct(), s.name + "'s direct");
        Assertions.assertEquals(s.limit, a.limit(), s.name + "'s limit");
        Assertions.assertEquals(s.doc, a.doc(), s.name + "'s doc");
        Assertions.assertEquals("", a.value(), s.name + "'s value: the method's name is the Lua name");
        Assertions.assertFalse(a.getter() || a.setter(), s.name + " is no getter or setter");
    }

    private static String argumentError(CardEnvironment card, String name, Object... args) {
        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> OcCalls.call(card, name, new RecordingContext(), args),
            name + Arrays.toString(args));
        return e.getMessage();
    }

    /** A value the read takes, as Lua 5.3 delivers it. */
    private static Object valid(Read read) {
        switch (read) {
            case INTEGER:
                return 1L;
            case BYTES:
                return new byte[0];
            default:
                return new byte[] { 'x' };
        }
    }
}
