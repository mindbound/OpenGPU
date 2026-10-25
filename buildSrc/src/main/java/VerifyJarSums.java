import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Checks downloaded jars by their whole sha256 (M0 step S10: ocelot-brain's three asie.pl jars, the owner's answer to
 * Q-S10-1): {@link #getJars()} must hold exactly the files {@link #getSums()} names, each with its sum. OpenGPU
 * keeps no Gradle dependency verification metadata, so this is the only check of what was fetched.
 */
// Untracked: hashing 5.7 MB takes a few milliseconds, and the log line on every run says which files the tests load
@UntrackedTask(because = "Checks the resolved jars and logs their sums on every run")
public abstract class VerifyJarSums extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getJars();

    /** File name to lower-case sha256. */
    @Input
    public abstract MapProperty<String, String> getSums();

    @TaskAction
    public void verify() throws IOException {
        Map<String, String> sums = getSums().get();
        Map<String, File> jars = new TreeMap<>();
        for (File jar : getJars().getFiles()) {
            jars.put(jar.getName(), jar);
        }
        if (!jars.keySet()
            .equals(new TreeSet<>(sums.keySet()))) {
            throw new GradleException(
                "resolved " + jars.keySet() + ", but the pinned sums name " + new TreeSet<>(sums.keySet()));
        }
        for (Map.Entry<String, File> entry : jars.entrySet()) {
            File jar = entry.getValue();
            String sum = sha256(jar);
            String pinned = sums.get(entry.getKey());
            if (!sum.equals(pinned)) {
                throw new GradleException(
                    entry.getKey() + " has sha256 " + sum + ", not the pinned " + pinned + " (" + jar + ")");
            }
            getLogger()
                .lifecycle("[opengpu-ocelot] {} sha256 {} ({} B) from {}", entry.getKey(), sum, jar.length(), jar);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
        byte[] chunk = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                digest.update(chunk, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b & 0xff));
        }
        return hex.toString();
    }
}
