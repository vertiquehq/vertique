// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.verification;

import java.util.Objects;

/**
 * Verification source for credentials validated via an OAuth 2.0 token introspection endpoint
 * (RFC 7662).
 *
 * @param endpoint      the introspection endpoint URI; must not be null or blank
 * @param responseShape the expected shape of the introspection response
 */
public record IntrospectionVerificationSource(String endpoint, IntrospectionResponseShape responseShape)
        implements VerificationSource {

    /**
     * Compact constructor — validates that {@code endpoint} is non-null and non-blank,
     * and that {@code responseShape} is non-null.
     */
    public IntrospectionVerificationSource {
        Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        Objects.requireNonNull(responseShape, "responseShape");
    }
}
