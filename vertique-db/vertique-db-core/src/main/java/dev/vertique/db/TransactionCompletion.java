// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs work strictly after a database transaction settles.
 *
 * <p>Call {@link #onSettled} from inside the transaction's work, with the connection the work received, to be told
 * once whether the transaction committed. The signal is the connection's own {@link Transaction#completion()}, so it
 * works for a transaction opened by {@link TransactionBuilder}, by {@code Pool.withTransaction}, or by the
 * application itself.
 *
 * <pre>{@code
 * repository.transaction().execute(conn ->
 *     writeRows(conn).onSuccess(v -> TransactionCompletion.onSettled(
 *         conn,
 *         () -> publish(rows),
 *         cause -> log.debug("not committed", cause))));
 * }</pre>
 *
 * <p>This is a notification, not a durability mechanism: if the process stops after the commit, the callback never
 * runs. Use an outbox for work that must not be lost.
 *
 * <p>A failed SQL statement fails the whole transaction, even when the work recovers from it and rolls back to a
 * savepoint: the commit does not happen and the failure callback runs.
 */
@Slf4j
public final class TransactionCompletion {

    private TransactionCompletion() {}

    /**
     * Runs {@code onCommit} after {@code tx} commits, or {@code onFailure} when it does not.
     *
     * <p>The transaction's completion is captured once, here, because the connection forgets its transaction as soon as
     * the commit or rollback starts. Exactly one of the two callbacks runs if the transaction settles. Registrations
     * are independent and unordered, a rollback to a savepoint does not settle the outer transaction, and a
     * registration made after the transaction settled is rejected rather than delivered.
     *
     * <p>{@code onFailure} receives the raw settlement failure: an explicit rollback reports a rollback exception, a
     * failed commit reports the commit or transport error, and a connection lost during the commit may leave the
     * database outcome unknown. It does not carry the application's own failure; capture that separately.
     *
     * <p>The callbacks run on the transaction's Vert.x context, are never offloaded, and must not block. They run
     * before the transaction's own result future completes. Anything a callback throws, an {@link Error} included, is
     * caught, logged at warn, and swallowed; it does not affect the enclosing operation or the other registrations.
     *
     * @param tx        the connection the transaction work received
     * @param onCommit  runs after the commit
     * @param onFailure runs with the settlement failure when the transaction did not commit
     * @throws IllegalStateException if {@code tx} is not a connection, has no active transaction, or its transaction
     *                               has already settled
     */
    public static void onSettled(SqlClient tx, Runnable onCommit, Consumer<Throwable> onFailure) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(onCommit, "onCommit");
        Objects.requireNonNull(onFailure, "onFailure");
        if (!(tx instanceof SqlConnection connection)) {
            throw new IllegalStateException(
                    "The commit signal needs a connection, not " + tx.getClass().getName());
        }
        Transaction transaction = connection.transaction();
        if (transaction == null) {
            throw new IllegalStateException("The connection has no active transaction");
        }
        Future<Void> completion = transaction.completion();
        if (completion.isComplete()) {
            throw new IllegalStateException("The transaction has already settled");
        }
        completion.onComplete(settled -> {
            try {
                if (settled.succeeded()) {
                    onCommit.run();
                } else {
                    onFailure.accept(settled.cause());
                }
            } catch (Throwable thrown) {
                log.warn("A transaction completion callback failed", thrown);
            }
        });
    }
}
