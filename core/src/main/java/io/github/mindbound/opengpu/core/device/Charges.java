package io.github.mindbound.opengpu.core.device;

/**
 * 28 §8.2's call-budget charges, pure functions. {@code tier} is OpenGPU's 1-3, and t = tier − 1 is OC's 0-based
 * tier, as OC's cost arrays and {@code bitblt} index it
 * ({@code OCT/scala/li/cil/oc/server/component/GraphicsCard.scala:64-73}). Every method throws
 * IllegalArgumentException for a tier outside 1-3.
 */
public final class Charges {

    /** 256 KB: readPixels, updateTexture and createTexture charge 1 per this many bytes. */
    public static final double BYTES_PER_UNIT = 262144;
    /** 1 MB: submit's byte part. */
    public static final double SUBMIT_BYTES_PER_UNIT = 1048576;

    private static final int SUBMIT_CHARGED_BYTES = 65536;

    private Charges() {}

    /**
     * 1/32·2^−t + min(length, 65 536)/1 MB: at most 0.094, so never two-phase.
     *
     * @throws IllegalArgumentException also for a negative length
     */
    public static double submit(int tier, int length) {
        int t = t(tier);
        if (length < 0) {
            throw new IllegalArgumentException("length " + length + " is negative");
        }
        return Math.scalb(1.0 / 32, -t) + Math.min(length, SUBMIT_CHARGED_BYTES) / SUBMIT_BYTES_PER_UNIT;
    }

    /** 1/8, 1/16, 1/32 at T1-T3. */
    public static double present(int tier) {
        return Math.scalb(1.0 / 8, -t(tier));
    }

    /**
     * bytes/256 KB: readPixels' w·h·bpp, updateTexture's {@code #data}, createTexture's w·h·bpp.
     *
     * @throws IllegalArgumentException for negative bytes
     */
    public static double bytes(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes " + bytes + " is negative");
        }
        return bytes / BYTES_PER_UNIT;
    }

    /** The tier's credit k = (t + 1)·0.5 ({@code GraphicsCard.scala:235}): 0.5, 1.0, 1.5. */
    public static double credit(int tier) {
        return (t(tier) + 1) * .5;
    }

    /** Whether a charge takes the two-phase path: cost > k ({@code GraphicsCard.scala:237}). */
    public static boolean twoPhase(int tier, double cost) {
        return cost > credit(tier);
    }

    /**
     * The retry's pause, ((c − k) − k)/k/20 s when c − k > k, else 0 ({@code GraphicsCard.scala:239-243}): T2 c = 4
     * gives 0.1 s, T3 c = 8 gives 0.166 666 667 s.
     */
    public static double pauseSeconds(int tier, double cost) {
        double k = credit(tier);
        double over = cost - k;
        return over > k ? ((over - k) / k) / 20 : 0;
    }

    private static int t(int tier) {
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("tier " + tier + " is outside 1..3");
        }
        return tier - 1;
    }
}
