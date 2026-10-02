// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relocates a captured schema, already free of refused constructs, into document components.
 *
 * <p>The schema becomes component {@code <component>} without its root {@code $defs}; each entry
 * {@code <def>} of that {@code $defs} becomes component {@code <component>.<def>}, the key through the
 * component-key replacement. In the component and in every relocated definition, each fragment-only
 * {@code $ref} at a schema position is rewritten to the relocated target:
 *
 * <ul>
 *   <li>{@code #} to {@code #/components/schemas/<component>};
 *   <li>{@code #/$defs/<def>} and {@code #/$defs/<def>/<rest>} to {@code
 *       #/components/schemas/<component>.<def>} and that followed by {@code /<rest>};
 *   <li>any other {@code #/<pointer>} to {@code #/components/schemas/<component>/<pointer>}.
 * </ul>
 *
 * <p>Rewritten fragments are written per {@link SchemaFragments#reference}. Nothing else changes: no
 * {@code $id} is added, and a root {@code $schema}, boolean subschemas, pattern text, and member order
 * are kept. Relocation works on a schema the document owns and changes it in place.
 */
final class SchemaRelocation {

    /** The first reference tokens of every rewritten fragment. */
    private static final List<String> COMPONENT_SCHEMAS = List.of("components", "schemas");

    private SchemaRelocation() {}

    /**
     * Tells whether a schema holds a {@code $ref} or a {@code $defs} at a schema position, so that it
     * is published as a component rather than inline.
     *
     * @param schema the schema
     * @return whether it holds a reference or definitions
     */
    static boolean holdsReferences(JsonNode schema) {
        boolean[] found = {false};
        SchemaPositions.walk(schema, (owner, keyword, value, pointer, atRoot) -> {
            if ("$ref".equals(keyword) || "$defs".equals(keyword)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    /**
     * Relocates a schema: removes its root {@code $defs} and rewrites every fragment-only reference in
     * it and in each removed definition.
     *
     * @param componentKey the key of the schema's own component
     * @param schema the schema, owned by the document and changed in place
     * @return the relocated definitions, each with its component key, in the order the schema
     *     declared them
     */
    static List<Definition> relocate(String componentKey, ObjectNode schema) {
        JsonNode removed = schema.remove("$defs");
        Map<String, String> definitionKeys = new LinkedHashMap<>();
        List<Definition> definitions = new ArrayList<>();
        if (removed instanceof ObjectNode defs) {
            for (Map.Entry<String, JsonNode> entry : defs.properties()) {
                String key = SchemaPublicationSubject.componentKey(componentKey + "." + entry.getKey());
                definitionKeys.put(entry.getKey(), key);
                definitions.add(new Definition(key, entry.getValue()));
            }
        }
        rewrite(schema, componentKey, definitionKeys);
        for (Definition definition : definitions) {
            rewrite(definition.schema(), componentKey, definitionKeys);
        }
        return definitions;
    }

    /** Rewrites every fragment-only reference at a schema position of one relocated schema. */
    private static void rewrite(JsonNode schema, String componentKey, Map<String, String> definitionKeys) {
        List<ObjectNode> owners = new ArrayList<>();
        SchemaPositions.walk(schema, (owner, keyword, value, pointer, atRoot) -> {
            if ("$ref".equals(keyword) && value.isTextual() && value.textValue().startsWith("#")) {
                owners.add(owner);
            }
        });
        for (ObjectNode owner : owners) {
            owner.put("$ref", rewritten(owner.get("$ref").textValue(), componentKey, definitionKeys));
        }
    }

    /** Maps one fragment-only reference of the captured schema to its relocated target. */
    private static String rewritten(String reference, String componentKey, Map<String, String> definitionKeys) {
        List<String> tokens = SchemaFragments.pointerTokens(reference);
        if (tokens == null) {
            throw new IllegalStateException("A reference that passed the refusal walk cannot be read");
        }
        List<String> target = new ArrayList<>(COMPONENT_SCHEMAS);
        if (tokens.size() >= 2 && "$defs".equals(tokens.get(0)) && definitionKeys.containsKey(tokens.get(1))) {
            target.add(definitionKeys.get(tokens.get(1)));
            target.addAll(tokens.subList(2, tokens.size()));
        } else {
            target.add(componentKey);
            target.addAll(tokens);
        }
        return SchemaFragments.reference(target);
    }

    /**
     * One definition relocated out of a schema.
     *
     * @param key the definition's component key
     * @param schema the definition, its references rewritten
     */
    record Definition(String key, JsonNode schema) {}
}
