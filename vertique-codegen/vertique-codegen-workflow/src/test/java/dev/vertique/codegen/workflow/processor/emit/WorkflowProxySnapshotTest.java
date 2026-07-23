// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden snapshot of the full source emitted for a canonical {@code @WorkflowContract} — the
 * {@code OrderWorkflow_WorkflowClientProxy} proxy and the {@code GeneratedWorkflowClientsModule}.
 *
 * <p>This freezes the <em>exact</em> generated text so that any change to the emitter (e.g. a
 * different {@code StartCommand} argument order, a dropped version pin, a changed key-source
 * fallback, or a binding that stops delegating to the factory) fails loudly and forces the emitter
 * to be updated in lockstep with the runtime {@code WorkflowProxyValidator}/{@code WorkflowClientFactory}
 * semantics it mirrors. The canonical contract exercises every START key-source interface fallback
 * ({@code IdempotencyKeyed} + {@code BusinessKeyed} + {@code SubjectReferenced}), a signal with a
 * {@code SignalDedupKeyed} payload, and a query.
 */
class WorkflowProxySnapshotTest {

    private static final String PROXY_FQN = "com.example.OrderWorkflow_WorkflowClientProxy";
    private static final String MODULE_FQN = "com.example.GeneratedWorkflowClientsModule";

    private static final String EXPECTED_PROXY = """
            package com.example;

            import dev.vertique.workflow.contract.BusinessKeyed;
            import dev.vertique.workflow.contract.IdempotencyKeyed;
            import dev.vertique.workflow.contract.SignalDedupKeyed;
            import dev.vertique.workflow.contract.SubjectReferenced;
            import dev.vertique.workflow.ops.StartCommand;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import dev.vertique.workflow.ops.WorkflowOperations;
            import dev.vertique.workflow.ops.WorkflowView;
            import io.vertx.core.Future;
            import jakarta.inject.Inject;
            import java.lang.Override;
            import java.lang.String;
            import java.lang.Void;
            import javax.annotation.processing.Generated;

            /**
             * Generated zero-reflection workflow client proxy for {@link OrderWorkflow}.
             *
             * <p>Selected at runtime by {@code WorkflowClientFactory} when present on the
             * classpath. Delegates every contract method to {@code WorkflowOperations}.
             */
            @Generated("dev.vertique.codegen.workflow.processor.WorkflowContractProcessor")
            public final class OrderWorkflow_WorkflowClientProxy implements OrderWorkflow {
              private final WorkflowOperations ops;

              /**
               * Constructs the generated proxy, receiving the workflow operations facade.
               *
               * @param ops the workflow operations to delegate contract methods to
               */
              @Inject
              public OrderWorkflow_WorkflowClientProxy(WorkflowOperations ops) {
                this.ops = ops;
              }

              @Override
              public String toString() {
                return "WorkflowProxy[" + OrderWorkflow.class.getName() + "]";
              }

              @Override
              public Future<WorkflowInstanceId> start(StartOrder cmd) {
                return this.ops.start(new StartCommand("orders", cmd, ((IdempotencyKeyed) cmd).idempotencyKey(), (Object) cmd instanceof BusinessKeyed __bk ? __bk.businessKey() : null, (Object) cmd instanceof SubjectReferenced __sr ? __sr.subjectRef() : null, 7L));
              }

              @Override
              public Future<Void> cancel(WorkflowInstanceId id, CancelOrder c) {
                return this.ops.signal(id, "cancel", c, ((SignalDedupKeyed) c).dedupKey());
              }

              @Override
              public Future<WorkflowView> status(WorkflowInstanceId id) {
                return this.ops.query(id);
              }
            }
            """;

    private static final String EXPECTED_MODULE = """
            package com.example;

            import dagger.Module;
            import dagger.Provides;
            import dev.vertique.workflow.client.WorkflowClientFactory;
            import jakarta.inject.Singleton;

            @Module
            public class GeneratedWorkflowClientsModule {
              @Provides
              @Singleton
              static OrderWorkflow provideOrderWorkflow(WorkflowClientFactory factory) {
                return factory.create(OrderWorkflow.class);
              }
            }
            """;

    @Test
    @DisplayName("generated proxy and module match the frozen golden snapshot")
    void matchesSnapshot() throws IOException {
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                        new WorkflowContractProcessor(), startOrder(), cancelOrder(), contract())
                .assertSuccess();

        assertEquals(EXPECTED_PROXY.strip(), generatedSource(result, PROXY_FQN).strip());
        assertEquals(
                EXPECTED_MODULE.strip(), generatedSource(result, MODULE_FQN).strip());
    }

    private static String generatedSource(ProcessorTestHarness.Result result, String fqn) throws IOException {
        return result.compilation()
                .generatedSourceFile(fqn)
                .orElseThrow(() -> new AssertionError("no generated source for " + fqn))
                .getCharContent(true)
                .toString();
    }

    private static JavaFileObject startOrder() {
        return SourceFiles.inline("com.example.StartOrder", """
                package com.example;
                import dev.vertique.workflow.contract.IdempotencyKeyed;
                import dev.vertique.workflow.contract.BusinessKeyed;
                import dev.vertique.workflow.contract.SubjectReferenced;
                import dev.vertique.workflow.subject.WorkflowSubjectRef;
                public record StartOrder(String id) implements IdempotencyKeyed, BusinessKeyed, SubjectReferenced {
                    public String idempotencyKey() { return id; }
                    public String businessKey() { return id; }
                    public WorkflowSubjectRef subjectRef() { return null; }
                }
                """);
    }

    private static JavaFileObject cancelOrder() {
        return SourceFiles.inline("com.example.CancelOrder", """
                package com.example;
                import dev.vertique.workflow.contract.SignalDedupKeyed;
                public record CancelOrder(String reason) implements SignalDedupKeyed {
                    public String dedupKey() { return reason; }
                }
                """);
    }

    private static JavaFileObject contract() {
        return SourceFiles.inline("com.example.OrderWorkflow", """
                package com.example;
                import dev.vertique.workflow.contract.WorkflowContract;
                import dev.vertique.workflow.contract.WorkflowStart;
                import dev.vertique.workflow.contract.WorkflowSignal;
                import dev.vertique.workflow.contract.WorkflowQuery;
                import dev.vertique.workflow.ops.WorkflowInstanceId;
                import dev.vertique.workflow.ops.WorkflowView;
                import io.vertx.core.Future;
                @WorkflowContract(definitionId = "orders", definitionVersion = 7)
                public interface OrderWorkflow {
                    @WorkflowStart Future<WorkflowInstanceId> start(StartOrder cmd);
                    @WorkflowSignal("cancel") Future<Void> cancel(WorkflowInstanceId id, CancelOrder c);
                    @WorkflowQuery("status") Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """);
    }
}
