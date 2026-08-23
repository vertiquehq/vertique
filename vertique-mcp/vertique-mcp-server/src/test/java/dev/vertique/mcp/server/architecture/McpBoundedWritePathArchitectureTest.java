// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.server.McpUnboundedCodecCallProbe;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R14 item 4 — the byte-unbounded {@code McpProtocolCodec} error helpers stay off every write path.
 *
 * <p>R12 (merge blocker 5) rerouted every dispatcher error write through {@code
 * McpRequestDispatcher#boundedErrorResponse}, which streams into the capped output stream, and away
 * from the codec's own {@code errorResponse}/{@code errorResponseFor}/{@code internalFallback} — each
 * an unrestricted {@code writeValueAsBytes} that never observes {@code mcp.output.maxBytes}. R12's
 * own evidence recorded that no test could prove that rerouting stayed done: the defective and fixed
 * paths emit identical bytes for any id short of an {@code OutOfMemoryError}, so no wire-observable
 * signal distinguishes them.
 *
 * <p>R14 item 4 supplies the protection R12 reported as unavailable. The fourth helper — {@code
 * errorResponseFor(JsonNode, NegotiationResult)}, the exact method the reverted call site used — was
 * deleted outright, so that specific regression no longer compiles. The three helpers the codec's own
 * golden and failure tests genuinely need survive, and this rule pins them: <strong>no production
 * class other than {@code McpProtocolCodec} itself may call any of them</strong>. Reintroducing the
 * R12 defect at any call site turns this test red.
 *
 * <p>The rule reads compiled production bytecode, so a call added from a class that does not exist
 * yet is covered without touching this test. {@link McpUnboundedCodecCallProbe} — compiled test
 * bytecode calling all three from outside the codec — proves the rule can fail.
 */
class McpBoundedWritePathArchitectureTest {

    private static final String CODEC = "dev.vertique.mcp.server.McpProtocolCodec";

    /** The three surviving byte-unbounded error-response helpers, by simple method name. */
    private static final Set<String> UNBOUNDED_HELPERS =
            Set.of("errorResponse", "errorResponseFor", "internalFallback");

    @Test
    @DisplayName("R14 item 4: no production class outside McpProtocolCodec calls a byte-unbounded error helper")
    void shouldKeepUnboundedCodecHelpersOffEveryProductionWritePath() {
        JavaClasses production = importCompiledProductionClasses();

        assertThat(typeNames(production))
                .as("the scan must genuinely cover the dispatcher — the one class that held the R12 defect — "
                        + "otherwise an empty violation list proves nothing")
                .contains("dev.vertique.mcp.server.McpRequestDispatcher", CODEC);

        assertThat(unboundedHelperCallsFromOutsideTheCodec(production))
                .as("DECISIVE: every error write must go through McpRequestDispatcher#boundedErrorResponse; "
                        + "a call to one of the codec's byte-unbounded helpers from any other production "
                        + "class is the R12 regression")
                .isEmpty();
    }

    @Test
    @DisplayName("R14 item 4: the same rule reports a violation for each unbounded call a probe makes")
    void shouldReportAViolationForEachUnboundedCallFromOutsideTheCodec() {
        JavaClasses probe = new ClassFileImporter().importClasses(McpUnboundedCodecCallProbe.class);

        List<String> violations = unboundedHelperCallsFromOutsideTheCodec(probe);

        assertThat(violations)
                .as("DECISIVE (sensitivity): the rule must flag all three surviving helpers when they are "
                        + "genuinely called from outside the codec, so the empty production result above is "
                        + "a fact about the production code and not about a rule that can never fail")
                .hasSize(3);
        assertThat(String.join("\n", violations))
                .contains("errorResponse")
                .contains("errorResponseFor")
                .contains("internalFallback");
    }

    /** Collects one description per call to a byte-unbounded codec helper originating outside the codec. */
    private static List<String> unboundedHelperCallsFromOutsideTheCodec(JavaClasses classes) {
        return classes.stream()
                .flatMap(type -> type.getMethodCallsFromSelf().stream())
                .filter(call -> CODEC.equals(call.getTargetOwner().getName()))
                .filter(call -> UNBOUNDED_HELPERS.contains(call.getTarget().getName()))
                .filter(call -> !CODEC.equals(call.getOriginOwner().getName()))
                .map(McpBoundedWritePathArchitectureTest::describe)
                .sorted()
                .toList();
    }

    private static String describe(JavaMethodCall call) {
        return call.getOriginOwner().getName() + " calls " + CODEC + "#"
                + call.getTarget().getName() + " at " + call.getSourceCodeLocation();
    }

    private static List<String> typeNames(JavaClasses classes) {
        return classes.stream().map(JavaClass::getName).toList();
    }

    /** Imports every compiled production class of {@code vertique-mcp-server}. */
    private static JavaClasses importCompiledProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importUrls(List.of(compiledLocationOf(McpServerConfig.class)));
    }

    private static URL compiledLocationOf(Class<?> type) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new AssertionError("No compiled location available for " + type.getName());
        }
        return codeSource.getLocation();
    }
}
