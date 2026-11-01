// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks that an implementer which overrides only the abstract members gets delivery methods that fail the future. */
class TransactionalWorkflowOperationsCorrelatedDefaultsTest {

    private interface OrderContract {}

    /** Minimal implementer that overrides only the abstract methods, leaving both delivery defaults in place. */
    private static final class MinimalOps implements TransactionalWorkflowOperations<Object> {

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
            return Future.failedFuture(new UnsupportedOperationException("not used by this test"));
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
    @DisplayName("both delivery defaults fail the returned future with UnsupportedOperationException")
    void defaultDeliveryMethodsFailTheFutureWithUnsupportedOperation() {
        // Given a minimal implementer and a correlated signal
        MinimalOps ops = new MinimalOps();
        CorrelatedSignal signal = CorrelatedSignal.of(OrderContract.class, "approve", "K-1", "payload", "d-1");

        // When each delivery method is called
        Future<CorrelatedSignalReceipt> byKey = ops.signalByKey(signal, new Object());
        Future<CorrelatedSignalReceipt> byBusinessKey = ops.signalByBusinessKey(signal, new Object());

        // Then each call returned a failed future instead of throwing
        assertThat(byKey.failed()).isTrue();
        assertThat(byKey.cause()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(byBusinessKey.failed()).isTrue();
        assertThat(byBusinessKey.cause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
