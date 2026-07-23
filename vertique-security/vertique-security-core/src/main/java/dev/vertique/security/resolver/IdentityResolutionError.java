// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.resolver;

/**
 * Classification of errors that a {@link SecurityIdentityResolver} may encounter during identity
 * resolution.
 *
 * <p>Carried by {@link IdentityResolutionException} to allow callers and monitoring code to
 * distinguish root causes without parsing exception messages.
 */
public enum IdentityResolutionError {

    /**
     * The request presents credentials for more than one client identity and the resolver
     * cannot determine which is authoritative.
     */
    AMBIGUOUS_CLIENT_ID,

    /**
     * The delegation grant presented in the request is structurally invalid, expired, or
     * refers to a subject that cannot be resolved.
     */
    INVALID_DELEGATION,

    /**
     * The principal classification carried by the credential material (e.g., the {@code iss}
     * claim or certificate subject) is not supported by this resolver.
     */
    UNSUPPORTED_PRINCIPAL_CLASSIFICATION,

    /**
     * The evidence classifies the actor as {@code USER} or {@code SERVICE}, but no stable
     * identifier ({@code sub}, {@code client_id}, {@code azp}, or an equivalent claim) could be
     * derived from it. Resolution fails rather than fabricating a shared placeholder id, which
     * would collide across distinct principals lacking evidence (PRD identity-002
     * FR-ID-CA-012).
     */
    UNDERIVABLE_PRINCIPAL_ID
}
