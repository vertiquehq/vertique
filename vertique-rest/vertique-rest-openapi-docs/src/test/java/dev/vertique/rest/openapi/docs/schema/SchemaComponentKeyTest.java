// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.schema;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit proof of the component key rule: every character of a candidate key outside {@code
 * [A-Za-z0-9._-]} becomes one {@code _}, a supplementary character counting once, and two
 * components that end up with one key fail publication naming both inputs and the rule.
 */
class SchemaComponentKeyTest {

    private static final String SUBJECT = "apidocs.documents.orders: mount '/api'";

    private static final JsonNode SCHEMA = JsonNodeFactory.instance.objectNode();

    @Test
    @DisplayName("every character of the key alphabet is kept")
    void everyAlphabetCharacterIsKept() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-";

        assertEquals(
                alphabet + ".request", SchemaPublicationSubject.body(alphabet).componentKey());
    }

    @ParameterizedTest(name = "operation id {0} gives key {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "get:item|get_item.request",
                "a b|a_b.request",
                "a/b{id}|a_b_id_.request",
                "a~b|a_b.request",
                "café|caf_.request",
                "😀|_.request",
                "😀😀|__.request"
            })
    @DisplayName("every other character, a supplementary one as one character, becomes an underscore")
    void everyOtherCharacterBecomesOneUnderscore(String operationId, String expectedKey) {
        assertEquals(expectedKey, SchemaPublicationSubject.body(operationId).componentKey());
    }

    @Test
    @DisplayName("the rule applies to the whole key, the location and the name of an input included")
    void theRuleAppliesToTheWholeKey() {
        assertAll(
                () -> assertEquals(
                        "get_item.query.a_b",
                        SchemaPublicationSubject.parameter("get:item", ParamLocation.QUERY, "a b")
                                .componentKey()),
                () -> assertEquals(
                        "get_item.form.x_y",
                        SchemaPublicationSubject.formField("get:item", "x y").componentKey()));
    }

    @Test
    @DisplayName("two inputs whose keys become equal fail publication naming the mount, both inputs and the rule")
    void collidingKeysFailPublication() {
        SchemaComponentCatalog catalog = new SchemaComponentCatalog(SUBJECT);
        SchemaPublicationSubject first = SchemaPublicationSubject.body("get:item");
        SchemaPublicationSubject second = SchemaPublicationSubject.body("get_item");
        catalog.register(first.componentKey(), SCHEMA, first, false);

        RestConfigurationException failure = assertThrows(
                RestConfigurationException.class, () -> catalog.register(second.componentKey(), SCHEMA, second, false));

        String message = failure.getMessage();
        assertAll(
                () -> assertTrue(message.startsWith(SUBJECT), message),
                () -> assertTrue(message.contains("'get:item'"), message),
                () -> assertTrue(message.contains("'get_item'"), message),
                () -> assertTrue(message.contains("[A-Za-z0-9._-]"), message),
                () -> assertTrue(message.contains("'_'"), message));
    }

    @Test
    @DisplayName("inputs whose keys stay different publish side by side")
    void distinctKeysDoNotCollide() {
        SchemaComponentCatalog catalog = new SchemaComponentCatalog(SUBJECT);
        SchemaPublicationSubject first = SchemaPublicationSubject.body("get:item");
        SchemaPublicationSubject second = SchemaPublicationSubject.body("get-item");

        assertDoesNotThrow(() -> {
            catalog.register(first.componentKey(), SCHEMA, first, false);
            catalog.register(second.componentKey(), SCHEMA, second, false);
        });
        assertEquals(
                Set.of("get_item.request", "get-item.request"),
                catalog.schemas().keySet());
    }
}
