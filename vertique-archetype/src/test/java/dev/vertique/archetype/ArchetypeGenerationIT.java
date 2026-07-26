// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        // Given the only four caller-supplied values and a fake Maven executable that captures its
        // invocation instead of resolving an unreleased archetype from a remote repository.
        Path generator = Path.of("bin", "new-vertique-app").toAbsolutePath();
        Path fakeMavenDirectory = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "fake-maven-bin-");
        Path fakeMaven = fakeMavenDirectory.resolve("mvn");
        Path capturedArguments = fixtureDirectory.resolve("maven-arguments.txt");
        Files.writeString(fakeMaven, "#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$MVN_ARGUMENTS_FILE\"\n");
        assertTrue(fakeMaven.toFile().setExecutable(true), "fake Maven executable must be executable");

        // When the launcher runs with closed standard input, it must neither prompt for nor require
        // a fifth application choice.
        String path = fakeMavenDirectory + ":" + System.getenv("PATH");
        ProcessBuilder launcher = new ProcessBuilder(
                        "/bin/sh",
                        "-c",
                        "PATH=$1; export PATH; shift; exec /bin/sh \"$@\"",
                        "launcher",
                        path,
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
                .redirectErrorStream(true);
        launcher.environment().put("MVN_ARGUMENTS_FILE", capturedArguments.toString());

        Process process = launcher.start();
        process.getOutputStream().close();

        boolean completed = process.waitFor(25, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
        }
        String launcherOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // Then the launcher makes exactly one noninteractive archetype invocation with its fixed
        // generated-project version and the supplied Vertique version.
        assertTrue(completed, "generator must complete without requesting additional input");
        assertTrue(
                Files.isRegularFile(capturedArguments), () -> "launcher did not invoke fake Maven:\n" + launcherOutput);
        assertEquals(0, process.exitValue(), () -> "generator failed:\n" + launcherOutput);
        assertEquals(
                List.of(
                        "-B",
                        "archetype:generate",
                        "-DarchetypeGroupId=dev.vertique",
                        "-DarchetypeArtifactId=vertique-archetype",
                        "-DarchetypeVersion=" + VERTIQUE_VERSION,
                        "-DgroupId=" + GROUP_ID,
                        "-DartifactId=" + ARTIFACT_ID,
                        "-Dpackage=" + PACKAGE_NAME,
                        "-Dversion=0.1.0-SNAPSHOT",
                        "-DvertiqueVersion=" + VERTIQUE_VERSION,
                        "-DoutputDirectory=."),
                Files.readAllLines(capturedArguments),
                "launcher must pass exactly the four supplied values and its fixed project version");
    }
}
