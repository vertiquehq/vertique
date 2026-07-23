// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.exception;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Configuration or contract failure within the inbox/outbox subsystem.
 *
 * <p>Base class for all configuration and contract violations raised during inbox/outbox
 * startup or wiring. Extends {@link ConfigurationException} so the transactional-messaging
 * module's failures align with the framework-wide configuration exception hierarchy.
 *
 * <p>Examples include: invalid claim-scope suppliers, misconfigured destination handlers,
 * and other wiring faults that are detected at startup or during the first claim cycle.
 */
public class InboxOutboxConfigurationException extends ConfigurationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message
     */
    public InboxOutboxConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given message and cause.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public InboxOutboxConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
