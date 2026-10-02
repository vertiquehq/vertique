// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.RestConfigurationException;
import java.util.List;

/**
 * Refuses the constructs a published schema may not hold, before any schema is relocated.
 *
 * <p>A document embeds captured schemas as components and rewrites only fragment-only references,
 * so a captured schema fails publication when, at a {@linkplain SchemaPositions schema position},
 * it holds:
 *
 * <ul>
 *   <li>{@code $id} anywhere, the root included, {@code $anchor}, {@code $dynamicAnchor}, or {@code
 *       $dynamicRef}, or {@code $defs} below the root;
 *   <li>a {@code $ref} that is not fragment-only (anything not starting with {@code #}), or whose
 *       fragment does not resolve to a schema inside the captured schema: an anchor-name fragment, a
 *       pointer to a missing member, and a pointer to a non-schema such as the {@code $defs} object
 *       itself all fail.
 * </ul>
 *
 * <p>The schema is walked twice: identifiers, anchors, and nested definitions are looked for first
 * over the whole schema, then references, so a reference to a refused anchor is reported as the
 * anchor. The failure names the application's mount, the operation, the input, and the pointer of
 * the offending keyword member; it never echoes a value, a reference, or schema text.
 */
final class SchemaRefusals {

    /** The closing clause of every refusal. */
    private static final String RULE = "; a published schema may hold only fragment-only references that resolve"
            + " inside it, and no '$id', '$anchor', '$dynamicAnchor', '$dynamicRef', or '$defs' below its root";

    private SchemaRefusals() {}

    /**
     * Fails when a captured schema holds a refused construct. The schema is only read.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param input the input the schema was captured for
     * @param schema the schema, as a document tree
     * @throws RestConfigurationException naming the first refused construct, identifiers and anchors
     *     before references
     */
    static void check(String subject, SchemaPublicationSubject input, JsonNode schema) {
        SchemaPositions.walk(schema, (owner, keyword, value, pointer, atRoot) -> {
            String reason =
                    switch (keyword) {
                        case "$id" -> "declares '$id'";
                        case "$anchor" -> "declares '$anchor'";
                        case "$dynamicAnchor" -> "declares '$dynamicAnchor'";
                        case "$dynamicRef" -> "uses '$dynamicRef'";
                        case "$defs" -> atRoot ? null : "declares '$defs' below the root";
                        default -> null;
                    };
            if (reason != null) {
                throw refusal(subject, input, reason, pointer);
            }
        });
        SchemaPositions.walk(schema, (owner, keyword, value, pointer, atRoot) -> {
            if (!"$ref".equals(keyword)) {
                return;
            }
            if (!value.isTextual() || !value.textValue().startsWith("#")) {
                throw refusal(subject, input, "has a '$ref' that is not fragment-only", pointer);
            }
            List<String> tokens = SchemaFragments.pointerTokens(value.textValue());
            if (tokens == null || !SchemaPositions.resolvesToSchema(schema, tokens)) {
                throw refusal(
                        subject, input, "has a '$ref' whose fragment does not resolve inside the schema", pointer);
            }
        });
    }

    private static RestConfigurationException refusal(
            String subject, SchemaPublicationSubject input, String reason, String pointer) {
        return new RestConfigurationException(
                subject + ": " + input.refusalPhrase() + " " + reason + " at '" + pointer + "'" + RULE);
    }
}
