// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Scoped savepoint utility for partial rollback within an active transaction.
 *
 * <p>On success, the savepoint is released. On failure, the savepoint is rolled back to and the
 * original error is propagated. The surrounding transaction remains open in both cases.
 *
 * <pre>{@code
 * repository.transaction().serializable().execute(conn ->
 *     doPartOne(conn)
 *         .compose(v -> Savepoint.execute(conn, "sp1", c -> doRiskyWork(c)))
 *         .recover(err -> doFallback(conn))
 * );
 * }</pre>
 *
 * <p>The connection must be within an active transaction. Savepoint names must be valid SQL
 * identifiers.
 */
public final class Savepoint {

    /** Valid SQL identifier: starts with letter/underscore, contains only alphanumerics/underscores. */
    private static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private Savepoint() {}

    /**
     * Executes the given function within a named savepoint. On success, releases the savepoint and
     * returns the result. On failure, rolls back to the savepoint and propagates the original error.
     *
     * @param conn the SQL connection (must be within an active transaction)
     * @param name the savepoint name (must be a valid SQL identifier)
     * @param fn   the function to execute within the savepoint scope
     * @param <T>  the result type
     * @return a future that completes with the function's result on success, or fails with the
     *         function's error on failure (after rolling back to the savepoint)
     */
    public static <T> Future<T> execute(SqlConnection conn, String name, Function<SqlConnection, Future<T>> fn) {
        Objects.requireNonNull(conn, "conn");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(fn, "fn");
        if (!VALID_NAME.matcher(name).matches()) {
            return Future.failedFuture(new IllegalArgumentException("Invalid savepoint name: " + name
                    + " — must be a valid SQL identifier (letters, digits, underscores)"));
        }
        return conn.query("SAVEPOINT " + name)
                .execute()
                .mapEmpty()
                .compose(v -> fn.apply(conn))
                .compose(
                        result -> conn.query("RELEASE SAVEPOINT " + name)
                                .execute()
                                .mapEmpty()
                                .map(result),
                        err -> conn.query("ROLLBACK TO SAVEPOINT " + name)
                                .execute()
                                .mapEmpty()
                                .compose(v -> Future.failedFuture(err)));
    }
}
