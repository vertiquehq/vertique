// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.exception;

import dev.vertique.core.exception.UnavailableException;

/**
 * Thrown when a service is unavailable because its restart budget has been exhausted.
 *
 * <p>The {@link ServiceSupervisor} marks a service as unavailable after the configured
 * maximum number of restarts within the supervision window. The {@link ServiceClientFactory}
 * proxy checks availability before sending and fails fast with this exception.
 */
public class ServiceUnavailableException extends UnavailableException {

    private final Class<?> contract;

    /**
     * Creates a new exception for the given contract interface with the default reason
     * "restart budget exhausted".
     *
     * @param contract the contract interface of the unavailable service
     */
    public ServiceUnavailableException(Class<?> contract) {
        this(contract, "restart budget exhausted");
    }

    /**
     * Creates a new exception for the given contract interface with a specific reason.
     *
     * @param contract the contract interface of the unavailable service
     * @param reason   a human-readable description of why the service is unavailable
     */
    public ServiceUnavailableException(Class<?> contract, String reason) {
        super("Service unavailable: " + contract.getSimpleName() + " (" + reason + ")");
        this.contract = contract;
    }

    /**
     * Creates a new exception for the given contract interface with a specific reason and cause.
     *
     * @param contract the contract interface of the unavailable service
     * @param reason   a human-readable description of why the service is unavailable
     * @param cause    the underlying cause (e.g., the transport-level exception)
     */
    public ServiceUnavailableException(Class<?> contract, String reason, Throwable cause) {
        super("Service unavailable: " + contract.getSimpleName() + " (" + reason + ")", cause);
        this.contract = contract;
    }

    /**
     * Returns the contract interface of the unavailable service.
     *
     * @return the contract interface class
     */
    public Class<?> contract() {
        return contract;
    }
}
