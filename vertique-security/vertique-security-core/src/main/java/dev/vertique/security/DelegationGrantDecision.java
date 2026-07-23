// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of evaluating whether a {@link DelegationGrant} currently authorizes a given
 * (actor, subject, scope) triple, produced by {@link DelegationGrantValidator}.
 *
 * <p>Mirrors the vocabulary of {@code AuthorizationDecision}: a boolean {@link #permitted()} plus a
 * stable, machine-readable {@link #reasonCode()} — exactly one reason per decision (see
 * {@link DelegationReasonCodes} for the frozen vocabulary). {@link #grantId()} echoes the grant id the
 * caller asked about, present even on a deny (e.g. "not found" or "lookup failed"), so callers and
 * audit output can always correlate the decision to the grant reference that was evaluated.
 * {@link #expiryUsed()} carries the grant's {@code expiresAt} that was checked, when a grant was
 * found; {@link Optional#empty()} when no grant was found at all (e.g. {@code GRANT_NOT_FOUND},
 * {@code GRANT_LOOKUP_FAILED}).
 *
 * @param permitted  {@code true} iff the grant currently authorizes the evaluated triple
 * @param reasonCode stable machine-readable reason code (see {@link DelegationReasonCodes}); never
 *                   {@code null}
 * @param grantId    the grant id that was evaluated; never {@code null}
 * @param expiryUsed the expiry instant checked against, when a grant was found; {@link Optional#empty()}
 *                   otherwise
 */
public record DelegationGrantDecision(
        boolean permitted, String reasonCode, String grantId, Optional<Instant> expiryUsed) {

    /**
     * Compact constructor — validates required fields.
     */
    public DelegationGrantDecision {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        Objects.requireNonNull(grantId, "grantId");
        Objects.requireNonNull(expiryUsed, "expiryUsed");
    }
}
