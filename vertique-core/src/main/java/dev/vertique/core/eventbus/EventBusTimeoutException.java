// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import dev.vertique.core.exception.TechnicalException;

/**
 * Thrown when an event bus request fails due to a timeout — no reply was received before the
 * configured deadline expired.
 *
 * <p>This is a transport-level exception produced by {@link EventBusExceptionMapper} when a
 * {@link io.vertx.core.eventbus.ReplyException} with failure type
 * {@link io.vertx.core.eventbus.ReplyFailure#TIMEOUT} is encountered.
 *
 * @see EventBusExceptionMapper
 * @see EventBusAddressUnavailableException
 * @see EventBusDispatchException
 */
public class EventBusTimeoutException extends TechnicalException {

    private final String address;

    /**
     * Constructs a new timeout exception for the given event bus address.
     *
     * @param address the event bus address where the request timed out, or {@code null}
     * @param cause   the underlying {@link io.vertx.core.eventbus.ReplyException}
     */
    public EventBusTimeoutException(String address, Throwable cause) {
        super("Event bus request timed out at " + address, cause);
        this.address = address;
    }

    /**
     * Constructs a new timeout exception with a custom message. For use by subclasses that need to
     * provide a more specific message while still setting the inherited {@code address} field.
     *
     * @param message a custom detail message
     * @param address the event bus address where the request timed out, or {@code null}
     * @param cause   the underlying cause
     */
    protected EventBusTimeoutException(String message, String address, Throwable cause) {
        super(message, cause);
        this.address = address;
    }

    /**
     * Returns the event bus address where the timeout occurred.
     *
     * @return the address, or {@code null} if not available
     */
    public String address() {
        return address;
    }
}
