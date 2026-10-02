// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.rest.core.RestConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.SortedMap;

/**
 * Embeds the captured input schemas of one document, in two phases.
 *
 * <p>First every captured schema of the document is {@linkplain #check checked}: it is copied into a
 * tree the document owns and walked for {@linkplain SchemaRefusals refused constructs}. Only then is
 * each checked schema {@linkplain #publish published}: a request body or response schema always
 * becomes a component (see {@link SchemaPublicationSubject#alwaysComponent()}), and a parameter or form field becomes one only when it holds a {@code $ref} or a {@code $defs} at a
 * schema position; any other schema is published inline, unchanged. A component is {@linkplain
 * SchemaRelocation relocated} and registered with its relocated definitions in the document's
 * {@link ComponentRegistry}, so key collisions are found as components are published.
 *
 * <p>The captured objects are only read; reserved-name redaction (see {@link ReservedNameRedaction}),
 * relocation, and reference rewriting change the owned copy.
 */
final class SchemaEmbedder {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String subject;

    private final ComponentRegistry registry;

    /**
     * Creates the embedder of one document.
     *
     * @param subject the failure-message subject naming the application and its mount
     */
    SchemaEmbedder(String subject) {
        this.subject = subject;
        this.registry = new ComponentRegistry(subject);
    }

    /**
     * Copies a captured schema into a tree the document owns and refuses the constructs a published
     * schema may not hold.
     *
     * @param input the input the schema was captured for
     * @param captured the captured schema, only read
     * @return the checked copy, to publish once every input of the document is checked
     * @throws RestConfigurationException when the schema holds a refused construct
     */
    CheckedSchema check(SchemaPublicationSubject input, JsonObject captured) {
        return check(input, SchemaTrees.tree(captured));
    }

    /**
     * Refuses the constructs a published schema may not hold in a tree the document already owns.
     *
     * @param input the input the schema was captured for
     * @param tree the document's own copy of the captured schema, made by {@link SchemaTrees#tree}
     * @return the checked copy, to publish once every input of the document is checked
     * @throws RestConfigurationException when the schema holds a refused construct
     */
    CheckedSchema check(SchemaPublicationSubject input, ObjectNode tree) {
        SchemaRefusals.check(subject, input, tree);
        return new CheckedSchema(input, tree);
    }

    /**
     * Publishes a checked schema, as a component or inline.
     *
     * @param checked a schema returned by {@link #check}, published at most once
     * @return a new reference object {@code {"$ref": "#/components/schemas/<key>"}} when the schema
     *     became a component, else the checked schema itself; a caller placing the result more than
     *     once places a {@linkplain JsonNode#deepCopy() copy} at each further place
     * @throws RestConfigurationException when a component key is already taken in the document
     */
    JsonNode publish(CheckedSchema checked) {
        SchemaPublicationSubject input = checked.input();
        ObjectNode schema = checked.tree();
        if (!input.alwaysComponent() && !SchemaRelocation.holdsReferences(schema)) {
            return schema;
        }
        String key = input.componentKey();
        List<SchemaRelocation.Definition> definitions = SchemaRelocation.relocate(key, schema);
        registry.register(key, schema, input, false);
        for (SchemaRelocation.Definition definition : definitions) {
            registry.register(definition.key(), definition.schema(), input, true);
        }
        return reference(key);
    }

    /**
     * Returns the component schemas published so far.
     *
     * @return the component schemas, keys in natural order
     */
    SortedMap<String, JsonNode> components() {
        return registry.schemas();
    }

    /**
     * Builds a reference object to a component.
     *
     * @param key the component key
     * @return a new {@code {"$ref": "#/components/schemas/<key>"}} object
     */
    static ObjectNode reference(String key) {
        return NODES.objectNode().put("$ref", SchemaFragments.reference(List.of("components", "schemas", key)));
    }

    /**
     * A captured schema copied into a tree the document owns and checked for refused constructs.
     *
     * @param input the input the schema was captured for
     * @param tree the owned copy
     */
    record CheckedSchema(SchemaPublicationSubject input, ObjectNode tree) {}
}
