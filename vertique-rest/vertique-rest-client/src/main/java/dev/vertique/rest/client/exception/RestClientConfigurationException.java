// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when a REST client proxy or its supporting infrastructure is misconfigured or when a
 * generated class is found on the classpath but cannot be instantiated.
 *
 * <p>Examples of scenarios that trigger this exception:
 * <ul>
 *   <li>A generated {@code {BeanType}_BeanParamAccessor} class exists but its public no-arg
 *       constructor throws at instantiation time (detected by
 *       {@link dev.vertique.rest.client.BeanParamAccessorRegistry}).</li>
 *   <li>A generated {@code {ClientType}_RestClientProxy} class exists but its constructor
 *       throws at instantiation time (detected by {@link dev.vertique.rest.client.RestClientBuilder}).</li>
 * </ul>
 *
 * <p>This exception is NOT thrown when a generated class is simply absent — that is the normal
 * fallback path, handled silently via {@link ClassNotFoundException}.
 *
 * <p>This exception does NOT extend {@link RestClientException} — it extends
 * {@link ConfigurationException} directly, reflecting that it is a wiring/startup failure rather
 * than a runtime request failure.
 */
public class RestClientConfigurationException extends ConfigurationException {

    /**
     * Creates a new configuration exception with the given message.
     *
     * @param message the detail message describing the misconfiguration
     */
    public RestClientConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates a new configuration exception with the given message and cause.
     *
     * @param message the detail message describing the misconfiguration
     * @param cause the underlying reflective or instantiation failure
     */
    public RestClientConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
