// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compilation;
import dev.vertique.codegen.test.ProcessorTestHarness;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T014 TP-001 — every {@code McpServerModule} composition requires a direct, non-{@code Optional}
 * {@code InputObjectProcessor} binding; a composition that omits one fails Dagger compilation with
 * exactly one missing-binding diagnostic naming it.
 */
class McpInputProcessingCompositionCompileTest {

    @Test
    @DisplayName("a composition omitting InputObjectProcessor fails Dagger compilation with exactly one "
            + "missing-binding diagnostic naming it, while the complete composition compiles")
    void shouldFailCompilationWithoutTheProcessorBinding() {
        // Given: a component including McpServerModule and a module providing InputObjectProcessor, and
        // an otherwise identical component omitting that one module.

        // When: each component is compiled once with the real Dagger ComponentProcessor.
        ProcessorTestHarness.Result complete =
                McpInputProcessingCompositionCompileTestFixture.compileCompleteComposition();
        ProcessorTestHarness.Result omitting =
                McpInputProcessingCompositionCompileTestFixture.compileCompositionOmittingInputProcessing();

        // Then: the complete component compiles...
        complete.assertSuccess();

        // ...and the omitting component fails with exactly one Dagger missing-binding diagnostic naming
        // InputObjectProcessor — not merely "compilation failed", which would also pass for any
        // unrelated error.
        omitting.assertFailed();
        List<Diagnostic<? extends JavaFileObject>> missingProcessorBindingErrors =
                missingBindingDiagnosticsNaming(omitting.compilation(), "InputObjectProcessor");
        assertThat(missingProcessorBindingErrors)
                .as(
                        "the omitting composition must fail with exactly one Dagger missing-binding diagnostic "
                                + "naming InputObjectProcessor, not zero and not several unrelated failures: %s",
                        diagnosticSummary(omitting.compilation()))
                .hasSize(1);
    }

    /**
     * Filters a compilation's diagnostics to {@code ERROR}-kind entries that both name
     * {@code simpleTypeName} and carry Dagger's own missing-binding phrasing, so an unrelated error
     * that merely happens to mention the type name is never miscounted as the binding failure.
     */
    private static List<Diagnostic<? extends JavaFileObject>> missingBindingDiagnosticsNaming(
            Compilation compilation, String simpleTypeName) {
        return compilation.diagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .filter(diagnostic -> {
                    String message = diagnostic.getMessage(null);
                    return message != null
                            && message.contains(simpleTypeName)
                            && (message.contains("cannot be provided") || message.contains("missing binding"));
                })
                .toList();
    }

    private static String diagnosticSummary(Compilation compilation) {
        StringBuilder summary = new StringBuilder("\nCompilation diagnostics:\n");
        compilation.diagnostics().forEach(diagnostic -> summary.append("  [")
                .append(diagnostic.getKind())
                .append("] ")
                .append(diagnostic.getMessage(null))
                .append('\n'));
        return summary.toString();
    }
}
