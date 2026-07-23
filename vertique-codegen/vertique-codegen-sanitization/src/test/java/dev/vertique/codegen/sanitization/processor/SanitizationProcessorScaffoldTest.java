// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scaffold smoke test for {@link SanitizationProcessor}.
 *
 * <p>Verifies that the processor is properly registered and compiles without errors against an
 * empty source set (no {@code @Path} resources, no DTOs — nothing to emit).
 */
class SanitizationProcessorScaffoldTest {

    @Test
    @DisplayName("processor compiles against empty source set without errors or generated files")
    void emptySourceSet_noErrors() {
        ProcessorTestHarness.run(new SanitizationProcessor(), SourceFiles.inline("com.example.Empty", """
                                package com.example;
                                public class Empty {}
                                """))
                .assertSuccess()
                .assertNoWarnings();
    }
}
