import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * Angelica's latest release for gl-testkit's latest linkage leg (M0 step S9): the {@code <release>} of Nexus's
 * maven-metadata.xml, read at build time (24:199, 25:534), as "&lt;version&gt; &lt;source&gt;":
 * <ol>
 * <li>-Popengpu.angelica.latest=&lt;v&gt; gives "&lt;v&gt; property", online or not, but fails the build on CI, where
 * the latest leg must be Nexus's {@code <release>} read at build time (S9 review TS3);</li>
 * <li>online, one GET, up to three attempts 2 s and 5 s apart, gives "&lt;v&gt; nexus", which glsmLinkageTest's
 * {@link Remember} then remembers in {@link Params#getRemembered()}; when every attempt fails the build fails,
 * everywhere, since running it again is cheap (the owner's Q-S9-2);</li>
 * <li>with --offline, the remembered release gives "&lt;v&gt; remembered@&lt;its fetchedAt&gt;", and none fails the
 * build.</li>
 * </ol>
 * Gradle obtains it only when it resolves the latest leg's jar, so only builds with glsmLinkageTest in their task graph
 * read Nexus: assemble, runClient and the release workflow's {@code assemble publish -x test} never do. The value stays
 * the same while nothing changes, so the configuration cache's own check of it, the same GET, keeps an entry valid
 * until a new release comes out.
 */
public abstract class NexusRelease implements ValueSource<String, NexusRelease.Params> {

    private static final Logger LOGGER = Logging.getLogger(NexusRelease.class);
    /** A release number, a -pre or other suffix included: {@code <release>} is used as Nexus gives it (24:199). */
    private static final Pattern VERSION = Pattern.compile("[0-9]+(\\.[0-9]+)+([-+][0-9A-Za-z.-]+)?");
    /** The pauses before the second and the third attempt. */
    private static final long[] PAUSES_MS = { 2_000L, 5_000L };
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    /** maven-metadata.xml is about 7 kB; anything far larger is no metadata. */
    private static final int MAX_BYTES = 1 << 20;

    public interface Params extends ValueSourceParameters {

        Property<String> getMetadataUrl();

        /** Gradle's --offline. */
        Property<Boolean> getOffline();

        /** -Popengpu.angelica.latest, unset by default. */
        Property<String> getForced();

        /** Whether the build runs on CI (GITHUB_ACTIONS=true), where {@link #getForced()} fails the build. */
        Property<Boolean> getCi();

        /**
         * The last release read online, with when and from where: in the Gradle user home, beside the jar cache, so
         * every working copy on the machine shares it. {@link Remember} writes it once glsmLinkageTest's inputs hold
         * the release's jar, so it names a release whose jar that home fetched (S9 plan P4.2). Gradle's cache clean-up
         * can still delete a jar unused for 30 days; --offline then finds no cached version of it until one online run
         * of glsmLinkageTest fetches it again.
         */
        RegularFileProperty getRemembered();
    }

    /**
     * glsmLinkageTest's first action: remembers the latest release when it was read online. It runs after Gradle has
     * resolved the task's inputs, the release's jar among them, so a build that stops before the task or fails to
     * fetch the jar remembers nothing (S9 review B1).
     */
    public static final class Remember implements Action<Task> {

        private final Provider<String> latest;
        private final File file;
        private final String url;

        public Remember(Provider<String> latest, File file, String url) {
            this.latest = latest;
            this.file = file;
            this.url = url;
        }

        @Override
        public void execute(Task task) {
            String spec = latest.get();
            if (spec.endsWith(" nexus")) {
                remember(file, spec.substring(0, spec.indexOf(' ')), url);
            }
        }
    }

    @Override
    public String obtain() {
        Params parameters = getParameters();
        File remembered = parameters.getRemembered()
            .get()
            .getAsFile();
        String forced = parameters.getForced()
            .getOrNull();
        if (forced != null) {
            if (parameters.getCi()
                .get()) {
                throw new GradleException(
                    "-Popengpu.angelica.latest=" + forced
                        + " is set on CI, where the latest linkage leg must be Nexus's <release> read at build time"
                        + " (24:199): remove it from the command line, gradle.properties or ORG_GRADLE_PROJECT_*");
            }
            String release = checked(forced, "-Popengpu.angelica.latest");
            LOGGER.lifecycle("Angelica latest: " + release + ", forced by -Popengpu.angelica.latest");
            return release + " property";
        }
        if (parameters.getOffline()
            .get()) {
            Properties last = read(remembered);
            if (last == null) {
                throw new GradleException(
                    "--offline: no Angelica <release> remembered in " + remembered
                        + "; run :gl-testkit:glsmLinkageTest once online, or pass -Popengpu.angelica.latest=<v>");
            }
            String release = checked(last.getProperty("release"), remembered.toString());
            String fetchedAt = last.getProperty("fetchedAt", "?");
            LOGGER.lifecycle(
                "Angelica latest: " + release + ", Nexus <release> remembered from an online read at " + fetchedAt);
            return release + " remembered@" + fetchedAt;
        }
        String url = parameters.getMetadataUrl()
            .get();
        String release = fetch(url);
        LOGGER.lifecycle("Angelica latest: " + release + ", Nexus <release> read online from " + url);
        return release + " nexus";
    }

    private static String fetch(String url) {
        DocumentBuilder parser = parser();
        IOException last = null;
        int attempts = PAUSES_MS.length + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) {
                pause(PAUSES_MS[attempt - 2]);
            }
            try {
                return release(parser, get(url), url);
            } catch (IOException e) {
                last = e;
                LOGGER.warn("Angelica latest: attempt " + attempt + " of " + attempts + " to read " + url + ": " + e);
            }
        }
        throw new GradleException(
            "Nexus's maven-metadata.xml for Angelica could not be read in " + attempts
                + " attempts ("
                + url
                + "); the build needs its <release> for the latest linkage leg, so run it again; locally, --offline"
                + " takes the remembered release and -Popengpu.angelica.latest=<v> sets one",
            last);
    }

    /** A parser with DOCTYPE disallowed; one that cannot be set up fails the build at once, as no retry mends it. */
    private static DocumentBuilder parser() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new GradleException("cannot set up a DOCTYPE-free XML parser for Nexus's maven-metadata.xml", e);
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url)
            .toURL()
            .openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status + " " + connection.getResponseMessage());
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                    out.write(chunk, 0, n);
                    if (out.size() > MAX_BYTES) {
                        throw new IOException("more than " + MAX_BYTES + " bytes");
                    }
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** /metadata/versioning/release; a document that does not parse counts as a failed attempt. */
    private static String release(DocumentBuilder parser, byte[] metadata, String url) throws IOException {
        Element root;
        try {
            root = parser.parse(new ByteArrayInputStream(metadata))
                .getDocumentElement();
        } catch (SAXException e) {
            throw new IOException("no XML document: " + e.getMessage(), e);
        }
        Element release = "metadata".equals(root.getTagName()) ? child(child(root, "versioning"), "release") : null;
        if (release == null) {
            throw new GradleException(url + " has no /metadata/versioning/release");
        }
        return checked(
            release.getTextContent()
                .trim(),
            url);
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && name.equals(((Element) node).getTagName())) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String checked(String release, String from) {
        if (release == null || !VERSION.matcher(release)
            .matches()) {
            throw new GradleException(from + " gives the Angelica release \"" + release + "\", which is no version");
        }
        return release;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new GradleException("interrupted while reading Angelica's <release>", e);
        }
    }

    /**
     * Writes the release to a temporary file beside {@code file}, then moves it over, so a concurrent build reads the
     * old record or the new one. A failure only warns: --offline builds then report the missing record themselves.
     */
    private static void remember(File file, String release, String url) {
        Properties record = new Properties();
        record.setProperty("release", release);
        record.setProperty(
            "fetchedAt",
            Instant.now()
                .truncatedTo(ChronoUnit.SECONDS)
                .toString());
        record.setProperty("url", url);
        Path target = file.toPath();
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), file.getName(), ".tmp");
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                record.store(out, "Angelica's latest release as Nexus last gave it, for --offline builds (OpenGPU S9)");
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Angelica latest: could not remember " + release + " in " + file + ": " + e);
            if (temp != null) {
                temp.toFile()
                    .delete();
            }
        }
    }

    private static Properties read(File file) {
        if (!file.isFile()) {
            return null;
        }
        Properties record = new Properties();
        try (Reader in = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            record.load(in);
        } catch (IOException e) {
            throw new GradleException("cannot read the remembered Angelica release in " + file, e);
        }
        return record;
    }
}
