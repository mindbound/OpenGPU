import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * One git repository's tree at one pinned commit (M0 step S10: ocelot-brain at e98a5b2, RM:209), kept in
 * {@link #getCheckout()} outside the build directory, so clean keeps it and every working copy on the machine shares
 * it.
 * <ul>
 * <li>With no checkout, it fetches exactly the commit (depth 1) from {@link #getRepositories()} in their order, up to
 * three attempts each, 2 s and 5 s apart and each stopped after 120 s, into a sibling directory, checks that and moves
 * it into place. When every attempt fails the build fails, since running it again is cheap (the owner's Q-S9-2).</li>
 * <li>With --offline it fetches only from file: repositories, which need no network, as Gradle's own offline mode
 * still reads file repositories; only the hand-run cases' test-only property names one.</li>
 * <li>Every run checks the checkout against the commit itself, whichever repository served it (S10 plan P5.4 and
 * amendments I1): HEAD and its tree must be the pinned ones, the commit must list {@link #getFiles()} files, and every
 * file outside .git is hashed as git hashes a blob, with no filter, and compared with that listing in both directions.
 * A listed file that is missing, a file the listing lacks and a file whose bytes differ each fail the check, so line
 * ending conversion (Git for Windows sets core.autocrlf=true system-wide), an attributes file, an edit or a stray file
 * fails whatever git's own configuration says. File modes are not compared: Windows keeps no exec bit, and no mode
 * changes what compiles.</li>
 * <li>A checkout that fails the check is left as it is, for its evidence, and the build fails. On CI
 * ({@link #getCi()}), where the Gradle home comes back from setup-gradle's cache and nobody can delete it, it is moved
 * aside to &lt;checkout&gt;.bad-&lt;n&gt; and fetched once more (S10 amendments I17). Once a checkout passes on CI,
 * every &lt;checkout&gt;.bad-&lt;n&gt; beside it is deleted, since its first difference is in the log and the cache
 * would otherwise carry it in every later entry (S10 review B3).</li>
 * </ul>
 * Every git call clears the credential helpers and core.askPass and runs with GCM_INTERACTIVE=never and no askpass
 * program, so a host that asks for credentials fails the attempt instead of opening Git Credential Manager's sign-in
 * window or a configured askpass one (S10 review B6); each call has a wall-time cap, and a fetch also gives up below
 * 1000 B/s for 30 s (S10 amendments I8).
 */
// Untracked: the check is the point, so it runs on every build (tens of milliseconds); the output properties still
// carry the task dependency to the source set that reads the checkout
@UntrackedTask(because = "Checks a directory outside the build directory on every build, fetching it at most once")
public abstract class PinnedGitCheckout extends DefaultTask {

    /** The pauses before the second and the third attempt on one repository. */
    private static final long[] PAUSES_MS = { 2_000L, 5_000L };
    private static final long FETCH_TIMEOUT_S = 120;
    private static final long LOCAL_TIMEOUT_S = 60;
    /** Moves refused while Windows Defender or the indexer still holds a freshly written file (S10 amendments I15). */
    private static final int MOVE_ATTEMPTS = 5;
    private static final long MOVE_PAUSE_MS = 1_000L;
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

    /** What the messages call the repository, "ocelot-brain". */
    @Input
    public abstract Property<String> getLabel();

    /** Tried in this order, up to three attempts each. */
    @Input
    public abstract ListProperty<String> getRepositories();

    /**
     * The property that replaced {@link #getRepositories()}, unset by default: a test-only switch for the hand-run
     * negative cases, which fails the build on CI.
     */
    @Input
    @Optional
    public abstract Property<String> getRepositoriesForcedBy();

    /** The full 40-digit commit id. */
    @Input
    public abstract Property<String> getCommit();

    /** The commit's root tree, 40 digits. */
    @Input
    public abstract Property<String> getTree();

    /** The number of files the commit lists. */
    @Input
    public abstract Property<Integer> getFiles();

    /** Gradle's --offline. */
    @Input
    public abstract Property<Boolean> getOffline();

    /** Whether the build runs on CI (GITHUB_ACTIONS=true). */
    @Input
    public abstract Property<Boolean> getCi();

    @OutputDirectory
    public abstract DirectoryProperty getCheckout();

    /**
     * The commit, tree, file count, bytes, store and the repository that served it, for the test reports (05:116). Its
     * one time stamp, fetchedAt, changes only when the checkout is fetched again, so a test task that reads it stays up
     * to date while the checkout does not change.
     */
    @OutputFile
    public abstract RegularFileProperty getPinFile();

    @TaskAction
    public void run() throws IOException {
        String label = getLabel().get();
        String forcedBy = getRepositoriesForcedBy().getOrNull();
        if (forcedBy != null && getCi().get()) {
            throw new GradleException(
                forcedBy + " is set on CI, where "
                    + label
                    + " must come from OpenGpuVersions' repositories: remove it"
                    + " from the command line, gradle.properties or ORG_GRADLE_PROJECT_*");
        }
        String commit = hex40(getCommit().get(), "commit");
        hex40(getTree().get(), "tree");
        Path dir = getCheckout().get()
            .getAsFile()
            .toPath();
        boolean fetched = false;
        if (!Files.isDirectory(dir.resolve(".git"))) {
            if (isNonEmptyDirectory(dir)) {
                fetched = failOrRefetch(dir, "it has files but no .git directory");
            } else {
                fetch(dir);
                fetched = true;
            }
        }
        long start = System.nanoTime();
        Check check = check(dir);
        if (check.problem != null && !fetched) {
            fetched = failOrRefetch(dir, check.problem);
            start = System.nanoTime();
            check = check(dir);
        }
        if (check.problem != null) {
            throw new GradleException(notThePin(dir, check.problem));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (getCi().get()) {
            deleteQuarantined(dir);
        }
        String source = git(dir.toFile(), LOCAL_TIMEOUT_S, true, "config", "--get", "opengpu.fetchedFrom").trim();
        String fetchedAt = git(dir.toFile(), LOCAL_TIMEOUT_S, true, "config", "--get", "opengpu.fetchedAt").trim();
        writePin(dir, check, source, fetchedAt);
        getLogger().lifecycle(
            "[opengpu-ocelot] {} {} verified at {}: tree {}, {} files, {} B, in {} ms; fetched {}",
            label,
            commit.substring(0, 7),
            dir,
            getTree().get()
                .substring(0, 8),
            check.files,
            check.bytes,
            ms,
            source.isEmpty() ? "before its source was recorded" : "from " + source + " at " + fetchedAt);
    }

    /**
     * A checkout that fails its check: locally the build fails and the directory stays; on CI it moves aside and is
     * fetched again. Returns true once fetched.
     */
    private boolean failOrRefetch(Path dir, String problem) throws IOException {
        if (!getCi().get()) {
            throw new GradleException(notThePin(dir, problem));
        }
        Path bad = null;
        for (int n = 1; bad == null; n++) {
            Path candidate = dir.resolveSibling(dir.getFileName() + ".bad-" + n);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                bad = candidate;
            }
        }
        try {
            Files.move(dir, bad, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new GradleException(notThePin(dir, problem) + "; moving it aside to " + bad + " failed: " + e, e);
        }
        getLogger().warn(
            "[opengpu-ocelot] {} failed its check ({}); on CI it moves to {}, which is deleted once a new fetch passes",
            dir,
            problem,
            bad);
        fetch(dir);
        return true;
    }

    /** The checkouts moved aside beside {@code dir}, on CI once {@code dir} has passed its check. */
    private void deleteQuarantined(Path dir) throws IOException {
        String prefix = dir.getFileName() + ".bad-";
        List<Path> bad;
        try (Stream<Path> siblings = Files.list(dir.getParent())) {
            bad = siblings.filter(
                p -> p.getFileName()
                    .toString()
                    .startsWith(prefix))
                .sorted()
                .collect(Collectors.toList());
        }
        for (Path path : bad) {
            deleteTree(path);
            getLogger().lifecycle("[opengpu-ocelot] deleted {}, a checkout that failed its check before", path);
        }
    }

    private String notThePin(Path dir, String problem) {
        return dir + " is not "
            + getLabel().get()
            + " "
            + getCommit().get()
                .substring(0, 7)
            + "'s tree ("
            + problem
            + "); delete the directory and build online to fetch it again";
    }

    /** Fetches into a sibling directory, checks it and moves it into place, so no half checkout is ever in place. */
    private void fetch(Path dir) throws IOException {
        String label = getLabel().get();
        String commit = getCommit().get();
        List<String> all = getRepositories().get();
        List<String> urls = getOffline().get() ? all.stream()
            .filter(url -> url.startsWith("file:"))
            .collect(Collectors.toList()) : all;
        if (urls.isEmpty()) {
            deleteIfEmpty(dir);
            throw new GradleException(
                label + " "
                    + commit.substring(0, 7)
                    + " is not in "
                    + dir
                    + "; run this build once without --offline"
                    + " to fetch it");
        }
        Files.createDirectories(dir.getParent());
        int attempts = PAUSES_MS.length + 1;
        GitFailure last = null;
        for (int u = 0; u < urls.size(); u++) {
            String url = urls.get(u);
            for (int attempt = 1; attempt <= attempts; attempt++) {
                if (attempt > 1) {
                    pause(PAUSES_MS[attempt - 2]);
                }
                Path partial = dir.resolveSibling(dir.getFileName() + ".partial-" + System.nanoTime());
                long start = System.nanoTime();
                try {
                    checkout(partial, url, commit);
                } catch (GitFailure e) {
                    deleteTree(partial);
                    last = e;
                    String next = attempt < attempts ? "trying again in " + PAUSES_MS[attempt - 1] / 1000 + " s"
                        : u + 1 < urls.size() ? "trying " + urls.get(u + 1) : "no repository left";
                    getLogger().warn(
                        "[opengpu-ocelot] {}: attempt {} of {} to fetch {} from {} failed after {} ms, {}: {}",
                        label,
                        attempt,
                        attempts,
                        commit.substring(0, 7),
                        url,
                        (System.nanoTime() - start) / 1_000_000,
                        next,
                        e.getMessage());
                    continue;
                } catch (IOException | RuntimeException e) {
                    deleteTree(partial);
                    throw e;
                }
                long ms = (System.nanoTime() - start) / 1_000_000;
                // A commit fetched by its id is that commit; a check that fails here means a local setting converted
                // files on checkout, which no other attempt mends
                Check check;
                try {
                    check = check(partial);
                } catch (IOException | RuntimeException e) {
                    deleteTree(partial);
                    throw e;
                }
                if (check.problem != null) {
                    deleteTree(partial);
                    throw new GradleException(
                        label + " "
                            + commit.substring(0, 7)
                            + " as checked out from "
                            + url
                            + " is not the commit's tree ("
                            + check.problem
                            + "): a git setting converted files on checkout (an attributes file, core.autocrlf or"
                            + " core.eol)");
                }
                if (moveIntoPlace(partial, dir)) {
                    getLogger().lifecycle(
                        "[opengpu-ocelot] {} {} fetched from {} (attempt {}) into {} in {} ms",
                        label,
                        commit.substring(0, 7),
                        url,
                        attempt,
                        dir,
                        ms);
                } else {
                    getLogger().lifecycle(
                        "[opengpu-ocelot] {} {}: another build put it in place first; checking that one",
                        label,
                        commit.substring(0, 7));
                }
                return;
            }
        }
        deleteIfEmpty(dir);
        throw new GradleException(
            label + " "
                + commit.substring(0, 7)
                + " could not be fetched in "
                + attempts
                + " attempts from each of "
                + urls
                + "; run the build again, which is cheap; --offline builds need it in "
                + dir,
            last);
    }

    private void checkout(Path partial, String url, String commit) throws IOException {
        Files.createDirectory(partial);
        File p = partial.toFile();
        git(p, LOCAL_TIMEOUT_S, false, "init", "--quiet");
        // The bytes as committed on every OS: Git for Windows' system config sets core.autocrlf=true, which would
        // rewrite the Lua sources; long paths for Git for Windows, which other gits ignore
        git(p, LOCAL_TIMEOUT_S, false, "config", "core.autocrlf", "false");
        git(p, LOCAL_TIMEOUT_S, false, "config", "core.eol", "lf");
        git(p, LOCAL_TIMEOUT_S, false, "config", "core.longpaths", "true");
        git(
            p,
            FETCH_TIMEOUT_S,
            false,
            "-c",
            "protocol.version=2",
            "-c",
            "transfer.fsckObjects=true",
            "-c",
            "http.lowSpeedLimit=1000",
            "-c",
            "http.lowSpeedTime=30",
            "fetch",
            "--quiet",
            "--depth",
            "1",
            "--no-tags",
            url,
            commit);
        git(
            p,
            LOCAL_TIMEOUT_S,
            false,
            "-c",
            "advice.detachedHead=false",
            "checkout",
            "--quiet",
            "--detach",
            "FETCH_HEAD");
        // For the log of every later run, which names the repository that served the fetch
        git(p, LOCAL_TIMEOUT_S, false, "config", "opengpu.fetchedFrom", url);
        git(
            p,
            LOCAL_TIMEOUT_S,
            false,
            "config",
            "opengpu.fetchedAt",
            Instant.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .toString());
    }

    /** Returns false when another build moved its checkout into place first. */
    private boolean moveIntoPlace(Path partial, Path dir) throws IOException {
        for (int attempt = 1;; attempt++) {
            try {
                // Gradle creates an @OutputDirectory before the action runs: an empty one is replaced
                if (Files.isDirectory(dir) && !isNonEmptyDirectory(dir)) {
                    Files.delete(dir);
                }
                Files.move(partial, dir, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
                deleteTree(partial);
                return false;
            } catch (AccessDeniedException e) {
                if (Files.isDirectory(dir.resolve(".git"))) {
                    deleteTree(partial);
                    return false;
                }
                if (attempt == MOVE_ATTEMPTS) {
                    deleteTree(partial);
                    throw new GradleException(
                        "moving " + partial + " to " + dir + " was refused " + MOVE_ATTEMPTS + " times: " + e,
                        e);
                }
                getLogger().info("moving {} to {} was refused ({}); trying again", partial, dir, e.getMessage());
                pause(MOVE_PAUSE_MS);
            }
        }
    }

    /** The outcome of one check: no problem, or the first thing that is not the pin's. */
    private static final class Check {

        String problem;
        int files;
        long bytes;
    }

    private Check check(Path dir) throws IOException {
        Check check = new Check();
        File d = dir.toFile();
        String head;
        String tree;
        String listing;
        // A .git that git cannot read is a checkout that fails the check, like one with the wrong files
        try {
            head = git(d, LOCAL_TIMEOUT_S, false, "rev-parse", "--verify", "HEAD^{commit}").trim();
            tree = git(d, LOCAL_TIMEOUT_S, false, "rev-parse", "HEAD^{tree}").trim();
            listing = git(d, LOCAL_TIMEOUT_S, false, "ls-tree", "-r", "-z", "--full-tree", "HEAD");
        } catch (GitFailure e) {
            check.problem = "git cannot read it: " + e.getMessage();
            return check;
        }
        if (!head.equals(getCommit().get())) {
            check.problem = "HEAD is " + head + ", not the pinned " + getCommit().get();
            return check;
        }
        if (!tree.equals(getTree().get())) {
            check.problem = "HEAD's tree is " + tree + ", not the pinned " + getTree().get();
            return check;
        }
        // "<mode> SP <type> SP <id> TAB <path>", NUL-terminated and unquoted
        Map<String, String> listed = new HashMap<>();
        for (String entry : listing.split("\0")) {
            if (entry.isEmpty()) {
                continue;
            }
            int tab = entry.indexOf('\t');
            String[] meta = entry.substring(0, tab)
                .split(" ");
            if (!"blob".equals(meta[1])) {
                check.problem = "the commit lists a " + meta[1] + " at " + entry.substring(tab + 1);
                return check;
            }
            listed.put(entry.substring(tab + 1), meta[2]);
        }
        if (listed.size() != getFiles().get()) {
            check.problem = "the commit lists " + listed.size() + " files, not " + getFiles().get();
            return check;
        }
        TreeSet<String> missing = new TreeSet<>(listed.keySet());
        TreeSet<String> extra = new TreeSet<>();
        TreeSet<String> differing = new TreeSet<>();
        Path git = dir.resolve(".git");
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                return path.equals(git) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                String name = dir.relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/');
                String id = listed.get(name);
                if (id == null || !attributes.isRegularFile()) {
                    extra.add(name);
                    return FileVisitResult.CONTINUE;
                }
                missing.remove(name);
                check.files++;
                check.bytes += attributes.size();
                if (!id.equals(blobId(path, attributes.size()))) {
                    differing.add(name);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!missing.isEmpty() || !extra.isEmpty() || !differing.isEmpty()) {
            check.problem = describe(missing, "missing") + ", "
                + describe(extra, "not in the commit")
                + ", "
                + describe(differing, "differing");
        }
        return check;
    }

    private static String describe(TreeSet<String> paths, String what) {
        return paths.size() + " " + what + (paths.isEmpty() ? "" : " (first: " + paths.first() + ")");
    }

    /** The id git gives the file's bytes as a blob, read with no filter. */
    private static String blobId(Path path, long size) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM has SHA-1", e);
        }
        digest.update(("blob " + size + "\0").getBytes(StandardCharsets.US_ASCII));
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

    private void writePin(Path dir, Check check, String source, String fetchedAt) throws IOException {
        Path pin = getPinFile().get()
            .getAsFile()
            .toPath();
        Files.createDirectories(pin.getParent());
        try (Writer out = Files.newBufferedWriter(pin, StandardCharsets.UTF_8)) {
            out.write("# " + getLabel().get() + " as " + getName() + " checked it (S10, 05:116)\n");
            out.write("commit=" + getCommit().get() + "\n");
            out.write("tree=" + getTree().get() + "\n");
            out.write("files=" + check.files + "\n");
            out.write("bytes=" + check.bytes + "\n");
            out.write("store=" + escaped(dir.toString()) + "\n");
            out.write("fetchedFrom=" + escaped(source) + "\n");
            out.write("fetchedAt=" + fetchedAt + "\n");
        }
    }

    /** A value as java.util.Properties reads it back: a Windows path's backslashes doubled. */
    private static String escaped(String value) {
        return value.replace("\\", "\\\\");
    }

    /** A git call that failed or ran past its cap: one failed attempt, where the caller retries. */
    private static final class GitFailure extends RuntimeException {

        GitFailure(String message) {
            super(message);
        }
    }

    /**
     * Runs git in {@code dir} and returns its standard output. With {@code mayFail} a non-zero exit returns "" (for
     * {@code git config --get} of an unset key); otherwise it, and a call stopped at its cap, throw {@link GitFailure}.
     */
    private String git(File dir, long timeoutSeconds, boolean mayFail, String... args) {
        List<String> command = new ArrayList<>(
            Arrays.asList(
                "git",
                "--no-optional-locks",
                "-c",
                "credential.helper=",
                "-c",
                "core.askPass=",
                "-C",
                dir.getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> environment = builder.environment();
        CLEARED_ENVIRONMENT.forEach(environment::remove);
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "never");
        String what = "git " + String.join(" ", args);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GradleException(
                "git not found (needed to fetch " + getLabel().get()
                    + " once and to check it on every build); put it on the Gradle daemon's PATH (./gradlew --stop"
                    + " after changing PATH): "
                    + e.getMessage(),
                e);
        }
        Drain out = new Drain(process.getInputStream());
        Drain err = new Drain(process.getErrorStream());
        try {
            process.getOutputStream()
                .close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // The children first (git-remote-https and the like): they outlive a killed git on Windows
                process.descendants()
                    .forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new GitFailure(what + " in " + dir + " ran past " + timeoutSeconds + " s and was stopped");
            }
            String stdout = out.text();
            if (process.exitValue() != 0) {
                if (mayFail) {
                    return "";
                }
                throw new GitFailure(
                    what + " in "
                        + dir
                        + " failed (exit "
                        + process.exitValue()
                        + "): "
                        + err.text()
                            .trim());
            }
            return stdout;
        } catch (IOException e) {
            throw new GitFailure(what + " in " + dir + ": " + e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            process.descendants()
                .forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new GradleException("interrupted while running " + what, e);
        }
    }

    /** Reads one of a process's streams on a thread of its own, so a full pipe never blocks the process. */
    private static final class Drain {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;

        Drain(InputStream in) {
            thread = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try (InputStream stream = in) {
                    for (int n = stream.read(chunk); n >= 0; n = stream.read(chunk)) {
                        synchronized (bytes) {
                            bytes.write(chunk, 0, n);
                        }
                    }
                } catch (IOException e) {
                    // The process was stopped; what was read stays
                }
            }, "opengpu-git-drain");
            thread.setDaemon(true);
            thread.start();
        }

        String text() throws InterruptedException {
            thread.join(10_000);
            synchronized (bytes) {
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static String hex40(String id, String what) {
        if (!id.matches("[0-9a-f]{40}")) {
            throw new GradleException("PinnedGitCheckout needs a full 40-digit " + what + " id, got '" + id + "'");
        }
        return id;
    }

    /** The empty directory Gradle made for the @OutputDirectory, so a build that fetched nothing leaves nothing. */
    private static void deleteIfEmpty(Path dir) throws IOException {
        if (Files.isDirectory(dir) && !isNonEmptyDirectory(dir)) {
            Files.delete(dir);
        }
    }

    private static boolean isNonEmptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny()
                .isPresent();
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new GradleException("interrupted while waiting to retry", e);
        }
    }

    /**
     * Deletes a partial checkout. Git writes its pack files read-only, which Windows refuses to delete, so each file is
     * made writable first.
     */
    private void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                path.toFile()
                    .setWritable(true);
                Files.delete(path);
            }
        } catch (IOException e) {
            getLogger().warn("[opengpu-ocelot] could not delete {}: {}; delete it by hand", root, e.toString());
        }
    }
}
