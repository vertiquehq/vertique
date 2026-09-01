// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural proof (T013, TP-003) that the prototype {@code GreetingLimit*} rate-limit scaffolding
 * — retired in favor of the real {@code RateLimitExceededException}/T010's {@code
 * RestRateLimitModule} pipeline — is fully gone: no compiled class remains for any of the three
 * retired simple names, and {@code HelloResource.java} carries no source-text reference to any of
 * them.
 */
class HelloExampleStructureTest {

    /**
     * The real retired set, verified against {@code sources/vertique} — exactly three files; there
     * is no fourth {@code GreetingLimitException.java}.
     */
    private static final List<String> RETIRED_SIMPLE_NAMES =
            List.of("GreetingLimitExceededException", "GreetingLimitExceptionMapper", "GreetingLimitProblemDetail");

    private static final Path RESOURCE_PACKAGE_CLASSES = Path.of("target/classes/dev/vertique/examples/hello/resource");

    private static final Path HELLO_RESOURCE_SOURCE =
            Path.of("src/main/java/dev/vertique/examples/hello/resource/HelloResource.java");

    @Test
    void shouldNotContainPrototypeGreetingLimitClasses() {
        for (String simpleName : RETIRED_SIMPLE_NAMES) {
            Path classFile = RESOURCE_PACKAGE_CLASSES.resolve(simpleName + ".class");
            assertFalse(Files.exists(classFile), "compiled class must not exist: " + classFile);
        }

        String helloResourceSource = readSource(HELLO_RESOURCE_SOURCE);
        for (String simpleName : RETIRED_SIMPLE_NAMES) {
            assertFalse(
                    helloResourceSource.contains(simpleName),
                    "HelloResource.java must not reference retired class " + simpleName);
        }
    }

    private static String readSource(Path path) {
        assertTrue(Files.exists(path), "expected source file to exist: " + path);
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
