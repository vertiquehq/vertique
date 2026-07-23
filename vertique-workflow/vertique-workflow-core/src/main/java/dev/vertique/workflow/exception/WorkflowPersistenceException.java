// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

/**
 * Thrown when a workflow operation fails because of a persistence-layer error that has no more
 * specific workflow-domain meaning.
 *
 * <p>This is the workflow-domain projection of a database failure that crossed the workflow API
 * boundary. The workflow engine runs every mutation inside a transaction whose outer {@code recover}
 * stage translates {@link dev.vertique.db.exception.DataAccessException} subtypes into workflow
 * exceptions (see {@code WorkflowExceptionMapper}). Database failures that map to a specific workflow
 * concept (e.g. an optimistic-locking failure → {@link WorkflowConflictException}) use that type;
 * everything else — deadlocks, lock-acquisition timeouts, statement timeouts, connection failures,
 * and generic data-access errors — surfaces as a {@code WorkflowPersistenceException}.
 *
 * <p>The {@link #retryable()} flag tells callers whether re-attempting the same operation has a
 * reasonable chance of succeeding. Transient infrastructure failures (deadlock, lock timeout, query
 * timeout) are retryable; a generic data-access failure is not, by default.
 */
public class WorkflowPersistenceException extends WorkflowTechnicalException {

    private final boolean retryable;

    /**
     * Creates a new {@code WorkflowPersistenceException}.
     *
     * @param message   human-readable description of the persistence failure
     * @param cause     the underlying persistence-layer exception that caused this error
     * @param retryable {@code true} if re-attempting the failed operation may succeed (transient
     *                  infrastructure failure); {@code false} for a non-transient failure
     */
    public WorkflowPersistenceException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * Returns whether re-attempting the failed operation may succeed.
     *
     * @return {@code true} for a transient infrastructure failure (deadlock, lock timeout, query
     *         timeout); {@code false} for a non-transient failure
     */
    public boolean retryable() {
        return retryable;
    }
}
