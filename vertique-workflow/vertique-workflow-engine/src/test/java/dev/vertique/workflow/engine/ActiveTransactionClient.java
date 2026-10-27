// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;

/**
 * Test client shaped like a connection whose Vert.x transaction is pending, which is the only client
 * state the engine's transaction-aware entrypoints accept.
 */
final class ActiveTransactionClient {

    private ActiveTransactionClient() {}

    /**
     * Creates a connection mock that reports a transaction whose completion has not happened yet.
     *
     * @return a fresh connection mock; it answers plain statements, such as the savepoint statements of a segment, with
     *     an empty success and no other SQL
     */
    @SuppressWarnings("unchecked")
    static SqlConnection create() {
        Transaction transaction = mock(Transaction.class);
        lenient()
                .when(transaction.completion())
                .thenReturn(Promise.<Void>promise().future());
        SqlConnection connection = mock(SqlConnection.class);
        lenient().when(connection.transaction()).thenReturn(transaction);
        Query<RowSet<Row>> statement = mock(Query.class);
        lenient().when(statement.execute()).thenReturn(Future.succeededFuture());
        lenient().when(connection.query(anyString())).thenReturn(statement);
        return connection;
    }
}
