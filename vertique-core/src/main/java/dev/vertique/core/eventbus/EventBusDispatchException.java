// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import dev.vertique.core.exception.TechnicalException;

/**
 * Thrown when an event bus request fails because the recipient actively rejected the message or a
 * fatal error occurred during delivery.
 *
 * <p>This is a transport-level exception produced by {@link EventBusExceptionMapper} when a
 * {@link io.vertx.core.eventbus.ReplyException} with failure type
 * {@link io.vertx.core.eventbus.ReplyFailure#RECIPIENT_FAILURE} or
 * {@link io.vertx.core.eventbus.ReplyFailure#ERROR} is encountered.
 *
 * @see EventBusExceptionMapper
 * @see EventBusTimeoutException
 * @see EventBusAddressUnavailableException
 */
public class EventBusDispatchException extends TechnicalException {

    private final String address;

    /**
     * Constructs a new dispatch exception for the given event bus address.
     *
     * @param address the event bus address where dispatch failed, or {@code null}
     * @param message the failure message from the recipient or transport layer
     * @param cause   the underlying {@link io.vertx.core.eventbus.ReplyException}
     */
    public EventBusDispatchException(String address, String message, Throwable cause) {
        super("Event bus dispatch failed at " + address + ": " + message, cause);
        this.address = address;
    }

    /**
     * Constructs a new dispatch exception with a custom full message. For use by subclasses that
     * need to provide a more specific message while still setting the inherited {@code address}
     * field. The parameter order differs from the public constructor to avoid an erasure conflict.
     *
     * @param cause       the underlying cause
     * @param fullMessage the complete custom detail message
     * @param address     the event bus address where dispatch failed, or {@code null}
     */
    protected EventBusDispatchException(Throwable cause, String fullMessage, String address) {
        super(fullMessage, cause);
        this.address = address;
    }

    /**
     * Returns the event bus address where dispatch failed.
     *
     * @return the address, or {@code null} if not available
     */
    public String address() {
        return address;
    }
}
