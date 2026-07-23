// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.Future;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the {@link Savepoint} utility issues SAVEPOINT/RELEASE and ROLLBACK TO SAVEPOINT
 * SQL in the correct order on success and failure paths.
 */
@ExtendWith(MockitoExtension.class)
class SavepointTest {

    @Mock
    SqlConnection conn;

    @Mock
    @SuppressWarnings("rawtypes")
    Query savepointQuery;

    @Mock
    @SuppressWarnings("rawtypes")
    Query releaseQuery;

    @Mock
    @SuppressWarnings("rawtypes")
    Query rollbackQuery;

    @Mock
    @SuppressWarnings("rawtypes")
    RowSet rowSet;

    @Test
    @DisplayName("on success: SAVEPOINT -> fn -> RELEASE SAVEPOINT")
    @SuppressWarnings("unchecked")
    void successPath() {
        String result = "ok";
        when(conn.query("SAVEPOINT sp1")).thenReturn(savepointQuery);
        when(savepointQuery.execute()).thenReturn(Future.succeededFuture(rowSet));
        when(conn.query("RELEASE SAVEPOINT sp1")).thenReturn(releaseQuery);
        when(releaseQuery.execute()).thenReturn(Future.succeededFuture(rowSet));

        Future<String> future = Savepoint.execute(conn, "sp1", c -> Future.succeededFuture(result));

        assertTrue(future.succeeded());
        assertSame(result, future.result());

        InOrder order = inOrder(conn);
        order.verify(conn).query("SAVEPOINT sp1");
        order.verify(conn).query("RELEASE SAVEPOINT sp1");
        verify(conn, never()).query("ROLLBACK TO SAVEPOINT sp1");
    }

    @Test
    @DisplayName("on failure: SAVEPOINT -> fn fails -> ROLLBACK TO SAVEPOINT -> propagate error")
    @SuppressWarnings("unchecked")
    void failurePath() {
        RuntimeException cause = new RuntimeException("fn failed");
        when(conn.query("SAVEPOINT sp1")).thenReturn(savepointQuery);
        when(savepointQuery.execute()).thenReturn(Future.succeededFuture(rowSet));
        when(conn.query("ROLLBACK TO SAVEPOINT sp1")).thenReturn(rollbackQuery);
        when(rollbackQuery.execute()).thenReturn(Future.succeededFuture(rowSet));

        Future<String> future = Savepoint.execute(conn, "sp1", c -> Future.failedFuture(cause));

        assertTrue(future.failed());
        assertSame(cause, future.cause());

        InOrder order = inOrder(conn);
        order.verify(conn).query("SAVEPOINT sp1");
        order.verify(conn).query("ROLLBACK TO SAVEPOINT sp1");
        verify(conn, never()).query("RELEASE SAVEPOINT sp1");
    }

    @Test
    @DisplayName("null conn throws NullPointerException")
    void nullConn() {
        assertThrows(NullPointerException.class, () -> Savepoint.execute(null, "sp1", c -> Future.succeededFuture()));
    }

    @Test
    @DisplayName("null name throws NullPointerException")
    void nullName() {
        assertThrows(NullPointerException.class, () -> Savepoint.execute(conn, null, c -> Future.succeededFuture()));
    }

    @Test
    @DisplayName("null fn throws NullPointerException")
    void nullFn() {
        assertThrows(NullPointerException.class, () -> Savepoint.execute(conn, "sp1", null));
    }
}
