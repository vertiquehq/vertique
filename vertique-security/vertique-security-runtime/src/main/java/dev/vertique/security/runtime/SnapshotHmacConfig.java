// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Root configuration record for the {@link SnapshotHmac} keyset backing
 * {@link IdentitySnapshotCodec}, deserialized from the {@code identity.snapshot.hmacKeys} section
 * of the application config via {@link dev.vertique.core.config.ConfigParser}
 * (PRD-ID-002 §14.3 "Durable carriage").
 *
 * <p>Carries exactly one required active signing key plus zero or more previous (rotated-out)
 * keys retained for verification during a rotation grace window — a snapshot signed under a
 * previous key still verifies via {@link SnapshotHmac#verify(byte[], String, String, String)}.
 *
 * @param active   the currently active signing key; required, must not be {@code null}
 * @param previous the previous (verification-only) keys, in configured order; never {@code null},
 *                 defaults to an empty list when omitted
 */
public record SnapshotHmacConfig(SnapshotHmacKeyConfig active, List<SnapshotHmacKeyConfig> previous) {

    /**
     * Compact constructor: validates the active key is present, defensively copies
     * {@code previous}, and rejects a {@code keyId} collision — both between the active key and any
     * previous key, and between two previous keys — since an unambiguous key-to-secret mapping is
     * required. The previous-vs-previous check runs <em>before</em> {@link #toSnapshotHmac()} builds
     * the id-to-secret map, so a duplicate keyId can never reach
     * {@link Collectors#toUnmodifiableMap} — whose duplicate-key {@link IllegalStateException} would
     * embed the colliding raw secret values in its message and leak them into the startup log.
     *
     * @throws ConfigurationException if {@code active} is {@code null}, a previous key reuses the
     *                                 active key's {@code keyId}, or two previous keys share a
     *                                 {@code keyId}; the message names only the offending
     *                                 {@code keyId}, never a secret
     */
    public SnapshotHmacConfig {
        if (active == null) {
            throw new ConfigurationException("identity.snapshot.hmacKeys.active is required");
        }
        Objects.requireNonNull(previous, "previous");
        previous = List.copyOf(previous);
        Set<String> seenPreviousKeyIds = new HashSet<>();
        for (SnapshotHmacKeyConfig previousKey : previous) {
            if (previousKey.keyId().equals(active.keyId())) {
                throw new ConfigurationException("identity.snapshot.hmacKeys.previous reuses active keyId '"
                        + active.keyId() + "'; previous key ids must be distinct from the active key id");
            }
            if (!seenPreviousKeyIds.add(previousKey.keyId())) {
                throw new ConfigurationException("identity.snapshot.hmacKeys.previous contains duplicate keyId '"
                        + previousKey.keyId() + "'; previous key ids must be distinct");
            }
        }
    }

    /**
     * Jackson-friendly factory that fills in the default empty {@code previous} list when omitted.
     *
     * @param active   the active signing key
     * @param previous the previous keys; defaults to an empty list when {@code null}
     * @return the deserialized keyset config
     */
    @JsonCreator
    static SnapshotHmacConfig fromJson(
            @JsonProperty("active") @Nullable SnapshotHmacKeyConfig active,
            @JsonProperty("previous") @Nullable List<SnapshotHmacKeyConfig> previous) {
        return new SnapshotHmacConfig(active, previous != null ? previous : List.of());
    }

    /**
     * Builds the {@link SnapshotHmac} keyset this config describes: every key (active and
     * previous) indexed by {@code keyId}, with {@link #active}'s {@code keyId} as the active key.
     *
     * @return a new {@link SnapshotHmac} backed by this config's keys
     */
    SnapshotHmac toSnapshotHmac() {
        Map<String, String> keysById = Stream.concat(Stream.of(active), previous.stream())
                .collect(Collectors.toUnmodifiableMap(SnapshotHmacKeyConfig::keyId, SnapshotHmacKeyConfig::secretRef));
        return new SnapshotHmac(keysById, active.keyId());
    }
}
