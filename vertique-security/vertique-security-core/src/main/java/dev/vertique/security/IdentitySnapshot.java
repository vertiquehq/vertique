// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable, signed, credential-free durable envelope carrying an {@link IdentitySnapshotContent}
 * across a durability boundary — e.g. a scheduled job, an outbox relay, or a workflow resume
 * (PRD-ID-002 §14.6 Phase-2 Contract Appendix, amendment A9).
 *
 * <p>Schema v2 splits the captured identity dimension ({@link #content()}) from the durable envelope
 * that signs and binds it: the {@link #carrier()} pins the snapshot to the specific durable row it was
 * written for (the F5 replay defense), {@link #issuedAt()}/{@link #expiresAt()} bound its temporal
 * validity, and {@link #integrity()} is the mandatory MAC over the whole envelope. There is
 * deliberately no evidence/token component anywhere in the tree; the snapshot is never valid without an
 * integrity envelope.
 *
 * <p>Freshness is enforced at decode against a three-term minimum expiry anchored on the immutable
 * {@link IdentitySnapshotContent#capturedAt()} (so a chained re-encode cannot renew authority) — see
 * {@code SnapshotFreshnessPolicy} in {@code vertique-security-runtime}. Carrier binding is enforced at
 * reconstruction: a snapshot whose signed {@link #carrier()} does not match the receive-side's expected
 * carrier is rejected fail-closed.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code schemaVersion} must be strictly positive (it is {@code 2} for this schema); decode-time
 *       compatibility (rejecting an unknown-newer version) is enforced by the runtime codec, not by
 *       this constructor</li>
 *   <li>{@code content}, {@code carrier}, {@code issuedAt}, {@code expiresAt}, and {@code integrity}
 *       are all required (non-null)</li>
 * </ul>
 *
 * @param schemaVersion the snapshot schema version; must be greater than zero ({@code 2} for this
 *                      schema)
 * @param content       the credential-free captured identity dimension
 * @param carrier       the durable row-carrier this snapshot is signed for (F5 replay defense)
 * @param issuedAt      when the durable envelope was minted (signed) by the codec
 * @param expiresAt     the signed expiry bound of the durable envelope
 * @param integrity     the mandatory signed envelope proving this snapshot's authenticity and
 *                      integrity over {@code content}, {@code carrier}, {@code issuedAt}, and
 *                      {@code expiresAt}
 */
public record IdentitySnapshot(
        int schemaVersion,
        IdentitySnapshotContent content,
        SnapshotCarrierBinding carrier,
        Instant issuedAt,
        Instant expiresAt,
        SnapshotIntegrity integrity) {

    /**
     * Compact constructor — validates the schema version and all required references.
     */
    public IdentitySnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be greater than zero");
        }
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(integrity, "integrity");
    }
}
