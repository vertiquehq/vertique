// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import jakarta.annotation.Nullable;

/**
 * Validates the caller-supplied SQL client of the engine's public transaction-aware entrypoints.
 *
 * <p>The engine only runs correctly on a {@link SqlConnection} whose transaction was begun through
 * Vert.x and has not yet completed: that is the only state in which the connection reports a
 * {@link SqlConnection#transaction() transaction} with a pending {@link Transaction#completion()
 * completion}. The following clients are therefore rejected:
 *
 * <ul>
 *   <li>a missing client;
 *   <li>a client that is not a connection, such as a pool or a wrapper around a connection;
 *   <li>a connection that has no Vert.x transaction, which includes a connection on which the
 *       caller issued a textual {@code BEGIN};
 *   <li>a connection whose Vert.x transaction has already completed.
 * </ul>
 *
 * <p>The check is a pure inspection of the handle: it never issues SQL and never blocks. Engine
 * entrypoints call {@link #reject} before their first delegation, so a rejected call never reaches
 * a repository, dedup, history, task or timer collaborator.
 */
final class WorkflowTransactionClientGuard {

    private WorkflowTransactionClientGuard() {}

    /**
     * Checks the client supplied to a transaction-aware entrypoint.
     *
     * @param operation the name of the entrypoint, used in the failure message
     * @param client the caller-supplied client, possibly {@code null}
     * @param <T> the result type of the entrypoint
     * @return {@code null} when the client is a connection with a pending Vert.x transaction;
     *     otherwise a failed future carrying an {@link IllegalArgumentException} that names the
     *     operation and the problem. Never throws.
     */
    @Nullable
    static <T> Future<T> reject(String operation, @Nullable SqlClient client) {
        String problem = problemOf(client);
        if (problem == null) {
            return null;
        }
        return Future.failedFuture(new IllegalArgumentException(operation
                + " requires a SqlConnection with a pending Vert.x transaction begun on that connection, but "
                + problem));
    }

    /**
     * Returns the reason the client is unusable, or {@code null} when it is valid.
     *
     * @param client the caller-supplied client, possibly {@code null}
     * @return a short description of the problem, or {@code null}
     */
    @Nullable
    static String problemOf(@Nullable SqlClient client) {
        if (client == null) {
            return "no client was supplied";
        }
        if (!(client instanceof SqlConnection connection)) {
            return "the client is not a connection";
        }
        try {
            Transaction transaction = connection.transaction();
            if (transaction == null) {
                return "the connection has no Vert.x transaction";
            }
            Future<Void> completion = transaction.completion();
            if (completion == null) {
                return "the connection's transaction has no completion state";
            }
            if (completion.isComplete()) {
                return "the connection's transaction has already completed";
            }
            return null;
        } catch (RuntimeException e) {
            return "the connection's transaction state could not be inspected";
        }
    }
}
