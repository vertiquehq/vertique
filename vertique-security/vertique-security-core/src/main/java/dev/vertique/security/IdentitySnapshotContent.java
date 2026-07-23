// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.security.authz.AuthorityClaim;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, credential-free capture of the full identity dimension of a {@link SecurityContext} at a
 * point in time — the captured <em>content</em> of an {@link IdentitySnapshot}, separated from the
 * durable envelope ({@code carrier}, {@code issuedAt}, {@code expiresAt}, {@code integrity}) that
 * signs and binds it (PRD-ID-002 §14.6 Phase-2 Contract Appendix, amendment A9).
 *
 * <p>{@link IdentitySnapshotFactory#capture(SecurityContext)} produces this content directly, with no
 * placeholder integrity/carrier — the durable encoder assembles and signs the surrounding
 * {@link IdentitySnapshot} envelope. Splitting content from envelope removes the former
 * "to-be-signed sentinel with placeholder integrity" invalid state: the capture step now returns only
 * what it can legitimately produce.
 *
 * <p>This carries everything needed to reconstruct a delegated identity structure (actor, subject,
 * delegation, client, authentication method kind, assurance, authorization claims, and origin) across
 * a durability boundary — e.g. a scheduled job, an outbox relay, or a workflow resume — without ever
 * carrying live credential material. There is deliberately no evidence/token component:
 * {@link #authenticationMethodKind()} records only the normalized kind of the original method, never
 * the credential itself.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code actor}, {@code authenticationMethodKind}, {@code originSummary}, and {@code capturedAt}
 *       are required (non-null)</li>
 *   <li>{@code subject}, {@code delegation}, {@code client}, and {@code assurance} are required
 *       {@link Optional} references (non-null {@code Optional}, contents may be empty)</li>
 *   <li>A null {@code authorizationClaims} list is treated as {@link List#of()} (empty); the list is
 *       defensively copied</li>
 * </ul>
 *
 * @param actor                     the acting principal at capture time; required
 * @param subject                   the principal on whose behalf the actor was acting, present only
 *                                  when the captured context was delegated
 * @param delegation                the delegation summary (kind + authority id only; never grant
 *                                  evidence), present only when the captured context was delegated
 * @param client                    the OAuth client (application) that initiated the original
 *                                  request, when known
 * @param authenticationMethodKind  the normalized kind of the original authentication method
 * @param authenticatedAt           when the original authentication occurred
 * @param assurance                 IdP-reported authentication assurance from the original
 *                                  authentication, when available
 * @param authorizationClaims       the typed authority claims held by the actor at capture time;
 *                                  attribution only (audit lineage) — never a reconstructed context's
 *                                  current authority; defensively copied; null treated as empty list
 * @param originSummary             a policy-bounded summary of the ingress kind (e.g. {@code "rest"}
 *                                  or {@code "camel"})
 * @param capturedAt                when this content was captured; the immutable end-to-end freshness
 *                                  anchor (a chained re-encode cannot renew authority derived from it)
 */
public record IdentitySnapshotContent(
        PrincipalRef actor,
        Optional<PrincipalRef> subject,
        Optional<DelegationSummary> delegation,
        Optional<ClientRef> client,
        String authenticationMethodKind,
        Instant authenticatedAt,
        Optional<AuthenticationAssurance> assurance,
        List<AuthorityClaim> authorizationClaims,
        String originSummary,
        Instant capturedAt) {

    /**
     * Compact constructor — validates required fields and defensively copies
     * {@code authorizationClaims}.
     */
    public IdentitySnapshotContent {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(delegation, "delegation");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(authenticationMethodKind, "authenticationMethodKind");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(assurance, "assurance");
        Objects.requireNonNull(originSummary, "originSummary");
        Objects.requireNonNull(capturedAt, "capturedAt");
        authorizationClaims = List.copyOf(authorizationClaims == null ? List.of() : authorizationClaims);
    }
}
