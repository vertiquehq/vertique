// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A field-level any-setter's value type is the map content type Jackson resolves for the field, not
 * the second type argument written on the field's declared type.
 *
 * <p>The declared type is a map subclass in every fixture here, so its written type arguments are
 * not the map's key and value: a one-argument subclass has no second argument at all, a subclass
 * that reorders its parameters puts the key type second, and a non-generic subclass writes none.
 * Each proof asserts the exact published {@code additionalProperties}, and the reordered case also
 * proves the oracle: Jackson binds the extras as integers.
 */
class AnySetterMapSubclassValueTypeTest {

    /** The extras schema of an integer value under the {@code vertique} profile. */
    private static final String INTEGER_EXTRAS = "{\"type\":\"integer\"}";

    /** The extras schema of a list-of-integers value under the {@code vertique} profile. */
    private static final String INTEGER_LIST_EXTRAS = "{\"items\":{\"type\":\"integer\"},\"type\":\"array\"}";

    private static JsonMapperProfile profile() {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of("vertique"));
    }

    /**
     * Generates a type's input document and returns its published {@code additionalProperties}.
     *
     * @param type the body type
     * @return the canonical text of the published {@code additionalProperties}
     */
    private static String extras(Class<?> type) {
        JsonNode document = assertCanonicalForm(
                AnnotationJsonSchemaGenerator.forInputProfile(profile()).generateCanonical(type));
        JsonNode extras = document.path("additionalProperties");
        JsonNode reference = extras.path("$ref");
        if (reference.isTextual() && reference.asText().startsWith("#/")) {
            extras = document.at(reference.asText().substring(1));
        }
        return extras.isMissingNode() ? "<absent> in " + document : extras.toString();
    }

    @Test
    @DisplayName("A one-argument map subclass any-setter describes its bound value type")
    void oneArgumentMapSubclassDescribesItsValueType() {
        assertEquals(INTEGER_EXTRAS, extras(StringKeysHolder.class));
    }

    @Test
    @DisplayName("A map subclass that reorders its parameters describes the value type, not the key type")
    void reorderedMapSubclassDescribesTheValueType() throws Exception {
        ObjectMapper mapper = profile().mapper();
        ReversedHolder bound = mapper.readValue("{\"name\":\"n\",\"extra\":5}", ReversedHolder.class);
        assertInstanceOf(Integer.class, bound.extras.get("extra"), "Jackson must bind the extras as integers");

        assertEquals(INTEGER_EXTRAS, extras(ReversedHolder.class));
    }

    @Test
    @DisplayName("A non-generic map subclass any-setter describes its inherited value type")
    void nonGenericMapSubclassDescribesItsValueType() {
        assertEquals(INTEGER_EXTRAS, extras(IntMapHolder.class));
    }

    @Test
    @DisplayName("A nested generic value type keeps its type arguments")
    void nestedGenericValueTypeKeepsItsArguments() {
        assertAll(
                () -> assertEquals(
                        INTEGER_LIST_EXTRAS, extras(NestedGenericHolder.class), "Map<String, List<Integer>>"),
                () -> assertEquals(
                        INTEGER_LIST_EXTRAS, extras(StringKeysOfListsHolder.class), "StringKeys<List<Integer>>"));
    }

    // --- Fixtures ---

    /**
     * A map subclass with one type parameter, the value type.
     *
     * @param <V> the value type
     */
    static class StringKeys<V> extends LinkedHashMap<String, V> {}

    /**
     * A map subclass whose parameters are written value-first.
     *
     * @param <V> the value type
     * @param <K> the key type
     */
    static class Reversed<V, K> extends LinkedHashMap<K, V> {}

    /** A map subclass with no type parameters of its own. */
    static class IntMap extends LinkedHashMap<String, Integer> {}

    /** A one-argument map subclass any-setter. */
    static final class StringKeysHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public StringKeys<Integer> extras = new StringKeys<>();
    }

    /** A reordered map subclass any-setter whose second written argument is the key type. */
    static final class ReversedHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Reversed<Integer, String> extras = new Reversed<>();
    }

    /** A non-generic map subclass any-setter. */
    static final class IntMapHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public IntMap extras = new IntMap();
    }

    /** A plain map any-setter whose value type is itself generic. */
    static final class NestedGenericHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, List<Integer>> extras = new LinkedHashMap<>();
    }

    /** A one-argument map subclass any-setter whose value type is itself generic. */
    static final class StringKeysOfListsHolder {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public StringKeys<List<Integer>> extras = new StringKeys<>();
    }
}
