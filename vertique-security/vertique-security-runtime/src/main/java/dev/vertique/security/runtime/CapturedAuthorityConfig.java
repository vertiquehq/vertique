// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.exception.ConfigurationException;
import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Configuration record for the Mode-3 captured-authority reconstruction opt-in, deserialized from
 * the {@code identity.snapshot.capturedAuthority} section of the application config via {@link
 * dev.vertique.core.config.ConfigParser} (PRD-ID-002 §14.3 Phase-2 Appendix, §14.6 P2.S4).
 *
 * <p>{@link #allowedTargetKinds()} is the per-durable-target-kind allowlist {@link
 * DefaultCapturedAuthorityReconstruction} enforces before ever trusting a snapshot's captured
 * claims as current authority. This record carries no policy about whether those kinds are
 * actually {@link dev.vertique.security.CarriageRequirement#REQUIRED} — that cross-check runs at
 * {@code CapturedAuthorityReconstructionModule} provider time against the sibling {@link
 * IdentitySnapshotConfig}, since it depends on both config sections.
 *
 * @param allowedTargetKinds the durable-target kinds captured-authority reconstruction is
 *                           permitted against; never {@code null}, defensively copied; defaults to
 *                           an empty set when omitted (Mode 3 permits no targets by default even
 *                           when the opt-in module is installed with no explicit allowlist)
 */
public record CapturedAuthorityConfig(Set<String> allowedTargetKinds) {

    /**
     * Compact constructor — defensively copies {@code allowedTargetKinds} and rejects a blank
     * entry.
     *
     * @throws ConfigurationException if {@code allowedTargetKinds} contains a {@code null} or
     *                                 blank entry
     */
    public CapturedAuthorityConfig {
        allowedTargetKinds = allowedTargetKinds == null ? Set.of() : Set.copyOf(allowedTargetKinds);
        for (String kind : allowedTargetKinds) {
            if (kind == null || kind.isBlank()) {
                throw new ConfigurationException(
                        "identity.snapshot.capturedAuthority.allowedTargetKinds entries must be non-blank");
            }
        }
    }

    /**
     * Jackson-friendly factory that defaults {@code allowedTargetKinds} to an empty set when
     * omitted.
     *
     * @param allowedTargetKinds the configured allowlist; {@code null} treated as empty
     * @return the deserialized captured-authority configuration
     */
    @JsonCreator
    static CapturedAuthorityConfig fromJson(
            @JsonProperty("allowedTargetKinds") @Nullable Set<String> allowedTargetKinds) {
        return new CapturedAuthorityConfig(allowedTargetKinds);
    }

    /**
     * Returns the default captured-authority configuration: an empty allowlist, so Mode 3 permits
     * no targets absent explicit operator configuration.
     *
     * @return the default configuration
     */
    public static CapturedAuthorityConfig defaults() {
        return new CapturedAuthorityConfig(Set.of());
    }
}
