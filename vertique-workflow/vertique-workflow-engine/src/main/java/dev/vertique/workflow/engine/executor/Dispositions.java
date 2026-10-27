// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.TransactionDeadlineExceededException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;

/**
 * INTERNAL: the rules that decide how a failed segment is disposed of.
 *
 * <p>A failure is read as the transaction runner returns it, after both of its mapping stages. It is, in this order:
 * a deadline overrun (crash-like: no disposition, the attempt stays spent), a step-limit overrun (the instance is
 * failed), a transient failure (the command is rescheduled without spending its attempt) or anything else, which a
 * retry repeats (the command and its instance are failed).
 */
final class Dispositions {

    /** The deepest cause chain examined for a deadline overrun or a step-limit overrun. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private Dispositions() {
        // static helpers only
    }

    /**
     * The pause before the retry of a transient failure: the base doubled once per earlier failure, capped at the
     * maximum.
     *
     * @param transientCount the command's transient failure count including this failure; at least 1
     * @param baseMs         the pause before the first retry, in milliseconds
     * @param maxMs          the longest pause, in milliseconds
     * @return the pause in milliseconds; equal to {@code baseMs} for a count of 1
     */
    static long transientBackoffMs(int transientCount, long baseMs, long maxMs) {
        long backoff = baseMs;
        for (int doublings = 1; doublings < transientCount; doublings++) {
            if (backoff >= maxMs) {
                break;
            }
            backoff = backoff > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : backoff * 2;
        }
        return Math.min(backoff, maxMs);
    }

    /**
     * Whether the failure is a transient one: a connection failure, a timeout, a serialization failure, a deadlock,
     * a lock timeout, an optimistic conflict of the instance, or an executor context that is not clean (a security
     * context or a deferred-execution origin already bound: the fault is the executor's environment, not the instance).
     *
     * <p>The workflow mapping stage returns the first four as a retryable {@link WorkflowPersistenceException} and the
     * optimistic conflict as a {@link WorkflowConflictException}; the database-layer types are accepted as well for a
     * failure that reaches the executor without that stage. A conflict subtype that signals a business rule (a stale
     * assignment, a duplicate key) is not an optimistic conflict.
     *
     * @param failure the failure as the runner returned it
     * @return {@code true} if the segment is retried without spending its attempt
     */
    static boolean isTransient(Throwable failure) {
        if (causeOfType(failure, ExecutorContextNotCleanException.class) != null) {
            return true;
        }
        if (failure instanceof WorkflowPersistenceException persistence) {
            return persistence.retryable();
        }
        return failure.getClass() == WorkflowConflictException.class
                || failure instanceof TransientDataAccessException
                || failure instanceof PessimisticLockingFailureException
                || failure instanceof OptimisticLockingFailureException;
    }

    /**
     * Whether the failure is, or is caused by, the overrun of the segment's deadline.
     *
     * @param failure the failure
     * @return {@code true} for the deadline outcome
     */
    static boolean isDeadline(Throwable failure) {
        return causeOfType(failure, TransactionDeadlineExceededException.class) != null;
    }

    /**
     * Whether the failure is, or is caused by, a settlement that found the claim gone.
     *
     * @param failure the failure
     * @return {@code true} if there is nothing to write: the claim lapses
     */
    static boolean isClaimLost(Throwable failure) {
        return causeOfType(failure, ClaimLostException.class) != null;
    }

    /**
     * The step-limit failure the failure is, or is caused by.
     *
     * @param failure the failure
     * @return the step-limit failure, or {@code null}
     */
    static StepLimitExceededException stepLimit(Throwable failure) {
        return causeOfType(failure, StepLimitExceededException.class);
    }

    private static <T extends Throwable> T causeOfType(Throwable failure, Class<T> type) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (type.isInstance(cause)) {
                return type.cast(cause);
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return null;
    }
}
