import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * OC-LuaJIT's watchdog kernel for the local ocelot leg (M0 step S10; S10 decisions D6), made as OC-LuaJIT's
 * smoke-test.sh:560-610 makes it for its additive arm: the checkout's own luajit runs its kernel patcher,
 * native/kernel/patch-machine-lua.lua, on brain's machine.lua, into {@link #getResources()} at
 * {@value #KERNEL}, the path OcljArch's architecture swaps in after OC's kernel has loaded (OcljArch.scala:108, :570).
 * OC-LuaJIT's shipped kernel, build/native/kernel/machine.lua, patched from GTNH OC's, is not used.
 * <ul>
 * <li>Both kernel paths, that one and OC's own {@value #OC_KERNEL}, are cleared first: a kernel left at OC's path would
 * shadow brain's for every architecture, and the leg would pass while OcljArch's own swap did nothing. Afterwards OC's
 * path must still be empty.</li>
 * <li>A Windows executable on Linux is refused by its first two bytes, MZ, before it runs: WSL runs it through interop,
 * and it cannot open the /mnt/c paths it is given.</li>
 * <li>luajit runs in this task's temporary directory, with LUA_INIT, LUA_PATH and LUA_CPATH removed and a wall-time
 * cap; it writes only the output kernel, and nothing in the OC-LuaJIT checkout.</li>
 * </ul>
 * ocluajitInputs has checked the patcher and luajit against the floor before this task runs.
 */
public abstract class OcLuajitKernel extends DefaultTask {

    /** OcljArch's resource domain, which no OC architecture loads (OcljArch.scala:568-570). */
    public static final String KERNEL = "assets/ocluajit/lua/machine.lua";
    /** OC's own kernel path, which brain's jar serves. */
    public static final String OC_KERNEL = "assets/opencomputers/lua/machine.lua";

    private static final long TIMEOUT_S = 60;
    /** LuaJIT runs LUA_INIT's code at start and searches LUA_PATH and LUA_CPATH: none of the caller's reaches it. */
    private static final List<String> CLEARED_ENVIRONMENT = Arrays
        .asList("LUA_INIT", "LUA_INIT_5_1", "LUA_PATH", "LUA_PATH_5_1", "LUA_CPATH", "LUA_CPATH_5_1");

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLuajitExecutable();

    /** OC-LuaJIT's native/kernel/patch-machine-lua.lua. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPatcher();

    /** Brain's src/main/resources/assets/opencomputers/lua/machine.lua, from the checked store. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMachineLua();

    /** A classpath root of the leg's test JVM, ahead of the test classpath. */
    @OutputDirectory
    public abstract DirectoryProperty getResources();

    @TaskAction
    public void run() throws IOException {
        Path resources = getResources().get()
            .getAsFile()
            .toPath();
        Path kernel = resources.resolve(KERNEL);
        Path ocKernel = resources.resolve(OC_KERNEL);
        Files.deleteIfExists(kernel);
        Files.deleteIfExists(ocKernel);
        File luajit = getLuajitExecutable().get()
            .getAsFile();
        boolean windows = System.getProperty("os.name")
            .startsWith("Windows");
        if (!windows && windowsExecutable(luajit.toPath())) {
            throw new GradleException(
                luajit + " is a Windows executable; on Linux the kernel patcher needs the luajit that"
                    + " native/build-native.sh builds there, build/native/luajit-<platform>/src/luajit"
                    + " (-Popengpu.ocluajit.luajit= names another)");
        }
        Files.createDirectories(kernel.getParent());
        List<String> command = Arrays.asList(
            luajit.getAbsolutePath(),
            getPatcher().get()
                .getAsFile()
                .getAbsolutePath(),
            getMachineLua().get()
                .getAsFile()
                .getAbsolutePath(),
            kernel.toString());
        ProcessRun.Result result = ProcessRun.run(
            "the kernel patcher",
            command,
            getTemporaryDir(),
            CLEARED_ENVIRONMENT,
            Collections.emptyMap(),
            TIMEOUT_S);
        if (result.exit != 0) {
            throw new GradleException(
                "the kernel patcher failed on ocelot-brain's machine.lua (exit " + result.exit
                    + "): an anchor no longer matches, or "
                    + luajit
                    + " does not run on this host: "
                    + (result.err + result.out).trim());
        }
        if (!Files.isRegularFile(kernel) || Files.size(kernel) == 0) {
            throw new GradleException("the kernel patcher exited 0 but wrote no kernel at " + kernel);
        }
        if (Files.exists(ocKernel)) {
            throw new GradleException(
                ocKernel + " exists after the patch: it would shadow brain's own kernel on the leg's classpath");
        }
        getLogger().lifecycle(
            "[opengpu-ocelot] OC-LuaJIT kernel {}: {} B, by {}: {}",
            kernel,
            Files.size(kernel),
            luajit,
            result.out.trim());
    }

    /** A PE file starts with the DOS header's MZ. */
    private static boolean windowsExecutable(Path file) throws IOException {
        byte[] start = new byte[2];
        try (InputStream in = Files.newInputStream(file)) {
            return in.read(start) == 2 && start[0] == 'M' && start[1] == 'Z';
        }
    }
}
