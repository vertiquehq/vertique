// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object representing an HTTP media type as defined by RFC 9110.
 *
 * <p>A media type consists of a {@code type}, a {@code subtype}, optional parameters
 * (excluding the {@code q} quality parameter), and a quality factor. Both type and
 * subtype are stored and compared in lowercase.
 *
 * <p>Use {@link #parse(String)} or {@link #valueOf(String)} to create instances from
 * raw header strings such as {@code "application/json;charset=utf-8;q=0.8"}.
 *
 * <p>Wildcard matching is supported via {@link #isCompatible(MediaType)}: {@code *}{@code /*}
 * matches any media type, and {@code application/*} matches any application subtype.
 */
public final class MediaType {

    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;
    private final double qualityFactor;

    // --- Constructor ---

    /**
     * Creates a {@code MediaType} with explicit type, subtype, parameters, and quality factor.
     *
     * @param type          the primary type (e.g. {@code "application"}), stored lowercase
     * @param subtype       the subtype (e.g. {@code "json"}), stored lowercase
     * @param parameters    the media type parameters excluding {@code q}, may be empty
     * @param qualityFactor the quality factor in range [0.0, 1.0]; defaults to 1.0
     */
    public MediaType(String type, String subtype, Map<String, String> parameters, double qualityFactor) {
        this.type = type.toLowerCase();
        this.subtype = subtype.toLowerCase();
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        this.qualityFactor = qualityFactor;
    }

    // --- Factory methods ---

    /**
     * Parses a raw media type string into a {@code MediaType} instance.
     *
     * <p>The raw string must contain at least one {@code /} separator between type and subtype.
     * Parameters are parsed from {@code key=value} tokens separated by semicolons. Whitespace
     * around semicolons and equals signs is trimmed. The special {@code q} parameter is
     * extracted as the quality factor and excluded from the parameter map.
     *
     * <p>Returns {@code null} if the input is {@code null}, blank, or does not contain a
     * {@code /} separator in the type/subtype portion.
     *
     * @param raw the raw media type string to parse, e.g. {@code "application/json;q=0.8"}
     * @return the parsed {@code MediaType}, or {@code null} if the input is invalid
     */
    public static MediaType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(";");
        String typeSubtype = parts[0].trim();
        int slashIndex = typeSubtype.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String type = typeSubtype.substring(0, slashIndex).trim();
        String subtype = typeSubtype.substring(slashIndex + 1).trim();
        if (type.isEmpty() || subtype.isEmpty()) {
            return null;
        }

        Map<String, String> parameters = new LinkedHashMap<>();
        double qualityFactor = 1.0;
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i].trim();
            if (param.isEmpty()) {
                continue;
            }
            int eqIndex = param.indexOf('=');
            if (eqIndex < 0) {
                continue;
            }
            String key = param.substring(0, eqIndex).trim();
            String value = param.substring(eqIndex + 1).trim();
            if ("q".equalsIgnoreCase(key)) {
                try {
                    double q = Double.parseDouble(value);
                    // Clamp to [0.0, 1.0] per RFC 9110 §12.4.2
                    qualityFactor = Math.max(0.0, Math.min(1.0, q));
                } catch (NumberFormatException ignored) {
                    // retain default 1.0
                }
            } else {
                parameters.put(key.toLowerCase(), value);
            }
        }
        return new MediaType(type, subtype, parameters, qualityFactor);
    }

    /**
     * Alias for {@link #parse(String)}. Parses a raw media type string into a
     * {@code MediaType} instance.
     *
     * @param raw the raw media type string to parse
     * @return the parsed {@code MediaType}, or {@code null} if the input is invalid
     */
    public static MediaType valueOf(String raw) {
        return parse(raw);
    }

    // --- Accessors ---

    /**
     * Returns the primary type in lowercase (e.g. {@code "application"}).
     *
     * @return the primary type
     */
    public String type() {
        return type;
    }

    /**
     * Returns the subtype in lowercase (e.g. {@code "json"}).
     *
     * @return the subtype
     */
    public String subtype() {
        return subtype;
    }

    /**
     * Returns an unmodifiable map of media type parameters, excluding the {@code q}
     * quality parameter. Keys are lowercase.
     *
     * @return the parameter map, never {@code null}
     */
    public Map<String, String> parameters() {
        return parameters;
    }

    /**
     * Returns the quality factor for this media type, in the range [0.0, 1.0].
     * Defaults to {@code 1.0} when no {@code q} parameter is present.
     *
     * @return the quality factor
     */
    public double qualityFactor() {
        return qualityFactor;
    }

    // --- Wildcard checks ---

    /**
     * Returns {@code true} if the primary type is a wildcard ({@code *}).
     *
     * @return {@code true} for {@code *}{@code /*} style media types
     */
    public boolean isWildcardType() {
        return "*".equals(type);
    }

    /**
     * Returns {@code true} if the subtype is a wildcard ({@code *}).
     *
     * @return {@code true} for {@code type/*} or {@code *}{@code /*} style media types
     */
    public boolean isWildcardSubtype() {
        return "*".equals(subtype);
    }

    // --- Compatibility ---

    /**
     * Returns {@code true} if this media type is compatible with {@code other} using
     * wildcard-aware matching.
     *
     * <p>Compatibility rules (evaluated in order):
     * <ol>
     *   <li>Either side has type {@code *} — always compatible</li>
     *   <li>Types differ — not compatible</li>
     *   <li>Either side has subtype {@code *} — compatible (types already matched)</li>
     *   <li>Subtypes match — compatible (parameters are ignored in this check)</li>
     * </ol>
     *
     * <p>Comparison is case-insensitive for both type and subtype.
     *
     * @param other the media type to compare against; must not be {@code null}
     * @return {@code true} if the two media types are compatible
     */
    public boolean isCompatible(MediaType other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this.isWildcardType() || other.isWildcardType()) {
            return true;
        }
        if (!this.type.equalsIgnoreCase(other.type)) {
            return false;
        }
        if (this.isWildcardSubtype() || other.isWildcardSubtype()) {
            return true;
        }
        return this.subtype.equalsIgnoreCase(other.subtype);
    }

    // --- Specificity ---

    /**
     * Returns a specificity score used for preference ordering during content negotiation.
     *
     * <ul>
     *   <li>0 — {@code *}{@code /*} (least specific)</li>
     *   <li>1 — {@code type/*}</li>
     *   <li>2 — {@code type/subtype} with no parameters</li>
     *   <li>3 — {@code type/subtype} with at least one parameter (most specific)</li>
     * </ul>
     *
     * @return the specificity score in the range [0, 3]
     */
    public int specificity() {
        if (isWildcardType()) {
            return 0;
        }
        if (isWildcardSubtype()) {
            return 1;
        }
        return parameters.isEmpty() ? 2 : 3;
    }

    // --- String representation ---

    /**
     * Returns the {@code type/subtype} string without any parameters, in lowercase.
     *
     * @return the base media type string, e.g. {@code "application/json"}
     */
    public String withoutParameters() {
        return type + "/" + subtype;
    }

    // --- Object contract ---

    /**
     * Two {@code MediaType} instances are equal when their type, subtype, and parameters
     * are equal. The quality factor is intentionally excluded from equality.
     *
     * @param obj the object to compare to
     * @return {@code true} if the objects represent the same media type
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MediaType other)) {
            return false;
        }
        return type.equals(other.type) && subtype.equals(other.subtype) && parameters.equals(other.parameters);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(type, subtype, parameters);
    }

    /**
     * Returns a string representation including type, subtype, parameters, and quality factor.
     * The format follows standard media type notation, e.g.
     * {@code "application/json;charset=utf-8;q=0.8"}.
     *
     * @return the string representation of this media type
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type).append('/').append(subtype);
        parameters.forEach((k, v) -> sb.append(';').append(k).append('=').append(v));
        if (qualityFactor < 1.0) {
            sb.append(";q=").append(qualityFactor);
        }
        return sb.toString();
    }
}
