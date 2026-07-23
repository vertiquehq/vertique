// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Objects;

/**
 * Verification source for credentials validated against an application-managed API key registry.
 *
 * <p>The {@code registryId} identifies which registry was consulted. The concrete registry
 * implementation is defined in the consuming application or auth module (deferred to auth-001).
 *
 * @param registryId stable identifier of the API key registry; must not be null or blank
 */
public record ApiKeyRegistryVerificationSource(String registryId) implements VerificationSource {

    /**
     * Compact constructor — validates that {@code registryId} is non-null and non-blank.
     */
    public ApiKeyRegistryVerificationSource {
        Objects.requireNonNull(registryId, "registryId");
        if (registryId.isBlank()) {
            throw new IllegalArgumentException("registryId must not be blank");
        }
    }
}
