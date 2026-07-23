// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.eventbus;

import dev.vertique.core.exception.UnavailableException;

/**
 * Thrown when an event bus request fails because no handlers are registered at the target address.
 *
 * <p>This is a transport-level exception produced by {@link EventBusExceptionMapper} when a
 * {@link io.vertx.core.eventbus.ReplyException} with failure type
 * {@link io.vertx.core.eventbus.ReplyFailure#NO_HANDLERS} is encountered.
 *
 * <p>This condition is typically transient — handlers may become available once the corresponding
 * service is deployed or recovers from a restart budget exhaustion.
 *
 * @see EventBusExceptionMapper
 * @see EventBusTimeoutException
 * @see EventBusDispatchException
 */
public class EventBusAddressUnavailableException extends UnavailableException {

    private final String address;

    /**
     * Constructs a new address unavailable exception for the given event bus address.
     *
     * @param address the event bus address where no handlers were found, or {@code null}
     * @param cause   the underlying {@link io.vertx.core.eventbus.ReplyException}
     */
    public EventBusAddressUnavailableException(String address, Throwable cause) {
        super("No handlers at address: " + address, cause);
        this.address = address;
    }

    /**
     * Returns the event bus address where no handlers were found.
     *
     * @return the address, or {@code null} if not available
     */
    public String address() {
        return address;
    }
}
