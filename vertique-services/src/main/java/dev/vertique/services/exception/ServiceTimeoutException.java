// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.exception;

import dev.vertique.core.eventbus.EventBusTimeoutException;

/**
 * Thrown when a service request times out — no reply was received before the deadline.
 *
 * <p>Extends {@link EventBusTimeoutException} to add the service contract interface,
 * enabling callers to identify which service timed out without parsing the message.
 *
 * <p>Code that catches {@link EventBusTimeoutException} also catches this subclass.
 *
 * @see EventBusTimeoutException
 * @see ServiceDispatchException
 * @see ServiceUnavailableException
 */
public class ServiceTimeoutException extends EventBusTimeoutException {

    private final Class<?> contract;

    /**
     * Creates a new service timeout exception.
     *
     * @param contract the service contract interface that timed out
     * @param address  the event bus address where the timeout occurred
     * @param cause    the underlying cause (typically a {@code ReplyException})
     */
    public ServiceTimeoutException(Class<?> contract, String address, Throwable cause) {
        super("Service request timed out: " + contract.getSimpleName() + " at " + address, address, cause);
        this.contract = contract;
    }

    /**
     * Returns the service contract interface that timed out.
     *
     * @return the contract interface class
     */
    public Class<?> contract() {
        return contract;
    }
}
