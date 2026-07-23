// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.authz;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.security.authz.PrincipalAuthorityResolver;
import jakarta.annotation.Nullable;
import java.time.Duration;

/**
 * Configuration record bounding every Mode-2 {@link PrincipalAuthorityResolver#resolve} call with
 * an operator-configured timeout, deserialized from the {@code identity.authz} section of the
 * application config via {@link dev.vertique.core.config.ConfigParser} (PRD identity-002 §14.3
 * Phase-2 Appendix, ADR-0169).
 *
 * <p>ADR-0169 describes "a resolver failure, timeout, or ambiguous result" as denying with {@link
 * dev.vertique.security.authz.AuthzReasonCodes#AUTHORITY_RESOLUTION_FAILED} — {@link
 * #resolutionTimeoutMs()} is the value that makes the "timeout" half of that guarantee real:
 * {@code SecurityAuthzModule} wraps every installed {@link PrincipalAuthorityResolver} in a {@link
 * TimeoutPrincipalAuthorityResolver} bounded by this value, so a delegate resolver whose returned
 * {@link io.vertx.core.Future} never completes cannot hang Mode-2 authorization indefinitely.
 *
 * <p>{@code SecurityAuthzModule} declares {@code Optional<PrincipalAuthorityResolutionConfig>} via
 * {@code @BindsOptionalOf}, defaulting to {@link #defaults()} when no application or config module
 * binds one — an application need not install anything extra to get a bounded Mode-2 resolver.
 * {@code PrincipalAuthorityResolutionConfigModule} is the opt-in companion that config-drives this
 * value from {@code identity.authz.resolutionTimeoutMs} instead of the hardcoded default.
 *
 * <p>Config path: {@code identity.authz} — for example:
 *
 * <pre>{@code
 * identity:
 *   authz:
 *     resolutionTimeoutMs: 5000
 * }</pre>
 *
 * @param resolutionTimeoutMs the bound, in milliseconds, on every {@link PrincipalAuthorityResolver#resolve}
 *                            call once wrapped by {@link TimeoutPrincipalAuthorityResolver}; defaults to
 *                            {@link #DEFAULT_RESOLUTION_TIMEOUT_MS} when omitted; must be positive
 */
public record PrincipalAuthorityResolutionConfig(long resolutionTimeoutMs) {

    /**
     * Default resolution timeout (milliseconds) applied when {@code resolutionTimeoutMs} is
     * omitted from config and no application binds an explicit {@link
     * PrincipalAuthorityResolutionConfig}. A {@link PrincipalAuthorityResolver} is expected to
     * consult durable, principal-keyed authority storage (ADR-0169) — normally a fast lookup — so
     * this default gives headroom for a cold cache or a brief store hiccup without hanging
     * authorization indefinitely.
     */
    public static final long DEFAULT_RESOLUTION_TIMEOUT_MS = 5_000L;

    /**
     * Compact constructor validating {@code resolutionTimeoutMs} is positive.
     *
     * @throws ConfigurationException if {@code resolutionTimeoutMs} is not positive
     */
    public PrincipalAuthorityResolutionConfig {
        if (resolutionTimeoutMs <= 0) {
            throw new ConfigurationException(
                    "identity.authz.resolutionTimeoutMs must be > 0, got: " + resolutionTimeoutMs);
        }
    }

    /**
     * Jackson-friendly factory that defaults {@code resolutionTimeoutMs} to {@link
     * #DEFAULT_RESOLUTION_TIMEOUT_MS} when omitted.
     *
     * @param resolutionTimeoutMs the configured timeout in milliseconds; {@code null} treated as
     *                            the default
     * @return the deserialized configuration
     */
    @JsonCreator
    static PrincipalAuthorityResolutionConfig fromJson(
            @JsonProperty("resolutionTimeoutMs") @Nullable Long resolutionTimeoutMs) {
        return new PrincipalAuthorityResolutionConfig(
                resolutionTimeoutMs != null ? resolutionTimeoutMs : DEFAULT_RESOLUTION_TIMEOUT_MS);
    }

    /**
     * Returns the default configuration: {@link #DEFAULT_RESOLUTION_TIMEOUT_MS}.
     *
     * @return the default configuration; never {@code null}
     */
    public static PrincipalAuthorityResolutionConfig defaults() {
        return new PrincipalAuthorityResolutionConfig(DEFAULT_RESOLUTION_TIMEOUT_MS);
    }

    /**
     * Returns the resolution timeout as a {@link Duration}.
     *
     * @return the configured timeout; never {@code null}
     */
    public Duration resolutionTimeout() {
        return Duration.ofMillis(resolutionTimeoutMs);
    }
}
