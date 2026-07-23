// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
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
 * Unit tests for {@link DefaultTaskService}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Each public method opens exactly one transaction via {@code pool.withTransaction} and
 *       delegates to the corresponding {@link TransactionalTaskService} method.</li>
 *   <li>The service rejects null arguments before calling {@code pool.withTransaction}.</li>
 * </ul>
 *
 * <p>Result-translation semantics (APPLIED / LOST_TO_RACE / STALE_NOOP) are covered by
 * {@link TransactionalDefaultTaskServiceTest} and are not re-tested here.
 */
@ExtendWith(MockitoExtension.class)
class DefaultTaskServiceTest {

    @Mock
    private Pool pool;

    @Mock
    private TransactionalTaskService<SqlClient> txService;

    @Mock
    private SqlConnection tx;

    private DefaultTaskService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTaskService(pool, txService);
    }

    /** Stubs {@code pool.withTransaction} to invoke the lambda synchronously with {@link #tx}. */
    private void stubPoolWithTransaction() {
        when(pool.withTransaction(any())).thenAnswer(inv -> {
            var fn = inv.<java.util.function.Function<SqlConnection, Future<Object>>>getArgument(0);
            return fn.apply(tx);
        });
    }

    // --- list ---

    @Nested
    @DisplayName("list")
    class List_ {

        @Test
        @DisplayName("opens a transaction and delegates to txService.list")
        void list_opensTransactionAndDelegates() {
            stubPoolWithTransaction();
            TaskFilter filter = TaskFilter.empty();
            PageCursor cursor = PageCursor.first(20);
            PagedResult<TaskRecord> expected = new PagedResult<>(java.util.List.of(), null, null);

            when(txService.list(eq(filter), eq(cursor), eq(tx))).thenReturn(Future.succeededFuture(expected));

            Future<PagedResult<TaskRecord>> result = service.list(filter, cursor);

            assertThat(result.succeeded()).isTrue();
            assertThat(result.result()).isSameAs(expected);
            verify(pool).withTransaction(any());
            verify(txService).list(filter, cursor, tx);
        }

        @Test
        @DisplayName("null filter → NullPointerException before opening transaction")
        void list_nullFilter_throwsNPEBeforeTransaction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.list(null, PageCursor.first(10)))
                    .withMessageContaining("filter");
            verifyNoInteractions(pool, txService);
        }

        @Test
        @DisplayName("null cursor → NullPointerException before opening transaction")
        void list_nullCursor_throwsNPEBeforeTransaction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.list(TaskFilter.empty(), null))
                    .withMessageContaining("cursor");
            verifyNoInteractions(pool, txService);
        }
    }

    // --- get ---

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("opens a transaction and delegates to txService.get")
        void get_opensTransactionAndDelegates() {
            stubPoolWithTransaction();
            UUID taskId = UUID.randomUUID();
            Optional<TaskRecord> expected = Optional.empty();

            when(txService.get(eq(taskId), eq(tx))).thenReturn(Future.succeededFuture(expected));

            Future<Optional<TaskRecord>> result = service.get(taskId);

            assertThat(result.succeeded()).isTrue();
            assertThat(result.result()).isSameAs(expected);
            verify(pool).withTransaction(any());
            verify(txService).get(taskId, tx);
        }

        @Test
        @DisplayName("null taskId → NullPointerException before opening transaction")
        void get_nullTaskId_throwsNPEBeforeTransaction() {
            assertThatNullPointerException().isThrownBy(() -> service.get(null)).withMessageContaining("taskId");
            verifyNoInteractions(pool, txService);
        }
    }

    // --- complete ---

    @Nested
    @DisplayName("complete")
    class Complete {

        private TaskCompletionCommand buildCmd() {
            return new TaskCompletionCommand(
                    UUID.randomUUID(), "approve", null, "idem-key-1", new WorkflowActor.User("user-1"), null);
        }

        @Test
        @DisplayName("opens a transaction and delegates to txService.complete")
        void complete_opensTransactionAndDelegates() {
            stubPoolWithTransaction();
            TaskCompletionCommand cmd = buildCmd();

            when(txService.complete(eq(cmd), eq(tx))).thenReturn(Future.succeededFuture());

            Future<Void> result = service.complete(cmd);

            assertThat(result.succeeded()).isTrue();
            verify(pool).withTransaction(any());
            verify(txService).complete(cmd, tx);
        }

        @Test
        @DisplayName("null cmd → NullPointerException before opening transaction")
        void complete_nullCmd_throwsNPEBeforeTransaction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.complete(null))
                    .withMessageContaining("cmd");
            verifyNoInteractions(pool, txService);
        }
    }

    // --- reassign ---

    @Nested
    @DisplayName("reassign")
    class Reassign {

        private TaskReassignmentCommand buildCmd() {
            return new TaskReassignmentCommand(
                    UUID.randomUUID(),
                    new TaskAssignment.Role("legal"),
                    new WorkflowActor.User("manager-bob"),
                    "idem-key-reassign-1",
                    null);
        }

        @Test
        @DisplayName("opens a transaction and delegates to txService.reassign")
        void reassign_opensTransactionAndDelegates() {
            stubPoolWithTransaction();
            TaskReassignmentCommand cmd = buildCmd();

            when(txService.reassign(eq(cmd), eq(tx))).thenReturn(Future.succeededFuture());

            Future<Void> result = service.reassign(cmd);

            assertThat(result.succeeded()).isTrue();
            verify(pool).withTransaction(any());
            verify(txService).reassign(cmd, tx);
        }

        @Test
        @DisplayName("null cmd → NullPointerException before opening transaction")
        void reassign_nullCmd_throwsNPEBeforeTransaction() {
            assertThatNullPointerException()
                    .isThrownBy(() -> service.reassign(null))
                    .withMessageContaining("cmd");
            verifyNoInteractions(pool, txService);
        }
    }
}
