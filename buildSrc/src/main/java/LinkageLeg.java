import java.io.File;
import java.util.Arrays;
import java.util.Set;

import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * One leg of gl-testkit's glsmLinkageTest (M0 step S9): the system properties that tell GlsmLinkageTest the leg's
 * Angelica release, where that release came from and its dev jar, one argument each, so a path with spaces stays one.
 * {@link #getSpec()} is "&lt;version&gt; &lt;source&gt;": "pinned" for an OpenGpuVersions pin, else what
 * {@link NexusRelease} returns.
 */
public abstract class LinkageLeg implements CommandLineArgumentProvider {

    @Input
    public abstract Property<String> getLeg();

    @Input
    public abstract Property<String> getSpec();

    /** The leg's dev jar, alone: its configuration is not transitive. */
    @Classpath
    public abstract ConfigurableFileCollection getJar();

    @Override
    public Iterable<String> asArguments() {
        String leg = getLeg().get();
        Set<File> files = getJar().getFiles();
        if (files.size() != 1) {
            throw new GradleException("the linkage leg " + leg + " resolved " + files + ", not one Angelica jar");
        }
        String spec = getSpec().get();
        int space = spec.indexOf(' ');
        if (space <= 0) {
            throw new GradleException(
                "the linkage leg " + leg + " has the spec \"" + spec + "\", not a version and a source");
        }
        String prefix = "-Dopengpu.linkage." + leg + ".";
        return Arrays.asList(
            prefix + "version=" + spec.substring(0, space),
            prefix + "source=" + spec.substring(space + 1),
            prefix + "jar="
                + files.iterator()
                    .next()
                    .getAbsolutePath());
    }
}
