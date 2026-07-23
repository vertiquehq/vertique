// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * SHA-256 digest utility for request/response body integrity verification.
 *
 * <p>Produces Base64-encoded SHA-256 digests suitable for use in
 * {@code Digest} HTTP headers per RFC 3230 / draft-ietf-httpbis-digest-headers.
 */
public final class DigestUtil {

    private DigestUtil() {}

    /**
     * Computes the Base64-encoded SHA-256 digest of the given bytes.
     *
     * @param data the bytes to hash
     * @return the Base64-encoded SHA-256 digest string
     */
    public static String sha256Base64(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /**
     * Computes the Base64-encoded SHA-256 digest of the given string's UTF-8 bytes.
     *
     * @param data the string to hash (encoded as UTF-8)
     * @return the Base64-encoded SHA-256 digest string
     */
    public static String sha256Base64(String data) {
        return sha256Base64(data.getBytes(StandardCharsets.UTF_8));
    }
}
