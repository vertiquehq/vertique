// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.exception.VertiqueSecurityException;
import dev.vertique.security.SnapshotDegradationReason;
import java.util.Objects;

/**
 * Thrown by {@link IdentitySnapshotCodec} when encoded bytes cannot be decoded into a valid
 * {@link dev.vertique.security.IdentitySnapshot} — e.g. malformed JSON, an unknown-newer schema
 * version, or a signature that fails HMAC verification.
 *
 * <p>Decoding is fail-closed: any of the above conditions throws rather than returning a partial
 * or unverified snapshot.
 *
 * <p>Carries a {@link #reason()} classifying <em>which</em> failure occurred (bad HMAC tag, unknown
 * or unavailable signing key, decode failure, or an incompatible schema version), so callers such
 * as {@link dev.vertique.security.runtime.DefaultIdentityReconstruction} can propagate a precise
 * reason rather than a single catch-all code.
 */
public class IdentitySnapshotCodecException extends VertiqueSecurityException {

    private final SnapshotDegradationReason reason;

    /**
     * Constructs a new exception with the given detail message, defaulting {@link #reason()} to
     * {@link SnapshotDegradationReason#DECODE_FAILED} for callers that do not yet classify the
     * failure.
     *
     * @param message the detail message
     */
    public IdentitySnapshotCodecException(String message) {
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
    public IdentitySnapshotCodecException(String message, Throwable cause) {
        this(message, cause, SnapshotDegradationReason.DECODE_FAILED);
    }

    /**
     * Constructs a new exception with the given detail message and typed degradation reason.
     *
     * @param message the detail message
     * @param reason  the typed reason decoding/verification failed; must not be {@code null}
     */
    public IdentitySnapshotCodecException(String message, SnapshotDegradationReason reason) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Constructs a new exception with the given detail message, cause, and typed degradation
     * reason.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     * @param reason  the typed reason decoding/verification failed; must not be {@code null}
     */
    public IdentitySnapshotCodecException(String message, Throwable cause, SnapshotDegradationReason reason) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Returns the typed reason decoding/verification failed.
     *
     * @return the degradation reason; never {@code null}
     */
    public SnapshotDegradationReason reason() {
        return reason;
    }
}
