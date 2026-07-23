// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.eventbus.EventBusDispatchException;

/**
 * Thrown when a service dispatch fails because the recipient rejected the message or a fatal
 * transport error occurred.
 *
 * <p>Extends {@link EventBusDispatchException} to add the service contract interface,
 * enabling callers to identify which service failed without parsing the message.
 *
 * <p>Code that catches {@link EventBusDispatchException} also catches this subclass.
 *
 * @see EventBusDispatchException
 * @see ServiceTimeoutException
 * @see ServiceUnavailableException
 */
public class ServiceDispatchException extends EventBusDispatchException {

    private final Class<?> contract;

    /**
     * Creates a new service dispatch exception.
     *
     * @param contract the service contract interface where dispatch failed
     * @param address  the event bus address where dispatch failed
     * @param message  the failure detail message from the recipient or transport layer
     * @param cause    the underlying cause (typically a {@code ReplyException})
     */
    public ServiceDispatchException(Class<?> contract, String address, String message, Throwable cause) {
        super(
                cause,
                "Service dispatch failed: " + contract.getSimpleName() + " at " + address + ": " + message,
                address);
        this.contract = contract;
    }

    /**
     * Returns the service contract interface where dispatch failed.
     *
     * @return the contract interface class
     */
    public Class<?> contract() {
        return contract;
    }
}
