// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Resolver SPI for mapping request authentication evidence to a canonical
 * {@link dev.vertique.security.SecurityIdentity}.
 *
 * <p>This package implements the contract described in PRD §7.5 and the Contract Appendix sections
 * "SecurityIdentityResolver", "SecurityIdentityResolutionContext", and
 * "AuthenticationEvidenceCollector". The chain runner (shipped in {@code rest-security} in a later
 * slice) sorts resolvers by {@code (priority, id)} and invokes them in order, stopping at the
 * first non-empty result.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link dev.vertique.security.resolver.SecurityIdentityResolver} — SPI interface;
 *       async, returning {@link io.vertx.core.Future}.</li>
 *   <li>{@link dev.vertique.security.resolver.SecurityIdentityResolutionContext} — immutable
 *       snapshot of accumulated evidence, optional request origin, optional correlation, and
 *       transport-specific attributes.</li>
 *   <li>{@link dev.vertique.security.resolver.AuthenticationEvidenceCollector} — transport-
 *       neutral accumulator for {@link dev.vertique.security.AuthenticationEvidence}
 *       entries.</li>
 *   <li>{@link dev.vertique.security.resolver.IdentityResolutionException} — runtime
 *       exception thrown by a resolver on error; carries a typed
 *       {@link dev.vertique.security.resolver.IdentityResolutionError}.</li>
 * </ul>
 */
package dev.vertique.security.resolver;
