// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JacksonFieldNameResolver#polymorphicSubtypes} reports what the mapper's own subtype
 * resolution reports for an {@code @JsonTypeInfo} base — the subtypes the engine cannot see because it
 * resolves policy metadata from the declared type.
 */
@DisplayName("JacksonFieldNameResolver polymorphic subtypes")
class JacksonPolymorphicSubtypesTest {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Cat.class, name = "cat"),
        @JsonSubTypes.Type(value = Dog.class, name = "dog")
    })
    abstract static class Animal {
        public String name;
    }

    static final class Cat extends Animal {
        public String whiskers;
    }

    static final class Dog extends Animal {
        public String collar;
    }

    /** Polymorphic by annotation, with no declared subtypes. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    abstract static class Open {}

    static final class Registered extends Open {}

    /** Not polymorphic. */
    static class Plain {
        public String name;
    }

    /** Two Java properties claiming one wire name: composing its property projection would throw. */
    static class Colliding {
        @JsonProperty("same")
        public String a;

        @JsonProperty("same")
        public String b;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = CollidingChild.class, name = "child"))
    static class CollidingBase {
        @JsonProperty("same")
        public String a;

        @JsonProperty("same")
        public String b;
    }

    static final class CollidingChild extends CollidingBase {}

    private static JacksonFieldNameResolver resolver(ObjectMapper mapper) {
        return JacksonFieldNameResolver.forMapper(mapper);
    }

    @Test
    @DisplayName("reports the @JsonSubTypes entries and never the base itself")
    void declaredSubtypesWithoutTheBase() {
        Set<Class<?>> subtypes = resolver(JsonMapper.builder().build()).polymorphicSubtypes(Animal.class);

        assertEquals(Set.of(Cat.class, Dog.class), subtypes);
    }

    @Test
    @DisplayName("reports a subtype registered on the mapper")
    void mapperRegisteredSubtype() {
        ObjectMapper mapper = JsonMapper.builder().build();
        mapper.registerSubtypes(Registered.class);

        assertEquals(Set.of(Registered.class), resolver(mapper).polymorphicSubtypes(Open.class));
    }

    @Test
    @DisplayName("is empty for a polymorphic base with no enumerable subtype")
    void openBaseWithoutSubtypes() {
        assertTrue(resolver(JsonMapper.builder().build())
                .polymorphicSubtypes(Open.class)
                .isEmpty());
    }

    @Test
    @DisplayName("is empty for a type that is not polymorphic")
    void plainTypeIsNotPolymorphic() {
        assertTrue(resolver(JsonMapper.builder().build())
                .polymorphicSubtypes(Plain.class)
                .isEmpty());
        assertTrue(resolver(JsonMapper.builder().build())
                .polymorphicSubtypes(String.class)
                .isEmpty());
    }

    @Test
    @DisplayName("never lists the asked type itself, even when it inherits the base's subtype annotation")
    void askedTypeIsNeverListed() {
        // Cat inherits Animal's @JsonSubTypes, so the mapper's resolution may name siblings; the engine
        // keeps only subtypes assignable to the type it asked about.
        assertFalse(resolver(JsonMapper.builder().build())
                .polymorphicSubtypes(Cat.class)
                .contains(Cat.class));
    }

    @Test
    @DisplayName("reads class annotations only, so a property-name collision cannot raise")
    void doesNotComposeThePropertyProjection() {
        JacksonFieldNameResolver resolver = resolver(JsonMapper.builder().build());

        assertDoesNotThrow(() -> resolver.polymorphicSubtypes(Colliding.class));
        assertEquals(Set.of(CollidingChild.class), resolver.polymorphicSubtypes(CollidingBase.class));
    }
}
