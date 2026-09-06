// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
 * <p>{@link #toString()} is safe to log: the JWT header renders in full, while claims and
 * introspection fields render by name with the value shown only for the non-sensitive registered
 * set ({@code iss}, {@code aud}, {@code exp}, {@code nbf}, {@code iat}, {@code jti}, {@code azp},
 * {@code typ}, {@code scope}, {@code scp}, {@code client_id}, {@code token_type}, {@code active})
 * and {@code <redacted>} for every other name. Observers and audit adapters read the maps directly
 * through the accessors; only the string rendering is redacted.
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

    /** Claim and introspection field names whose values are safe to render in {@link #toString()}. */
    private static final Set<String> DISCLOSED_NAMES = Set.of(
            "iss",
            "aud",
            "exp",
            "nbf",
            "iat",
            "jti",
            "azp",
            "typ",
            "scope",
            "scp",
            "client_id",
            "token_type",
            "active");

    /**
     * Log-safe rendering: the header in full (sorted by name, nothing redacted), claims and
     * introspection fields by name with values shown only for {@link #DISCLOSED_NAMES} and
     * {@code <redacted>} otherwise, and {@code absent} for an empty part. A nested object under a
     * disclosed claim is redacted rather than rendered, collections render element by element, and
     * control characters are replaced so a value cannot forge a log line.
     *
     * @return a rendering that never contains the value of a claim outside the disclosed set
     */
    @Override
    public String toString() {
        return "TokenAttributes[jwtHeader=" + describe(jwtHeader, true)
                + ", jwtClaims=" + describe(jwtClaims, false)
                + ", introspectionResponse=" + describe(introspectionResponse, false)
                + "]";
    }

    private static String describe(Optional<Map<String, Object>> part, boolean discloseAll) {
        return part.map(map -> map.keySet().stream()
                        .sorted()
                        .map(name -> name + "="
                                + (discloseAll
                                        ? neutralise(map.get(name))
                                        : DISCLOSED_NAMES.contains(name) ? render(map.get(name)) : "<redacted>"))
                        .collect(Collectors.joining(", ", "{", "}")))
                .orElse("absent");
    }

    private static String render(Object value) {
        if (value instanceof Map<?, ?>) {
            return "<redacted>";
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(TokenAttributes::render).collect(Collectors.joining(", ", "[", "]"));
        }
        return neutralise(value);
    }

    private static String neutralise(Object value) {
        return String.valueOf(value).replaceAll("\\p{Cntrl}", "?");
    }
}
