// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.RestConfigurationException;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Collects the component schemas of one document, keyed in natural order, and refuses two distinct
 * components with one key.
 *
 * <p>Every registration is a distinct component, so registering a key a second time fails
 * publication, naming the mount and the input of both components. A component that is a relocated
 * definition is named as {@code a relocated definition of <input>}; when either side is one, the
 * message says {@code one component} instead of the key, so no definition name is echoed.
 */
final class ComponentRegistry {

    private final String subject;

    private final SortedMap<String, Component> components = new TreeMap<>();

    /**
     * Creates an empty registry for one document.
     *
     * @param subject the failure-message subject naming the application and its mount
     */
    ComponentRegistry(String subject) {
        this.subject = subject;
    }

    /**
     * Registers one component.
     *
     * @param key the component key, already through the key replacement
     * @param schema the component schema, owned by the document
     * @param input the input the component is published for
     * @param relocated whether the component is a definition relocated out of the input's schema
     * @throws RestConfigurationException when a component with the key is already registered
     */
    void register(String key, JsonNode schema, SchemaPublicationSubject input, boolean relocated) {
        Component existing = components.get(key);
        if (existing != null) {
            String named = existing.relocated() || relocated ? "one component" : "component '" + key + "'";
            throw new RestConfigurationException(subject + ": " + named + " would be published for both "
                    + existing.phrase() + " and " + phrase(input, relocated) + "; component keys replace every"
                    + " character outside [A-Za-z0-9._-] with '_', so rename one of them");
        }
        components.put(key, new Component(schema, phrase(input, relocated), relocated));
    }

    /**
     * Returns the registered component schemas.
     *
     * @return an unmodifiable view, keys in natural order
     */
    SortedMap<String, JsonNode> schemas() {
        SortedMap<String, JsonNode> schemas = new TreeMap<>();
        components.forEach((key, component) -> schemas.put(key, component.schema()));
        return Collections.unmodifiableSortedMap(schemas);
    }

    private static String phrase(SchemaPublicationSubject input, boolean relocated) {
        return relocated ? "a relocated definition of " + input.collisionPhrase() : input.collisionPhrase();
    }

    /**
     * One registered component.
     *
     * @param schema the component schema
     * @param phrase how a collision names the component's input
     * @param relocated whether the component is a relocated definition
     */
    private record Component(JsonNode schema, String phrase, boolean relocated) {}
}
