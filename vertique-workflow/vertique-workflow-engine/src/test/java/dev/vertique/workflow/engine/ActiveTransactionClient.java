// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.vertx.core.Promise;
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
     * @return a fresh connection mock; it answers no SQL
     */
    static SqlConnection create() {
        Transaction transaction = mock(Transaction.class);
        lenient()
                .when(transaction.completion())
                .thenReturn(Promise.<Void>promise().future());
        SqlConnection connection = mock(SqlConnection.class);
        lenient().when(connection.transaction()).thenReturn(transaction);
        return connection;
    }
}
