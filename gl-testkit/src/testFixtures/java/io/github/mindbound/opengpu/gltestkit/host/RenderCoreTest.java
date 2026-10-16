package io.github.mindbound.opengpu.gltestkit.host;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Tag;

import io.github.mindbound.opengpu.gltestkit.GlTest;

/**
 * Marks a test class that runs {@code :gl}'s render core: a {@link GlTest}, for the context, plus the render-core
 * tag. The render core needs a compatibility context or GLSM's emulation ({@code glPushAttrib},
 * {@code GL_ALPHA_TEST}), so glCoreTest, the raw core 3.3 leg, excludes the tag (H28;
 * gl-testkit/build.gradle.kts's glTestTask). The GLSM legs' tests are {@code GlsmTest}s, which those tasks run anyway.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@GlTest
@Tag("render-core")
public @interface RenderCoreTest {}
