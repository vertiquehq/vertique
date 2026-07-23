// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;

/**
 * Configuration record for a single {@link SnapshotHmac} keyset entry: a stable {@code keyId} and
 * a reference to the key's secret material.
 *
 * <p>{@code secretRef} carries the resolved secret value itself (e.g. sourced from an environment
 * variable or a {@code vertique-config-*} secret provider before this section is parsed) — it is
 * never logged or re-serialized; see {@link SnapshotHmacConfig} for the keyset this entry belongs
 * to.
 *
 * @param keyId     the stable identifier for this key; must not be {@code null} or blank
 * @param secretRef the resolved secret key material; must not be {@code null} or blank
 */
public record SnapshotHmacKeyConfig(
        String keyId,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String secretRef) {

    /**
     * Minimum {@code secretRef} length, in UTF-8 bytes, required for adequate HMAC key strength.
     * {@code secretRef} is used directly as the HMAC key material (see {@link SnapshotHmac} —
     * {@code new SecretKeySpec(secret.getBytes(UTF_8), algorithm)}), and this HMAC is the sole
     * forgery defense over the app-writable durable snapshot store (ADR-0162), so the key must be at
     * least as long as the HMAC-SHA256 output size (32 bytes) — a shorter key weakens that defense.
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * Compact constructor validating non-blank {@code keyId} and {@code secretRef}, and enforcing
     * the minimum HMAC key strength of {@value #MIN_SECRET_BYTES} UTF-8 bytes on {@code secretRef}
     * (the value used directly as HMAC key material).
     *
     * @throws ConfigurationException if either component is {@code null} or blank, or if
     *                                 {@code secretRef} is shorter than {@value #MIN_SECRET_BYTES}
     *                                 UTF-8 bytes; the message names only the {@code keyId}, never a
     *                                 secret
     */
    public SnapshotHmacKeyConfig {
        if (keyId == null || keyId.isBlank()) {
            throw new ConfigurationException("identity.snapshot.hmacKeys[].keyId must be non-blank");
        }
        if (secretRef == null || secretRef.isBlank()) {
            throw new ConfigurationException("identity.snapshot.hmacKeys[" + keyId + "].secretRef must be non-blank");
        }
        if (secretRef.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new ConfigurationException("identity.snapshot.hmacKeys[" + keyId + "].secretRef must be at least "
                    + MIN_SECRET_BYTES + " bytes (UTF-8) for HMAC-SHA256 key strength");
        }
    }

    /**
     * Jackson-friendly factory; both properties are required (a missing/blank value fails in the
     * compact constructor).
     *
     * @param keyId     the key id
     * @param secretRef the resolved secret material
     * @return the deserialized key config
     */
    @JsonCreator
    static SnapshotHmacKeyConfig fromJson(
            @JsonProperty("keyId") @Nullable String keyId, @JsonProperty("secretRef") @Nullable String secretRef) {
        return new SnapshotHmacKeyConfig(keyId != null ? keyId : "", secretRef != null ? secretRef : "");
    }

    /**
     * Redacted rendering — never includes {@link #secretRef}.
     *
     * @return a log-safe string representation
     */
    @Override
    public String toString() {
        return "SnapshotHmacKeyConfig[keyId=" + keyId + ", secretRef=***]";
    }
}
