// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.job.delayed.exception.DelayedJobPersistenceException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Stage-2 (delayed-job-boundary) exception mapper for the delayed-job enqueue pipeline.
 *
 * <p>Applied in the enqueue methods' outer {@code recover} after the repository save has run (and
 * stage 1, {@code PgDbExceptionMapper}, has already mapped DB driver exceptions to
 * {@link DataAccessException} subtypes). It owns the delayed-job-domain semantics: it translates
 * database failures into delayed-job exceptions so that callers of the delayed-job API see
 * delayed-job exceptions, not data-access exceptions.
 *
 * <p>Translation policy ({@link #translate(Throwable, String)}):
 *
 * <ul>
 *   <li><b>{@link OptimisticLockingFailureException}</b> → {@link DelayedJobPersistenceException}
 *       with {@code retryable=true}. Optimistic locking failures are transient — re-attempting may
 *       succeed.</li>
 *   <li><b>{@link PessimisticLockingFailureException}</b> → {@link DelayedJobPersistenceException}
 *       with {@code retryable=true}. Although it is not a {@link TransientDataAccessException},
 *       a lock-acquisition failure is retry-safe, so it is classified explicitly before the
 *       transient branch.</li>
 *   <li><b>{@link TransientDataAccessException}</b> (any subtype — deadlock, query timeout,
 *       connection failure) → {@link DelayedJobPersistenceException} with {@code retryable=true}
 *       (transient infrastructure failure; re-attempting may succeed).</li>
 *   <li><b>Any other {@link DataAccessException}</b> → {@link DelayedJobPersistenceException} with
 *       {@code retryable=false} (non-transient persistence failure).</li>
 *   <li><b>Anything else</b> (e.g. validation {@link IllegalArgumentException} or an
 *       already-semantic exception) → returned unchanged. Non-DB, non-delayed-job throwables are
 *       not classified here.</li>
 * </ul>
 *
 * <p>Order matters: the specific {@link OptimisticLockingFailureException} and
 * {@link PessimisticLockingFailureException} cases, then the {@link TransientDataAccessException}
 * branch, are tested before the generic {@link DataAccessException} fallback.
 *
 * <p><b>Message sanitization invariant:</b> the delayed-job exception's own message intentionally
 * excludes the upstream {@link DataAccessException#getMessage()} text to avoid forwarding potentially
 * sensitive database detail (e.g. SQL fragments, table/column names) into the delayed-job API
 * surface. Diagnostic root detail is preserved on the chained {@code cause} and remains accessible
 * via {@link Throwable#getCause()}.
 */
@Singleton
public class DelayedJobExceptionMapper {

    /**
     * Creates a stateless delayed-job-boundary exception mapper.
     */
    @Inject
    public DelayedJobExceptionMapper() {}

    /**
     * Translates a repository failure into a delayed-job-domain exception per the class policy.
     *
     * @param failure   the failure raised by (or propagated through) the repository save, after
     *                  stage-1 DB mapping
     * @param operation a short identifier of the failed operation, used in the translated message
     * @return the delayed-job-mapped exception, or {@code failure} unchanged when it is a
     *         validation {@link IllegalArgumentException}, an already-semantic exception, or any
     *         other non-{@link DataAccessException} throwable
     */
    public Throwable translate(Throwable failure, String operation) {
        if (failure instanceof OptimisticLockingFailureException dae) {
            return new DelayedJobPersistenceException(
                    message("Optimistic locking conflict", operation, dae), dae, true);
        }
        if (failure instanceof PessimisticLockingFailureException dae) {
            return new DelayedJobPersistenceException(message("Lock acquisition failure", operation, dae), dae, true);
        }
        if (failure instanceof TransientDataAccessException dae) {
            return new DelayedJobPersistenceException(
                    message("Transient persistence failure", operation, dae), dae, true);
        }
        if (failure instanceof DataAccessException dae) {
            return new DelayedJobPersistenceException(message("Persistence failure", operation, dae), dae, false);
        }
        return failure;
    }

    /**
     * Builds a sanitized, non-sensitive message combining the failure kind, the operation label,
     * the cause's simple class name, and the SQL state when available.
     *
     * <p>The upstream {@link DataAccessException#getMessage()} text is intentionally excluded.
     * Forwarding raw DB messages into the delayed-job exception's own message creates a latent
     * information-leak coupling: if the DB layer's message policy changes (e.g. SQL fragments
     * appear), the delayed-job API would inadvertently surface that detail to callers. Root detail
     * is preserved on the chained cause instead.
     *
     * @param kind      the human-readable failure category (e.g. {@code "Deadlock"})
     * @param operation the short operation identifier used in the translated message
     * @param cause     the underlying data-access exception whose type and SQL state are used
     * @return a sanitized message of the form
     *         {@code "<kind> during delayed job operation '<operation>' [<CauseSimpleName>]"} or
     *         {@code "<kind> during delayed job operation '<operation>' [<CauseSimpleName>, sqlState=<state>]"}
     *         when a SQL state is present
     */
    private static String message(String kind, String operation, DataAccessException cause) {
        String causeType = cause.getClass().getSimpleName();
        String sqlState = cause.sqlState();
        String qualifier = sqlState != null ? causeType + ", sqlState=" + sqlState : causeType;
        return kind + " during delayed job operation '" + operation + "' [" + qualifier + "]";
    }
}
