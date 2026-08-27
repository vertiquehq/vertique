// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;

/**
 * Configuration record bounding every {@link SecurityPolicyEnforcer#decide} role/scope and action
 * gate future with an operator-configured deadline, deserialized from the {@code security.authz}
 * section of the application config via {@link dev.vertique.core.config.ConfigParser} (issue #417,
 * R42 — the deferred configurability half of R01/R07's fail-closed gate-timeout fix).
 *
 * <p>{@link SecurityModule} (via {@link AuthModule}) declares {@code Optional<AuthorizationGateConfig>}
 * via {@code @BindsOptionalOf}, defaulting to {@link #defaults()} when no application or config
 * module binds one — an application need not install anything extra to get the framework's
 * {@link #DEFAULT_GATE_DEADLINE_MS} bound, byte-identical to the pre-R42 hardcoded constant.
 * {@link AuthorizationGateConfigModule} is the opt-in companion that config-drives this value from
 * {@code security.authz.gateDeadlineMs} instead of the hardcoded default.
 *
 * <p>The same {@link SecurityPolicyEnforcer} instance enforces REST (via {@code AuthorizationContributor}),
 * WebSocket (via {@code WebSocketMount}), and MCP (via {@code McpPolicyEnforcer}, which wraps this
 * class) authorization gates — so this is a single knob across all three transports; there is no
 * per-transport override.
 *
 * <p>Config path: {@code security.authz} — for example:
 *
 * <pre>{@code
 * security:
 *   authz:
 *     gateDeadlineMs: 5000
 * }</pre>
 *
 * @param gateDeadlineMs the bound, in milliseconds, on every {@link SecurityPolicyEnforcer#decide}
 *                       role/scope and action gate future; defaults to
 *                       {@link #DEFAULT_GATE_DEADLINE_MS} when omitted; must be positive
 */
public record AuthorizationGateConfig(long gateDeadlineMs) {

    /**
     * Default gate deadline (milliseconds) applied when {@code gateDeadlineMs} is omitted from
     * config and no application binds an explicit {@link AuthorizationGateConfig}. An app-provided
     * {@link AuthorizationDecisionPoint} (a remote PDP, an OPA sidecar) or {@link
     * dev.vertique.security.authz.Authorizer} is documented as "must not block", but a non-blocking
     * future that simply never resolves is not blocking — this default gives headroom for a genuine
     * remote call without hanging authorization indefinitely. Matches {@code
     * PrincipalAuthorityResolutionConfig#DEFAULT_RESOLUTION_TIMEOUT_MS}.
     */
    public static final long DEFAULT_GATE_DEADLINE_MS = 5_000L;

    /**
     * Compact constructor validating {@code gateDeadlineMs} is positive.
     *
     * @throws ConfigurationException if {@code gateDeadlineMs} is not positive
     */
    public AuthorizationGateConfig {
        if (gateDeadlineMs <= 0) {
            throw new ConfigurationException("security.authz.gateDeadlineMs must be > 0, got: " + gateDeadlineMs);
        }
    }

    /**
     * Jackson-friendly factory that defaults {@code gateDeadlineMs} to {@link
     * #DEFAULT_GATE_DEADLINE_MS} when omitted.
     *
     * @param gateDeadlineMs the configured deadline in milliseconds; {@code null} treated as the
     *                       default
     * @return the deserialized configuration
     */
    @JsonCreator
    static AuthorizationGateConfig fromJson(@JsonProperty("gateDeadlineMs") @Nullable Long gateDeadlineMs) {
        return new AuthorizationGateConfig(gateDeadlineMs != null ? gateDeadlineMs : DEFAULT_GATE_DEADLINE_MS);
    }

    /**
     * Returns the default configuration: {@link #DEFAULT_GATE_DEADLINE_MS}.
     *
     * @return the default configuration; never {@code null}
     */
    public static AuthorizationGateConfig defaults() {
        return new AuthorizationGateConfig(DEFAULT_GATE_DEADLINE_MS);
    }
}
