// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.exception;

import dev.vertique.core.exception.TechnicalException;

/**
 * Technical root for inbox/outbox infrastructure failures.
 *
 * <p>Base class for infrastructure-level runtime failures raised within the transactional-messaging
 * subsystem. Extends {@link TechnicalException} so inbox/outbox technical failures align with the
 * framework-wide technical exception hierarchy.
 *
 * <p>Concrete subtypes, such as {@link InboxOutboxPersistenceException}, refine this class with
 * domain-specific semantics (e.g. retryability).
 */
public class InboxOutboxTechnicalException extends TechnicalException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public InboxOutboxTechnicalException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public InboxOutboxTechnicalException(String message, Throwable cause) {
        super(message, cause);
    }
}
