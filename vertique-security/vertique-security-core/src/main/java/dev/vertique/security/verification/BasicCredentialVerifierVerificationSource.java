// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Objects;

/**
 * Verification source for credentials validated by a Basic authentication credential verifier.
 *
 * <p>The {@code verifierId} identifies the verifier implementation (e.g., an LDAP directory
 * adapter or an in-memory user store). The concrete verifier is provided by the consuming
 * application or auth module.
 *
 * @param verifierId stable identifier of the credential verifier; must not be null or blank
 */
public record BasicCredentialVerifierVerificationSource(String verifierId) implements VerificationSource {

    /**
     * Compact constructor — validates that {@code verifierId} is non-null and non-blank.
     */
    public BasicCredentialVerifierVerificationSource {
        Objects.requireNonNull(verifierId, "verifierId");
        if (verifierId.isBlank()) {
            throw new IllegalArgumentException("verifierId must not be blank");
        }
    }
}
