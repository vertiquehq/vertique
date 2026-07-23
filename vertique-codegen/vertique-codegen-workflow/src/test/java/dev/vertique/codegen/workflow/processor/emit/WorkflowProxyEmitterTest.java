// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link WorkflowProxyEmitter} generates a {@code {Contract}_WorkflowClientProxy}
 * whose source shape correctly delegates each operation role (START, SIGNAL, QUERY) to
 * {@code WorkflowOperations}, reproduces the contract interface signature exactly, and handles
 * idempotency/dedup key extraction from both explicit parameters and payload marker interfaces.
 */
class WorkflowProxyEmitterTest {

    private static final String GENERATED_FQN = "com.example.OrderWorkflow_WorkflowClientProxy";

    // --- Shared payload fixtures ---

    private static JavaFileObject startOrderPayload() {
        return SourceFiles.inline("com.example.StartOrder", """
                package com.example;
                import dev.vertique.workflow.contract.IdempotencyKeyed;
                public record StartOrder(String orderId) implements IdempotencyKeyed {
                    @Override public String idempotencyKey() { return orderId; }
                }
                """);
    }

    private static JavaFileObject cancelOrderPayload() {
        return SourceFiles.inline("com.example.CancelOrder", """
                package com.example;
                import dev.vertique.workflow.contract.SignalDedupKeyed;
                public record CancelOrder(String reason) implements SignalDedupKeyed {
                    @Override public String dedupKey() { return reason; }
                }
                """);
    }

    private static JavaFileObject orderWorkflowContract() {
        return SourceFiles.inline("com.example.OrderWorkflow", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "orders", definitionVersion = 5L)
                public interface OrderWorkflow {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartOrder cmd);
                    @WorkflowSignal("cancel")
                    Future<Void> cancel(WorkflowInstanceId id, CancelOrder c);
                    @WorkflowQuery("status")
                    Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """);
    }

    // --- Tests ---

    @Test
    @DisplayName("generates proxy implementing the contract with correct WorkflowOperations delegation")
    void generatesProxySource() {
        ProcessorTestHarness.run(
                        new WorkflowContractProcessor(),
                        startOrderPayload(),
                        cancelOrderPayload(),
                        orderWorkflowContract())
                .assertSuccess()
                .assertGeneratedSourceContains(GENERATED_FQN, "implements OrderWorkflow")
                .assertGeneratedSourceContains(GENERATED_FQN, "WorkflowOperations ops")
                .assertGeneratedSourceContains(GENERATED_FQN, "this.ops = ops")
                .assertGeneratedSourceContains(GENERATED_FQN, "this.ops.start(new StartCommand(\"orders\"")
                .assertGeneratedSourceContains(GENERATED_FQN, "5L")
                .assertGeneratedSourceContains(GENERATED_FQN, ".idempotencyKey()")
                .assertGeneratedSourceContains(GENERATED_FQN, "this.ops.signal(id, \"cancel\"")
                .assertGeneratedSourceContains(GENERATED_FQN, ".dedupKey()")
                .assertGeneratedSourceContains(GENERATED_FQN, "this.ops.query(id)")
                .assertGeneratedSourceContains(
                        GENERATED_FQN, "\"WorkflowProxy[\" + OrderWorkflow.class.getName() + \"]\"");
    }

    @Test
    @DisplayName("start body uses explicit @IdempotencyKey param, not IdempotencyKeyed cast, when param is present")
    void startWithExplicitIdempotencyKeyParam() {
        JavaFileObject plainPayload = SourceFiles.inline("com.example.StartOrder", """
                package com.example;
                public record StartOrder(String data) {}
                """);
        JavaFileObject contract = SourceFiles.inline("com.example.OrderWorkflow", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.IdempotencyKey;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "orders", definitionVersion = 3L)
                public interface OrderWorkflow {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(@IdempotencyKey String key, StartOrder cmd);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), plainPayload, contract)
                .assertSuccess()
                .assertGeneratedSourceContains(GENERATED_FQN, "key")
                .assertGeneratedSourceDoesNotContain(GENERATED_FQN, "IdempotencyKeyed) ");
    }

    @Test
    @DisplayName("nested contract Outer.Inner generates flattened Outer_Inner_WorkflowClientProxy")
    void generatesFlattenedProxyForNestedContract() {
        JavaFileObject startPayload = SourceFiles.inline("com.example.StartOrder", """
                package com.example;
                import dev.vertique.workflow.contract.IdempotencyKeyed;
                public record StartOrder(String id) implements IdempotencyKeyed {
                    @Override public String idempotencyKey() { return id; }
                }
                """);
        JavaFileObject nested = SourceFiles.inline("com.example.Outer", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                public final class Outer {
                    @WorkflowContract(definitionId = "nested", definitionVersion = 1L)
                    public interface Inner {
                        @WorkflowStart
                        Future<WorkflowInstanceId> start(StartOrder cmd);
                    }
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), startPayload, nested)
                .assertSuccess()
                .assertGeneratedSourceContains("com.example.Outer_Inner_WorkflowClientProxy", "implements Outer.Inner");
    }

    @Test
    @DisplayName("receiver is qualified this.ops so an operation parameter named 'ops' does not shadow the field")
    void qualifiesReceiverWhenParameterNamedOps() {
        // A payload parameter literally named "ops" — the runtime JDK proxy accepts this (it captures ops in
        // a closure). The generated proxy must compile too: a bare 'ops.start(...)' would bind to the
        // StartOrder parameter and fail. assertSuccess proves the qualified 'this.ops' receiver compiles.
        JavaFileObject payload = SourceFiles.inline("com.example.StartOrder", """
                package com.example;
                import dev.vertique.workflow.contract.IdempotencyKeyed;
                public record StartOrder(String id) implements IdempotencyKeyed {
                    @Override public String idempotencyKey() { return id; }
                }
                """);
        JavaFileObject contract = SourceFiles.inline("com.example.OrderWorkflow", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "orders", definitionVersion = 1L)
                public interface OrderWorkflow {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartOrder ops);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), payload, contract)
                .assertSuccess()
                .assertGeneratedSourceContains(GENERATED_FQN, "this.ops.start(");
    }

    @Test
    @DisplayName(
            "optional business/subject keys use a runtime instanceof, not a compile-time decision on the declared type")
    void optionalKeySourcesResolvedAtRuntime() {
        // The payload's DECLARED type implements only IdempotencyKeyed (the required source). The optional
        // BusinessKeyed/SubjectReferenced fallbacks must be emitted as runtime `instanceof` checks (matching
        // the reflective WorkflowProxyValidator) — a compile-time `null` based on the declared type would
        // silently drop the key when a caller passes a runtime SUBTYPE that implements the marker.
        JavaFileObject payload = SourceFiles.inline("com.example.StartOrder", """
                package com.example;
                import dev.vertique.workflow.contract.IdempotencyKeyed;
                public record StartOrder(String id) implements IdempotencyKeyed {
                    @Override public String idempotencyKey() { return id; }
                }
                """);
        JavaFileObject contract = SourceFiles.inline("com.example.OrderWorkflow", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "orders", definitionVersion = 1L)
                public interface OrderWorkflow {
                    @WorkflowStart
                    Future<WorkflowInstanceId> start(StartOrder cmd);
                }
                """);

        ProcessorTestHarness.run(new WorkflowContractProcessor(), payload, contract)
                .assertSuccess()
                .assertGeneratedSourceContains(GENERATED_FQN, "instanceof BusinessKeyed")
                .assertGeneratedSourceContains(GENERATED_FQN, "instanceof SubjectReferenced")
                // required-key source stays a direct cast (validation guarantees the declared type implements it)
                .assertGeneratedSourceContains(GENERATED_FQN, "((IdempotencyKeyed) cmd).idempotencyKey()");
    }
}
