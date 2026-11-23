// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import io.vertx.core.Future;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies that the contract-bound operations forward {@code result} to the wrapped binding. */
class ContractBoundOperationsResultTest {

    @Test
    void resultIsForwardedToTheDelegate() {
        WorkflowOperations delegate = mock(WorkflowOperations.class);
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        when(delegate.result(id, String.class)).thenReturn(Future.succeededFuture("receipt"));

        Future<String> result = new ContractBoundOperations(delegate, "order").result(id, String.class);

        assertThat(result.result()).isEqualTo("receipt");
        verify(delegate).result(id, String.class);
    }

    @Test
    void anUnimplementedBindingFailsWithUnsupportedOperation() {
        WorkflowOperations bare = new WorkflowOperations() {
            @Override
            public Future<WorkflowInstanceId> start(dev.vertique.workflow.ops.StartCommand cmd) {
                return Future.failedFuture("unused");
            }

            @Override
            public Future<Void> signal(WorkflowInstanceId id, String signalName, Object payload, String dedupKey) {
                return Future.failedFuture("unused");
            }

            @Override
            public Future<Void> cancel(WorkflowInstanceId id, String reason) {
                return Future.failedFuture("unused");
            }

            @Override
            public Future<Void> retry(WorkflowInstanceId id) {
                return Future.failedFuture("unused");
            }

            @Override
            public Future<dev.vertique.workflow.ops.WorkflowView> query(WorkflowInstanceId id) {
                return Future.failedFuture("unused");
            }
        };

        Future<String> result = bare.result(new WorkflowInstanceId(UUID.randomUUID()), String.class);

        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(UnsupportedOperationException.class);
    }
}
