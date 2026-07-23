// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.security.IdentitySnapshot;
import dev.vertique.security.IdentitySnapshotContent;
import dev.vertique.security.SnapshotDegradationReason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed freshness policy applied at snapshot decode (PRD-ID-002 §14.6 amendment A9, the F5
 * replay defense). After {@link IdentitySnapshotCodec#decode(byte[])} has verified a snapshot's HMAC,
 * {@link #check(IdentitySnapshot)} rejects a snapshot whose temporal envelope is malformed, impossibly
 * future-dated, or past its effective expiry — so a tampered or stale durable row cannot be replayed
 * into a live identity.
 *
 * <p>The <strong>effective expiry</strong> is the three-term minimum of the signed {@code expiresAt}
 * and two operator-configured budgets:
 * <pre>{@code
 *   effectiveExpiry = min(
 *       expiresAt,
 *       issuedAt + maxCarrierLifetime,          // only when configured
 *       content.capturedAt + maxSnapshotLifetime // only when configured
 *   )
 *   accept iff now <= effectiveExpiry + clockSkew
 * }</pre>
 * The snapshot-lifetime budget is anchored on the <em>immutable</em>
 * {@link IdentitySnapshotContent#capturedAt()} rather than {@code issuedAt}, so a chained re-encode
 * (which mints a fresh {@code issuedAt}) cannot renew authority that has already aged out. With both
 * budgets absent — the sub-slice default before operators configure them — only the signed
 * {@code expiresAt} bounds the snapshot.
 *
 * <p>The verifier reapplies its <em>current</em> budgets on every decode (it does not trust a
 * previously-signed expiry alone), so an operator can tighten already-persisted rows by lowering a
 * budget. A snapshot is never re-signed or refreshed on this path.
 *
 * @param maxCarrierLifetime the optional carrier-lifetime budget measured from {@code issuedAt}; empty
 *                           when unconfigured
 * @param maxSnapshotLifetime the optional snapshot-lifetime budget measured from the immutable
 *                           {@code capturedAt}; empty when unconfigured
 * @param clockSkew          the tolerated clock skew applied on both the future-dating checks and the
 *                           expiry check; never {@code null}
 * @param clock              the clock supplying "now"; injected so decode-time freshness is
 *                           deterministic under test; never {@code null}
 */
public record SnapshotFreshnessPolicy(
        Optional<Duration> maxCarrierLifetime,
        Optional<Duration> maxSnapshotLifetime,
        Duration clockSkew,
        Clock clock) {

    /**
     * Compact constructor — validates all components are non-null.
     */
    public SnapshotFreshnessPolicy {
        Objects.requireNonNull(maxCarrierLifetime, "maxCarrierLifetime");
        Objects.requireNonNull(maxSnapshotLifetime, "maxSnapshotLifetime");
        Objects.requireNonNull(clockSkew, "clockSkew");
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies the snapshot's temporal envelope, failing closed when it is malformed, future-dated
     * beyond the tolerated skew, or past its effective expiry.
     *
     * @param snapshot the HMAC-verified snapshot to check; must not be {@code null}
     * @throws IdentitySnapshotCodecException with reason
     *         {@link SnapshotDegradationReason#MALFORMED_TEMPORAL} for a future-dated or
     *         inconsistently-ordered envelope, or {@link SnapshotDegradationReason#EXPIRED} when the
     *         effective expiry has passed
     */
    public void check(IdentitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Instant now = clock.instant();
        Instant skewCeiling = now.plus(clockSkew);
        Instant capturedAt = snapshot.content().capturedAt();
        Instant issuedAt = snapshot.issuedAt();
        Instant expiresAt = snapshot.expiresAt();

        if (capturedAt.isAfter(skewCeiling)) {
            throw malformed("capturedAt " + capturedAt + " is future-dated beyond the tolerated clock skew");
        }
        if (issuedAt.isAfter(skewCeiling)) {
            throw malformed("issuedAt " + issuedAt + " is future-dated beyond the tolerated clock skew");
        }
        if (issuedAt.isBefore(capturedAt)) {
            throw malformed("issuedAt " + issuedAt + " precedes capturedAt " + capturedAt);
        }
        if (expiresAt.isBefore(issuedAt)) {
            throw malformed("expiresAt " + expiresAt + " precedes issuedAt " + issuedAt);
        }

        Instant effectiveExpiry = expiresAt;
        if (maxCarrierLifetime.isPresent()) {
            effectiveExpiry = earliest(effectiveExpiry, issuedAt.plus(maxCarrierLifetime.orElseThrow()));
        }
        if (maxSnapshotLifetime.isPresent()) {
            effectiveExpiry = earliest(effectiveExpiry, capturedAt.plus(maxSnapshotLifetime.orElseThrow()));
        }

        if (now.isAfter(effectiveExpiry.plus(clockSkew))) {
            throw new IdentitySnapshotCodecException(
                    "identity snapshot is past its effective expiry " + effectiveExpiry,
                    SnapshotDegradationReason.EXPIRED);
        }
    }

    /**
     * Returns the earlier of two instants.
     *
     * @param a the first instant
     * @param b the second instant
     * @return {@code b} when it is before {@code a}, otherwise {@code a}
     */
    private static Instant earliest(Instant a, Instant b) {
        return b.isBefore(a) ? b : a;
    }

    /**
     * Builds a fail-closed {@link IdentitySnapshotCodecException} with reason
     * {@link SnapshotDegradationReason#MALFORMED_TEMPORAL}.
     *
     * @param detail the human-readable failure detail
     * @return the exception to throw
     */
    private static IdentitySnapshotCodecException malformed(String detail) {
        return new IdentitySnapshotCodecException(
                "identity snapshot has a malformed temporal envelope: " + detail,
                SnapshotDegradationReason.MALFORMED_TEMPORAL);
    }
}
