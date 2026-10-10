// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object representing an HTTP media type as defined by RFC 9110.
 *
 * <p>A media type consists of a {@code type}, a {@code subtype}, optional parameters
 * (excluding the {@code q} quality parameter), and a quality factor. Both type and
 * subtype are stored and compared in lowercase, using {@link Locale#ROOT} so the result does not
 * depend on the JVM's default locale.
 *
 * <p>Use {@link #parse(String)} or {@link #valueOf(String)} to create instances from
 * raw header strings such as {@code "application/json;charset=utf-8;q=0.8"}. Parsing is
 * quote-aware (see {@link HeaderElement}), and quoted-string parameter values are exposed unquoted.
 *
 * <p>Wildcard matching is supported via {@link #isCompatible(MediaType)}: {@code *}{@code /*}
 * matches any media type, and {@code application/*} matches any application subtype. RFC 9110
 * defines no range with a wildcard type and a concrete subtype, so such a value (for example
 * {@code *}{@code /xml}) is malformed: {@link #parse(String)} returns {@code null} for it and it never
 * matches anything but a full wildcard.
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
     * @throws IllegalArgumentException if the type, the subtype or a parameter name contains a
     *                                  control character (below {@code U+0020}, or {@code U+007F}),
     *                                  which could otherwise split a header line when written
     */
    public MediaType(String type, String subtype, Map<String, String> parameters, double qualityFactor) {
        requireNoControlCharacters("type", type);
        requireNoControlCharacters("subtype", subtype);
        parameters.keySet().forEach(name -> requireNoControlCharacters("parameter name", name));
        this.type = type.toLowerCase(Locale.ROOT);
        this.subtype = subtype.toLowerCase(Locale.ROOT);
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        this.qualityFactor = qualityFactor;
    }

    // --- Factory methods ---

    /**
     * Parses a raw media type string into a {@code MediaType} instance.
     *
     * <p>The raw string must contain at least one {@code /} separator between type and subtype.
     * It is read as a single {@link HeaderElement}: parameters are {@code key=value} tokens
     * separated by semicolons, and semicolons and commas inside a double-quoted parameter value do
     * not split. Whitespace around semicolons and equals signs is trimmed. Quoted-string
     * parameter values are returned unquoted and unescaped. The special {@code q} parameter is
     * extracted as the quality factor and excluded from the parameter map.
     *
     * <p>This reads a media type the way a {@code Content-Type} is read, not an {@code Accept}
     * entry: {@code q} is an ordinary parameter with a lenient value. A {@code q} that parses as a
     * number is clamped to [0, 1]; one that does not (including a quoted value) is ignored and
     * leaves the quality factor at {@code 1.0}. An unusable {@code q} never makes the media type
     * invalid. Use {@link AcceptNegotiator#parseAcceptHeader(String)} for {@code Accept} entries,
     * where an invalid {@code q} drops the entry.
     *
     * <p>Returns {@code null} if the input is {@code null}, blank, does not contain a
     * {@code /} separator in the type/subtype portion, or is malformed as described by
     * {@link HeaderElement}: an unterminated quoted string, characters after a closing quote, an
     * empty value, or more than {@value HeaderElement#MAX_PARAMETERS} parameters. A wildcard type
     * with a concrete subtype, such as {@code *}{@code /xml}, is not a media range and yields
     * {@code null} as well. A caller that guards a trust boundary with the result must treat
     * {@code null} for a non-blank input as a rejection, not as "nothing declared".
     *
     * @param raw the raw media type string to parse, e.g. {@code "application/json;q=0.8"}
     * @return the parsed {@code MediaType}, or {@code null} if the input is invalid
     */
    public static MediaType parse(String raw) {
        return fromElement(HeaderElement.parseLenient(raw));
    }

    /**
     * Builds a media type from a parsed header element, or returns {@code null} when the
     * element value has no {@code /} separator, a blank type or subtype, a wildcard type with a
     * concrete subtype, or a control character in the type, the subtype or a parameter name.
     */
    static MediaType fromElement(HeaderElement element) {
        if (element == null) {
            return null;
        }
        String typeSubtype = element.value();
        int slashIndex = typeSubtype.indexOf('/');
        if (slashIndex < 0) {
            return null;
        }
        String type = typeSubtype.substring(0, slashIndex).trim();
        String subtype = typeSubtype.substring(slashIndex + 1).trim();
        if (type.isEmpty() || subtype.isEmpty()) {
            return null;
        }
        if ("*".equals(type) && !"*".equals(subtype)) {
            return null;
        }
        if (hasControlCharacter(type)
                || hasControlCharacter(subtype)
                || element.parameters().keySet().stream().anyMatch(MediaType::hasControlCharacter)) {
            return null;
        }
        return new MediaType(type, subtype, element.parameters(), element.quality());
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
     * Returns {@code true} if the primary type is a wildcard ({@code *}). A parsed media type with a
     * wildcard type is always {@code *}{@code /*}; only a directly constructed instance can pair a
     * wildcard type with a concrete subtype, and that never matches anything but a full wildcard.
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
     *   <li>Either side is {@code *}{@code /*} (type and subtype both {@code *}) — always
     *       compatible</li>
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
        if (this.isFullWildcard() || other.isFullWildcard()) {
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

    private boolean isFullWildcard() {
        return isWildcardType() && isWildcardSubtype();
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
     * {@code "application/json;charset=utf-8;q=0.8"}. A parameter value that is empty or contains
     * anything other than token characters is written as a quoted string, so the output parses
     * back to the same parameters. Each control character other than a horizontal tab is written
     * as {@code _}, so a value can never carry a line break into a header and two values that
     * differ by a control character stay different; such a value parses back with {@code _} in
     * place of the control character.
     *
     * @return the string representation of this media type
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(type).append('/').append(subtype);
        parameters.forEach((k, v) -> sb.append(';').append(k).append('=').append(isToken(v) ? v : quoted(v)));
        if (qualityFactor < 1.0) {
            sb.append(";q=").append(qualityFactor);
        }
        return sb.toString();
    }

    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean tokenChar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tokenChar) {
                return false;
            }
        }
        return true;
    }

    private static String quoted(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isControl(c) && c != '\t') {
                // CR, LF and the other control characters cannot appear in a header value; a
                // placeholder keeps lengths and positions, so no two values collapse into one
                sb.append('_');
                continue;
            }
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static boolean isControl(char c) {
        return c < 0x20 || c == 0x7F;
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (isControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static void requireNoControlCharacters(String what, String value) {
        if (hasControlCharacter(value)) {
            throw new IllegalArgumentException(what + " must not contain control characters");
        }
    }
}
