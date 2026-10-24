// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * A transaction bounded by {@code TransactionBuilder.deadline(Duration)} did not complete by its
 * deadline, or a statement was executed on its connection after the deadline had passed.
 *
 * <p>Non-transient: this is deliberately not a {@link TransientDataAccessException}, so default
 * retry classification does not retry it. Running the same work under the same deadline is not
 * expected to succeed. The exception passes through {@link dev.vertique.db.DbExceptionMapper}
 * untranslated.
 */
public class TransactionDeadlineExceededException extends DataAccessException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public TransactionDeadlineExceededException(String message) {
        super(message);
    }
}
