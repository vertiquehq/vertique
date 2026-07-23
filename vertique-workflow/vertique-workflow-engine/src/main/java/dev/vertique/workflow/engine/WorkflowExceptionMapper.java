// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import dev.vertique.db.exception.DataAccessException;
import dev.vertique.db.exception.OptimisticLockingFailureException;
import dev.vertique.db.exception.PessimisticLockingFailureException;
import dev.vertique.db.exception.TransientDataAccessException;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowException;
import dev.vertique.workflow.exception.WorkflowPersistenceException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Stage-2 (workflow-boundary) exception mapper for the workflow transaction runner.
 *
 * <p>Applied in the runner's outer {@code recover} after {@code TransactionBuilder.execute(...)}
 * has run (and stage 1, {@link WorkflowPgExceptionMapper}, has already mapped DB driver exceptions
 * to {@link DataAccessException} subtypes). It owns the workflow-domain semantics: it translates
 * database failures into workflow exceptions so that callers of the workflow API see workflow
 * exceptions, not data-access exceptions.
 *
 * <p>Translation policy ({@link #translate(Throwable, String)}):
 *
 * <ul>
 *   <li><b>{@link WorkflowException}</b> (the workflow business-rule root and its subtypes —
 *       {@code WorkflowSubjectVersionUnavailableException}, {@code WorkflowMigrationIllegalStateException},
 *       {@code WorkflowMigrationHandlerMissingException}) → returned unchanged by an explicit guard.
 *       The other workflow-semantic exception roots ({@link WorkflowConflictException} and the
 *       not-found/configuration/technical/unavailable families) extend core semantic roots, not
 *       {@link DataAccessException}, so they fall through every translation branch and are returned
 *       unchanged by the final {@code return failure;}. The engine already raises workflow exceptions
 *       at the source (e.g. optimistic conflicts converted from a row-count of zero), and they must
 *       reach the caller intact.</li>
 *   <li><b>{@link OptimisticLockingFailureException}</b> → {@link WorkflowConflictException}
 *       (cause-preserving), matching the engine's source-thrown conflict semantics.</li>
 *   <li><b>{@link PessimisticLockingFailureException}</b> → {@link WorkflowPersistenceException} with
 *       {@code retryable=true}. Although it is <em>not</em> a {@link TransientDataAccessException}
 *       (it extends {@code ConcurrencyFailureException}), a lock-acquisition failure is retry-safe,
 *       so it is classified explicitly before the transient branch.</li>
 *   <li><b>{@link TransientDataAccessException}</b> (any subtype — deadlock, query timeout,
 *       connection failure, and the raw base type produced for SQL-state classes 53/58) →
 *       {@link WorkflowPersistenceException} with {@code retryable=true} (transient infrastructure
 *       failure; re-attempting may succeed).</li>
 *   <li><b>Any other {@link DataAccessException}</b> → {@link WorkflowPersistenceException} with
 *       {@code retryable=false} (non-transient persistence failure).</li>
 *   <li><b>Anything else</b> (e.g. an unknown {@link RuntimeException} such as a programmer or
 *       configuration error) → returned unchanged. Documented policy: do not classify non-DB,
 *       non-workflow throwables as either DB or workflow business errors.</li>
 * </ul>
 *
 * <p>Order matters: the specific {@link OptimisticLockingFailureException} and
 * {@link PessimisticLockingFailureException} cases, then the {@link TransientDataAccessException}
 * branch (which subsumes deadlock / timeout / connection / raw-transient subtypes), are tested
 * before the generic {@link DataAccessException} fallback.
 *
 * <p><b>Message sanitization invariant:</b> the workflow-layer exception's own message intentionally
 * excludes the upstream {@link DataAccessException#getMessage()} text to avoid forwarding potentially
 * sensitive database detail (e.g. SQL fragments, table/column names) into the workflow API surface.
 * Diagnostic root detail is preserved on the chained {@code cause} and remains accessible via
 * {@link Throwable#getCause()}.
 */
@Singleton
public class WorkflowExceptionMapper {

    /**
     * Creates a stateless workflow-boundary exception mapper.
     *
     * <p>Public so dialect modules and their tests can construct the stage-2 mapper directly when
     * assembling a {@code WorkflowTransactionRunner} without Dagger (the same reason the class itself
     * is public — it is used by the dialect runner outside this package).
     */
    @Inject
    public WorkflowExceptionMapper() {}

    /**
     * Translates a transaction-body failure into a workflow-domain exception per the class policy.
     *
     * @param failure   the failure raised by (or propagated through) the transaction body, after
     *                  stage-1 DB mapping
     * @param operation a short identifier of the failed operation, used in the translated message
     * @return the workflow-mapped exception, or {@code failure} unchanged when it is already a
     *         workflow-semantic exception (a {@link WorkflowException} business-rule violation caught
     *         by the explicit guard, or any other workflow semantic exception that is not a
     *         {@link DataAccessException} and therefore falls through to the final branch) or an
     *         unknown non-DB throwable
     */
    public Throwable translate(Throwable failure, String operation) {
        if (failure instanceof WorkflowException) {
            return failure;
        }
        if (failure instanceof OptimisticLockingFailureException dae) {
            return new WorkflowConflictException(message("Optimistic locking conflict", operation, dae), dae);
        }
        if (failure instanceof PessimisticLockingFailureException dae) {
            return new WorkflowPersistenceException(message("Lock acquisition failure", operation, dae), dae, true);
        }
        if (failure instanceof TransientDataAccessException dae) {
            return new WorkflowPersistenceException(
                    message("Transient persistence failure", operation, dae), dae, true);
        }
        if (failure instanceof DataAccessException dae) {
            return new WorkflowPersistenceException(message("Persistence failure", operation, dae), dae, false);
        }
        return failure;
    }

    /**
     * Builds a sanitized, non-sensitive message combining the failure kind, the operation label,
     * the cause's simple class name, and the SQL state when available.
     *
     * <p>The upstream {@link DataAccessException#getMessage()} text is intentionally excluded.
     * Forwarding raw DB messages into the workflow exception's own message creates a latent
     * information-leak coupling: if the DB layer's message policy changes (e.g. SQL fragments
     * appear), the workflow API would inadvertently surface that detail to callers. Root detail
     * is preserved on the chained cause instead.
     *
     * @param kind      the human-readable failure category (e.g. {@code "Deadlock"})
     * @param operation the short operation identifier used in the translated message
     * @param cause     the underlying data-access exception whose type and SQL state are used
     * @return a sanitized message of the form
     *         {@code "<kind> during workflow operation '<operation>' [<CauseSimpleName>]"} or
     *         {@code "<kind> during workflow operation '<operation>' [<CauseSimpleName>, sqlState=<state>]"}
     *         when a SQL state is present
     */
    private static String message(String kind, String operation, DataAccessException cause) {
        String causeType = cause.getClass().getSimpleName();
        String sqlState = cause.sqlState();
        String qualifier = sqlState != null ? causeType + ", sqlState=" + sqlState : causeType;
        return kind + " during workflow operation '" + operation + "' [" + qualifier + "]";
    }
}
