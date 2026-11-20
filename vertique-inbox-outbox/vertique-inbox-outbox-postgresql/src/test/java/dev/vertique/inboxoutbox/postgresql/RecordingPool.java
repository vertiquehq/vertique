// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.mockito.Mockito.mock;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps a real pool and records every prepared statement it is asked to run and every result set the database
 * sent back, so a test can prove what a service selects and what it received.
 */
final class RecordingPool {

    /** One prepared statement and the rows the database returned for it, per execution. */
    record Statement(String sql, List<RowSet<Row>> results) {}

    private final List<Statement> statements = new CopyOnWriteArrayList<>();
    private final Pool pool;

    RecordingPool(Pool delegate) {
        this.pool = mock(Pool.class, invocation -> {
            Object result = invocation.getMethod().invoke(delegate, invocation.getArguments());
            if ("preparedQuery".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length == 1
                    && invocation.getArguments()[0] instanceof String sql) {
                @SuppressWarnings("unchecked")
                PreparedQuery<RowSet<Row>> real = (PreparedQuery<RowSet<Row>>) result;
                Statement statement = new Statement(sql, new CopyOnWriteArrayList<>());
                statements.add(statement);
                return recording(real, statement);
            }
            return result;
        });
    }

    Pool pool() {
        return pool;
    }

    List<Statement> statements() {
        return List.copyOf(statements);
    }

    void clear() {
        statements.clear();
    }

    @SuppressWarnings("unchecked")
    private static PreparedQuery<RowSet<Row>> recording(PreparedQuery<RowSet<Row>> real, Statement statement) {
        return mock(PreparedQuery.class, invocation -> {
            Object result = invocation.getMethod().invoke(real, invocation.getArguments());
            if ("execute".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length == 1
                    && invocation.getArguments()[0] instanceof Tuple
                    && result instanceof Future<?> future) {
                future.onSuccess(rows -> statement.results().add((RowSet<Row>) rows));
            }
            return result;
        });
    }
}
