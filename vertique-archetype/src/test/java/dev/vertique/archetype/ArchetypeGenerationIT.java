// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Integration proof for the four-value minimal application generator contract. */
class ArchetypeGenerationIT {

    private static final String GROUP_ID = "com.example";
    private static final String ARTIFACT_ID = "minimal-app";
    private static final String PACKAGE_NAME = "com.example.minimalapp";
    private static final String VERTIQUE_VERSION = "0.1.0";

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void generatesMinimalApp(@TempDir Path fixtureDirectory) throws IOException, InterruptedException {
        // Given a fixture directory and the only four values a caller supplies.
        Path generator = Path.of("bin", "new-vertique-app").toAbsolutePath();

        // When the generator is invoked with closed standard input, it must neither prompt for nor
        // require any additional values.
        Process process = new ProcessBuilder(
                        generator.toString(),
                        "--group-id",
                        GROUP_ID,
                        "--artifact-id",
                        ARTIFACT_ID,
                        "--package",
                        PACKAGE_NAME,
                        "--vertique-version",
                        VERTIQUE_VERSION)
                .directory(fixtureDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();

        boolean completed = process.waitFor(Duration.ofSeconds(25));
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Then an application exists with each supplied value substituted into its templates.
        assertTrue(completed, "generator must complete without requesting additional input");
        assertEquals(0, process.exitValue(), () -> "generator failed:%n" + output);

        Path generatedProject = fixtureDirectory.resolve(ARTIFACT_ID);
        assertTrue(Files.isDirectory(generatedProject), "generator must create the artifact directory");
        assertContains(generatedProject.resolve("pom.xml"), "<groupId>" + GROUP_ID + "</groupId>");
        assertContains(generatedProject.resolve("pom.xml"), "<artifactId>" + ARTIFACT_ID + "</artifactId>");
        assertContains(generatedProject.resolve("pom.xml"), "<version>0.1.0-SNAPSHOT</version>");
        assertBomVersion(generatedProject.resolve("pom.xml"));
        assertContains(
                generatedProject.resolve("src/main/java/com/example/minimalapp/AppComponent.java"),
                "package " + PACKAGE_NAME + ";");

        try (Stream<Path> files = Files.walk(generatedProject)) {
            List<Path> renderedFiles = files.filter(Files::isRegularFile).toList();
            assertFalse(renderedFiles.isEmpty(), "generator must render project files");
            for (Path renderedFile : renderedFiles) {
                String rendered = Files.readString(renderedFile);
                assertFalse(rendered.contains("${groupId}"), () -> unresolvedToken(renderedFile, "${groupId}"));
                assertFalse(rendered.contains("${artifactId}"), () -> unresolvedToken(renderedFile, "${artifactId}"));
                assertFalse(rendered.contains("${package}"), () -> unresolvedToken(renderedFile, "${package}"));
                assertFalse(
                        rendered.contains("${vertiqueVersion}"),
                        () -> unresolvedToken(renderedFile, "${vertiqueVersion}"));
            }
        }
    }

    private static void assertContains(Path file, String expected) throws IOException {
        assertTrue(Files.isRegularFile(file), () -> "expected generated file: " + file);
        assertTrue(
                Files.readString(file).contains(expected),
                () -> "expected generated file " + file + " to contain: " + expected);
    }

    private static void assertBomVersion(Path pom) throws IOException {
        String pomContents = Files.readString(pom);
        int bomArtifact = pomContents.indexOf("<artifactId>vertique-bom</artifactId>");
        assertTrue(bomArtifact >= 0, "generated POM must import vertique-bom");

        int dependencyStart = pomContents.lastIndexOf("<dependency>", bomArtifact);
        int dependencyEnd = pomContents.indexOf("</dependency>", bomArtifact);
        assertTrue(dependencyStart >= 0 && dependencyEnd >= 0, "generated POM must delimit the BOM dependency");

        String bomDependency = pomContents.substring(dependencyStart, dependencyEnd);
        assertTrue(
                bomDependency.contains("<groupId>dev.vertique</groupId>"),
                "generated POM must import the Vertique BOM");
        assertTrue(
                bomDependency.contains("<version>" + VERTIQUE_VERSION + "</version>"),
                "generated POM must use vertiqueVersion for its BOM import");
    }

    private static String unresolvedToken(Path renderedFile, String token) {
        return "generator left " + token + " unresolved in " + renderedFile;
    }
}
