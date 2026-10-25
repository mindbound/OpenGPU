import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * The local OC-LuaJIT leg's inputs, read in place from the owner's checkout and checked on every run before anything
 * compiles, runs or loads them (M0 step S10; RM:221; S10 decisions D6, which replace the plan's Q-S10-2 default and
 * amendments F2):
 * <ul>
 * <li>the checkout's HEAD is the floor, OpenGpuVersions.OCLUAJIT_FLOOR (a98787b), or a descendant of it;</li>
 * <li>each source the leg compiles or runs ({@link #getSources()}: the adapter OcljArch.scala, the LuaStateLuaJIT.java
 * it imports, the kernel patcher) has the floor's blob id, as git hashes the working file with the checkout's own
 * attributes ({@code hash-object --path}, without -w, so nothing is written);</li>
 * <li>the native and the luajit executable were modified at or after the floor's committer time, and the native has
 * the name OcljArch's factory loads; a failure names the file, its mtime and the floor, and says how to rebuild
 * it.</li>
 * </ul>
 * Every failure is listed in one message. On success it writes {@link #getProvenance()}: the HEAD, the floor, and each
 * file's sha256, size and mtime, the lines Brain.init puts into ocelot.txt (S10 plan P13.4). Every git call only reads:
 * --no-optional-locks, so no index refresh is ever written (amendments F8), -c safe.directory=&lt;checkout&gt; for
 * WSL's
 * view of a Windows checkout, the credential helpers and core.askPass cleared, no prompt and a wall-time cap, as
 * PinnedGitCheckout's; no
 * git config is written. -Popengpu.ocluajit.floor replaces the floor for the hand-run cases only and fails the build on
 * CI (amendments I16).
 */
@UntrackedTask(because = "Reads the owner's OC-LuaJIT checkout in place, whose HEAD and files can change at any time")
public abstract class OcLuajitInputs extends DefaultTask {

    private static final long GIT_TIMEOUT_S = 60;
    /** Variables that would point git at another repository or a credential prompt. */
    private static final List<String> CLEARED_ENVIRONMENT = Arrays.asList(
        "GIT_ASKPASS",
        "SSH_ASKPASS",
        "GIT_DIR",
        "GIT_WORK_TREE",
        "GIT_INDEX_FILE",
        "GIT_OBJECT_DIRECTORY",
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        "GIT_COMMON_DIR");
    /** OC-LuaJIT's own build of the additive native and of luajit (native/build-native.sh). */
    private static final String REBUILD = "OCLJ_VARIANT=additive sh native/build-native.sh";

    /** The OC-LuaJIT checkout, -Popengpu.ocluajit.dir. */
    @Internal
    public abstract DirectoryProperty getCheckout();

    /** The commit the sources must match and the builds must not predate. */
    @Input
    public abstract Property<String> getFloor();

    /** The property that replaced {@link #getFloor()}, unset by default: test-only, refused on CI. */
    @Input
    @Optional
    public abstract Property<String> getFloorForcedBy();

    /** Whether the build runs on CI (GITHUB_ACTIONS=true). */
    @Input
    public abstract Property<Boolean> getCi();

    /** What the leg compiles or runs from the checkout, by role, each a path relative to it, with forward slashes. */
    @Input
    public abstract MapProperty<String, String> getSources();

    /** The additive native, Brain.init's staged copy's source. */
    @Internal
    public abstract RegularFileProperty getNativeLibrary();

    /** The file name OcljArch's factory loads, libjnluajit52-&lt;platform&gt;&lt;ext&gt; (OcljArch.scala:764-769). */
    @Input
    public abstract Property<String> getNativeName();

    /** The luajit that runs the kernel patcher. */
    @Internal
    public abstract RegularFileProperty getLuajitExecutable();

    /** The report lines for ocelot.txt, one per line. */
    @OutputFile
    public abstract RegularFileProperty getProvenance();

    @TaskAction
    public void run() throws IOException {
        // A record is only ever the last passing run's
        Path provenance = getProvenance().get()
            .getAsFile()
            .toPath();
        Files.deleteIfExists(provenance);
        String forcedBy = getFloorForcedBy().getOrNull();
        if (forcedBy != null && getCi().get()) {
            throw new GradleException(
                forcedBy + " is set on CI, where the OC-LuaJIT leg's floor is OpenGpuVersions.OCLUAJIT_FLOOR: remove it"
                    + " from the command line, gradle.properties or ORG_GRADLE_PROJECT_*");
        }
        File dir = getCheckout().get()
            .getAsFile();
        if (!new File(dir, ".git").exists()) {
            throw new GradleException(
                dir + " is not a git checkout; -Popengpu.ocluajit.dir names the owner's OC-LuaJIT checkout");
        }
        Map<String, String> sources = getSources().get();
        File nativeLibrary = getNativeLibrary().get()
            .getAsFile();
        File luajit = getLuajitExecutable().get()
            .getAsFile();
        List<String> problems = new ArrayList<>();
        // Every file first, so one run names all that are missing
        for (Map.Entry<String, String> source : sources.entrySet()) {
            if (!new File(dir, source.getValue()).isFile()) {
                problems.add("the " + source.getKey() + " " + source.getValue() + " is missing");
            }
        }
        for (File built : Arrays.asList(nativeLibrary, luajit)) {
            if (!built.isFile()) {
                problems.add(
                    "the " + role(built, nativeLibrary)
                        + " "
                        + shown(dir, built)
                        + " is missing; build it in "
                        + dir
                        + " with `"
                        + REBUILD
                        + "`");
            }
        }
        if (!nativeLibrary.getName()
            .equals(getNativeName().get())) {
            problems.add(
                "the native " + shown(dir, nativeLibrary)
                    + " is not named "
                    + getNativeName().get()
                    + ", the file OcljArch's factory loads");
        }
        refuseIf(dir, problems);

        String head = git(dir, "rev-parse", "--verify", "HEAD^{commit}").trim();
        ProcessRun.Result floorId = gitResult(dir, "rev-parse", "--verify", "--quiet", getFloor().get() + "^{commit}");
        if (floorId.exit != 0) {
            problems.add(
                "the floor " + getFloor().get()
                    + " ("
                    + floorSource()
                    + ") is not a commit in the checkout, whose HEAD is "
                    + head
                    + ": the checkout is older than the floor or has not fetched it; bring it to the floor or later");
            refuseIf(dir, problems);
        }
        String floor = floorId.out.trim();
        String floor7 = floor.substring(0, 7);
        ProcessRun.Result ancestor = gitResult(dir, "merge-base", "--is-ancestor", floor, head);
        if (ancestor.exit == 1) {
            problems.add("HEAD is " + head + ", not the floor " + floor + " or a descendant of it");
        } else if (ancestor.exit != 0) {
            throw failed(dir, "merge-base --is-ancestor " + floor + " " + head, ancestor);
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String path = source.getValue();
            ProcessRun.Result committed = gitResult(dir, "rev-parse", "--verify", "--quiet", floor + ":" + path);
            String working = git(dir, "hash-object", "--path=" + path, "--", path).trim();
            if (committed.exit != 0) {
                problems.add("the " + source.getKey() + " " + path + " is not in " + floor7);
                continue;
            }
            String blob = committed.out.trim();
            if (!blob.equals(working)) {
                problems.add(
                    "the " + source.getKey()
                        + " "
                        + path
                        + " is not "
                        + floor7
                        + "'s: its blob is "
                        + working
                        + ", "
                        + floor7
                        + "'s is "
                        + blob);
                continue;
            }
            lines.add(
                "OC-LuaJIT " + source.getKey()
                    + " "
                    + path
                    + ": blob "
                    + blob.substring(0, 8)
                    + ", "
                    + floor7
                    + "'s; "
                    + described(new File(dir, path)));
        }
        String[] committedAt = git(dir, "show", "-s", "--format=%ct%n%cI", floor).trim()
            .split("\n");
        long floorSeconds = Long.parseLong(committedAt[0].trim());
        String floorTime = committedAt[1].trim();
        for (File built : Arrays.asList(nativeLibrary, luajit)) {
            long modified = Files.getLastModifiedTime(built.toPath())
                .toMillis();
            if (modified < floorSeconds * 1000) {
                problems.add(
                    "the " + role(built, nativeLibrary)
                        + " "
                        + shown(dir, built)
                        + " was last modified "
                        + time(modified)
                        + ", before the floor "
                        + floor7
                        + "'s commit time "
                        + floorTime
                        + ", so it may be a build of an older commit; rebuild it in "
                        + dir
                        + " with `"
                        + REBUILD
                        + "`");
                continue;
            }
            lines.add(
                "OC-LuaJIT " + role(built, nativeLibrary)
                    + " "
                    + shown(dir, built)
                    + ": at or after the floor's commit time; "
                    + described(built));
        }
        refuseIf(dir, problems);

        String whose = head.equals(floor) ? "the floor itself" : "a descendant of the floor";
        lines.add(
            0,
            "OC-LuaJIT checkout " + dir
                + ": HEAD "
                + head
                + ", "
                + whose
                + "; floor "
                + floor
                + " ("
                + floorSource()
                + ") committed "
                + floorTime);
        Files.createDirectories(provenance.getParent());
        Files.write(provenance, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        lines.forEach(line -> getLogger().lifecycle("[opengpu-ocelot] {}", line));
    }

    private String floorSource() {
        return getFloorForcedBy().isPresent() ? getFloorForcedBy().get() + ", test-only"
            : "OpenGpuVersions.OCLUAJIT_FLOOR";
    }

    private static void refuseIf(File dir, List<String> problems) {
        if (problems.isEmpty()) {
            return;
        }
        throw new GradleException(
            "The local OC-LuaJIT leg refuses its inputs in " + dir
                + " (S10 decisions D6):\n- "
                + String.join("\n- ", problems));
    }

    private static String role(File built, File nativeLibrary) {
        return built.equals(nativeLibrary) ? "native" : "luajit";
    }

    /** A path relative to the checkout when it is inside it, as git shows paths. */
    private static String shown(File dir, File file) {
        Path d = dir.toPath()
            .toAbsolutePath()
            .normalize();
        Path f = file.toPath()
            .toAbsolutePath()
            .normalize();
        return f.startsWith(d) ? d.relativize(f)
            .toString()
            .replace(File.separatorChar, '/') : f.toString();
    }

    private static String described(File file) throws IOException {
        Path path = file.toPath();
        return Files.size(path) + " B, mtime "
            + time(
                Files.getLastModifiedTime(path)
                    .toMillis())
            + ", sha256 "
            + sha256(path);
    }

    private static String time(long millis) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(millis)
                .atZone(ZoneId.systemDefault()));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-256", e);
        }
        byte[] chunk = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(path)) {
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

    /** A git call that must succeed; its standard output. */
    private String git(File dir, String... args) {
        ProcessRun.Result result = gitResult(dir, args);
        if (result.exit != 0) {
            throw failed(dir, String.join(" ", args), result);
        }
        return result.out;
    }

    private ProcessRun.Result gitResult(File dir, String... args) {
        List<String> command = new ArrayList<>(
            Arrays.asList(
                "git",
                "--no-optional-locks",
                "-c",
                "credential.helper=",
                "-c",
                "core.askPass=",
                "-c",
                "safe.directory=" + dir.getAbsolutePath()
                    .replace(File.separatorChar, '/'),
                "-C",
                dir.getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        Map<String, String> set = new HashMap<>();
        set.put("GIT_TERMINAL_PROMPT", "0");
        set.put("GCM_INTERACTIVE", "never");
        return ProcessRun.run(
            "git " + String.join(" ", args) + " in " + dir,
            command,
            dir,
            CLEARED_ENVIRONMENT,
            Collections.unmodifiableMap(set),
            GIT_TIMEOUT_S);
    }

    private static GradleException failed(File dir, String what, ProcessRun.Result result) {
        return new GradleException(
            "git " + what + " in " + dir + " failed (exit " + result.exit + "): " + result.err.trim());
    }
}
