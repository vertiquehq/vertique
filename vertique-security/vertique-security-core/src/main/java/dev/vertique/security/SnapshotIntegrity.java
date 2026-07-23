// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Objects;

/**
 * Mandatory signed envelope proving an {@link IdentitySnapshot} was produced by the framework's
 * durable snapshot codec and has not been tampered with in transit or at rest.
 *
 * <p>The {@link #tag()} is a base64url-encoded message authentication code computed over the
 * snapshot's canonical bytes plus {@link #keyId()} and {@link #algorithm()}. Verification accepts
 * the currently active signing key plus any configured previous (rotated-out) keys within a grace
 * window; a snapshot whose {@link #keyId()} is unknown, or whose {@link #tag()} fails to verify
 * under that key, is rejected — never partially trusted.
 *
 * <p>{@link IdentitySnapshotFactory#capture(SecurityContext)} produces a to-be-signed snapshot
 * carrying a placeholder envelope; the durable codec is the single HMAC signer, computing the
 * authoritative tag at encode time. Identity reconstruction verifies this envelope before
 * restoring a {@link SecurityContext} from a snapshot.
 *
 * <p>Construction rules:
 * <ul>
 *   <li>{@code algorithm}, {@code keyId}, and {@code tag} are all required (non-null, non-blank)</li>
 * </ul>
 *
 * @param algorithm the MAC algorithm used to produce {@link #tag()} (e.g. {@code "HmacSHA256"})
 * @param keyId     identifies the signing key/version used to produce {@link #tag()}
 * @param tag       base64url-encoded MAC over the canonical snapshot bytes plus {@code keyId} and
 *                  {@code algorithm}
 */
public record SnapshotIntegrity(String algorithm, String keyId, String tag) {

    /**
     * Compact constructor — validates required fields.
     */
    public SnapshotIntegrity {
        Objects.requireNonNull(algorithm, "algorithm");
        if (algorithm.isBlank()) {
            throw new IllegalArgumentException("algorithm must not be blank");
        }
        Objects.requireNonNull(keyId, "keyId");
        if (keyId.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        Objects.requireNonNull(tag, "tag");
        if (tag.isBlank()) {
            throw new IllegalArgumentException("tag must not be blank");
        }
    }
}
