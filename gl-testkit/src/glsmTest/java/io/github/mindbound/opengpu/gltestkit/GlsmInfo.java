package io.github.mindbound.opengpu.gltestkit;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.gtnewhorizons.angelica.config.SystemProperties;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;

/**
 * The GLSM a GLSM-leg test ran under, recorded with every run next to {@link GlInfo}'s context (the GLSM spike, roadmap
 * M0): which jars, and what GLSM made of the context.
 */
public final class GlsmInfo {

    /** The Angelica release the Test task pinned. */
    public final String angelica;
    public final String jar;
    public final String jarSha1;
    public final String gtnhlibJar;
    public final String jvmdgJar;
    /** The thread that ran GLStateManager's static initializer, which GLSM keeps as its main thread. */
    public final String mainThread;
    public final boolean coreProfile;
    /** GL_VERSION as GLSM parsed it. */
    public final String gl;
    public final String vendor;
    public final boolean dsaRequested;
    /** The DSA implementation GLSM chose, by class name. */
    public final String dsa;
    public final boolean splashComplete;
    public final boolean caching;
    public final String unmappedGL;
    public final long initMillis;

    // Reads GLSM's state; call it on the GL thread, after GLStateManager.initialize
    private GlsmInfo(long initNanos) throws Exception {
        angelica = String.valueOf(System.getProperty("opengpu.glsm.angelica"));
        File angelicaJar = jarOf(GLStateManager.class);
        jar = angelicaJar.getName();
        jarSha1 = sha1(angelicaJar);
        gtnhlibJar = jarName("com.gtnewhorizon.gtnhlib.asm.ClassConstantPoolParser");
        jvmdgJar = jarName("xyz.wagyourtail.jvmdg.j9.stub.java_base.J_L_Runtime");
        mainThread = GLStateManager.getMainThread()
            .getName();
        coreProfile = RenderSystem.isCoreProfile();
        gl = RenderSystem.getGlMajor() + "." + RenderSystem.getGlMinor();
        vendor = String.valueOf(GLStateManager.getVENDOR());
        dsaRequested = GLStateManager.getInitConfig()
            .isDSAEnabled();
        dsa = dsaImplementation();
        splashComplete = GLStateManager.isSplashComplete();
        caching = GLStateManager.isCachingEnabled();
        unmappedGL = String.valueOf(SystemProperties.UNMAPPED_GL);
        initMillis = initNanos / 1_000_000L;
    }

    static GlsmInfo query(long initNanos) throws Exception {
        return new GlsmInfo(initNanos);
    }

    /** The record as ordered key=value pairs: the log line, glsm.txt and the test report's entries. */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("glsm.angelica", angelica);
        map.put("glsm.jar", jar);
        map.put("glsm.jarSha1", jarSha1);
        map.put("glsm.gtnhlib", gtnhlibJar);
        map.put("glsm.jvmdg", jvmdgJar);
        map.put("glsm.mainThread", mainThread);
        map.put("glsm.coreProfile", String.valueOf(coreProfile));
        map.put("glsm.gl", gl);
        map.put("glsm.vendor", vendor);
        map.put("glsm.dsaRequested", String.valueOf(dsaRequested));
        map.put("glsm.dsa", dsa);
        map.put("glsm.splashComplete", String.valueOf(splashComplete));
        map.put("glsm.caching", String.valueOf(caching));
        map.put("glsm.unmappedGL", unmappedGL);
        map.put("glsm.initMillis", String.valueOf(initMillis));
        return Collections.unmodifiableMap(map);
    }

    private static File jarOf(Class<?> type) throws Exception {
        return new File(
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    // For the record only: "-" when the class or its jar cannot be found
    private static String jarName(String className) {
        try {
            return jarOf(Class.forName(className, false, GlsmInfo.class.getClassLoader())).getName();
        } catch (Exception | LinkageError e) {
            return "-";
        }
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] chunk = new byte[65536];
            for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                digest.update(chunk, 0, n);
            }
        }
        return String.format("%040x", new BigInteger(1, digest.digest()));
    }

    // RenderSystem keeps its choice in a private field and has no getter (RenderSystem.java:42 at 2.2.8)
    private static String dsaImplementation() {
        try {
            Field field = RenderSystem.class.getDeclaredField("dsaState");
            field.setAccessible(true);
            Object state = field.get(null);
            return state == null ? "-"
                : state.getClass()
                    .getSimpleName();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "unknown (" + e + ")";
        }
    }
}
