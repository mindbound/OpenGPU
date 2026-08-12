import com.gtnewhorizons.angelica.glsm.CompatShaderTransformer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Drives Angelica's real CompatShaderTransformer over OpenGPU's four dry-run shaders.
 *
 * Standalone rather than a JUnit test because Angelica's own test discovery is broken in this
 * clone (NoClassDefFoundError: DisplayListIDAllocator, 12 discovery errors) and aborts before
 * any test runs.
 *
 * transform() NEVER THROWS: on AST failure it catches, logs, and falls back to fixupVersion(),
 * which only rewrites the #version line. So the discriminating signal is whether the
 * fixed-function built-ins are GONE -- surviving gl_TexCoord/gl_Color under a core #version
 * would not compile, which is the silent failure worth detecting.
 */
public class RunTransform {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        String[] programs = { "plasma", "blur", "dissolve", "domains" };
        StringBuilder report = new StringBuilder();

        for (String name : programs) {
            Path in = dir.resolve("dryrun_" + name + ".frag");
            String src = new String(Files.readAllBytes(in), StandardCharsets.UTF_8);

            boolean isCore = CompatShaderTransformer.isCoreShader(src);
            String out;
            try {
                out = CompatShaderTransformer.transform(src, true);
            } catch (Throwable t) {
                report.append(String.format("%-9s THREW %s: %s%n", name,
                    t.getClass().getSimpleName(), String.valueOf(t.getMessage())));
                continue;
            }
            Files.write(dir.resolve("angelica_" + name + ".frag"),
                out.getBytes(StandardCharsets.UTF_8));

            String version = "(none)";
            for (String line : out.split("\n")) {
                if (line.trim().startsWith("#version")) { version = line.trim(); break; }
            }
            boolean srcTex = src.contains("gl_TexCoord"), outTex = out.contains("gl_TexCoord");
            boolean srcCol = src.contains("gl_Color"),    outCol = out.contains("gl_Color");
            boolean outFrag = out.contains("gl_FragColor");

            report.append(String.format(
                "%-9s isCore=%-5s out:%-18s texCoord %s->%s  color %s->%s  gl_FragColor=%-5s  %d->%d chars%n",
                name, isCore, version, srcTex, outTex, srcCol, outCol, outFrag, src.length(), out.length()));
            if (srcTex && outTex) report.append("    *** gl_TexCoord SURVIVED — AST path did not run\n");
            if (srcCol && outCol) report.append("    *** gl_Color SURVIVED — AST path did not run\n");
        }

        Files.write(dir.resolve("angelica_transform_report.txt"),
            report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }
}
