// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Structural policy tests for the open-core example Maven modules. */
class ExamplePomCodegenPolicyTest {

    private static final Path EXAMPLES_ROOT =
            Path.of("..", "..", "examples").toAbsolutePath().normalize();
    private static final Pattern SHARED_PARENT = Pattern.compile("(?s)<parent>\\s*"
            + "<groupId>dev\\.vertique</groupId>\\s*"
            + "<artifactId>vertique-example-parent</artifactId>\\s*"
            + "<version>\\$\\{revision}</version>\\s*"
            + "<relativePath>\\.\\./vertique-example-parent/pom\\.xml</relativePath>\\s*"
            + "</parent>");
    private static final Pattern PROCESSOR_DEPENDENCY = Pattern.compile(
            "(?s)<dependency>\\s*.*?<artifactId>vertique-codegen-(?!core</artifactId>)[^<]+</artifactId>"
                    + ".*?</dependency>");
    private static final List<String> EXAMPLES = List.of(
            "vertique-example-aop",
            "vertique-example-custom-response",
            "vertique-example-db",
            "vertique-example-events",
            "vertique-example-hello",
            "vertique-example-localization",
            "vertique-example-rest-client",
            "vertique-example-services-codegen",
            "vertique-example-services",
            "vertique-example-sse",
            "vertique-example-webhook",
            "vertique-example-websocket",
            "vertique-example-workflow-order-fulfillment");

    @Test
    void examplesUseSharedParentWithoutProcessorDeclarations() throws IOException {
        List<String> violations = new ArrayList<>();

        for (String example : EXAMPLES) {
            Path pom = EXAMPLES_ROOT.resolve(example).resolve("pom.xml");
            assertTrue(Files.isRegularFile(pom), () -> "Missing example POM " + pom);
            String xml = Files.readString(pom);

            if (!SHARED_PARENT.matcher(xml).find()) {
                violations.add(example + ": does not inherit vertique-example-parent");
            }
            if (PROCESSOR_DEPENDENCY.matcher(xml).find()) {
                violations.add(example + ": declares a Vertique processor project dependency");
            }
            if (xml.contains("<annotationProcessorPaths")) {
                violations.add(example + ": declares annotationProcessorPaths");
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Examples must inherit the shared codegen parent without processor boilerplate:\n"
                        + String.join("\n", violations));
    }
}
