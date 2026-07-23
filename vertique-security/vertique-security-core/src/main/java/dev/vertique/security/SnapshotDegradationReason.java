// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

/**
 * Typed reason a durably-carried {@link IdentitySnapshot} could not be reconstructed into a
 * verified {@link SecurityContext}.
 *
 * <p>Distinguishes forgery (a tampered integrity tag) from key-management failures (an unknown or
 * unavailable signing key) from plain decode/schema failures, so the receive-side
 * {@link SnapshotDegradationMarker} and the async
 * {@code dev.vertique.security.events.IdentitySnapshotDegradationEvent} it feeds can carry a
 * precise, machine-readable reason rather than a single catch-all code.
 *
 * @see IdentityReconstructionException#reason()
 * @see SnapshotDegradationMarker#reasonCode()
 */
public enum SnapshotDegradationReason {

    /** The recomputed HMAC tag did not match the tag carried on the snapshot (tamper/forgery). */
    BAD_HMAC,

    /** The snapshot's integrity envelope named a signing key id absent from the keyset. */
    UNKNOWN_KEY,

    /** No signing key is configured at all for the verifying keyset. */
    KEY_UNAVAILABLE,

    /** The encoded bytes could not be parsed or deserialized into a snapshot. */
    DECODE_FAILED,

    /** The snapshot's {@code schemaVersion} is newer than this codec supports. */
    SCHEMA_INCOMPATIBLE,

    /**
     * The snapshot's effective expiry (the three-term minimum of its signed {@code expiresAt}, its
     * carrier-lifetime budget, and its capture-anchored snapshot-lifetime budget) has passed —
     * enforced fail-closed at decode by the freshness policy.
     */
    EXPIRED,

    /**
     * The snapshot's temporal fields are internally inconsistent or impossibly future-dated (e.g.
     * {@code issuedAt} before {@code capturedAt}, {@code expiresAt} before {@code issuedAt}, or a
     * capture/issue instant beyond the allowed clock skew) — a malformed envelope rejected
     * fail-closed at decode by the freshness policy.
     */
    MALFORMED_TEMPORAL,

    /**
     * A {@link CarriageRequirement#REQUIRED} target received a dispatch with no identity snapshot at
     * all — the expected carriage is absent, either stripped/deleted on the app-writable store or
     * never written in the first place. Distinct from {@link #DECODE_FAILED} et al., which describe
     * a snapshot that arrived but failed verification; this reason describes the snapshot's total
     * absence on a target that was declared to always carry one.
     */
    EXPECTED_ABSENT
}
