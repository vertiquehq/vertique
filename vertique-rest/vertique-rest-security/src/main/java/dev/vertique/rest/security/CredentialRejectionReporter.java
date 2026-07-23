// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import dev.vertique.security.AuthMethod;
import dev.vertique.security.verification.VerificationSource;
import io.vertx.ext.web.RoutingContext;
import java.util.Map;
import java.util.Optional;

/**
 * SPI for auth-module handlers (JWT, API-key, mTLS, HMAC, Basic) to report a credential
 * rejection. The default implementation assembles a
 * {@link dev.vertique.security.events.CredentialRejectedEvent} from the supplied parameters
 * plus the pre-auth-bound {@link dev.vertique.security.origin.RequestOrigin} and
 * {@link dev.vertique.core.correlation.CorrelationContext} on the routing context and context
 * holder respectively, and emits it immediately via
 * {@link dev.vertique.security.runtime.events.SecurityEventEmitter}. The failing auth handler then calls
 * {@code ctx.fail()}, which short-circuits {@link dev.vertique.rest.security.IdentityResolutionMiddleware}
 * and all downstream handlers for the request.
 *
 * <p>Auth handlers call this when verification of presented credential material fails (malformed
 * token, expired token, invalid signature, unknown registry entry, certificate chain rejected,
 * HMAC mismatch, etc.). The {@code reasonCode} is a stable identifier propagated into audit; see
 * PRD §7.13 for the canonical reason-code vocabulary.
 *
 * <p>{@code safeAttributes} MAY carry redacted/structured failure context (decoded JWT header
 * without payload, certificate chain summary, retry count). MUST NOT carry raw token material,
 * raw API keys, raw passwords, raw HMAC signatures, or raw request bodies.
 *
 * <p>Implementations are expected to be {@code @Singleton} and thread-safe in the Vert.x sense —
 * each invocation operates on a distinct {@link RoutingContext} owned by the calling event-loop
 * context; no cross-request shared mutable state is needed.
 */
public interface CredentialRejectionReporter {

    /**
     * Reports a credential rejection for the current request by assembling a
     * {@link dev.vertique.security.events.CredentialRejectedEvent} and emitting it immediately
     * via {@link dev.vertique.security.runtime.events.SecurityEventEmitter}.
     *
     * <p>The {@link dev.vertique.core.correlation.CorrelationContext} MUST already be bound on the
     * {@link dev.vertique.core.context.ContextHolder} before this method is called. In normal
     * request flow, {@link dev.vertique.rest.core.correlation.CorrelationIngressMiddleware} binds
     * it before any auth handler runs, so this precondition is automatically satisfied.
     *
     * @param ctx                the current routing context; must not be {@code null}
     * @param attemptedMethod    the authentication method that was attempted; must not be
     *                           {@code null}
     * @param credentialId       stable, non-sensitive identifier for the credential (e.g. JWT
     *                           {@code jti} claim, API key prefix); non-null {@link Optional}
     * @param verificationSource the verification back-end that rejected the credential; non-null
     *                           {@link Optional} — use {@link Optional#empty()} when rejection
     *                           occurred before a source was selected
     * @param reasonCode         machine-readable rejection reason (e.g. {@code "TOKEN_EXPIRED"},
     *                           {@code "SIGNATURE_INVALID"}); must not be blank
     * @param safeAttributes     additional audit-safe key/value context; must not contain raw
     *                           credential material; may be {@code null} (treated as empty)
     * @throws NullPointerException  if {@code ctx}, {@code attemptedMethod}, {@code credentialId},
     *                               {@code verificationSource}, or {@code reasonCode} is
     *                               {@code null}
     * @throws IllegalStateException if {@link dev.vertique.core.correlation.CorrelationContext} is
     *                               not bound on the context holder
     */
    void report(
            RoutingContext ctx,
            AuthMethod attemptedMethod,
            Optional<String> credentialId,
            Optional<VerificationSource> verificationSource,
            String reasonCode,
            Map<String, Object> safeAttributes);
}
