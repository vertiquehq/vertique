// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.exception;

import dev.vertique.core.exception.ConflictException;

/**
 * Thrown when an optimistic-concurrency conflict is detected during a workflow instance update.
 *
 * <p>The engine uses optimistic locking (version field) when updating workflow instances. If
 * another concurrent transaction has already updated the instance (incrementing the version), the
 * update will match 0 rows and this exception is thrown. The calling transaction is rolled back.
 */
public class WorkflowConflictException extends ConflictException {

    /**
     * Creates a new {@code WorkflowConflictException} with the given message.
     *
     * @param message description of the conflict
     */
    public WorkflowConflictException(String message) {
        super(message);
    }

    /**
     * Creates a new {@code WorkflowConflictException} with the given message and underlying cause.
     *
     * <p>Used when a conflict surfaces from a lower layer (e.g. an optimistic-locking failure raised
     * by the database driver and translated by {@code WorkflowExceptionMapper}) so the originating
     * exception is preserved as the cause rather than discarded.
     *
     * @param message description of the conflict
     * @param cause   the underlying exception that caused this conflict
     */
    public WorkflowConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
