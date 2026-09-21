// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H4: a getter-only collection with no backing field publishes its item schema. The gate-level half
 * of this proof (a wrong-typed item is rejected and a valid body accepted, at both the REST and MCP
 * boundaries) lives in {@code ProfiledSchemaSynthesisIT} and {@code McpToolInputShapesIT}.
 */
class GetterOnlyCollectionDescriptionTest {

    /**
     * {@link #getItems()}'s only storage is {@link #internal}, a field named unrelated to the
     * property's own wire name ({@code items}) — Jackson binds this the same way it binds any
     * getter-only mutable collection with no declared setter: by fetching the existing instance
     * through the getter and populating it in place. The schema library's own member scoping is
     * independent of Jackson's field/setter discovery, so the generator's scoped-member describe path
     * must still find this getter and describe it, publishing {@code items} with an element type —
     * never falling back to an opaque, unscoped description of the raw deserializer type.
     */
    static final class GetterOnlyCollectionNoBackingFieldDto {
        private final List<Integer> internal = new ArrayList<>();

        public List<Integer> getItems() {
            return internal;
        }
    }

    private static JsonMapperProfile vertiqueProfile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    @Test
    @DisplayName("H4: a getter-only collection with no backing field is published with its item schema")
    void getterOnlyCollectionWithNoBackingFieldPublishesItsItemSchema() {
        String canonical = AnnotationJsonSchemaGenerator.forInputProfile(vertiqueProfile())
                .generateCanonical(GetterOnlyCollectionNoBackingFieldDto.class);
        JsonNode document = assertCanonicalForm(canonical);

        JsonNode items = document.at("/properties/items");
        assertFalse(items.isMissingNode(), "the property must be published even with no backing field; document: "
                + document);
        assertEquals(
                "array",
                items.at("/type").asText(),
                "the property must describe an array, not fall back to an opaque, unscoped description;"
                        + " document: " + document);
        assertEquals(
                "integer",
                items.at("/items/type").asText(),
                "the array's item schema must describe the element type; document: " + document);
    }
}
