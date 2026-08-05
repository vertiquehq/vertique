// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies {@link DatabaseHealthCheck} reports UP when the pool query succeeds
 * and DOWN when it fails.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseHealthCheckTest {

    @Mock
    Pool pool;

    @Mock
    Query<RowSet<Row>> query;

    @Test
    @DisplayName("returns UP when SELECT 1 succeeds")
    @SuppressWarnings("unchecked")
    void upOnSuccess() {
        RowSet<Row> rowSet = mock(RowSet.class);
        when(pool.query(anyString())).thenReturn(query);
        when(query.execute()).thenReturn(Future.succeededFuture(rowSet));

        DatabaseHealthCheck check = new DatabaseHealthCheck(pool);
        HealthCheckResult result = check.check().result();

        assertEquals(HealthStatus.UP, result.status());
        assertTrue(result.data().isEmpty());
    }

    @Test
    @DisplayName("returns DOWN with error message when query fails")
    void downOnFailure() {
        when(pool.query(anyString())).thenReturn(query);
        when(query.execute()).thenReturn(Future.failedFuture(new RuntimeException("connection refused")));

        DatabaseHealthCheck check = new DatabaseHealthCheck(pool);
        HealthCheckResult result = check.check().result();

        assertEquals(HealthStatus.DOWN, result.status());
        assertEquals("connection refused", result.data().get("error"));
    }

    @Test
    @DisplayName("returns DOWN with the exception class name when the failure has no message")
    void downOnFailureWithNullMessage() {
        when(pool.query(anyString())).thenReturn(query);
        when(query.execute()).thenReturn(Future.failedFuture(new RuntimeException()));

        DatabaseHealthCheck check = new DatabaseHealthCheck(pool);
        Future<HealthCheckResult> future = check.check();
        HealthCheckResult result = future.result();

        assertTrue(future.succeeded());
        assertEquals(HealthStatus.DOWN, result.status());
        assertEquals("java.lang.RuntimeException", result.data().get("error"));
    }

    @Test
    @DisplayName("name returns 'database'")
    void name() {
        DatabaseHealthCheck check = new DatabaseHealthCheck(pool);
        assertEquals("database", check.name());
    }
}
