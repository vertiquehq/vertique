// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import dev.vertique.core.exception.VertiqueSecurityException;
import java.util.Objects;

/**
 * Thrown by {@link SnapshotHmac} when a sign or verify operation cannot be completed against the
 * configured keyset — e.g. the requested key id is unknown, or no key is configured at all.
 *
 * <p>This exception signals a fail-closed condition: the caller MUST NOT treat a thrown
 * {@code SnapshotHmacException} as a "not verified" result, only as an inability to determine
 * verification at all. Callers that want a boolean verification outcome for a known, correctly
 * configured keyset should rely on {@link SnapshotHmac#verify(byte[], String, String, String)}
 * returning {@code false}; this exception is reserved for keyset misconfiguration.
 *
 * <p>Carries a {@link #kind()} distinguishing a {@code keyId} that is simply absent from an
 * otherwise-populated keyset ({@link Kind#UNKNOWN_KEY}) from a keyset with no key material
 * configured at all ({@link Kind#KEY_UNAVAILABLE}), so callers such as
 * {@link IdentitySnapshotCodec} can map the failure to the correct
 * {@link dev.vertique.security.SnapshotDegradationReason}.
 */
public class SnapshotHmacException extends VertiqueSecurityException {

    /**
     * Classifies why a {@link SnapshotHmac} operation could not be completed against the
     * configured keyset.
     */
    public enum Kind {
        /** The keyset has key material configured, but not under the requested {@code keyId}. */
        UNKNOWN_KEY,

        /** The keyset has no key material configured at all. */
        KEY_UNAVAILABLE,

        /**
         * The requested MAC algorithm is not in the server-side allowlist of trusted HMAC
         * primitives — a fail-closed rejection of an attacker-influenced algorithm name before any
         * {@code Mac.getInstance} call.
         */
        UNSUPPORTED_ALGORITHM
    }

    private final Kind kind;

    /**
     * Constructs a new exception with the given detail message, defaulting {@link #kind()} to
     * {@link Kind#UNKNOWN_KEY} for callers that do not yet classify the failure.
     *
     * @param message the detail message
     */
    public SnapshotHmacException(String message) {
        this(message, Kind.UNKNOWN_KEY);
    }

    /**
     * Constructs a new exception with the given detail message and cause, defaulting
     * {@link #kind()} to {@link Kind#UNKNOWN_KEY} for callers that do not yet classify the
     * failure.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     */
    public SnapshotHmacException(String message, Throwable cause) {
        this(message, cause, Kind.UNKNOWN_KEY);
    }

    /**
     * Constructs a new exception with the given detail message and classified {@link Kind}.
     *
     * @param message the detail message
     * @param kind    the classification of this failure; must not be {@code null}
     */
    public SnapshotHmacException(String message, Kind kind) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /**
     * Constructs a new exception with the given detail message, cause, and classified {@link Kind}.
     *
     * @param message the detail message
     * @param cause   the underlying cause
     * @param kind    the classification of this failure; must not be {@code null}
     */
    public SnapshotHmacException(String message, Throwable cause, Kind kind) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    /**
     * Returns the classification of why this operation could not be completed.
     *
     * @return the failure kind; never {@code null}
     */
    public Kind kind() {
        return kind;
    }
}
