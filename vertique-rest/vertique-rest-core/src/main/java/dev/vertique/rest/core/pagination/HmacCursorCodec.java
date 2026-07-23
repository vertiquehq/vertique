// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.pagination;

import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link CursorCodec} that signs cursor tokens using HMAC-SHA256.
 *
 * <p>Protects cursor tokens from tampering by clients. Supports multi-key rotation
 * for zero-downtime secret updates and an optional TTL for cursor expiration.
 *
 * <p><strong>Token format:</strong> {@code Base64URL(JSON{ kid, iat, exp?, tok, mac })}
 * <ul>
 *   <li>{@code kid} — key ID used to sign; used to locate the verification key</li>
 *   <li>{@code iat} — issued-at epoch seconds</li>
 *   <li>{@code exp} — expiry epoch seconds (only present when TTL is configured)</li>
 *   <li>{@code tok} — the raw backend cursor string</li>
 *   <li>{@code mac} — Base64URL HMAC-SHA256 over {@code kid|iat|exp_or_empty|tok}</li>
 * </ul>
 * The outer Base64URL encoding (without padding) satisfies the {@link CursorCodec#encode}
 * URL-safety contract.
 *
 * <p><strong>TTL semantics:</strong> A token is considered expired when
 * {@code exp <= Instant.now().getEpochSecond()} — i.e., it expires at the boundary second itself.
 *
 * <p><strong>Replay trade-off:</strong> Tokens are not bound to a session or user identity.
 * A stolen token is replayable until it expires. This is acceptable for pagination.
 *
 * <p>App wiring with multi-key rotation:
 * <pre>{@code
 * @Provides @Singleton
 * CursorCodec cursorCodec(@VertxConfig JsonObject config) {
 *     return new HmacCursorCodec(
 *         List.of(
 *             new HmacCursorCodec.Key("v2", config.getString("cursor.secretV2")),
 *             new HmacCursorCodec.Key("v1", config.getString("cursor.secretV1"))
 *         ),
 *         Duration.ofHours(1));
 * }
 * }</pre>
 */
public class HmacCursorCodec implements CursorCodec {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern VALID_KID = Pattern.compile("[A-Za-z0-9_-]+");

    private final List<Key> keys;

    @Nullable
    private final Duration ttl;

    /**
     * A named HMAC-SHA256 key used for signing and verification.
     *
     * <p>The key identifier ({@code id}) must consist only of ASCII alphanumeric characters,
     * hyphens, and underscores ({@code [A-Za-z0-9_-]+}). This restriction prevents delimiter
     * injection in the HMAC message format.
     *
     * @param id        the key identifier, embedded in tokens to select the correct verification key
     * @param secretKey the HMAC secret key
     */
    public record Key(String id, SecretKey secretKey) {

        /** Compact constructor — validates key identifier format. */
        public Key {
            Objects.requireNonNull(id, "Key id must not be null");
            if (!VALID_KID.matcher(id).matches()) {
                throw new IllegalArgumentException("Key id must match [A-Za-z0-9_-]+, got '" + id + "'");
            }
            Objects.requireNonNull(secretKey, "secretKey must not be null");
        }

        /**
         * Creates a {@code Key} from a string secret.
         *
         * @param id     the key identifier; must match {@code [A-Za-z0-9_-]+}
         * @param secret the secret string; must be at least 32 bytes when UTF-8 encoded
         * @throws IllegalArgumentException if {@code id} has invalid format or the secret is too short
         */
        public Key(String id, String secret) {
            this(id, toSecretKey(id, secret));
        }

        private static SecretKey toSecretKey(String id, String secret) {
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 32) {
                throw new IllegalArgumentException(
                        "Key '" + id + "' must be >= 32 bytes when UTF-8 encoded, got " + bytes.length);
            }
            return new SecretKeySpec(bytes, ALGORITHM);
        }
    }

    /**
     * Creates an {@code HmacCursorCodec} with multiple keys and an optional TTL.
     *
     * <p>The first key in the list is used for signing. All keys are tried during
     * decoding (matched by {@code kid}), enabling zero-downtime key rotation.
     *
     * @param keys the signing/verification keys; must not be empty
     * @param ttl  the cursor lifetime; {@code null} for no expiration
     * @throws IllegalArgumentException if {@code keys} is null or empty
     */
    public HmacCursorCodec(List<Key> keys, @Nullable Duration ttl) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("At least one key is required");
        }
        this.keys = List.copyOf(keys);
        this.ttl = ttl;
    }

    /**
     * Convenience constructor for a single key.
     *
     * @param keyId  the key identifier
     * @param secret the secret string; must be at least 32 bytes UTF-8
     * @param ttl    the cursor lifetime; {@code null} for no expiration
     */
    public HmacCursorCodec(String keyId, String secret, @Nullable Duration ttl) {
        this(List.of(new Key(keyId, secret)), ttl);
    }

    /**
     * Encodes the raw cursor string to a signed, Base64URL-encoded opaque token.
     *
     * @param rawCursor the raw backend cursor; must not be null
     * @return a Base64URL-encoded signed token (no padding)
     * @throws NullPointerException if {@code rawCursor} is null
     * @throws RuntimeException if the HMAC algorithm is unavailable (standard JVMs always provide it)
     */
    @Override
    public String encode(String rawCursor) {
        Objects.requireNonNull(rawCursor, "rawCursor must not be null");
        Key activeKey = keys.get(0);
        long iat = Instant.now().getEpochSecond();
        Long exp = ttl != null ? iat + ttl.toSeconds() : null;
        byte[] macBytes = computeMac(activeKey.secretKey(), activeKey.id(), iat, exp, rawCursor);
        String mac = Base64.getUrlEncoder().withoutPadding().encodeToString(macBytes);

        JsonObject payload = new JsonObject()
                .put("kid", activeKey.id())
                .put("iat", iat)
                .put("tok", rawCursor)
                .put("mac", mac);

        if (exp != null) {
            payload.put("exp", exp);
        }

        byte[] jsonBytes = payload.encode().getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(jsonBytes);
    }

    /**
     * Decodes and verifies a signed token, returning the raw backend cursor string.
     *
     * @param opaqueToken the token from the client
     * @return the raw cursor string
     * @throws InvalidCursorException if the token is malformed, tampered with, or expired
     */
    @Override
    public String decode(String opaqueToken) {
        try {
            byte[] jsonBytes = Base64.getUrlDecoder().decode(opaqueToken);
            JsonObject payload = new JsonObject(new String(jsonBytes, StandardCharsets.UTF_8));

            String kid = payload.getString("kid");
            Long iat = payload.getLong("iat");
            String tok = payload.getString("tok");
            String mac = payload.getString("mac");
            Long exp = payload.getLong("exp");

            if (kid == null || iat == null || tok == null || mac == null) {
                throw new InvalidCursorException("Invalid cursor");
            }

            Key matchedKey = findKey(kid);

            byte[] expectedMac = computeMac(matchedKey.secretKey(), kid, iat, exp, tok);
            if (!MessageDigest.isEqual(Base64.getUrlDecoder().decode(mac), expectedMac)) {
                throw new InvalidCursorException("Invalid cursor");
            }

            if (exp != null && exp <= Instant.now().getEpochSecond()) {
                throw new InvalidCursorException("Invalid cursor");
            }

            return tok;
        } catch (InvalidCursorException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidCursorException("Invalid cursor", e);
        }
    }

    // --- Helpers ---

    private Key findKey(String kid) {
        return keys.stream()
                .filter(k -> k.id().equals(kid))
                .findFirst()
                .orElseThrow(() -> new InvalidCursorException("Invalid cursor"));
    }

    private static byte[] computeMac(SecretKey secretKey, String kid, long iat, @Nullable Long exp, String tok) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            String message = kid + "|" + iat + "|" + (exp != null ? exp : "") + "|" + tok;
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
