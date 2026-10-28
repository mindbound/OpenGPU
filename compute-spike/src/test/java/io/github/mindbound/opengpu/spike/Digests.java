package io.github.mindbound.opengpu.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The leg's {@code digests.txt} (S14 plan P8.2): the SHA-256 of each kernel run's output words, by name, with the JVM
 * that ran them, which the build's verifySpikeSameOutput compares between the Java 8 and the Java 21 leg. The build
 * passes the file as {@code opengpu.spike.digests} and deletes it before each run; a JVM without the property fails
 * here, so a leg that stopped passing it fails its tests rather than leaving no file to compare. Every record rewrites
 * the whole file, digests sorted by name, so it is complete after each test. The lines are {@code key=value}:
 * {@code java.specification.version}, {@code java.version}, {@code java.vendor} and {@code java.home}, then one
 * {@code digest.<name>} per run.
 */
final class Digests {

    private static final String[] JVM = { "java.specification.version", "java.version", "java.vendor", "java.home" };
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Map<String, String> RECORDED = new TreeMap<>();

    private Digests() {}

    /** The SHA-256 of the buffers' words, each big-endian, in order, as lowercase hex. */
    static String sha256(int[]... buffers) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform has SHA-256", e);
        }
        byte[] word = new byte[4];
        for (int[] buffer : buffers) {
            for (int value : buffer) {
                word[0] = (byte) (value >>> 24);
                word[1] = (byte) (value >>> 16);
                word[2] = (byte) (value >>> 8);
                word[3] = (byte) value;
                sha.update(word);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : sha.digest()) {
            hex.append(Character.forDigit((b >>> 4) & 0xf, 16))
                .append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    /**
     * Records {@code digest} as {@code name}'s and rewrites the file. A name recorded twice must keep its digest: the
     * same run giving two outputs in one JVM is a failure in itself.
     */
    static synchronized void record(String name, String digest) {
        if (!NAME.matcher(name)
            .matches()) {
            throw new IllegalArgumentException("digest name '" + name + "' is not lowercase letters, digits and '-'");
        }
        String before = RECORDED.putIfAbsent(name, digest);
        if (before != null && !before.equals(digest)) {
            throw new AssertionError(name + " gave " + digest + " after " + before + " in the same JVM");
        }
        List<String> lines = new ArrayList<>();
        for (String key : JVM) {
            lines.add(key + "=" + System.getProperty(key));
        }
        RECORDED.forEach((key, value) -> lines.add("digest." + key + "=" + value));
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    private static Path file() {
        String path = System.getProperty("opengpu.spike.digests");
        if (path == null) {
            throw new IllegalStateException("opengpu.spike.digests is not set: run the spike's tests through Gradle");
        }
        return Paths.get(path);
    }
}
