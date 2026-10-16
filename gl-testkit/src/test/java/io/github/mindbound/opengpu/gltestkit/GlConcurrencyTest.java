package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;
import io.github.mindbound.opengpu.gltestkit.scenario.ConcurrencyScenario;

/**
 * Scenario 17 on the compatibility leg under its own {@code @Timeout(60)} (S8 plan §4.3, §0: every threaded test),
 * applied directly, as GlScenarioTest applies every scenario under its rows' wider timeout. Run it once on two real
 * cores too (S8 amendments E), which is stricter than CI's runner (S8's first CI run reports 4 Gradle worker leases,
 * one per processor): {@code -Popengpu.gl.jvmArgs=-XX:ActiveProcessorCount=2} only sizes the JVM's own view
 * (availableProcessors, its GC and JIT threads) and leaves the threads on every core, so the run needs an affinity mask
 * on a Gradle process with no daemon, which its test JVM inherits: on Windows {@code ./gradlew --stop}, then
 * {@code cmd /c start "" /affinity 3 /wait cmd /c gradlew.bat --no-daemon ...}; on Linux
 * {@code taskset -c 0,1 ./gradlew --no-daemon ...} (S8 review TE-1).
 */
@RenderCoreTest
class GlConcurrencyTest {

    @Test
    @Timeout(60)
    void theThreadsOfTheGameAgreeWithTheOracle(GlThread gl) {
        List<String> failures = new ConcurrencyScenario().apply(gl);
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }
}
