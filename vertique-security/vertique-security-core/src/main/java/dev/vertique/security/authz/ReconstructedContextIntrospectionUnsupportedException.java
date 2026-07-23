// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.authz;

import dev.vertique.core.exception.BusinessRuleException;
import dev.vertique.security.SecurityContext;

/**
 * Thrown when capability introspection ({@link AuthorizationIntrospector#allowedActions(SecurityContext)}
 * or {@link AuthorizationIntrospector#capabilities(SecurityContext)}) is invoked with a reconstructed
 * {@link SecurityContext} — one whose {@link SecurityContext#reconstruction()} is present.
 *
 * <p>Introspection answers "what can this actor do right now", computed as a pure, synchronous
 * function of the context's <em>currently held</em> {@link AuthorizationClaims}. A reconstructed
 * context's current authority, however, is re-resolved <strong>live</strong> at authorize-time by
 * Mode 2 ({@code ReconstructedAuthorityResolvingAuthorizer}, PRD identity-002 §14.3 Phase-2 Appendix,
 * FR-ID-CA-010) — the claims a reconstructed context carries at introspection time are not
 * necessarily the claims that would actually govern a concrete
 * {@link Authorizer#authorize(AuthorizationRequest)} call for the same actor. Silently computing an
 * introspection answer from those attribution-only/stale claims would violate
 * {@link AuthorizationIntrospector}'s Agreement invariant for exactly the contexts where live
 * re-resolution matters most, and do so invisibly — the caller sees a plausible-looking but
 * potentially wrong capability set rather than any signal that the answer can't be trusted.
 *
 * <p>Rather than return that misleading result, the framework's exposed
 * {@link AuthorizationIntrospector} binding ({@code NarrowingIntrospector}) throws this exception for
 * a reconstructed context instead. Introspection is an in-memory, synchronous operation with no
 * production caller in V1, so this is a loud, typed contract boundary rather than a live-traffic
 * limitation: a caller that needs an authorization answer for a reconstructed context's specific
 * action calls {@link Authorizer#authorize(AuthorizationRequest)} instead, which correctly re-resolves
 * current authority live.
 *
 * <p>Extends {@link BusinessRuleException} (HTTP 400 at the REST boundary, via the framework's
 * {@code ValidationException} mapping) rather than {@code TechnicalException} (500): the introspection
 * call itself is well-formed, but violates the domain rule that introspection is unsupported for a
 * reconstructed context — a caller/API-usage constraint the caller can act on (switch to
 * {@code authorize()}), not an unexpected infrastructure failure.
 */
public class ReconstructedContextIntrospectionUnsupportedException extends BusinessRuleException {

    /**
     * Constructs a new exception with the given message.
     *
     * @param message the detail message describing why introspection was rejected
     */
    public ReconstructedContextIntrospectionUnsupportedException(String message) {
        super(message);
    }
}
