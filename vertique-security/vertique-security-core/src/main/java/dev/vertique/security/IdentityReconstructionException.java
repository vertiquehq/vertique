// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import dev.vertique.core.exception.VertiqueSecurityException;
import java.util.Objects;

/**
 * Thrown by {@link IdentityReconstruction} when a {@link IdentitySnapshot} cannot be safely
 * reconstructed into a {@link SecurityContext}.
 *
 * <p>Reconstruction is the trust boundary of FR-ID-CA-008: it re-verifies the snapshot's integrity
 * envelope before minting any context. This exception is thrown for a {@code null} snapshot, a
 * tampered or otherwise invalid integrity tag, an unknown signing key, or any other condition the
 * underlying codec rejects. Reconstruction is fail-closed — this exception is always thrown before
 * any {@link SecurityContext} is built; there is no partial or unbound result.
 *
 * <p>Carries a {@link #reason()} classifying <em>which</em> failure occurred, so a caller (e.g. the
 * receive-side {@code IdentitySnapshotReconstructionInitializer}) can bind a precise
 * {@link SnapshotDegradationMarker} rather than a single catch-all reason code.
 */
public class IdentityReconstructionException extends VertiqueSecurityException {

    private final SnapshotDegradationReason reason;

    /**
     * Constructs a new exception with the given detail message, defaulting {@link #reason()} to
     * {@link SnapshotDegradationReason#DECODE_FAILED} for callers that do not yet classify the
     * failure.
     *
     * @param message the detail message
     */
    public IdentityReconstructionException(String message) {
        this(message, SnapshotDegradationReason.DECODE_FAILED);
    }

    /**
     * Constructs a new exception with the given detail message and cause, defaulting
     * {@link #reason()} to {@link SnapshotDegradationReason#DECODE_FAILED} for callers that do not
     * yet classify the failure.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public IdentityReconstructionException(String message, Throwable cause) {
        this(message, cause, SnapshotDegradationReason.DECODE_FAILED);
    }

    /**
     * Constructs a new exception with the given detail message and typed degradation reason.
     *
     * @param message the detail message
     * @param reason  the typed reason this reconstruction failed; must not be {@code null}
     */
    public IdentityReconstructionException(String message, SnapshotDegradationReason reason) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Constructs a new exception with the given detail message, cause, and typed degradation
     * reason.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     * @param reason  the typed reason this reconstruction failed; must not be {@code null}
     */
    public IdentityReconstructionException(String message, Throwable cause, SnapshotDegradationReason reason) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the typed reason this reconstruction failed.
     *
     * @return the degradation reason; never {@code null}
     */
    public SnapshotDegradationReason reason() {
        return reason;
    }
}
