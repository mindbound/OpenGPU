package io.github.mindbound.opengpu.gltestkit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.gl.ReadbackFinish;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * {@code -Popengpu.gl.readbackFinish}, as gl-testkit's build passes it to every test JVM, {@code auto} (production's)
 * without {@code -P}: this JVM has it as a policy, the fixtures' backends take the value it names, and a JVM without
 * it fails loudly instead of running the fixtures at a policy nobody asked for (Q-S8-3). No context: the plain test
 * task runs it.
 */
class ReadbackFinishPropertyTest {

    @Test
    void theBuildPassesThePropertyAndTheFixturesTakeThePolicyItNames() {
        String saved = System.getProperty(GlTestHost.READBACK_FINISH_PROPERTY);
        Assertions.assertNotNull(
            saved,
            GlTestHost.READBACK_FINISH_PROPERTY + ": gl-testkit's build passes it to every test JVM, -P or not");
        Assertions.assertDoesNotThrow(GlTestHost::readbackFinish, saved);
        try {
            String[] values = { "auto", "always", "never", " Never ", "ALWAYS" };
            ReadbackFinish[] expected = { ReadbackFinish.AUTO, ReadbackFinish.ALWAYS, ReadbackFinish.NEVER,
                ReadbackFinish.NEVER, ReadbackFinish.ALWAYS };
            for (int i = 0; i < values.length; i++) {
                System.setProperty(GlTestHost.READBACK_FINISH_PROPERTY, values[i]);
                Assertions.assertEquals(expected[i], GlTestHost.readbackFinish(), values[i]);
            }
            System.setProperty(GlTestHost.READBACK_FINISH_PROPERTY, "sometimes");
            IllegalArgumentException e = Assertions
                .assertThrows(IllegalArgumentException.class, GlTestHost::readbackFinish);
            Assertions.assertTrue(
                e.getMessage()
                    .contains(GlTestHost.READBACK_FINISH_PROPERTY),
                e.getMessage());
            System.clearProperty(GlTestHost.READBACK_FINISH_PROPERTY);
            IllegalStateException missing = Assertions
                .assertThrows(IllegalStateException.class, GlTestHost::readbackFinish, "a JVM without the property");
            Assertions.assertTrue(
                missing.getMessage()
                    .contains(GlTestHost.READBACK_FINISH_PROPERTY + " is not set"),
                missing.getMessage());
        } finally {
            System.setProperty(GlTestHost.READBACK_FINISH_PROPERTY, saved);
        }
    }
}
