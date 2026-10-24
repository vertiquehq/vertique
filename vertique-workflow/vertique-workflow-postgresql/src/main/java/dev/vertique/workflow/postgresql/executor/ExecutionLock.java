// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.executor;

import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The per-instance execution lock: an exclusive, transaction-scoped PostgreSQL advisory lock in the two-integer form.
 *
 * <p>Both integers derive from the instance id under a namespace prefix of their own. PostgreSQL keeps the
 * two-integer and the single-bigint advisory lock spaces apart, so this lock never collides with the single-bigint
 * definition locks. Held until the transaction commits or rolls back; taking a lock the transaction already holds
 * is a no-op.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li>{@link #acquire}: waits. The caller's {@code lock_timeout} is saved, replaced by the configured timeout for
 *       this one acquisition and restored, all in one statement, so the timeout never leaks into the rest of the
 *       caller's transaction. A timeout or a deadlock fails the statement and PostgreSQL aborts the
 *       transaction.</li>
 *   <li>{@link #tryAcquire}: never waits; answers whether the lock was free.</li>
 * </ul>
 *
 * <p>The wait statement is a chain of data-dependent common table expressions, so the order save, set, acquire,
 * restore does not depend on the planner.
 */
final class ExecutionLock {

    private static final String NAMESPACE = "vertique.workflow.execution-lock:";

    /** The largest value PostgreSQL accepts for {@code lock_timeout}, in milliseconds. */
    private static final long MAX_LOCK_TIMEOUT_MS = Integer.MAX_VALUE;

    private static final String ACQUIRE_SQL = """
            WITH saved AS MATERIALIZED (
                SELECT current_setting('lock_timeout') AS value
            ), armed AS MATERIALIZED (
                SELECT set_config('lock_timeout', $1, true) AS applied FROM saved
            ), locked AS MATERIALIZED (
                SELECT pg_advisory_xact_lock($2::int4, $3::int4) AS acquired FROM armed
            )
            SELECT set_config('lock_timeout', saved.value, true) FROM saved, locked
            """;

    private static final String TRY_SQL = "SELECT pg_try_advisory_xact_lock($1::int4, $2::int4)";

    private final String timeoutSetting;

    /**
     * Creates the lock with the wait applied to {@link #acquire}.
     *
     * @param lockTimeoutMs the longest wait for the lock, in milliseconds; at least 1
     */
    ExecutionLock(long lockTimeoutMs) {
        if (lockTimeoutMs < 1) {
            throw new IllegalArgumentException("lockTimeoutMs must be at least 1");
        }
        this.timeoutSetting = Math.min(lockTimeoutMs, MAX_LOCK_TIMEOUT_MS) + "ms";
    }

    /**
     * Takes the instance's execution lock, waiting up to the configured timeout.
     *
     * @param instance the instance to lock
     * @param tx       the caller's transaction
     * @return a {@link Future} that completes once the transaction holds the lock; it fails with the database error
     *     when the wait times out or a deadlock is detected
     */
    Future<Void> acquire(WorkflowInstanceId instance, SqlClient tx) {
        int[] key = keyOf(instance);
        return tx.preparedQuery(ACQUIRE_SQL)
                .execute(Tuple.of(timeoutSetting, key[0], key[1]))
                .mapEmpty();
    }

    /**
     * Takes the instance's execution lock if it is free, without waiting.
     *
     * @param instance the instance to lock
     * @param tx       the caller's transaction
     * @return a {@link Future} containing {@code true} when the transaction now holds the lock and {@code false}
     *     when another transaction holds it
     */
    Future<Boolean> tryAcquire(WorkflowInstanceId instance, SqlClient tx) {
        int[] key = keyOf(instance);
        return tx.preparedQuery(TRY_SQL)
                .execute(Tuple.of(key[0], key[1]))
                .map(rows -> rows.iterator().next().getBoolean(0));
    }

    /**
     * Derives the two integers of an instance's lock: the two halves of the first eight bytes of the SHA-256 of the
     * namespace-prefixed instance id.
     */
    static int[] keyOf(WorkflowInstanceId instance) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((NAMESPACE + instance.value()).getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(digest);
            return new int[] {buffer.getInt(), buffer.getInt()};
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
