package io.github.mindbound.opengpu.gltestkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that runs under Angelica's GLSM (the GLSM spike, roadmap M0): a {@link GlTest}, for the context,
 * plus {@link GlsmExtension}, which brings GLSM up on it once per test JVM and resolves {@link GlsmInfo} parameters.
 * Only the GLSM legs, glsmFloorTest and glsmPackTest in gl-testkit/build.gradle.kts, run these classes, each on the
 * CORE_33 context with one Angelica release; those tagged glsm-release run on glsmReleaseConfigTest and
 * glsmPackReleaseConfigTest instead, with GLSM's error checks off as in a release client (S8 review HY-1).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@GlTest
@ExtendWith(GlsmExtension.class)
public @interface GlsmTest {}
