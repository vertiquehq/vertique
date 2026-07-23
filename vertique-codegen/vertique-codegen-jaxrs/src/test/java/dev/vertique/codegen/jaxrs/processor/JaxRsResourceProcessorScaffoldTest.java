// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs.processor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.jaxrs.JaxRsPipelineProcessor;
import java.util.ServiceLoader;
import java.util.stream.StreamSupport;
import javax.annotation.processing.Processor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smoke test verifying that {@link JaxRsPipelineProcessor} is reachable via
 * {@link ServiceLoader} from the {@code META-INF/services} registration.
 *
 * <p>If the processor JAR is on the annotation-processor path but the service-file entry is
 * missing or misspelled, {@code javac} silently skips the processor. This test catches that
 * misconfiguration before any compile-time validation is deployed to downstream modules.
 */
class JaxRsResourceProcessorScaffoldTest {

    @Test
    @DisplayName("JaxRsPipelineProcessor appears in ServiceLoader<Processor> results")
    void serviceLoader_findsJaxRsResourceProcessor() {
        boolean found = StreamSupport.stream(
                        ServiceLoader.load(Processor.class, JaxRsPipelineProcessor.class.getClassLoader())
                                .spliterator(),
                        false)
                .anyMatch(p -> p instanceof JaxRsPipelineProcessor);

        assertTrue(
                found,
                "JaxRsPipelineProcessor must be registered in META-INF/services/javax.annotation.processing.Processor");
    }
}
