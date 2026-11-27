// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A tool method with no schema parameters has no input to canonicalize or sanitize, but its
 * method-level and type-level {@code @Canonicalize}/{@code @Sanitize} versus {@code @Skip*}
 * conflicts are still compile errors: the conflict is a property of the declaration, not of a
 * parameter, so a tool is rejected the same way whether or not it takes input.
 */
class McpParameterlessToolPolicyConflictTest {

    @Test
    @DisplayName("a tool with no parameters and @Sanitize + @SkipSanitization on its method is a compile error")
    void methodConflictOnToolWithoutParametersIsACompileError() {
        var result = ProcessorTestHarness.run(
                new McpToolProcessor(), SourceFiles.inline("dev.vertique.test.mcp.parameterless.MethodConflict", """
                        package dev.vertique.test.mcp.parameterless;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import jakarta.inject.Inject;

                        public class MethodConflict {

                            @Inject
                            public MethodConflict() {}

                            @Sanitize(B.class)
                            @SkipSanitization
                            @McpTool(name = "parameterless.method-conflict", description = "Method-level conflict.")
                            public String ping() {
                                return "pong";
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("MethodConflict.ping");
    }

    @Test
    @DisplayName("a tool with no parameters whose class has @Sanitize + @SkipSanitization is a compile error")
    void typeConflictOnToolWithoutParametersIsACompileError() {
        var result = ProcessorTestHarness.run(
                new McpToolProcessor(), SourceFiles.inline("dev.vertique.test.mcp.parameterless.TypeConflict", """
                        package dev.vertique.test.mcp.parameterless;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import jakarta.inject.Inject;

                        @Sanitize(B.class)
                        @SkipSanitization
                        public class TypeConflict {

                            @Inject
                            public TypeConflict() {}

                            @McpTool(name = "parameterless.type-conflict", description = "Type-level conflict.")
                            public String ping() {
                                return "pong";
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("TypeConflict");
    }

    @Test
    @DisplayName("a tool whose only parameter is the cancellation signal still reports a method-level conflict")
    void methodConflictOnToolWithOnlyACancellationSignalIsACompileError() {
        var result = ProcessorTestHarness.run(
                new McpToolProcessor(),
                SourceFiles.inline("dev.vertique.test.mcp.parameterless.SignalOnlyConflict", """
                        package dev.vertique.test.mcp.parameterless;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.core.sanitization.SkipSanitization;
                        import dev.vertique.sanitization.processing.testkit.B;
                        import dev.vertique.mcp.annotation.McpTool;
                        import dev.vertique.mcp.tool.McpCancellationSignal;
                        import jakarta.inject.Inject;

                        public class SignalOnlyConflict {

                            @Inject
                            public SignalOnlyConflict() {}

                            @Sanitize(B.class)
                            @SkipSanitization
                            @McpTool(name = "parameterless.signal-only-conflict", description = "Signal-only conflict.")
                            public String ping(McpCancellationSignal signal) {
                                return "pong";
                            }
                        }
                        """));

        result.assertFailed();
        result.assertErrorMessage("Conflicting @Sanitize");
        result.assertErrorMessage("SignalOnlyConflict.ping");
    }
}
