// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.io.IOException;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Golden snapshot of the full source emitted for a canonical {@code @WorkflowContract} — the
 * {@code OrderWorkflow_WorkflowClientProxy} proxy; no clients module is generated.
 *
 * <p>This freezes the <em>exact</em> generated text so that any change to the emitter (e.g. an
 * embedded definition id or version, a proxy that constructs the start command itself, a changed
 * key-source fallback, or a binding that stops reaching the proxy) fails loudly and forces the
 * emitter to be updated in lockstep with the runtime {@code WorkflowProxyValidator}/{@code WorkflowClientFactory}
 * semantics it mirrors. The canonical contract exercises every START key-source interface fallback
 * ({@code IdempotencyKeyed} + {@code BusinessKeyed} + {@code SubjectReferenced}), a signal with a
 * {@code SignalDedupKeyed} payload, and a query.
 */
class WorkflowProxySnapshotTest {

    private static final String PROXY_FQN = "com.example.OrderWorkflow_WorkflowClientProxy";

    private static final String EXPECTED_PROXY = """
            package com.example;

            import dev.vertique.workflow.client.WorkflowClientBinding;
            import dev.vertique.workflow.contract.BusinessKeyed;
            import dev.vertique.workflow.contract.IdempotencyKeyed;
            import dev.vertique.workflow.contract.SignalDedupKeyed;
            import dev.vertique.workflow.contract.SubjectReferenced;
            import dev.vertique.workflow.ops.WorkflowInstanceId;
            import dev.vertique.workflow.ops.WorkflowOperations;
            import dev.vertique.workflow.ops.WorkflowView;
            import io.vertx.core.Future;
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

              private final WorkflowClientBinding binding;

              /**
               * Constructs the generated proxy, receiving the workflow operations facade and the client binding.
               *
               * @param ops the workflow operations to delegate contract methods to
               * @param binding the binding that supplies the definition id, version and scopes of started instances
               */
              public OrderWorkflow_WorkflowClientProxy(WorkflowOperations ops, WorkflowClientBinding binding) {
                this.ops = ops;
                this.binding = binding;
              }

              @Override
              public String toString() {
                return "WorkflowProxy[" + OrderWorkflow.class.getName() + "]";
              }

              @Override
              public Future<WorkflowInstanceId> start(StartOrder cmd) {
                return this.ops.start(this.binding.startCommand(cmd, ((IdempotencyKeyed) cmd).idempotencyKey(), (Object) cmd instanceof BusinessKeyed __bk ? __bk.businessKey() : null, (Object) cmd instanceof SubjectReferenced __sr ? __sr.subjectRef() : null));
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

    @Test
    @DisplayName("generated proxy matches the frozen golden snapshot and embeds no definition literal")
    void matchesSnapshot() throws IOException {
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                        new WorkflowContractProcessor(), startOrder(), cancelOrder(), contract())
                .assertSuccess();

        String proxy = generatedSource(result, PROXY_FQN);
        assertEquals(EXPECTED_PROXY.strip(), proxy.strip());
        // The definition id and version live in the binding, never in generated source
        assertFalse(proxy.contains("\"snap-def\""), "generated proxy must not embed the definition id");
        assertFalse(proxy.contains("7L"), "generated proxy must not embed the definition version");
        // The command is built only through the framework, so its shape can change without regenerating proxies
        assertFalse(proxy.contains("new StartCommand("), "generated proxy must not construct a StartCommand");
        assertTrue(proxy.contains("binding.startCommand("), "generated proxy must build the command via the binding");
        assertFalse(proxy.contains("@Inject"), "generated proxy takes its binding by construction, not injection");
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
                @WorkflowContract(definitionId = "snap-def", definitionVersion = 7)
                public interface OrderWorkflow {
                    @WorkflowStart Future<WorkflowInstanceId> start(StartOrder cmd);
                    @WorkflowSignal("cancel") Future<Void> cancel(WorkflowInstanceId id, CancelOrder c);
                    @WorkflowQuery("status") Future<WorkflowView> status(WorkflowInstanceId id);
                }
                """);
    }
}
