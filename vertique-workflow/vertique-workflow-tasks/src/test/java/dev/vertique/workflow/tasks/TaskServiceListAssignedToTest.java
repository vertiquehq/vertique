// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@code listAssignedTo} on both task services and for the fail-closed defaults. */
class TaskServiceListAssignedToTest {

    private static final TaskPrincipals PRINCIPALS = TaskPrincipals.ofUser("alice", Set.of("r"), Set.of("q"));
    private static final TaskFilter FILTER = TaskFilter.empty().withStatus(TaskStatus.OPEN);
    private static final PageCursor CURSOR = PageCursor.first(20);
    private static final PagedResult<TaskRecord> PAGE = new PagedResult<>(List.of(), null, null);

    @Test
    @DisplayName("the transactional service delegates to the store on the caller's transaction")
    @SuppressWarnings("unchecked")
    void transactionalDelegatesToStore() {
        TaskStore<SqlClient> store = mock(TaskStore.class);
        SqlClient tx = mock(SqlClient.class);
        when(store.findAssignedTo(PRINCIPALS, FILTER, CURSOR, tx)).thenReturn(Future.succeededFuture(PAGE));
        var service = new TransactionalDefaultTaskService(store, mock(TransactionalTaskCallbacks.class));

        Future<PagedResult<TaskRecord>> result = service.listAssignedTo(PRINCIPALS, FILTER, CURSOR, tx);

        assertThat(result.result()).isSameAs(PAGE);
        verify(store).findAssignedTo(PRINCIPALS, FILTER, CURSOR, tx);
    }

    @Test
    @DisplayName("the transactional service refuses null arguments before touching the store")
    @SuppressWarnings("unchecked")
    void transactionalRefusesNulls() {
        TaskStore<SqlClient> store = mock(TaskStore.class);
        SqlClient tx = mock(SqlClient.class);
        var service = new TransactionalDefaultTaskService(store, mock(TransactionalTaskCallbacks.class));

        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(null, FILTER, CURSOR, tx));
        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(PRINCIPALS, null, CURSOR, tx));
        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(PRINCIPALS, FILTER, null, tx));
        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(PRINCIPALS, FILTER, CURSOR, null));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("the pooled service opens one transaction and delegates")
    @SuppressWarnings("unchecked")
    void pooledOpensTransactionAndDelegates() {
        Pool pool = mock(Pool.class);
        TransactionalTaskService<SqlClient> txService = mock(TransactionalTaskService.class);
        SqlConnection tx = mock(SqlConnection.class);
        when(pool.withTransaction(any())).thenAnswer(inv -> {
            Function<SqlConnection, Future<Object>> fn = inv.getArgument(0);
            return fn.apply(tx);
        });
        when(txService.listAssignedTo(PRINCIPALS, FILTER, CURSOR, tx)).thenReturn(Future.succeededFuture(PAGE));

        Future<PagedResult<TaskRecord>> result =
                new DefaultTaskService(pool, txService).listAssignedTo(PRINCIPALS, FILTER, CURSOR);

        assertThat(result.result()).isSameAs(PAGE);
        verify(pool).withTransaction(any());
        verify(txService).listAssignedTo(PRINCIPALS, FILTER, CURSOR, tx);
    }

    @Test
    @DisplayName("the pooled service refuses null arguments before opening a transaction")
    @SuppressWarnings("unchecked")
    void pooledRefusesNulls() {
        Pool pool = mock(Pool.class);
        TransactionalTaskService<SqlClient> txService = mock(TransactionalTaskService.class);
        var service = new DefaultTaskService(pool, txService);

        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(null, FILTER, CURSOR));
        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(PRINCIPALS, null, CURSOR));
        assertThatNullPointerException().isThrownBy(() -> service.listAssignedTo(PRINCIPALS, FILTER, null));
        verifyNoInteractions(pool, txService);
    }

    @Test
    @DisplayName("an implementation written before the method refuses the query instead of listing every task")
    @SuppressWarnings("unchecked")
    void defaultsFailClosed() {
        TaskStore<SqlClient> store = mock(TaskStore.class, CALLS_REAL_METHODS);
        TransactionalTaskService<SqlClient> txService = mock(TransactionalTaskService.class, CALLS_REAL_METHODS);
        TaskService service = mock(TaskService.class, CALLS_REAL_METHODS);
        SqlClient tx = mock(SqlClient.class);

        assertThat(store.findAssignedTo(PRINCIPALS, FILTER, CURSOR, tx).cause())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(txService.listAssignedTo(PRINCIPALS, FILTER, CURSOR, tx).cause())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(service.listAssignedTo(PRINCIPALS, FILTER, CURSOR).cause())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
