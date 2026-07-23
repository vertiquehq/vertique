// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.validation;

import java.util.Set;

/**
 * Shared keyword-classification helpers used by request-validation strategies that process
 * vertx-json-schema {@code Basic}-format output units.
 *
 * <p>In Basic output format, the schema validator emits <em>intermediate structural errors</em> for
 * traversal keywords such as {@code properties}, {@code allOf}, and {@code $ref}. These wrappers
 * contain no concrete violated constraint and should be skipped; only the leaf errors that name a
 * concrete keyword (e.g. {@code minLength}, {@code required}) carry actionable information.
 *
 * <p>Both the {@code web-validation} strategy ({@code vertique-rest-validation}) and the
 * {@code openapi-contract} strategy ({@code vertique-rest-openapi-validation}) apply this
 * classification. This helper centralises the shared logic — the keyword set and the
 * {@code String}-based last-segment extraction — so neither module maintains its own copy.
 *
 * <p><strong>The {@code additionalProperties} keyword is included in {@link #STRUCTURAL_KEYWORDS}
 * but is excluded from {@link #isStructural}.</strong> In the {@code openapi-contract} strategy,
 * an {@code additionalProperties} violation is a concrete client error (an undeclared property was
 * sent) and must be treated as a real keyword. The {@code web-validation} strategy uses
 * {@link #STRUCTURAL_KEYWORDS} directly (via a {@code contains} check) to include
 * {@code additionalProperties} among the structural wrappers. Each strategy therefore controls
 * whether to treat {@code additionalProperties} as structural or concrete based on its own logic
 * and may use either {@link #isStructural} or {@link #STRUCTURAL_KEYWORDS} as appropriate.
 *
 * <p>This class is a utility class with no state; all members are {@code static}.
 */
public final class SchemaErrorKeywords {

    private SchemaErrorKeywords() {}

    // --- Keyword set ---

    /**
     * JSON Schema traversal keywords that appear as intermediate wrapper errors in vertx-json-schema
     * Basic output when a nested sub-schema fails. These wrappers carry no concrete violated
     * constraint. Includes {@code additionalProperties}: the {@code web-validation} strategy treats
     * it as a structural wrapper; the {@code openapi-contract} strategy treats it as a concrete
     * constraint and must exclude it from the structural check explicitly (see {@link #isStructural}).
     */
    public static final Set<String> STRUCTURAL_KEYWORDS = Set.of(
            "properties",
            "additionalProperties",
            "items",
            "prefixItems",
            "allOf",
            "anyOf",
            "oneOf",
            "if",
            "then",
            "else",
            "not",
            "$ref",
            "unevaluatedProperties",
            "unevaluatedItems");

    // --- Extraction and classification ---

    /**
     * Returns the last {@code /}-delimited segment of a keyword location, or {@code null} when
     * the location is absent, empty, or its last segment is empty.
     *
     * <p>This is the raw segment extraction: no structural filtering is applied. Callers use the
     * returned segment together with {@link #STRUCTURAL_KEYWORDS} or {@link #isStructural} to
     * determine whether the error represents a structural wrapper or a concrete violated constraint.
     *
     * @param keywordLocation the keyword location from a vertx-json-schema output unit (e.g.
     *                        {@code #/properties/name/minLength}); may be {@code null}
     * @return the last segment (e.g. {@code "minLength"}), or {@code null} when the location is
     *     absent or yields an empty last segment
     */
    public static String extractKeyword(String keywordLocation) {
        if (keywordLocation == null || keywordLocation.isEmpty()) {
            return null;
        }
        int lastSlash = keywordLocation.lastIndexOf('/');
        String segment = lastSlash < 0 ? keywordLocation : keywordLocation.substring(lastSlash + 1);
        return segment.isEmpty() ? null : segment;
    }

    /**
     * Returns {@code true} when the keyword location's last segment is a structural traversal
     * keyword that carries no concrete violated constraint — specifically, a member of
     * {@link #STRUCTURAL_KEYWORDS} other than {@code additionalProperties}.
     *
     * <p>The {@code additionalProperties} keyword is excluded from this check because in the
     * {@code openapi-contract} strategy it represents a concrete client error (an undeclared
     * property was sent under {@code additionalProperties: false}), not a structural wrapper.
     * Strategies that must treat {@code additionalProperties} as structural (i.e.
     * {@code web-validation}) use {@link #STRUCTURAL_KEYWORDS} directly.
     *
     * @param keywordLocation the keyword location to classify; may be {@code null}
     * @return {@code true} when the last segment is a non-{@code additionalProperties} member of
     *     {@link #STRUCTURAL_KEYWORDS}; {@code false} otherwise (including when the location is
     *     absent or yields an empty segment)
     */
    public static boolean isStructural(String keywordLocation) {
        String segment = extractKeyword(keywordLocation);
        return segment != null && STRUCTURAL_KEYWORDS.contains(segment) && !"additionalProperties".equals(segment);
    }
}
