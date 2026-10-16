package io.github.mindbound.opengpu.gl;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The owner's Q-S8-3 rule (2026-10-16): the readbacks' {@code glFinish} is on for every driver but those verified
 * clean, which is NVIDIA's so far, read from {@code GL_VENDOR}; {@code ALWAYS} and {@code NEVER} ignore the vendor.
 */
class ReadbackFinishTest {

    /** GL_VENDOR strings of drivers not verified clean, as they report it, and an empty and a missing one. */
    private static final List<String> NOT_VERIFIED = Arrays.asList(
        "Intel",
        "Intel Inc.",
        "ATI Technologies Inc.",
        "AMD",
        "Advanced Micro Devices, Inc.",
        "Mesa",
        "Mesa/X.org",
        "Mesa Project",
        "nouveau",
        "VMware, Inc.",
        "Microsoft Corporation",
        "Apple",
        "",
        null);

    @Test
    void autoLeavesItOffOnlyOnNvidia() {
        Assertions.assertTrue(ReadbackFinish.verifiedClean("NVIDIA Corporation"));
        Assertions.assertFalse(ReadbackFinish.AUTO.finishes("NVIDIA Corporation"));
        for (String vendor : NOT_VERIFIED) {
            Assertions.assertFalse(ReadbackFinish.verifiedClean(vendor), String.valueOf(vendor));
            Assertions.assertTrue(ReadbackFinish.AUTO.finishes(vendor), String.valueOf(vendor));
        }
        // A prefix, matched as the driver writes it: a lower-case or embedded "nvidia" is some other driver's
        Assertions.assertTrue(ReadbackFinish.AUTO.finishes("nvidia"));
        Assertions.assertTrue(ReadbackFinish.AUTO.finishes("Mesa (NVIDIA)"));
    }

    @Test
    void alwaysAndNeverIgnoreTheVendor() {
        for (String vendor : NOT_VERIFIED) {
            Assertions.assertTrue(ReadbackFinish.ALWAYS.finishes(vendor), String.valueOf(vendor));
            Assertions.assertFalse(ReadbackFinish.NEVER.finishes(vendor), String.valueOf(vendor));
        }
        Assertions.assertTrue(ReadbackFinish.ALWAYS.finishes("NVIDIA Corporation"));
        Assertions.assertFalse(ReadbackFinish.NEVER.finishes("NVIDIA Corporation"));
    }
}
