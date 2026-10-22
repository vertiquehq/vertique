// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.validate;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a contract name holding an ISO control character fails compilation, by the same rule
 * the runtime name index applies, and that the diagnostic names the contract interface and the rule
 * without repeating the name.
 *
 * <p>Each control character is built at run time and written into the source text as the raw
 * character, so the compiler sees it inside the annotation's string literal.
 */
class ContractNameControlCharacterTest {

    private static final String ORDERS_FQN = "com.example.Orders";
    private static final String PROXY_FQN = "com.example.Orders_WorkflowClientProxy";

    private static final JavaFileObject START_ORDER = SourceFiles.inline("com.example.StartOrder", """
            package com.example;
            import dev.vertique.workflow.contract.IdempotencyKeyed;
            public record StartOrder(String orderId) implements IdempotencyKeyed {
                @Override public String idempotencyKey() { return orderId; }
            }
            """);

    private static final JavaFileObject CANCEL_ORDER = SourceFiles.inline("com.example.CancelOrder", """
            package com.example;
            import dev.vertique.workflow.contract.SignalDedupKeyed;
            public record CancelOrder(String reason) implements SignalDedupKeyed {
                @Override public String dedupKey() { return reason; }
            }
            """);

    /** The bell, a tab, the delete character and the next-line character of the C1 range. */
    private static final List<Integer> CONTROL_CODE_POINTS = List.of(0x07, 0x09, 0x7F, 0x85);

    /**
     * Builds the orders contract annotated with the given logical name.
     *
     * @param name the annotation's name value, written between the quotes as is
     * @return the contract source
     */
    private static JavaFileObject named(String name) {
        return SourceFiles.inline(ORDERS_FQN, """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract("%s")
                public interface Orders {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartOrder payload);
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(WorkflowInstanceId id, CancelOrder payload);
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """.formatted(name));
    }

    private static ProcessorTestHarness.Result compile(JavaFileObject contract) {
        return ProcessorTestHarness.run(new WorkflowContractProcessor(), START_ORDER, CANCEL_ORDER, contract);
    }

    private static List<String> errorMessages(ProcessorTestHarness.Result result) {
        return result.compilation().errors().stream()
                .map(d -> d.getMessage(null))
                .toList();
    }

    @Test
    @DisplayName("a contract name holding a control character fails compilation without repeating the name")
    void contractNameWithControlCharacterFailsCompilation() {
        // Given a contract name with one control character between two letters, for each control character
        List<String> problems = new ArrayList<>();
        for (int codePoint : CONTROL_CODE_POINTS) {
            String name = "or" + Character.toString(codePoint) + "ders";
            String label = "U+" + String.format(Locale.ROOT, "%04X", codePoint);

            // When it is compiled with the contract processor
            ProcessorTestHarness.Result result = compile(named(name));

            // Then compilation fails with an error naming the contract interface and its name as the subject
            List<String> errors = errorMessages(result);
            if (errors.isEmpty()) {
                problems.add(label + ": compiled without an error");
                continue;
            }
            boolean namesRule = errors.stream()
                    .anyMatch(m -> m.contains(ORDERS_FQN)
                            && m.toLowerCase(Locale.ROOT).contains("contract name")
                            && m.toLowerCase(Locale.ROOT).contains("control"));
            if (!namesRule) {
                problems.add(label + ": no error naming " + ORDERS_FQN + ", the contract name and the control rule: "
                        + errors);
            }

            // And no error repeats the name, or any control character of it
            boolean echoed =
                    errors.stream().anyMatch(m -> m.contains(name) || m.contains("or" + Character.toString(codePoint)));
            boolean anyControl = errors.stream().anyMatch(m -> m.codePoints().anyMatch(cp -> cp == codePoint));
            if (echoed || anyControl) {
                problems.add(label + ": an error repeats the name or its control character: " + errors);
            }
        }
        assertTrue(problems.isEmpty(), "control characters in a contract name must be refused: " + problems);
    }

    @Test
    @DisplayName("a name without control characters still compiles and gets its proxy")
    void contractNameWithoutControlCharacterStillCompiles() {
        // Given a contract name made of letters, a space and a hyphen
        // When it is compiled with the contract processor
        ProcessorTestHarness.Result result = compile(named("order-flow v2"));

        // Then it compiles and the proxy is generated
        result.assertSuccess().assertGeneratedSourceContains(PROXY_FQN, "implements Orders");
    }
}
