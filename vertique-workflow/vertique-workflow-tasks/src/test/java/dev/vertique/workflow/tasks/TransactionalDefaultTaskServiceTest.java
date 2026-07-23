// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowIdempotencyConflictException;
import dev.vertique.workflow.exception.WorkflowTaskNotFoundException;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TransactionalDefaultTaskService}.
 *
 * <p>Verifies that the service:
 * <ul>
 *   <li>Delegates list/get to {@link TaskStore} directly (no engine involvement).</li>
 *   <li>Delegates complete/reassign to {@link TransactionalTaskCallbacks} and translates
 *       {@link TaskMutationResult} to the correct {@link Future} outcome.</li>
 *   <li>Does NOT touch {@link TaskStore} during complete/reassign — the SPI owns all
 *       task-row mutations.</li>
 *   <li>Rejects null arguments at the service API boundary before reaching the SPI.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TransactionalDefaultTaskServiceTest {

    @Mock
    private TaskStore<SqlClient> taskStore;

    @Mock
    private TransactionalTaskCallbacks<SqlClient> taskCallbacks;

    @Mock
    private SqlClient tx;

    private TransactionalDefaultTaskService service;

    @BeforeEach
    void setUp() {
        service = new TransactionalDefaultTaskService(taskStore, taskCallbacks);
    }

    // --- list ---

    @Nested
    @DisplayName("list")
    class List_ {

        @Test
        @DisplayName("delegates to taskStore.findByFilter and returns the result")
        void list_delegatesToTaskStore() {
            TaskFilter filter = TaskFilter.empty();
            PageCursor cursor = PageCursor.first(20);
            PagedResult<TaskRecord> expected = new PagedResult<>(java.util.List.of(), null, null);

            when(taskStore.findByFilter(eq(filter), eq(cursor), same(tx))).thenReturn(Future.succeededFuture(expected));

            Future<PagedResult<TaskRecord>> result = service.list(filter, cursor, tx);

            assertThat(result.succeeded()).isTrue();
            assertThat(result.result()).isSameAs(expected);
            verify(taskStore).findByFilter(filter, cursor, tx);
        }

        @Test
        @DisplayName("null filter → NullPointerException before SPI call")
        void list_nullFilter_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.list(null, PageCursor.first(10), tx))
                    .withMessageContaining("filter");
            verifyNoInteractions(taskStore, taskCallbacks);
        }

        @Test
        @DisplayName("null cursor → NullPointerException before SPI call")
        void list_nullCursor_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.list(TaskFilter.empty(), null, tx))
                    .withMessageContaining("cursor");
            verifyNoInteractions(taskStore, taskCallbacks);
        }

        @Test
        @DisplayName("null tx → NullPointerException before SPI call")
        void list_nullTx_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.list(TaskFilter.empty(), PageCursor.first(10), null))
                    .withMessageContaining("tx");
            verifyNoInteractions(taskStore, taskCallbacks);
        }
    }

    // --- get ---

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("delegates to taskStore.findById and returns the result")
        void get_delegatesToTaskStore() {
            UUID taskId = UUID.randomUUID();
            Optional<TaskRecord> expected = Optional.empty();

            when(taskStore.findById(eq(taskId), same(tx))).thenReturn(Future.succeededFuture(expected));

            Future<Optional<TaskRecord>> result = service.get(taskId, tx);

            assertThat(result.succeeded()).isTrue();
            assertThat(result.result()).isSameAs(expected);
            verify(taskStore).findById(taskId, tx);
        }

        @Test
        @DisplayName("null taskId → NullPointerException before SPI call")
        void get_nullTaskId_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.get(null, tx))
                    .withMessageContaining("taskId");
            verifyNoInteractions(taskStore, taskCallbacks);
        }

        @Test
        @DisplayName("null tx → NullPointerException before SPI call")
        void get_nullTx_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.get(UUID.randomUUID(), null))
                    .withMessageContaining("tx");
            verifyNoInteractions(taskStore, taskCallbacks);
        }
    }

    // --- complete ---

    @Nested
    @DisplayName("complete")
    class Complete {

        private TaskCompletionCommand buildCmd(UUID taskId) {
            return new TaskCompletionCommand(
                    taskId, "approve", null, "idem-key-1", new WorkflowActor.User("user-1"), null);
        }

        @Test
        @DisplayName("APPLIED → succeeded Future")
        void complete_applied_succeeds() {
            UUID taskId = UUID.randomUUID();
            TaskCompletionCommand cmd = buildCmd(taskId);

            when(taskCallbacks.taskCompleted(same(cmd), same(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.APPLIED));

            Future<Void> result = service.complete(cmd, tx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskCompleted(cmd, tx);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("LOST_TO_RACE → succeeded Future (idempotent retry)")
        void complete_lostToRace_succeeds() {
            UUID taskId = UUID.randomUUID();
            TaskCompletionCommand cmd = buildCmd(taskId);

            when(taskCallbacks.taskCompleted(same(cmd), same(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.LOST_TO_RACE));

            Future<Void> result = service.complete(cmd, tx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskCompleted(cmd, tx);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("STALE_NOOP → failed Future with IllegalStateException (SPI contract violation)")
        void complete_staleNoop_failsWithContractViolation() {
            // The engine never returns STALE_NOOP from taskCompleted — no-wait / not-OPEN paths
            // surface as typed exceptions (WorkflowTaskNotWaitingException / WorkflowConflictException)
            // directly. If a misbehaving SPI ever returns STALE_NOOP, the service must fail loudly
            // rather than translate it into a misleading typed exception.
            UUID taskId = UUID.randomUUID();
            TaskCompletionCommand cmd = buildCmd(taskId);

            when(taskCallbacks.taskCompleted(same(cmd), same(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.STALE_NOOP));

            Future<Void> result = service.complete(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.cause().getMessage()).contains("STALE_NOOP").contains(taskId.toString());
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("SPI throws WorkflowIdempotencyConflictException → propagates unchanged")
        void complete_idempotencyConflict_propagates() {
            UUID taskId = UUID.randomUUID();
            TaskCompletionCommand cmd = buildCmd(taskId);
            WorkflowIdempotencyConflictException ex =
                    new WorkflowIdempotencyConflictException("task-complete", "idem-key-1", "fp-old", "fp-new");

            when(taskCallbacks.taskCompleted(same(cmd), same(tx))).thenReturn(Future.failedFuture(ex));

            Future<Void> result = service.complete(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(ex);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("SPI throws WorkflowConflictException → propagates unchanged")
        void complete_conflictException_propagates() {
            UUID taskId = UUID.randomUUID();
            TaskCompletionCommand cmd = buildCmd(taskId);
            WorkflowConflictException ex = new WorkflowConflictException("concurrent modification");

            when(taskCallbacks.taskCompleted(same(cmd), same(tx))).thenReturn(Future.failedFuture(ex));

            Future<Void> result = service.complete(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(ex);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("SPI throws WorkflowTaskNotFoundException → propagates unchanged")
        void complete_taskNotFound_propagates() {
            UUID taskId = UUID.randomUUID();
            TaskCompletionCommand cmd = buildCmd(taskId);
            WorkflowTaskNotFoundException ex = new WorkflowTaskNotFoundException(taskId);

            when(taskCallbacks.taskCompleted(same(cmd), same(tx))).thenReturn(Future.failedFuture(ex));

            Future<Void> result = service.complete(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(ex);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("null cmd → NullPointerException before SPI call")
        void complete_nullCmd_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.complete(null, tx))
                    .withMessageContaining("cmd");
            verifyNoInteractions(taskStore, taskCallbacks);
        }

        @Test
        @DisplayName("null tx → NullPointerException before SPI call")
        void complete_nullTx_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.complete(buildCmd(UUID.randomUUID()), null))
                    .withMessageContaining("tx");
            verifyNoInteractions(taskStore, taskCallbacks);
        }
    }

    // --- reassign ---

    @Nested
    @DisplayName("reassign")
    class Reassign {

        private TaskReassignmentCommand buildCmd(UUID taskId) {
            return new TaskReassignmentCommand(
                    taskId,
                    new TaskAssignment.Role("legal"),
                    new WorkflowActor.User("manager-bob"),
                    "idem-key-reassign-1",
                    "specialist required");
        }

        @Test
        @DisplayName("APPLIED → succeeded Future")
        void reassign_applied_succeeds() {
            UUID taskId = UUID.randomUUID();
            TaskReassignmentCommand cmd = buildCmd(taskId);

            when(taskCallbacks.taskReassigned(same(cmd), same(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.APPLIED));

            Future<Void> result = service.reassign(cmd, tx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskReassigned(cmd, tx);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("LOST_TO_RACE → succeeded Future (idempotent retry)")
        void reassign_lostToRace_succeeds() {
            UUID taskId = UUID.randomUUID();
            TaskReassignmentCommand cmd = buildCmd(taskId);

            when(taskCallbacks.taskReassigned(same(cmd), same(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.LOST_TO_RACE));

            Future<Void> result = service.reassign(cmd, tx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskReassigned(cmd, tx);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("STALE_NOOP → failed Future with IllegalStateException (SPI contract violation)")
        void reassign_staleNoop_failsWithIllegalState() {
            UUID taskId = UUID.randomUUID();
            TaskReassignmentCommand cmd = buildCmd(taskId);

            when(taskCallbacks.taskReassigned(same(cmd), same(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.STALE_NOOP));

            Future<Void> result = service.reassign(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.cause().getMessage()).contains("STALE_NOOP");
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("SPI throws WorkflowConflictException → propagates unchanged")
        void reassign_conflictException_propagates() {
            UUID taskId = UUID.randomUUID();
            TaskReassignmentCommand cmd = buildCmd(taskId);
            WorkflowConflictException ex = new WorkflowConflictException("task already terminal");

            when(taskCallbacks.taskReassigned(same(cmd), same(tx))).thenReturn(Future.failedFuture(ex));

            Future<Void> result = service.reassign(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(ex);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("SPI throws WorkflowIdempotencyConflictException → propagates unchanged")
        void reassign_idempotencyConflict_propagates() {
            UUID taskId = UUID.randomUUID();
            TaskReassignmentCommand cmd = buildCmd(taskId);
            WorkflowIdempotencyConflictException ex = new WorkflowIdempotencyConflictException(
                    "task-reassign", "idem-key-reassign-1", "fp-old", "fp-new");

            when(taskCallbacks.taskReassigned(same(cmd), same(tx))).thenReturn(Future.failedFuture(ex));

            Future<Void> result = service.reassign(cmd, tx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(ex);
            verifyNoInteractions(taskStore);
        }

        @Test
        @DisplayName("null cmd → NullPointerException before SPI call")
        void reassign_nullCmd_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.reassign(null, tx))
                    .withMessageContaining("cmd");
            verifyNoInteractions(taskStore, taskCallbacks);
        }

        @Test
        @DisplayName("null tx → NullPointerException before SPI call")
        void reassign_nullTx_throwsNPE() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.reassign(buildCmd(UUID.randomUUID()), null))
                    .withMessageContaining("tx");
            verifyNoInteractions(taskStore, taskCallbacks);
        }
    }
}
