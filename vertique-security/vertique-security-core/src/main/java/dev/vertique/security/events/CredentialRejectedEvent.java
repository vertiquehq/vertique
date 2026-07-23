// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.verification.VerificationSource;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Event fired when the framework rejects presented credential material before identity resolution.
 *
 * <p>This is a request-ingress credential-verification event, not an interactive login failure.
 * It fires when an auth handler rejects credential material on a route that requires
 * authentication — for example an expired JWT, an unknown API key, a failed signature check, or a
 * missing/malformed {@code Authorization} header (reason codes {@code BEARER_MISSING} /
 * {@code BEARER_MALFORMED}). It does NOT fire for anonymous access to public endpoints, where no
 * authentication is required and the request simply resolves to {@code ANONYMOUS}.
 *
 * <p>The {@code safeAttributes} map carries audit-safe key/value pairs. It must never contain
 * raw credential material (tokens, passwords, keys). The constructor defensively copies the map.
 *
 * @param occurredAt         wall-clock instant when the credential was rejected; never null
 * @param correlation        correlation context for the request that triggered this event; never null
 * @param origin             captured network-envelope facts; non-null {@link Optional} — use
 *                           {@link Optional#empty()} when origin was not captured
 * @param attemptedMethod    the authentication method that was attempted; never null
 * @param credentialId       stable, non-sensitive identifier for the credential (e.g. JWT {@code jti}
 *                           claim, API key prefix); non-null {@link Optional}
 * @param verificationSource the verification back-end that rejected the credential; non-null
 *                           {@link Optional} — empty when rejection occurred before a source was
 *                           selected
 * @param reasonCode         machine-readable rejection reason (e.g. {@code "TOKEN_EXPIRED"},
 *                           {@code "SIGNATURE_INVALID"}); must not be blank
 * @param safeAttributes     additional audit-safe attributes; defensively copied; never null after
 *                           construction
 */
public record CredentialRejectedEvent(
        Instant occurredAt,
        CorrelationContext correlation,
        Optional<RequestOrigin> origin,
        AuthMethod attemptedMethod,
        Optional<String> credentialId,
        Optional<VerificationSource> verificationSource,
        String reasonCode,
        Map<String, Object> safeAttributes) {

    /**
     * Compact constructor — validates required fields, rejects a blank {@code reasonCode},
     * and defensively copies {@code safeAttributes}.
     */
    public CredentialRejectedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlation, "correlation");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(attemptedMethod, "attemptedMethod");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(verificationSource, "verificationSource");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        safeAttributes = Map.copyOf(safeAttributes == null ? Map.of() : safeAttributes);
    }
}
