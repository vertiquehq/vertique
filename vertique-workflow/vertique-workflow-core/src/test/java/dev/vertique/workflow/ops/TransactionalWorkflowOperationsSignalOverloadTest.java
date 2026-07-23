// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the metadata-aware 8-arg {@code signal(...)} default method on
 * {@link TransactionalWorkflowOperations} (PRD-WF-007, Contract Appendix C4).
 *
 * <p>Uses a minimal test-double implementation that overrides only the legacy 7-arg
 * {@code signal(...)} overload, proving the delegation contract without a real engine: a
 * {@code null} carrier delegates to the legacy overload unchanged, and a non-null carrier fails
 * fast with {@link UnsupportedOperationException} rather than silently dropping the caller-supplied
 * context (the "no-silent-drop" contract).
 */
class TransactionalWorkflowOperationsSignalOverloadTest {

    private static final WorkflowInstanceId WORKFLOW_ID = new WorkflowInstanceId(UUID.randomUUID());

    /**
     * Minimal test double overriding only the legacy 7-arg {@code signal(...)} overload (and the
     * other abstract members), leaving the new 8-arg metadata-aware {@code signal(...)} to run the
     * interface's default implementation under test.
     */
    private static final class MinimalOps implements TransactionalWorkflowOperations<Object> {

        private WorkflowInstanceId capturedId;
        private String capturedSignalName;
        private Object capturedPayload;
        private String capturedDedupKey;
        private String capturedForkStepId;
        private String capturedBranchId;
        private Object capturedTx;
        private int legacySignalCallCount;

        @Override
        public Future<WorkflowInstanceId> start(StartCommand cmd, Object tx) {
            return Future.failedFuture(new UnsupportedOperationException("not used by this test"));
        }

        @Override
        public Future<Void> signal(
                WorkflowInstanceId id, String signalName, Object payload, String signalDedupKey, Object tx) {
            return Future.failedFuture(new UnsupportedOperationException("not used by this test"));
        }

        @Override
        public Future<Void> signal(
                WorkflowInstanceId id,
                String signalName,
                Object payload,
                String signalDedupKey,
                String forkStepId,
                String branchId,
                Object tx) {
            legacySignalCallCount++;
            this.capturedId = id;
            this.capturedSignalName = signalName;
            this.capturedPayload = payload;
            this.capturedDedupKey = signalDedupKey;
            this.capturedForkStepId = forkStepId;
            this.capturedBranchId = branchId;
            this.capturedTx = tx;
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> cancel(WorkflowInstanceId id, String reason, Object tx) {
            return Future.failedFuture(new UnsupportedOperationException("not used by this test"));
        }

        @Override
        public Future<Void> retry(WorkflowInstanceId id, Object tx) {
            return Future.failedFuture(new UnsupportedOperationException("not used by this test"));
        }
    }

    @Test
    @DisplayName("signal() 8-arg: null metadata delegates to the legacy 7-arg overload with identical arguments")
    void signal_8arg_nullMetadata_delegatesToLegacy7ArgOverload() throws ExecutionException, InterruptedException {
        MinimalOps ops = new MinimalOps();
        Object tx = new Object();

        Future<Void> result =
                ops.signal(WORKFLOW_ID, "approve", "payload-value", "dedup-1", "fork-1", "branch-a", null, tx);

        assertThat(result.succeeded()).isTrue();
        assertThat(ops.legacySignalCallCount).isEqualTo(1);
        assertThat(ops.capturedId).isEqualTo(WORKFLOW_ID);
        assertThat(ops.capturedSignalName).isEqualTo("approve");
        assertThat(ops.capturedPayload).isEqualTo("payload-value");
        assertThat(ops.capturedDedupKey).isEqualTo("dedup-1");
        assertThat(ops.capturedForkStepId).isEqualTo("fork-1");
        assertThat(ops.capturedBranchId).isEqualTo("branch-a");
        assertThat(ops.capturedTx).isSameAs(tx);
    }

    @Test
    @DisplayName("signal() 8-arg: non-null metadata with no override fails fast with UnsupportedOperationException")
    void signal_8arg_nonNullMetadata_noOverride_failsFastWithUnsupportedOperationException() {
        MinimalOps ops = new MinimalOps();
        Object tx = new Object();
        DurableMetadata carrier = DurableMetadata.of("tenant", new JsonObject().put("tenantId", "T1"));

        Future<Void> result =
                ops.signal(WORKFLOW_ID, "approve", "payload-value", "dedup-1", "fork-1", "branch-a", carrier, tx);

        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(ops.legacySignalCallCount)
                .as("the legacy overload must never be invoked when the carrier is non-null and unsupported")
                .isZero();
    }

    @Test
    @DisplayName("signal() 8-arg: non-null metadata never silently drops the carrier (no side effects observed)")
    void signal_8arg_nonNullMetadata_neverSilentlyDropsCarrier() {
        MinimalOps ops = new MinimalOps();
        Object tx = new Object();
        DurableMetadata carrier = DurableMetadata.of("tenant", new JsonObject().put("tenantId", "T1"));

        assertThatThrownBy(() -> {
                    Future<Void> result = ops.signal(
                            WORKFLOW_ID, "approve", "payload-value", "dedup-1", "fork-1", "branch-a", carrier, tx);
                    if (result.failed()) {
                        throw result.cause();
                    }
                })
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("signal metadata not supported");
    }
}
