// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Configuration record bounding every Vert.x {@link io.vertx.ext.auth.authorization.AuthorizationProvider}
 * invocation made by the {@link VertxAuthorizationImporter} with an operator-configured deadline,
 * deserialized from the {@code security.authz} section of the application config via {@link
 * dev.vertique.core.config.ConfigParser}.
 *
 * <p>{@link VertxAuthorizationImportModule} declares {@code Optional<AuthorizationImportConfig>} via
 * {@code @BindsOptionalOf}, defaulting to {@link #defaults()} when no application or config module binds
 * one — an application that installs the import need not install anything extra to get the framework's
 * {@link #DEFAULT_IMPORT_TIMEOUT_MS} bound. {@link AuthorizationImportConfigModule} is the opt-in
 * companion that config-drives this value from {@code security.authz.importTimeoutMs}; setting the key
 * without installing that module has no effect.
 *
 * <p>The bound applies to each provider individually, so an import across {@code N} providers can wait
 * up to {@code N × importTimeoutMs} before failing.
 *
 * <p>Config path: {@code security.authz} — for example:
 *
 * <pre>{@code
 * security:
 *   authz:
 *     importTimeoutMs: 5000
 * }</pre>
 *
 * @param importTimeoutMs the bound, in milliseconds, on each provider invocation; defaults to {@link
 *     #DEFAULT_IMPORT_TIMEOUT_MS} when omitted; must be positive
 */
public record AuthorizationImportConfig(long importTimeoutMs) {

    /**
     * Default provider-invocation deadline (milliseconds) applied when {@code importTimeoutMs} is
     * omitted from config and no application binds an explicit {@link AuthorizationImportConfig}.
     * Matches {@link AuthorizationGateConfig#DEFAULT_GATE_DEADLINE_MS}.
     */
    public static final long DEFAULT_IMPORT_TIMEOUT_MS = 5_000L;

    /**
     * Compact constructor validating {@code importTimeoutMs} is positive.
     *
     * @throws ConfigurationException if {@code importTimeoutMs} is not positive
     */
    public AuthorizationImportConfig {
        if (importTimeoutMs <= 0) {
            throw new ConfigurationException("security.authz.importTimeoutMs must be > 0, got: " + importTimeoutMs);
        }
    }

    /**
     * Jackson-friendly factory that defaults {@code importTimeoutMs} to {@link
     * #DEFAULT_IMPORT_TIMEOUT_MS} when omitted.
     *
     * @param importTimeoutMs the configured deadline in milliseconds; {@code null} treated as the
     *     default
     * @return the deserialized configuration
     */
    @JsonCreator
    static AuthorizationImportConfig fromJson(@JsonProperty("importTimeoutMs") @Nullable Long importTimeoutMs) {
        return new AuthorizationImportConfig(importTimeoutMs != null ? importTimeoutMs : DEFAULT_IMPORT_TIMEOUT_MS);
    }

    /**
     * Returns the framework-default configuration.
     *
     * @return a configuration carrying {@link #DEFAULT_IMPORT_TIMEOUT_MS}
     */
    public static AuthorizationImportConfig defaults() {
        return new AuthorizationImportConfig(DEFAULT_IMPORT_TIMEOUT_MS);
    }
}
