package io.github.mindbound.opengpu.gltestkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that needs a real GL context. It gets the gl tag, which only the context-bound Test tasks run
 * (glCompatTest and glCoreTest in gl-testkit/build.gradle.kts; docs/research/23-gl-testing-strategy.md §4.2), and
 * {@link GlContextExtension}, which creates the context once per test JVM and resolves {@link GlThread} and
 * {@link GlInfo} parameters.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag("gl")
@ExtendWith(GlContextExtension.class)
public @interface GlTest {}
