// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.keyed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.KeyedBy;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the reusable keyed-collection deserialization path that now lives in
 * {@code vertique-json}: {@link KeyedCollectionModule}, {@link KeyedCollectionDeserializer}, and the
 * {@link KeyedBy} annotation.
 *
 * <p>The test drives a plain {@link ObjectMapper} with {@link KeyedCollectionModule} registered —
 * this module cannot depend on {@code vertique-config-core}, so it must not reach for
 * {@code DefaultConfigParser}. The framework's section-root keyed-object behavior (entry key →
 * identity property) is reproduced here with {@link KeyedCollectionDeserializer#injectKey} +
 * {@code convertValue}, mirroring how {@code DefaultConfigParser.parseKeyedObject} drives it.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>each keyed-object entry's key is injected into the configured identity property;
 *   <li>an element that re-declares the identity equal to its key is accepted, while a conflicting
 *       value is rejected with a clear error;
 *   <li>nested {@code @KeyedBy} collections inject both outer and inner keys (the field-keyed path);
 *   <li>empty objects, non-object values for a keyed field, and blank keys are handled;
 *   <li>fixed (constant) properties are injected alongside the key so a validating compact
 *       constructor sees every required field.
 * </ul>
 */
class KeyedCollectionDeserializerTest {

    /** A mapper with the keyed-collection module registered, mirroring the config mapper's wiring. */
    private static ObjectMapper keyedMapper() {
        return JsonMapper.builder().addModule(new KeyedCollectionModule()).build();
    }

    /**
     * Reproduces the framework's section-root keyed-object parse: walk each entry in insertion order,
     * inject the entry key (and any fixed props) into the named identity property, then convert.
     *
     * @param section the keyed-object section
     * @param identityProp the identity property each entry key is injected into
     * @param elementType the element type
     * @param fixedProps constant properties injected into every element before conversion
     * @param <T> the element type
     * @return the parsed list, one element per entry, in insertion order
     */
    private static <T> List<T> parseKeyedObject(
            JsonObject section, String identityProp, Class<T> elementType, Map<String, Object> fixedProps) {
        if (section == null || section.isEmpty()) {
            return List.of();
        }
        ObjectMapper mapper = keyedMapper();
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(section.encode());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        List<T> result = new ArrayList<>();
        var fields = root.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode value = entry.getValue();
            ObjectNode element = KeyedCollectionDeserializer.injectKey(entry.getKey(), value, identityProp, fixedProps);
            result.add(mapper.convertValue(element, elementType));
        }
        return List.copyOf(result);
    }

    private static <T> List<T> parseKeyedObject(JsonObject section, String identityProp, Class<T> elementType) {
        return parseKeyedObject(section, identityProp, elementType, Map.of());
    }

    /**
     * A keyed element whose identity is {@code id} and which validates {@code id} non-blank.
     *
     * @param id the identity property, injected from the entry key
     * @param weight an arbitrary scalar payload
     */
    record Item(String id, int weight) {
        Item {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
        }
    }

    /**
     * A keyed element whose identity is {@code operation}, used for nesting.
     *
     * @param operation the identity property, injected from the inner entry key
     * @param enabled an arbitrary scalar payload
     */
    record Op(String operation, boolean enabled) {}

    /**
     * A parent record containing a nested {@code @KeyedBy("operation")} collection.
     *
     * @param name the parent name
     * @param operations the nested keyed collection of operations
     */
    record Parent(String name, @KeyedBy("operation") List<Op> operations) {}

    /**
     * A keyed element with two required fields, used to verify fixed-property injection.
     *
     * @param type a fixed (constant) property injected for the whole call
     * @param name the identity property, injected from the entry key
     */
    record Svc(String type, String name) {
        Svc {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("type must not be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }

    /**
     * A keyed element with an integer {@code version}, used to verify type-aware fixed-property
     * comparison (an injected int {@code 2} against an existing JSON number {@code 2} vs string
     * {@code "2"}).
     *
     * @param name the identity property, injected from the entry key
     * @param version a fixed (constant) integer property
     */
    record Versioned(String name, int version) {}

    /**
     * A keyed element with a floating-point {@code weight}, used to verify value-based numeric
     * comparison of an injected {@code Double} against an existing JSON float.
     *
     * @param name the identity property, injected from the entry key
     * @param weight a fixed (constant) floating-point property
     */
    record Weighted(String name, double weight) {}

    // --- key injection ---

    @Nested
    @DisplayName("key injection")
    class KeyInjection {

        @Test
        @DisplayName("each entry key is injected into the identity property")
        void keyInjectedIntoIdentity() {
            JsonObject section = new JsonObject()
                    .put("a", new JsonObject().put("weight", 1))
                    .put("b", new JsonObject().put("weight", 2));
            List<Item> items = parseKeyedObject(section, "id", Item.class);
            assertEquals(2, items.size());
            assertEquals("a", items.get(0).id());
            assertEquals(1, items.get(0).weight());
            assertEquals("b", items.get(1).id());
            assertEquals(2, items.get(1).weight());
        }

        @Test
        @DisplayName("explicit identity equal to the key is accepted")
        void explicitIdentityMatchingKey_ok() {
            JsonObject section =
                    new JsonObject().put("a", new JsonObject().put("id", "a").put("weight", 7));
            List<Item> items = parseKeyedObject(section, "id", Item.class);
            assertEquals(1, items.size());
            assertEquals("a", items.get(0).id());
            assertEquals(7, items.get(0).weight());
        }

        @Test
        @DisplayName("explicit identity differing from the key throws a clear, value-free error")
        void explicitIdentityConflictingKey_throws() {
            JsonObject section = new JsonObject()
                    .put("a", new JsonObject().put("id", "other").put("weight", 1));
            RuntimeException ex =
                    assertThrows(RuntimeException.class, () -> parseKeyedObject(section, "id", Item.class));
            assertNotNull(ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("a") && ex.getMessage().contains("id"),
                    "Expected conflict message naming the entry key and property, got: " + ex.getMessage());
            assertFalse(
                    ex.getMessage().contains("other"),
                    "Conflict message must not embed the conflicting config value, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("explicit numeric identity equal to the string key is rejected (type-aware compare)")
        void explicitNumericIdentityVsStringKey_throws() {
            JsonObject section =
                    new JsonObject().put("1", new JsonObject().put("id", 1).put("weight", 3));
            RuntimeException ex =
                    assertThrows(RuntimeException.class, () -> parseKeyedObject(section, "id", Item.class));
            assertNotNull(ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("1") && ex.getMessage().contains("id"),
                    "Expected conflict message naming the entry key and property, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("explicit string identity equal to the string key is accepted (type-aware compare)")
        void explicitStringIdentityMatchingKey_ok() {
            JsonObject section = new JsonObject()
                    .put("orders", new JsonObject().put("id", "orders").put("weight", 5));
            List<Item> items = parseKeyedObject(section, "id", Item.class);
            assertEquals(1, items.size());
            assertEquals("orders", items.get(0).id());
            assertEquals(5, items.get(0).weight());
        }

        @Test
        @DisplayName("fixed int prop equal to an existing numeric value is accepted (type-aware compare)")
        void fixedIntProp_matchingNumeric_ok() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject().put("version", 2));
            List<Versioned> items = parseKeyedObject(section, "name", Versioned.class, Map.of("version", 2));
            assertEquals(1, items.size());
            assertEquals("alpha", items.get(0).name());
            assertEquals(2, items.get(0).version());
        }

        @Test
        @DisplayName("fixed int prop against an existing string value is rejected (type-aware compare)")
        void fixedIntProp_vsStringValue_throws() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject().put("version", "2"));
            RuntimeException ex = assertThrows(
                    RuntimeException.class,
                    () -> parseKeyedObject(section, "name", Versioned.class, Map.of("version", 2)));
            assertNotNull(ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("alpha") && ex.getMessage().contains("version"),
                    "Expected conflict message naming the entry key and property, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("fixed long prop equal to an existing JSON int is accepted (value-based compare)")
        void fixedLongProp_matchingJsonInt_ok() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject().put("version", 2));
            List<Versioned> items = parseKeyedObject(section, "name", Versioned.class, Map.of("version", 2L));
            assertEquals(1, items.size());
            assertEquals("alpha", items.get(0).name());
            assertEquals(2, items.get(0).version());
        }

        @Test
        @DisplayName("fixed double prop equal to an existing JSON float is accepted (value-based compare)")
        void fixedDoubleProp_matchingJsonFloat_ok() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject().put("weight", 2.0));
            List<Weighted> items = parseKeyedObject(section, "name", Weighted.class, Map.of("weight", 2.0));
            assertEquals(1, items.size());
            assertEquals("alpha", items.get(0).name());
            assertEquals(2.0, items.get(0).weight());
        }

        @Test
        @DisplayName("fixed int prop equal to an existing JSON float is accepted (value-equal across int/float)")
        void fixedIntProp_matchingJsonFloat_ok() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject().put("version", 2.0));
            List<Versioned> items = parseKeyedObject(section, "name", Versioned.class, Map.of("version", 2));
            assertEquals(1, items.size());
            assertEquals("alpha", items.get(0).name());
            assertEquals(2, items.get(0).version());
        }

        @Test
        @DisplayName("fixed Float prop equal to an existing JSON float is accepted (canonical-string compare)")
        void fixedFloatProp_matchingJsonFloat_ok() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject().put("weight", 0.1));
            List<Weighted> items = parseKeyedObject(section, "name", Weighted.class, Map.of("weight", 0.1f));
            assertEquals(1, items.size());
            assertEquals("alpha", items.get(0).name());
        }

        @Test
        @DisplayName("fallback non-numeric prop matches a JSON string but not a JSON number (textual-only)")
        void fixedNonNumericProp_textualFallback() throws Exception {
            // An injected value that is neither String, Number, nor Boolean exercises the textual-only
            // fallback branch of matches(...). A Character '2' must match an existing JSON string "2"
            // but must NOT match an existing JSON number 2, even though both stringify to "2".
            ObjectMapper mapper = keyedMapper();
            ObjectNode numericExisting = (ObjectNode) mapper.readTree("{\"p\":2}");
            ObjectNode stringExisting = (ObjectNode) mapper.readTree("{\"p\":\"2\"}");

            // existing JSON number 2 → type mismatch → conflict thrown
            assertThrows(
                    RuntimeException.class,
                    () -> KeyedCollectionDeserializer.injectKey(
                            "alpha", numericExisting, "name", Map.of("p", (Object) '2')));
            // existing JSON string "2" → textual match → accepted (no throw)
            ObjectNode accepted =
                    KeyedCollectionDeserializer.injectKey("alpha", stringExisting, "name", Map.of("p", (Object) '2'));
            assertNotNull(accepted);
        }

        @Test
        @DisplayName("nested keyed collections inject both outer and inner keys")
        void nestedKeyedCollections() throws Exception {
            JsonObject section = new JsonObject()
                    .put("name", "parent")
                    .put(
                            "operations",
                            new JsonObject()
                                    .put("create", new JsonObject().put("enabled", true))
                                    .put("delete", new JsonObject().put("enabled", false)));
            Parent parent = keyedMapper().readValue(section.encode(), Parent.class);
            assertEquals("parent", parent.name());
            assertEquals(2, parent.operations().size());
            assertEquals("create", parent.operations().get(0).operation());
            assertTrue(parent.operations().get(0).enabled());
            assertEquals("delete", parent.operations().get(1).operation());
        }
    }

    // --- edge cases ---

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty object yields an empty list")
        void emptyObject_emptyList() {
            List<Item> items = parseKeyedObject(new JsonObject(), "id", Item.class);
            assertNotNull(items);
            assertTrue(items.isEmpty());
        }

        @Test
        @DisplayName("non-object value for a keyed field raises a clear error")
        void nonObjectForKeyedField_clearError() {
            JsonObject section = new JsonObject().put("a", "not-an-object");
            assertThrows(RuntimeException.class, () -> parseKeyedObject(section, "id", Item.class));
        }

        @Test
        @DisplayName("blank key is rejected")
        void blankKey_rejected() {
            JsonObject section = new JsonObject().put("   ", new JsonObject().put("weight", 1));
            assertThrows(RuntimeException.class, () -> parseKeyedObject(section, "id", Item.class));
        }
    }

    // --- fixed properties ---

    @Nested
    @DisplayName("fixed properties")
    class FixedProperties {

        @Test
        @DisplayName("fixed props and the key are both injected before deserialization")
        void fixedPropsInjected() {
            JsonObject section = new JsonObject().put("alpha", new JsonObject()).put("beta", new JsonObject());
            List<Svc> services = parseKeyedObject(section, "name", Svc.class, Map.of("type", "default"));
            assertEquals(2, services.size());
            assertEquals("default", services.get(0).type());
            assertEquals("alpha", services.get(0).name());
            assertEquals("default", services.get(1).type());
            assertEquals("beta", services.get(1).name());
        }
    }
}
