import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * One :ocelot-harness Test task's paths (M0 step S10), as system properties, one argument each, so a path with spaces
 * stays one: the run directory, which the harness empties and fills with brain.conf, the natives and the workspaces;
 * the report directory for ocelot.txt, which the task declares as an output itself; and the project directory, where
 * ShutdownTest looks for stray natives. None of the three is an input. fetchOcelotBrain's pin record and, for the task
 * that runs BuildersVerbatimTest, docs/research/28-command-stream-v1.md are inputs by content, not by path, so the
 * task's results do not depend on where the working copy sits, and an edit to 28 runs the test again (S10 amendments
 * I10). The OC-LuaJIT leg adds its native, which Brain.init stages (I11), and ocluajitInputs' provenance record (S10
 * decisions D6), both inputs by content too.
 */
public abstract class OcelotRun implements CommandLineArgumentProvider {

    @Internal
    public abstract DirectoryProperty getRunDir();

    @Internal
    public abstract DirectoryProperty getReportDir();

    @Internal
    public abstract DirectoryProperty getProjectDir();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPinFile();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDocs28();

    /** The OC-LuaJIT leg's native, copied into the run directory by Brain.init. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStageNative();

    /** ocluajitInputs' record of the OC-LuaJIT leg's inputs, for ocelot.txt. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getOcluajitProvenance();

    @Override
    public Iterable<String> asArguments() {
        List<String> args = new ArrayList<>();
        args.add("-Dopengpu.ocelot.runDir=" + path(getRunDir()));
        args.add("-Dopengpu.ocelot.reportDir=" + path(getReportDir()));
        args.add("-Dopengpu.ocelot.projectDir=" + path(getProjectDir()));
        args.add(
            "-Dopengpu.ocelot.pinFile=" + getPinFile().get()
                .getAsFile()
                .getAbsolutePath());
        if (getDocs28().isPresent()) {
            args.add(
                "-Dopengpu.ocelot.docs28=" + getDocs28().get()
                    .getAsFile()
                    .getAbsolutePath());
        }
        if (getStageNative().isPresent()) {
            args.add(
                "-Dopengpu.ocelot.stageNative=" + getStageNative().get()
                    .getAsFile()
                    .getAbsolutePath());
            // Where OcljArch's factory would unpack a native it had to take from a jar: in the run directory, which
            // Brain.init empties, never its default "." (OcljArch.scala:800-804; S10 plan P9.4)
            args.add("-Docljit.librariespath=" + path(getRunDir()) + File.separator + "ocljit");
        }
        if (getOcluajitProvenance().isPresent()) {
            args.add(
                "-Dopengpu.ocelot.ocluajitProvenance=" + getOcluajitProvenance().get()
                    .getAsFile()
                    .getAbsolutePath());
        }
        return args;
    }

    private static String path(DirectoryProperty dir) {
        return dir.get()
            .getAsFile()
            .getAbsolutePath();
    }
}
