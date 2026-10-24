// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import io.vertx.sqlclient.Tuple;

/**
 * Test client shaped like a connection whose Vert.x transaction is pending, which is the only client
 * state the workflow engine's transaction-aware entrypoints accept. The only SQL it answers is a prepared
 * statement that returns no rows, which is what taking an instance's execution lock needs.
 */
final class ActiveTransactionConnection {

    private ActiveTransactionConnection() {}

    /**
     * Creates a connection mock that reports a transaction whose completion has not happened yet.
     *
     * @return a fresh connection mock; every prepared statement succeeds with an empty result
     */
    @SuppressWarnings("unchecked")
    static SqlConnection create() {
        Transaction transaction = mock(Transaction.class);
        lenient()
                .when(transaction.completion())
                .thenReturn(Promise.<Void>promise().future());
        SqlConnection connection = mock(SqlConnection.class);
        lenient().when(connection.transaction()).thenReturn(transaction);
        PreparedQuery<RowSet<Row>> statement = mock(PreparedQuery.class);
        lenient().when(statement.execute(any(Tuple.class))).thenReturn(Future.succeededFuture(mock(RowSet.class)));
        lenient().when(connection.preparedQuery(anyString())).thenReturn(statement);
        return connection;
    }
}
