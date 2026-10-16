package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import io.github.mindbound.opengpu.gltestkit.GlThread;

/**
 * S8 plan §4.3's GL scenarios: their class names in the table's order, and the one way they run (S8 plan §2.4).
 * GlScenarioTest runs each directly on the compatibility leg; GlsmScenarioTest loads each through GlsmLoader on both
 * GLSM legs and calls its {@code apply}, which comes back here inside the loader (H30). The same class and the same
 * assertions run on all three legs; the reference is the tier-E oracle, never another leg (23:89).
 */
public final class Scenarios {

    private static final String PACKAGE = "io.github.mindbound.opengpu.gltestkit.scenario.";

    /** Every scenario, in S8 plan §4.3's order, as class names, so reading the list loads no scenario class. */
    public static final List<String> ALL = Collections.unmodifiableList(
        Arrays.asList(
            PACKAGE + "GoldensScenario", // 1
            PACKAGE + "GeneratedScenario", // 2
            PACKAGE + "AllValuesScenario", // 3
            PACKAGE + "UploadsScenario", // 4
            PACKAGE + "ResetScenario", // 5
            PACKAGE + "TextFont0Scenario", // 6
            PACKAGE + "ExpandScenario", // 7
            PACKAGE + "HygieneScenario", // 8
            PACKAGE + "ReadbackScenario", // 9
            PACKAGE + "HostLifecycleScenario", // 10
            PACKAGE + "RoundRobinScenario", // 11
            PACKAGE + "DeviceGoldensScenario", // 12
            PACKAGE + "DeviceOrderingScenario", // 13
            PACKAGE + "DeviceLifecycleScenario", // 14
            PACKAGE + "G10Scenario", // 15
            PACKAGE + "H24BoundScenario", // 16
            PACKAGE + "ConcurrencyScenario" // 17
        ));

    private Scenarios() {}

    /**
     * Runs the scenario on the GL thread's context and returns its failures, empty when it passed; it never throws.
     * For a {@link Scenario}: the MC-like start (unless the scenario sets its own), the body with a
     * {@link ScenarioRun}, then in finally every host and renderer the run made closed (24:218's empty ledger checked)
     * and the start checked and undone. Any other function is applied as it is. Each run logs one
     * "[opengpu-scenario]" line with its wall time (S8 amendments E, CI time).
     */
    public static List<String> run(Function<GlThread, List<String>> scenario, GlThread gl) {
        long start = System.nanoTime();
        String name = scenario.getClass()
            .getSimpleName();
        List<String> failures = new ArrayList<>();
        String leg = "?";
        if (scenario instanceof Scenario) {
            ScenarioRun run = new ScenarioRun(gl, name);
            try {
                leg = run.leg();
                if (((Scenario) scenario).hostileStart()) {
                    run.start(true);
                }
                ((Scenario) scenario).run(run);
            } catch (Throwable e) {
                run.fail("ended by " + describe(e));
            } finally {
                try {
                    run.finish();
                } catch (Throwable e) {
                    run.fail("finishing: " + describe(e));
                }
            }
            synchronized (run.failures()) {
                failures.addAll(run.failures());
            }
            if (run.dropped() > 0) {
                failures.add(name + ": " + run.dropped() + " more failure(s) not kept");
            }
        } else {
            try {
                List<String> found = scenario.apply(gl);
                if (found != null) {
                    failures.addAll(found);
                }
            } catch (Throwable e) {
                failures.add(name + ": ended by " + describe(e));
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000L;
        System.out.println(
            String.format(
                Locale.ROOT,
                "[opengpu-scenario] %s on %s: %d ms, %d failure(s)",
                name,
                leg,
                millis,
                failures.size()));
        if (failures.size() > ScenarioRun.MAX_FAILURES) {
            int more = failures.size() - ScenarioRun.MAX_FAILURES;
            List<String> kept = new ArrayList<>(failures.subList(0, ScenarioRun.MAX_FAILURES));
            kept.add("... and " + more + " more");
            return kept;
        }
        return failures;
    }

    /** The throwable and the top of its stack, and its cause's. */
    static String describe(Throwable e) {
        StringBuilder text = new StringBuilder(String.valueOf(e));
        StackTraceElement[] trace = e.getStackTrace();
        for (int i = 0; i < Math.min(10, trace.length); i++) {
            text.append("\n    at ")
                .append(trace[i]);
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            text.append("\n  caused by ")
                .append(describe(cause));
        }
        return text.toString();
    }
}
