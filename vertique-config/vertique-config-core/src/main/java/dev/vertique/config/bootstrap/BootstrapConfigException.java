// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.bootstrap;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when the bootstrap configuration load fails at startup.
 *
 * <p>Wraps any error that prevents the temporary Vert.x bootstrap load from completing:
 * a retriever timeout, a store I/O failure, or a load interrupted by a fatal exception.
 *
 * <h2>Message Contract</h2>
 * <p>The message MUST include contextual information (key counts, paths) but MUST NOT include
 * any resolved configuration values.
 *
 * <h2>Hierarchy</h2>
 * <p>Extends {@link ConfigurationException} because a bootstrap failure is a startup/wiring
 * failure — the application cannot reach a running state without a configuration tree.
 *
 * @see BootstrapConfigLoader
 * @see dev.vertique.config.source.ConfigPropertySourceException
 */
public class BootstrapConfigException extends ConfigurationException {

    /**
     * Constructs an exception with the given message.
     *
     * @param message a non-secret description of the failure (counts or paths only, no values)
     */
    public BootstrapConfigException(String message) {
        super(message);
    }

    /**
     * Constructs an exception with the given message and cause.
     *
     * @param message a non-secret description of the failure (counts or paths only, no values)
     * @param cause   the underlying cause; may be {@code null}
     */
    public BootstrapConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
