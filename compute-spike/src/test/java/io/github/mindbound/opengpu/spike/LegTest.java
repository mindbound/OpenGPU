package io.github.mindbound.opengpu.spike;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Holds each leg to its Java (S14 plan P8.3): the build passes the leg's expected {@code java.specification.version},
 * 1.8 for test and 21 for test21. The convention gives every Test task a Java 8 launcher in a configureEach
 * (opengpu.java8.gradle), and test21 sets its own after it; if the convention's ever won, test21 would run on Java 8,
 * and the two legs would agree by running the same JVM twice. This fails that leg instead.
 */
class LegTest {

    @Test
    void theLegRunsOnItsExpectedJava() {
        String expected = System.getProperty("opengpu.spike.expectJava");
        Assertions.assertNotNull(expected, "opengpu.spike.expectJava is not set: run the spike's tests through Gradle");
        Assertions.assertEquals(
            expected,
            System.getProperty("java.specification.version"),
            () -> "this leg ran on " + System.getProperty("java.version") + " from " + System.getProperty("java.home"));
    }
}
