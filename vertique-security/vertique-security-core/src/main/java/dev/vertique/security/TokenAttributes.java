// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable container for safe, decoded token metadata captured during authentication.
 *
 * <p>All three fields are Optional: a JWT-only flow populates {@link #jwtHeader()} and
 * {@link #jwtClaims()} but leaves {@link #introspectionResponse()} empty; a pure introspection
 * flow may populate only {@link #introspectionResponse()}. This record MUST NOT hold raw token
 * strings, API keys, or any other secret material.
 *
 * <p>Inner map contents are defensively copied so mutations to source maps after construction do
 * not affect this record.
 *
 * @param jwtHeader            decoded JWT header (e.g., {@code {"alg":"RS256","kid":"k1"}}); empty
 *                             when no JWT was presented
 * @param jwtClaims            decoded JWT payload claims (e.g., {@code {"sub":"u1","exp":...}});
 *                             empty when no JWT was presented
 * @param introspectionResponse selected fields from the introspection response body; empty when
 *                              introspection was not used
 */
public record TokenAttributes(
        Optional<Map<String, Object>> jwtHeader,
        Optional<Map<String, Object>> jwtClaims,
        Optional<Map<String, Object>> introspectionResponse) {

    /**
     * Compact constructor — validates that all Optional references are non-null and
     * defensively copies the inner maps when present.
     */
    public TokenAttributes {
        Objects.requireNonNull(jwtHeader, "jwtHeader");
        Objects.requireNonNull(jwtClaims, "jwtClaims");
        Objects.requireNonNull(introspectionResponse, "introspectionResponse");
        jwtHeader = jwtHeader.map(Map::copyOf);
        jwtClaims = jwtClaims.map(Map::copyOf);
        introspectionResponse = introspectionResponse.map(Map::copyOf);
    }
}
