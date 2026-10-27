// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.exception.WorkflowInvalidPayloadException;
import dev.vertique.workflow.exception.WorkflowSignalRejectedException;
import dev.vertique.workflow.exception.WorkflowStaleSubjectVersionException;
import dev.vertique.workflow.exception.WorkflowSubjectVersionUnavailableException;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * INTERNAL: runs one operation of a segment under a database savepoint, so that a deterministic failure of that
 * operation undoes its writes and nothing else.
 *
 * <p>A failure is classified before the connection is touched again. A failure that stands for a lost or timed-out
 * transaction (the segment deadline, a lost claim, a transient database failure) and any failure that is not a
 * deterministic one of the operation itself is passed on as it is, and the segment rolls back as a whole. Only a
 * step-limit overrun and a deterministic failure (an invalid payload, a callback or definition failure, a reviewed
 * subject version that does not match the task's, a task that requires a versioned subject on an instance that has
 * none) are contained:
 * the transaction rolls back to the savepoint and the outcome is returned for the caller to settle.
 */
public final class Savepoints {

    private static final int MAX_CAUSE_DEPTH = 16;

    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** Why an operation was rolled back to its savepoint. */
    public enum Containment {
        /** The operation executed more nodes than the segment's step cap allows. */
        STEP_LIMIT,
        /** The operation failed in a way that a retry repeats. */
        DETERMINISTIC
    }

    /**
     * What an operation under a savepoint came to.
     *
     * @param <T> the result type of the operation
     */
    public sealed interface Attempt<T> {

        /**
         * The operation succeeded and its savepoint was released.
         *
         * @param value the operation's result
         * @param <T>   the result type
         */
        record Applied<T>(T value) implements Attempt<T> {}

        /**
         * The operation failed and the transaction was rolled back to its savepoint.
         *
         * @param kind  why the operation was contained
         * @param cause the failure; for logging only, never stored
         * @param <T>   the result type
         */
        record Contained<T>(Containment kind, Throwable cause) implements Attempt<T> {}
    }

    private Savepoints() {
        // static helpers only
    }

    /**
     * Runs an operation under a savepoint.
     *
     * @param tx   the segment's transaction handle
     * @param work the operation
     * @param <T>  the result type of the operation
     * @return the outcome; the future fails when the failure is not one to contain, and the transaction is then left
     *     for the caller to roll back
     */
    public static <T> Future<Attempt<T>> attempt(SqlClient tx, Supplier<Future<T>> work) {
        String name = "wf_sp_" + SEQUENCE.incrementAndGet();
        return tx.query("SAVEPOINT " + name).execute().compose(started -> {
            Future<T> running;
            try {
                running = work.get();
            } catch (RuntimeException e) {
                running = Future.failedFuture(e);
            }
            return running.transform(result -> {
                if (result.succeeded()) {
                    T value = result.result();
                    return tx.query("RELEASE SAVEPOINT " + name)
                            .execute()
                            .<Attempt<T>>map(released -> new Attempt.Applied<>(value));
                }
                Containment kind = classify(result.cause());
                if (kind == null) {
                    return Future.<Attempt<T>>failedFuture(result.cause());
                }
                return tx.query("ROLLBACK TO SAVEPOINT " + name)
                        .execute()
                        .compose(rolledBack ->
                                tx.query("RELEASE SAVEPOINT " + name).execute())
                        .<Attempt<T>>map(released -> new Attempt.Contained<>(kind, result.cause()));
            });
        });
    }

    private static Containment classify(Throwable failure) {
        if (Dispositions.isDeadline(failure) || Dispositions.isClaimLost(failure) || isTransientIn(failure)) {
            return null;
        }
        if (Dispositions.stepLimit(failure) != null) {
            return Containment.STEP_LIMIT;
        }
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof WorkflowInvalidPayloadException
                    || cause instanceof WorkflowDefinitionException
                    || cause instanceof WorkflowSignalRejectedException
                    || cause instanceof WorkflowStaleSubjectVersionException
                    || cause instanceof WorkflowSubjectVersionUnavailableException) {
                return Containment.DETERMINISTIC;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return null;
    }

    private static boolean isTransientIn(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (Dispositions.isTransient(cause)) {
                return true;
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return false;
    }
}
