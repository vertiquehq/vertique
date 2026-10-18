// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.schema.AnnotationJsonSchemaGenerator;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical Draft 2020-12 documents for payload types, produced directly by the schema generator under the reserved
 * {@code vertique} profile and never through the code under test, so the shape rule is exercised on exactly what the
 * generator emits.
 */
final class Documents {

    private static final AnnotationJsonSchemaGenerator GENERATOR = AnnotationJsonSchemaGenerator.forInputProfile(
            new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique")));

    private Documents() {}

    /**
     * Generates the canonical document of {@code type}.
     *
     * @param type the resolved type
     * @return the parsed document
     */
    static JsonObject of(Type type) {
        return new JsonObject(GENERATOR.generateCanonical(type));
    }

    /**
     * The generic type {@code Map<String, Object>}.
     *
     * @return the type
     */
    static Type mapOfStringToObject() {
        return new TypeReference<Map<String, Object>>() {}.getType();
    }

    /**
     * The generic type {@code List<element>}.
     *
     * @return the type
     */
    static Type listOfPlainRecords() {
        return new TypeReference<List<dev.vertique.workflow.schema.fixtures.Payloads.PlainRecord>>() {}.getType();
    }
}
