// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Map;
import java.util.Objects;

/**
 * Escape-hatch {@link VerificationSource} for application-defined verification mechanisms
 * that do not fit any of the standard permits.
 *
 * <p>The {@code customType} field carries an application-defined discriminator (e.g.,
 * {@code "vendor-x"}) that is distinct from the Jackson {@code "type"} property. The
 * {@code attributes} map carries arbitrary metadata specific to the custom verification
 * mechanism.
 *
 * @param customType application-defined type discriminator; must not be null or blank
 * @param attributes mechanism-specific attributes; defensively copied; null treated as empty
 */
public record CustomVerificationSource(String customType, Map<String, Object> attributes)
        implements VerificationSource {

    /**
     * Compact constructor — validates {@code customType} and defensively copies
     * {@code attributes}.
     */
    public CustomVerificationSource {
        Objects.requireNonNull(customType, "customType");
        if (customType.isBlank()) {
            throw new IllegalArgumentException("customType must not be blank");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}
