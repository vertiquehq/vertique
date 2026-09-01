// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.spi;

/**
 * Selects the security-identity dimension {@link RateLimitAdapterSupport#subjectKey} frames into a
 * rate-limit key (contracts/rate-limit-runtime.md, "Subject resolution SPI"; {@code spec.md} §5.2).
 */
public enum RateLimitSubject {

    /** No identity dimension is resolved; the resolver is never consulted. */
    NONE,

    /** {@code SecurityIdentity.actor()} — the directly authenticated principal. */
    ACTOR,

    /**
     * {@code SecurityIdentity.subject()} when present, otherwise {@code actor()} — the
     * delegation-aware facet.
     */
    EFFECTIVE_PRINCIPAL,

    /** {@code SecurityIdentity.client()} — the OAuth 2.0 client the request arrived through. */
    CLIENT
}
