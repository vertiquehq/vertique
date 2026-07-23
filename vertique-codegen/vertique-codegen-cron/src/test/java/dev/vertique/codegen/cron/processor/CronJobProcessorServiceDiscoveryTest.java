// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.cron.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code META-INF/services/javax.annotation.processing.Processor} registration
 * actually points at {@link CronJobProcessor} and that the registered class is loadable as a
 * {@link Processor}.
 *
 * <p>The other tests in this module instantiate {@code new CronJobProcessor()} directly, which
 * does not exercise the consumer's actual integration path (annotation processor jar on
 * {@code <annotationProcessorPaths>} → {@link java.util.ServiceLoader} → processor instantiated
 * by javac). A typo'd FQN, a missing service file, or a refactor-renamed processor class would
 * slip past those tests; this one catches those failures.
 *
 * <p>{@code compile-testing}'s {@code Compiler.javac()} disables {@code ServiceLoader} discovery
 * when {@code withProcessors(...)} is not called, so the discovery path itself cannot be
 * exercised inside this harness. The strongest contract this test can pin without spawning a
 * separate javac process is: the service file resolves to a real, instantiable {@link Processor}
 * implementation.
 */
class CronJobProcessorServiceDiscoveryTest {

    private static final String SERVICE_FILE = "META-INF/services/javax.annotation.processing.Processor";

    @Test
    @DisplayName("META-INF service file lists CronJobProcessor and the class is a loadable Processor")
    void metaInfServiceFile_resolvesToLoadableProcessor() throws Exception {
        List<String> entries = readServiceFile();

        assertFalse(entries.isEmpty(), "Service file " + SERVICE_FILE + " must list at least one processor");
        assertTrue(
                entries.contains(CronJobProcessor.class.getName()),
                "Service file " + SERVICE_FILE + " must list CronJobProcessor; got: " + entries);

        for (String fqn : entries) {
            Class<?> clazz = Class.forName(fqn, true, getClass().getClassLoader());
            assertTrue(
                    Processor.class.isAssignableFrom(clazz),
                    "Service-listed class " + fqn + " must implement " + Processor.class.getName());
            Processor instance = (Processor) clazz.getDeclaredConstructor().newInstance();
            assertFalse(
                    instance.getSupportedAnnotationTypes().isEmpty(),
                    "Service-listed processor " + fqn + " must declare supported annotations");
        }
    }

    private List<String> readServiceFile() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SERVICE_FILE)) {
            assertNotNull(in, "Classpath resource " + SERVICE_FILE + " must exist");
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
        }
    }
}
