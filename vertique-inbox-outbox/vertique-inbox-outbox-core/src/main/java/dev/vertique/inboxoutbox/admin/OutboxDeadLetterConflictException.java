// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.ValidationException;

/**
 * Thrown when an administration request conflicts with the current state of the store.
 *
 * <p>{@link #reason()} tells the cases apart. The message is a fixed text per reason and contains no
 * SQL, driver text, payload, reason or actor data. An {@link Reason#OPERATION_ID_CONFLICT} reveals
 * nothing about the receipt it collided with.
 */
public class OutboxDeadLetterConflictException extends ConflictException {

    private static final long serialVersionUID = 1L;

    /** Why the request conflicted. */
    public enum Reason {

        /** The target entry exists but is not dead-lettered. */
        NOT_DEAD_LETTER("The outbox entry is not dead-lettered"),

        /** The entry changed since the caller read it: the expected revision does not match. */
        REVISION_CONFLICT("The outbox entry revision does not match the expected revision"),

        /** The operation ID was already used with a different intent. */
        OPERATION_ID_CONFLICT("The operation ID was already used for a different request"),

        /** The stored payload and headers exceed the configured content limit. */
        CONTENT_LIMIT_EXCEEDED("The outbox entry content exceeds the configured limit"),

        /** A retry cannot increment the revision because it is already at its maximum. */
        REVISION_EXHAUSTED("The outbox entry revision cannot be incremented further");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    private final Reason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the request conflicted; never {@code null}
     * @throws ValidationException if {@code reason} is {@code null}
     */
    public OutboxDeadLetterConflictException(Reason reason) {
        super(AdminChecks.required(reason, "reason").message());
        this.reason = reason;
    }

    /**
     * Returns why the request conflicted.
     *
     * @return the reason
     */
    public Reason reason() {
        return reason;
    }
}
