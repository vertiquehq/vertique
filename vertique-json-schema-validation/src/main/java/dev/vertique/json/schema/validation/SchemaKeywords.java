// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import io.vertx.json.schema.OutputUnit;
import java.util.Set;

/**
 * The JSON Schema keyword vocabulary that schema walks and validation-error classification share: which
 * keywords carry data rather than schema, which carry schemas under arbitrary member names, and which are
 * structural wrappers in {@code Basic} output.
 *
 * <p>In {@code Basic} output the engine emits intermediate structural errors for traversal keywords such as
 * {@code properties}, {@code allOf} and {@code $ref}. They name no concrete violated constraint; only the leaf
 * errors that name a concrete keyword, such as {@code minLength} or {@code required}, carry actionable
 * information.
 *
 * <p>INTERNAL framework seam: this type is shared by framework modules and is not an application contract.
 * It is a stateless utility; every member is {@code static}.
 */
public final class SchemaKeywords {

    /** The JSON Schema keyword whose string value is a regular expression. */
    public static final String PATTERN = "pattern";

    /** The JSON Schema keyword whose object keys are regular expressions. */
    public static final String PATTERN_PROPERTIES = "patternProperties";

    /**
     * The keywords whose value is JSON data rather than schema. A schema walk never descends into one, so a
     * {@code pattern} member inside a {@code const} or {@code enum} value is not treated as a pattern. An
     * exclusion rather than an allowlist of subschema keywords, so a walk still reaches every schema position,
     * including an annotation keyword a profile fragment carries.
     */
    public static final Set<String> LITERAL_KEYWORDS = Set.of("const", "enum", "default", "examples", "example");

    /**
     * The keywords whose value is an object keyed by names rather than by keywords: each member is a schema
     * whatever it is called, so a property named {@code const} is still walked.
     */
    public static final Set<String> NAMED_MEMBER_KEYWORDS =
            Set.of("properties", PATTERN_PROPERTIES, "$defs", "dependentSchemas");

    /**
     * The traversal keywords that appear as intermediate wrapper errors in {@code Basic} output when a nested
     * subschema fails. They carry no concrete violated constraint. Includes {@code additionalProperties}.
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

    private SchemaKeywords() {}

    /**
     * Returns the last {@code /}-delimited segment of a keyword location, or {@code null} when the location is
     * absent, empty, or its last segment is empty. No structural filtering is applied.
     *
     * @param keywordLocation the keyword location of an output unit, for example
     *     {@code #/properties/name/minLength}; may be {@code null}
     * @return the last segment, for example {@code "minLength"}, or {@code null} when the location is absent or
     *     yields an empty last segment
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
     * Returns whether the keyword location's last segment is a member of {@link #STRUCTURAL_KEYWORDS}, that is
     * a structural wrapper that names no concrete constraint. {@code additionalProperties} is treated as
     * structural.
     *
     * @param keywordLocation the keyword location to classify; may be {@code null}
     * @return {@code true} when the last segment is a structural keyword; {@code false} otherwise, including
     *     when the location is absent or yields an empty segment
     */
    public static boolean isStructuralLocation(String keywordLocation) {
        String segment = extractKeyword(keywordLocation);
        return segment != null && STRUCTURAL_KEYWORDS.contains(segment);
    }

    /**
     * Returns the concrete constraint keyword a validation error names: the last segment of its keyword
     * location, or {@code null} when the location is absent or the segment is a structural wrapper.
     *
     * @param error the output unit of the error
     * @return the violated keyword, for example {@code "minLength"}, or {@code null} when the keyword location
     *     is absent or names a structural traversal keyword
     */
    public static String constraintKeyword(OutputUnit error) {
        String segment = extractKeyword(error.getKeywordLocation());
        if (segment == null) {
            return null;
        }
        return STRUCTURAL_KEYWORDS.contains(segment) ? null : segment;
    }
}
