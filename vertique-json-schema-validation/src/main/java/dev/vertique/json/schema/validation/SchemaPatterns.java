// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static dev.vertique.json.schema.validation.SchemaKeywords.LITERAL_KEYWORDS;
import static dev.vertique.json.schema.validation.SchemaKeywords.NAMED_MEMBER_KEYWORDS;
import static dev.vertique.json.schema.validation.SchemaKeywords.PATTERN;
import static dev.vertique.json.schema.validation.SchemaKeywords.PATTERN_PROPERTIES;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Registration-time check that every regular expression a schema declares compiles, so a defective pattern is
 * reported when the schema is registered rather than on the first document validated against it.
 *
 * <p>INTERNAL framework seam: this type is shared by framework modules and is not an application contract. It is
 * a stateless utility; every member is {@code static}.
 */
public final class SchemaPatterns {

    /** The start of the reference token that stands in for a withheld {@code patternProperties} key. */
    private static final String REDACTED_KEY_PREFIX = "[key-";

    /** The end of the reference token that stands in for a withheld {@code patternProperties} key. */
    private static final String REDACTED_KEY_SUFFIX = "]";

    private SchemaPatterns() {}

    /**
     * Compiles every regular expression the schema declares and reports each one the regex engine rejects.
     *
     * <p>The walk has no position allowlist: a {@code pattern} member is a regular expression wherever it
     * carries a string, and the keys of a {@code patternProperties} object are regular expressions wherever that
     * object appears. A <em>property</em> named {@code pattern} holds a subschema rather than a string, so it is
     * descended into rather than compiled. The value of a {@link SchemaKeywords#LITERAL_KEYWORDS} member is JSON
     * data and is never entered, unless the member is a property name inside a
     * {@link SchemaKeywords#NAMED_MEMBER_KEYWORDS} object, whose members are schemas whatever they are called.
     * The keys of a {@code patternProperties} object are compiled before the walk descends into their
     * subschemas.
     *
     * <p>Every failure is collected, in document order. The pattern text, a {@code patternProperties} key and the
     * engine's exception never appear in the result; see {@link PatternCompileFailure} for how a failure is
     * located without naming a key. The schema is not modified.
     *
     * @param schema the schema to walk
     * @return the failures in document order; unmodifiable and empty when every declared expression compiles
     */
    public static List<PatternCompileFailure> precompile(JsonObject schema) {
        List<PatternCompileFailure> failures = new ArrayList<>();
        walk(schema, "", false, false, failures);
        return Collections.unmodifiableList(failures);
    }

    /**
     * Walks one node, compiling the expressions it declares and descending into every member except literal
     * data.
     *
     * <p>Every member name this object contributes to the pointer is a schema keyword or a property name,
     * except inside a {@code patternProperties} object, whose member names are themselves regular expressions.
     * {@code membersArePatterns} marks that case, and the member name is then replaced by its bracketed ordinal:
     * a failure beneath a key that happens to compile discloses the key no more than a failure in one that does
     * not.
     *
     * @param value              the current node: an object, an array, or a scalar
     * @param pointer            the JSON pointer of {@code value} within the schema
     * @param membersArePatterns whether the member names of {@code value} are regular expressions, so that
     *     naming one in a pointer would disclose pattern text
     * @param membersAreNames    whether the member names of {@code value} are names rather than keywords, so that
     *     a member named like a literal keyword is still a schema
     * @param failures           the collector the failures are appended to
     */
    private static void walk(
            Object value,
            String pointer,
            boolean membersArePatterns,
            boolean membersAreNames,
            List<PatternCompileFailure> failures) {
        if (value instanceof JsonObject object) {
            int ordinal = 0;
            for (String field : object.fieldNames()) {
                Object member = object.getValue(field);
                String memberPointer = membersArePatterns
                        ? pointer + "/" + redactedKeySegment(ordinal)
                        : pointer + "/" + escapePointerSegment(field);
                ordinal++;
                if (!membersAreNames && LITERAL_KEYWORDS.contains(field)) {
                    continue;
                }
                boolean patternProperties = PATTERN_PROPERTIES.equals(field) && member instanceof JsonObject;
                if (PATTERN.equals(field) && member instanceof String regex) {
                    compile(memberPointer, regex, failures);
                } else if (patternProperties && member instanceof JsonObject byPattern) {
                    // The pointer deliberately stops at this object: a key is a pattern, so naming it would
                    // disclose the text the failure must withhold.
                    for (String key : byPattern.fieldNames()) {
                        compile(memberPointer, key, failures);
                    }
                }
                boolean namedMembers =
                        !membersAreNames && NAMED_MEMBER_KEYWORDS.contains(field) && member instanceof JsonObject;
                walk(member, memberPointer, patternProperties, namedMembers, failures);
            }
        } else if (value instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                walk(array.getValue(index), pointer + "/" + index, false, false, failures);
            }
        }
    }

    /**
     * Builds the pointer segment that stands in for one withheld {@code patternProperties} key: the key's
     * zero-based ordinal in document order, bracketed. The brackets keep it distinguishable from a member name
     * and from an array index.
     *
     * @param ordinal the key's zero-based position among the object's members
     * @return the redacted reference token
     */
    private static String redactedKeySegment(int ordinal) {
        return REDACTED_KEY_PREFIX + ordinal + REDACTED_KEY_SUFFIX;
    }

    /**
     * Escapes one JSON pointer reference token: {@code ~} becomes {@code ~0} and {@code /} becomes {@code ~1}, in
     * that order.
     *
     * @param segment the member name to escape
     * @return the escaped reference token
     */
    private static String escapePointerSegment(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    /**
     * Compiles one declared regular expression, discarding the compiled pattern, and records a failure when the
     * engine rejects it.
     *
     * @param pointer  the JSON pointer of the position that declares the expression
     * @param regex    the declared regular expression
     * @param failures the collector a failure is appended to
     */
    private static void compile(String pointer, String regex, List<PatternCompileFailure> failures) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException rejected) {
            // The exception's own message quotes the complete pattern, so only its description and index are
            // read out of it; it is not kept as a cause or a suppressed exception.
            failures.add(new PatternCompileFailure(pointer, rejected.getDescription(), rejected.getIndex()));
        }
    }
}
