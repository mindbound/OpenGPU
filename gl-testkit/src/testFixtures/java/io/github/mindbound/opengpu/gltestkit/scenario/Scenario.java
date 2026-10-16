package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.List;
import java.util.function.Function;

import io.github.mindbound.opengpu.gltestkit.GlThread;

/**
 * One GL scenario of S8 plan §4.3, run the same way on all three production legs (23:89): directly on the
 * compatibility leg by GlScenarioTest, and through GlsmLoader on both GLSM legs by GlsmScenarioTest, which sees only
 * this {@code Function<GlThread, List<String>>} (H30). A subclass is a {@code public final} class of this package
 * with a public no-argument constructor that does no work, and its name goes into {@link Scenarios#ALL}. It writes
 * {@link #run} only: {@link #apply} hands it to {@link Scenarios#run}, which sets the MC-like start
 * ({@link #hostileStart()}), runs the body with a {@link ScenarioRun}, closes every host the body made through the run
 * (in finally), checks and undoes the start, and logs the wall time. JDK and {@code :core} types only cross the
 * boundary; GL work goes through the run's hosts and {@link ScenarioRun#onGl}.
 */
public abstract class Scenario implements Function<GlThread, List<String>> {

    /** {@link Scenarios#run}{@code (this, gl)}: the failures, empty when the scenario passed. */
    @Override
    public final List<String> apply(GlThread gl) {
        return Scenarios.run(this, gl);
    }

    /**
     * The scenario's body. Findings go to {@link ScenarioRun#check}, {@link ScenarioRun#fail} and the compare methods;
     * an exception ends the scenario as one more failure.
     */
    protected abstract void run(ScenarioRun run) throws Exception;

    /**
     * Whether {@link Scenarios#run} sets {@code GlHygiene.mcLikeStart()} before the body, checks it after every GL
     * entry point of the run's hosts and undoes it after (S8 amendments E: hygiene everywhere). True but for the
     * Hygiene scenario, which sets its own starts.
     */
    protected boolean hostileStart() {
        return true;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
