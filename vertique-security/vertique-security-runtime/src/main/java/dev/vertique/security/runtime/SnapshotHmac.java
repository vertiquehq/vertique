// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keyset-based HMAC signer/verifier for {@link dev.vertique.security.IdentitySnapshot} envelopes.
 *
 * <p>The keyset carries every key currently trusted for verification — the active signing key
 * plus zero or more previous (rotated-out) keys retained for a rotation grace window — indexed by
 * key id. {@link #sign(byte[], String, String)} always signs with the key identified by the
 * caller-supplied {@code keyId} (in practice the active key); {@link #verify(byte[], String,
 * String, String)} looks the signing key up by the tag's own {@code keyId}, so a snapshot signed
 * under a key that has since been demoted to "previous" still verifies.
 *
 * <p>Every operation is fail-closed: a {@code keyId} absent from the configured keyset throws
 * {@link SnapshotHmacException} rather than silently failing verification. A recomputed tag that
 * simply does not match the supplied tag is a normal (non-exceptional) {@code false} result from
 * {@link #verify(byte[], String, String, String)}.
 */
public class SnapshotHmac {

    /**
     * Server-side allowlist of trusted MAC algorithm names. The algorithm carried on a snapshot's
     * integrity envelope originates from the app-writable durable store, so it is
     * attacker-influenced; pinning it to this small SHA-2 HMAC family (rather than trusting the
     * blob-declared value) prevents an algorithm-confusion downgrade to a weaker or bogus primitive
     * while preserving rotation agility across the family.
     */
    private static final Set<String> ALLOWED_ALGORITHMS = Set.of("HmacSHA256", "HmacSHA384", "HmacSHA512");

    /**
     * Minimum secret length, in UTF-8 bytes, required for adequate HMAC key strength. Mirrors
     * {@code SnapshotHmacKeyConfig.MIN_SECRET_BYTES} (kept as a separate local constant because that
     * one is private to its own config-validation boundary) — this HMAC is the sole forgery defense
     * over the app-writable durable snapshot store (ADR-0162), so every key in the keyset must be at
     * least as long as the HMAC-SHA256 output size (32 bytes).
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final Map<String, String> keysById;
    private final String activeKeyId;

    /**
     * Constructs a {@code SnapshotHmac} over the given keyset.
     *
     * @param keysById    key material indexed by key id; defensively copied; must contain
     *                    {@code activeKeyId} for {@link #sign(byte[], String, String)} calls
     *                    against the active key to succeed; every value must be at least
     *                    {@value #MIN_SECRET_BYTES} UTF-8 bytes
     * @param activeKeyId the id of the currently active signing key
     * @throws NullPointerException     if {@code keysById} or {@code activeKeyId} is {@code null}
     * @throws IllegalArgumentException if any secret value in {@code keysById} is shorter than
     *                                  {@value #MIN_SECRET_BYTES} UTF-8 bytes; the message names
     *                                  only the offending {@code keyId}, never the secret material
     */
    public SnapshotHmac(Map<String, String> keysById, String activeKeyId) {
        this.keysById = Map.copyOf(Objects.requireNonNull(keysById, "keysById"));
        this.activeKeyId = Objects.requireNonNull(activeKeyId, "activeKeyId");
        for (Map.Entry<String, String> entry : this.keysById.entrySet()) {
            if (entry.getValue().getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "hmac key '" + entry.getKey() + "' must be at least " + MIN_SECRET_BYTES + " bytes (UTF-8)");
            }
        }
    }

    /**
     * Signs the given payload under the key identified by {@code keyId}.
     *
     * @param payload   the canonical bytes to sign
     * @param keyId     the id of the key to sign with; must be present in the configured keyset
     * @param algorithm the {@code javax.crypto.Mac} algorithm name (e.g. {@code "HmacSHA256"})
     * @return the base64url-encoded MAC tag over {@code payload} plus {@code keyId} and
     *         {@code algorithm}
     * @throws SnapshotHmacException if {@code keyId} is not configured, or the algorithm/key
     *                                material is invalid
     */
    public String sign(byte[] payload, String keyId, String algorithm) {
        byte[] mac = computeMac(payload, keyId, algorithm);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }

    /**
     * Verifies that {@code tag} is the correct MAC for {@code payload} under the key identified by
     * {@code keyId}, using a constant-time comparison.
     *
     * @param payload   the canonical bytes that were signed
     * @param keyId     the id of the key the tag claims to be signed with
     * @param algorithm the {@code javax.crypto.Mac} algorithm name (e.g. {@code "HmacSHA256"})
     * @param tag       the base64url-encoded MAC tag to verify
     * @return {@code true} if the recomputed MAC matches {@code tag}; {@code false} otherwise
     * @throws SnapshotHmacException if {@code keyId} is not configured in this keyset, or the
     *                                algorithm/key material is invalid — a fail-closed condition
     *                                distinct from a simple verification mismatch
     */
    public boolean verify(byte[] payload, String keyId, String algorithm, String tag) {
        Objects.requireNonNull(tag, "tag");
        byte[] expected = computeMac(payload, keyId, algorithm);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(tag);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Returns the id of the currently active signing key.
     *
     * @return the active key id
     */
    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * Computes the raw MAC bytes over {@code payload} plus {@code keyId} and {@code algorithm},
     * using the key material registered under {@code keyId}.
     *
     * <p>The {@code algorithm} is checked against {@link #ALLOWED_ALGORITHMS} <em>before</em> any
     * {@code Mac.getInstance} call, so an algorithm outside the trusted SHA-2 HMAC family — which
     * may originate from the app-writable durable store — is rejected fail-closed and never
     * instantiated as a MAC primitive.
     *
     * @param payload   the canonical bytes to authenticate
     * @param keyId     the id of the key to use
     * @param algorithm the {@code javax.crypto.Mac} algorithm name; must be in the server allowlist
     * @return the raw MAC bytes
     * @throws SnapshotHmacException if {@code algorithm} is outside {@link #ALLOWED_ALGORITHMS},
     *                                {@code keyId} is unknown to this keyset, or the key material is
     *                                invalid
     */
    private byte[] computeMac(byte[] payload, String keyId, String algorithm) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(algorithm, "algorithm");
        if (!ALLOWED_ALGORITHMS.contains(algorithm)) {
            throw new SnapshotHmacException(
                    "unsupported MAC algorithm '" + algorithm + "' for keyId '" + keyId
                            + "'; only the SHA-2 HMAC family is trusted",
                    SnapshotHmacException.Kind.UNSUPPORTED_ALGORITHM);
        }
        String secret = keysById.get(keyId);
        if (secret == null) {
            SnapshotHmacException.Kind kind = keysById.isEmpty()
                    ? SnapshotHmacException.Kind.KEY_UNAVAILABLE
                    : SnapshotHmacException.Kind.UNKNOWN_KEY;
            throw new SnapshotHmacException("no key configured for keyId '" + keyId + "'", kind);
        }
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            mac.update(payload);
            mac.update(keyId.getBytes(StandardCharsets.UTF_8));
            mac.update(algorithm.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new SnapshotHmacException("unable to compute MAC for keyId '" + keyId + "'", e);
        }
    }
}
