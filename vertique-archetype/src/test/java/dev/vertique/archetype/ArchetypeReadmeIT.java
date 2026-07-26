// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.archetype;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Integration proof for the generated application's documented commands. */
class ArchetypeReadmeIT {

    private static final Path GENERATED_README =
            Path.of("src", "main", "resources", "archetype-resources", "README.md");
    private static final Pattern COMMAND_BLOCK = Pattern.compile("```(?:bash|sh|shell)?\\R(.*?)```", Pattern.DOTALL);
    private static final Pattern LINE_CONTINUATION = Pattern.compile("\\\\\\R\\s*");

    @Test
    void documentsSupportedCommands() throws IOException {
        // Given the generated application's README.
        String readme = Files.readString(GENERATED_README);

        // When its shell command blocks are parsed.
        List<String> commands = COMMAND_BLOCK
                .matcher(readme)
                .results()
                .map(ArchetypeReadmeIT::commandFrom)
                .toList();

        // Then it documents exactly the supported generation, run, verification, package, and
        // container-image commands.
        assertEquals(
                List.of(
                        "bin/new-vertique-app --group-id <groupId> --artifact-id <artifactId> --package "
                                + "<packageName> --vertique-version <vertiqueVersion>",
                        "mvn -ntp exec:java",
                        "mvn -ntp verify",
                        "mvn -ntp package",
                        "mvn -ntp jib:dockerBuild"),
                commands);
    }

    private static String commandFrom(MatchResult commandBlock) {
        String contents = commandBlock.group(1);
        return LINE_CONTINUATION.matcher(contents).replaceAll(" ").trim();
    }
}
