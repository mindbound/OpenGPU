import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;
import org.w3c.dom.Element;

/**
 * Fails when a Test task wrote no TEST-*.xml or ran no test, and, unless skips are allowed, when it skipped one. A Test
 * task whose sources are gone goes NO-SOURCE, which Gradle's failOnNoDiscoveredTests does not catch; Angelica guards
 * its root test and its GL tasks the same way (build.gradle.kts, glsm/build.gradle.kts:286-304).
 */
public abstract class VerifyTestsRan extends DefaultTask {

    /** Registers verify&lt;Task&gt;Ran for {@code test} and puts both into check. */
    public static TaskProvider<VerifyTestsRan> register(Project project, TaskProvider<Test> test,
        boolean allowSkipped) {
        String name = test.getName();
        TaskProvider<VerifyTestsRan> verify = project.getTasks()
            .register(
                "verify" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Ran",
                VerifyTestsRan.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription(
                        "Fails when " + name + " ran no test" + (allowSkipped ? "." : " or skipped one."));
                    task.dependsOn(test);
                    task.getTestTaskName()
                        .set(name);
                    // The value, not the report's property: the configuration cache refuses an output property
                    // that no task produces
                    task.getResults()
                        .set(
                            test.get()
                                .getReports()
                                .getJunitXml()
                                .getOutputLocation()
                                .get());
                    task.getAllowSkipped()
                        .set(allowSkipped);
                });
        project.getTasks()
            .named("check")
            .configure(check -> check.dependsOn(test, verify));
        return verify;
    }

    @Input
    public abstract Property<String> getTestTaskName();

    /** Read by the action itself: a missing directory is one of the failures it reports. */
    @Internal
    public abstract DirectoryProperty getResults();

    @Input
    public abstract Property<Boolean> getAllowSkipped();

    @TaskAction
    public void verify() throws Exception {
        String name = getTestTaskName().get();
        File dir = getResults().get()
            .getAsFile();
        File[] xmls = dir.listFiles((d, f) -> f.startsWith("TEST-") && f.endsWith(".xml"));
        if (xmls == null || xmls.length == 0) {
            throw new GradleException(name + " wrote no TEST-*.xml to " + dir + ": it went NO-SOURCE or never ran");
        }
        int tests = 0;
        int skipped = 0;
        for (File xml : xmls) {
            Element suite = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(xml)
                .getDocumentElement();
            tests += Integer.parseInt(suite.getAttribute("tests"));
            skipped += Integer.parseInt(suite.getAttribute("skipped"));
        }
        if (tests == 0) {
            throw new GradleException(name + " ran no test");
        }
        if (skipped > 0 && !getAllowSkipped().get()) {
            throw new GradleException(name + " skipped " + skipped + " of " + tests + " tests; these run, never skip");
        }
    }
}
