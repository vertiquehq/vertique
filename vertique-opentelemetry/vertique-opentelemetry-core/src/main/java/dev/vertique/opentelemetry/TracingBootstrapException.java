// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dev.vertique.core.exception.ConfigurationException;

/**
 * Thrown when the OpenTelemetry tracing subsystem fails to bootstrap during application startup.
 *
 * <p>This exception is thrown by {@link OpenTelemetryBootstrapContributor} and propagates to the
 * Vertique launcher, which logs the full throwable chain and aborts startup with exit code 11
 * ({@code ExitCodes.VERTX_INITIALIZATION}).
 *
 * <p><strong>No cause constructor is provided.</strong> This is intentional for secret safety:
 * third-party SDK exceptions (e.g. from {@code AutoConfiguredOpenTelemetrySdk}) may embed
 * configuration values (hostnames, credentials, endpoint URLs) in their message or cause chain.
 * By severing the cause chain at this boundary the contributor prevents accidental credential
 * logging — the launcher logs the full exception chain, so any cause attached here would be
 * emitted to the log. Each throw site encodes only the failing class's simple name to convey
 * context without risk.
 */
public final class TracingBootstrapException extends ConfigurationException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message; should identify what failed using only class names or
     *                structural context — never config values or endpoint details
     */
    public TracingBootstrapException(String message) {
        super(message);
    }
}
