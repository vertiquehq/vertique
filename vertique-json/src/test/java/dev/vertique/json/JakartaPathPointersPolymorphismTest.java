// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Property;
import io.vertx.core.json.Json;
import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves how the JSON pointer resolver names the members of a polymorphic value in projection mode,
 * with the route's name projection and the request document.
 *
 * <p>When the type id is a plain member of the object itself or of its holder (a type property, an
 * existing property, an external property, deduction from the members, a visible type id, a default
 * implementation), the runtime class of the bound value decides the member names, so a subtype's
 * renamed member is named as the client sent it. A runtime class the mapper's subtypes do not list may
 * rename even the members it inherits, so the pointer stops at the property holding the value; so does
 * a path that records no runtime class at all. Type information that wraps the value (a wrapper object or
 * array), object identity and single-element array unwrapping move the members away from where the
 * value's own names would put them, so the pointer stops at the property holding the value.
 *
 * <p>Each row's document is shaped as the row's mapper reads it, which the row also checks, and every
 * non-root pointer must resolve in that document.
 */
class JakartaPathPointersPolymorphismTest {

    // --- Hierarchies, one per kind of type information; each subtype renames its member ---

    /** The type id is a member of the object. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = ByProperty.Dog.class, name = "dog"),
        @JsonSubTypes.Type(value = ByProperty.Cat.class, name = "cat")
    })
    public interface ByProperty {
        /** The subtype whose member the violation names. */
        record Dog(@JsonProperty("woof") String bark) implements ByProperty {}

        /** A sibling subtype with a different member. */
        record Cat(String meow) implements ByProperty {}
    }

    /** The type id is a member the subtype also declares. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = ByExistingProperty.Dog.class, name = "dog"))
    public interface ByExistingProperty {
        /** The subtype whose member the violation names. */
        record Dog(String kind, @JsonProperty("woof") String bark) implements ByExistingProperty {}
    }

    /** The type id is a member of the holder; the type information sits on the holder's property. */
    @JsonSubTypes(@JsonSubTypes.Type(value = ByExternalProperty.Dog.class, name = "dog"))
    public interface ByExternalProperty {
        /** The subtype whose member the violation names. */
        record Dog(@JsonProperty("woof") String bark) implements ByExternalProperty {}
    }

    /** The subtype is deduced from the members the document holds. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({@JsonSubTypes.Type(ByDeduction.Dog.class), @JsonSubTypes.Type(ByDeduction.Cat.class)})
    public interface ByDeduction {
        /** The subtype whose member the violation names. */
        record Dog(@JsonProperty("woof") String bark) implements ByDeduction {}

        /** A sibling subtype with a different member. */
        record Cat(String meow) implements ByDeduction {}
    }

    /** The type id is a member of the object and is also handed to the subtype. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type", visible = true)
    @JsonSubTypes(@JsonSubTypes.Type(value = ByVisibleProperty.Dog.class, name = "dog"))
    public interface ByVisibleProperty {
        /** The subtype whose member the violation names; it also reads the type id. */
        record Dog(String type, @JsonProperty("woof") String bark) implements ByVisibleProperty {}
    }

    /** A document without a type id binds the default implementation. */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.PROPERTY,
            property = "type",
            defaultImpl = ByDefaultImpl.Dog.class)
    @JsonSubTypes(@JsonSubTypes.Type(value = ByDefaultImpl.Dog.class, name = "dog"))
    public interface ByDefaultImpl {
        /** The default implementation, whose member the violation names. */
        record Dog(@JsonProperty("woof") String bark) implements ByDefaultImpl {}
    }

    /** The type id wraps the value in an object with a single member. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonSubTypes(@JsonSubTypes.Type(value = ByWrapperObject.Dog.class, name = "dog"))
    public interface ByWrapperObject {
        /** The subtype whose member the violation names. */
        record Dog(@JsonProperty("woof") String bark) implements ByWrapperObject {}
    }

    /** The type id and the value are the two elements of an array. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    @JsonSubTypes(@JsonSubTypes.Type(value = ByWrapperArray.Dog.class, name = "dog"))
    public interface ByWrapperArray {
        /** The subtype whose member the violation names. */
        record Dog(@JsonProperty("woof") String bark) implements ByWrapperArray {}
    }

    /** A wrapper-object type id on a base that declares the violated member itself. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
    @JsonSubTypes(@JsonSubTypes.Type(value = NamedByWrapperObject.Dog.class, name = "dog"))
    public abstract static class NamedByWrapperObject {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** The subtype the document binds; it inherits the member. */
        public static class Dog extends NamedByWrapperObject {}
    }

    /** A wrapper-array type id on a base that declares the violated member itself. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_ARRAY)
    @JsonSubTypes(@JsonSubTypes.Type(value = NamedByWrapperArray.Dog.class, name = "dog"))
    public abstract static class NamedByWrapperArray {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** The subtype the document binds; it inherits the member. */
        public static class Dog extends NamedByWrapperArray {}
    }

    /**
     * Class-name type ids with one listed subtype: the mapper also binds a subtype the list does not
     * name, so the listed set does not contain every runtime class.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    @JsonSubTypes(@JsonSubTypes.Type(ByClassName.Dog.class))
    public abstract static class ByClassName {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** The listed subtype. */
        public static class Dog extends ByClassName {
            private String bark;

            @JsonProperty("woof")
            public String getBark() {
                return bark;
            }

            @JsonProperty("woof")
            public void setBark(String bark) {
                this.bark = bark;
            }
        }

        /** A subtype the list does not name; its member exists only on the subtype. */
        public static class Cat extends ByClassName {
            private String purr;

            @JsonProperty("meow")
            public String getPurr() {
                return purr;
            }

            @JsonProperty("meow")
            public void setPurr(String purr) {
                this.purr = purr;
            }
        }
    }

    /**
     * Class-name type ids with one listed subtype, where the unlisted subtype renames the member it
     * inherits from the base.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    @JsonSubTypes(@JsonSubTypes.Type(RenamingByClassName.Dog.class))
    public abstract static class RenamingByClassName {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** The listed subtype; it keeps the base's name. */
        public static class Dog extends RenamingByClassName {}

        /** A subtype the list does not name; it binds the inherited member under another name. */
        public static class Cat extends RenamingByClassName {
            @Override
            @JsonProperty("nom")
            public String getName() {
                return super.getName();
            }

            @Override
            @JsonProperty("nom")
            public void setName(String name) {
                super.setName(name);
            }
        }
    }

    /** A type-property base whose listed subtype binds the inherited member under another name. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(@JsonSubTypes.Type(value = ByPropertyRenaming.Dog.class, name = "dog"))
    public abstract static class ByPropertyRenaming {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** The listed subtype; it renames the member it inherits. */
        public static class Dog extends ByPropertyRenaming {
            @Override
            @JsonProperty("dog_name")
            public String getName() {
                return super.getName();
            }

            @Override
            @JsonProperty("dog_name")
            public void setName(String name) {
                super.setName(name);
            }
        }
    }

    /** A bean the mapper may bind from an object id reference to an earlier instance. */
    @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class, property = "@id")
    public static class Pal {
        private String bark;

        @JsonProperty("woof")
        public String getBark() {
            return bark;
        }

        @JsonProperty("woof")
        public void setBark(String bark) {
            this.bark = bark;
        }
    }

    /** A class unrelated to every hierarchy above. */
    public record Stranger(@JsonProperty("woof") String bark) {}

    /** A plain record without type information. */
    public record Mutt(@JsonProperty("woof") String bark) {}

    // --- Holders ---

    /** Holds a type-property value and a list of them. */
    public record PropertyHolder(ByProperty pet, List<ByProperty> pets) {}

    /** Holds an existing-property value. */
    public record ExistingPropertyHolder(ByExistingProperty pet) {}

    /** Holds an external-property value; the type id is the holder's {@code kind} member. */
    public static class ExternalPropertyHolder {
        private ByExternalProperty pet;

        public ByExternalProperty getPet() {
            return pet;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXTERNAL_PROPERTY, property = "kind")
        public void setPet(ByExternalProperty pet) {
            this.pet = pet;
        }
    }

    /** Holds a deduced value and a list of them. */
    public record DeductionHolder(ByDeduction pet, List<ByDeduction> pets) {}

    /** Holds a value whose type id is visible to the subtype. */
    public record VisiblePropertyHolder(ByVisibleProperty pet) {}

    /** Holds a value with a default implementation. */
    public record DefaultImplHolder(ByDefaultImpl pet) {}

    /** Holds a wrapper-object value and a list of them. */
    public record WrapperObjectHolder(ByWrapperObject pet, List<ByWrapperObject> pets) {}

    /** Holds a wrapper-array value and a list of them. */
    public record WrapperArrayHolder(ByWrapperArray pet, List<ByWrapperArray> pets) {}

    /** Holds a wrapper-object value whose base declares the violated member. */
    public record NamedWrapperObjectHolder(NamedByWrapperObject pet) {}

    /** Holds a wrapper-array value whose base declares the violated member. */
    public record NamedWrapperArrayHolder(NamedByWrapperArray pet) {}

    /** Holds a class-name typed value. */
    public record ClassNameHolder(ByClassName pet) {}

    /** Holds a class-name typed value whose unlisted subtype renames an inherited member. */
    public record RenamingClassNameHolder(RenamingByClassName pet) {}

    /** Holds an identity-bearing value and a list of them. */
    public record IdentityHolder(Pal pet, List<Pal> pets) {}

    /** Holds a plain value. */
    public record MuttHolder(Mutt pet) {}

    /** Holds a type-property value whose subtype renames an inherited member. */
    public record RenamingPropertyHolder(ByPropertyRenaming pet) {}

    // --- Mappers ---

    /** A route mapper with Jackson's defaults. */
    private static final ObjectMapper PLAIN = JsonMapper.builder().build();

    /** A route mapper that ignores undeclared members. */
    private static final ObjectMapper TOLERANT = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** A route mapper that reads a one-element array where a single value is declared. */
    private static final ObjectMapper UNWRAPS_SINGLE_VALUE_ARRAYS = JsonMapper.builder()
            .enable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
            .build();

    // --- Paths ---

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    private static Property property(String name, Class<?> ownerType) {
        return new Property(name, ownerType);
    }

    private static Index index(int index) {
        return new Index(index);
    }

    private static final String CAT_CLASS = ByClassName.Cat.class.getName();

    private static final String DOG_CLASS = ByClassName.Dog.class.getName();

    private static final String RENAMING_CAT_CLASS = RenamingByClassName.Cat.class.getName();

    private static final String RENAMING_DOG_CLASS = RenamingByClassName.Dog.class.getName();

    static Stream<Arguments> polymorphismRows() {
        return Stream.of(
                // --- the type id is a plain member: the runtime class names the members ---
                arguments(
                        "type property",
                        PLAIN,
                        PropertyHolder.class,
                        path(property("pet", PropertyHolder.class), property("bark", ByProperty.Dog.class)),
                        "{\"pet\":{\"type\":\"dog\",\"woof\":\"\"}}",
                        "/pet/woof"),
                arguments(
                        "type property on a list element",
                        PLAIN,
                        PropertyHolder.class,
                        path(property("pets", PropertyHolder.class), index(0), property("bark", ByProperty.Dog.class)),
                        "{\"pets\":[{\"type\":\"dog\",\"woof\":\"\"},{\"type\":\"cat\",\"meow\":\"\"}]}",
                        "/pets/0/woof"),
                arguments(
                        "type property on a second list element of another subtype",
                        PLAIN,
                        PropertyHolder.class,
                        path(property("pets", PropertyHolder.class), index(1), property("meow", ByProperty.Cat.class)),
                        "{\"pets\":[{\"type\":\"dog\",\"woof\":\"\"},{\"type\":\"cat\",\"meow\":\"\"}]}",
                        "/pets/1/meow"),
                arguments(
                        "existing property",
                        PLAIN,
                        ExistingPropertyHolder.class,
                        path(
                                property("pet", ExistingPropertyHolder.class),
                                property("bark", ByExistingProperty.Dog.class)),
                        "{\"pet\":{\"kind\":\"dog\",\"woof\":\"\"}}",
                        "/pet/woof"),
                arguments(
                        "external property",
                        PLAIN,
                        ExternalPropertyHolder.class,
                        path(
                                property("pet", ExternalPropertyHolder.class),
                                property("bark", ByExternalProperty.Dog.class)),
                        "{\"kind\":\"dog\",\"pet\":{\"woof\":\"\"}}",
                        "/pet/woof"),
                arguments(
                        "deduction",
                        PLAIN,
                        DeductionHolder.class,
                        path(property("pet", DeductionHolder.class), property("bark", ByDeduction.Dog.class)),
                        "{\"pet\":{\"woof\":\"\"}}",
                        "/pet/woof"),
                arguments(
                        "deduction on a list element",
                        PLAIN,
                        DeductionHolder.class,
                        path(
                                property("pets", DeductionHolder.class),
                                index(1),
                                property("bark", ByDeduction.Dog.class)),
                        "{\"pets\":[{\"meow\":\"\"},{\"woof\":\"\"}]}",
                        "/pets/1/woof"),
                arguments(
                        "visible type id",
                        PLAIN,
                        VisiblePropertyHolder.class,
                        path(
                                property("pet", VisiblePropertyHolder.class),
                                property("bark", ByVisibleProperty.Dog.class)),
                        "{\"pet\":{\"type\":\"dog\",\"woof\":\"\"}}",
                        "/pet/woof"),
                arguments(
                        "default implementation without a type id",
                        PLAIN,
                        DefaultImplHolder.class,
                        path(property("pet", DefaultImplHolder.class), property("bark", ByDefaultImpl.Dog.class)),
                        "{\"pet\":{\"woof\":\"\"}}",
                        "/pet/woof"),
                arguments(
                        "listed class-name subtype",
                        PLAIN,
                        ClassNameHolder.class,
                        path(property("pet", ClassNameHolder.class), property("bark", ByClassName.Dog.class)),
                        "{\"pet\":{\"@class\":\"" + DOG_CLASS + "\",\"woof\":\"\"}}",
                        "/pet/woof"),
                // --- a runtime class the projection does not list: stop at the property holding the value ---
                arguments(
                        "unlisted runtime class, member only on the subtype",
                        PLAIN,
                        ClassNameHolder.class,
                        path(property("pet", ClassNameHolder.class), property("purr", ByClassName.Cat.class)),
                        "{\"pet\":{\"@class\":\"" + CAT_CLASS + "\",\"meow\":\"\"}}",
                        "/pet"),
                arguments(
                        "unlisted runtime class, member declared on the base",
                        PLAIN,
                        ClassNameHolder.class,
                        path(property("pet", ClassNameHolder.class), property("name", ByClassName.Cat.class)),
                        "{\"pet\":{\"@class\":\"" + CAT_CLASS + "\",\"name\":\"\"}}",
                        "/pet"),
                // --- no usable runtime class: stop at the property holding the value ---
                arguments(
                        "type property without a recorded runtime class",
                        PLAIN,
                        PropertyHolder.class,
                        path(property("pet", PropertyHolder.class), property("bark", null)),
                        "{\"pet\":{\"type\":\"dog\",\"woof\":\"\"}}",
                        "/pet"),
                arguments(
                        "type property with a runtime class outside the declared hierarchy",
                        PLAIN,
                        PropertyHolder.class,
                        path(property("pet", PropertyHolder.class), property("bark", Stranger.class)),
                        "{\"pet\":{\"type\":\"dog\",\"woof\":\"\"}}",
                        "/pet"),
                // --- type information that moves the members: stop at the property ---
                arguments(
                        "wrapper object",
                        PLAIN,
                        WrapperObjectHolder.class,
                        path(property("pet", WrapperObjectHolder.class), property("bark", ByWrapperObject.Dog.class)),
                        "{\"pet\":{\"dog\":{\"woof\":\"\"}}}",
                        "/pet"),
                arguments(
                        "wrapper object on a list element",
                        PLAIN,
                        WrapperObjectHolder.class,
                        path(
                                property("pets", WrapperObjectHolder.class),
                                index(0),
                                property("bark", ByWrapperObject.Dog.class)),
                        "{\"pets\":[{\"dog\":{\"woof\":\"\"}}]}",
                        "/pets"),
                arguments(
                        "wrapper array",
                        PLAIN,
                        WrapperArrayHolder.class,
                        path(property("pet", WrapperArrayHolder.class), property("bark", ByWrapperArray.Dog.class)),
                        "{\"pet\":[\"dog\",{\"woof\":\"\"}]}",
                        "/pet"),
                arguments(
                        "wrapper array on a list element",
                        PLAIN,
                        WrapperArrayHolder.class,
                        path(
                                property("pets", WrapperArrayHolder.class),
                                index(0),
                                property("bark", ByWrapperArray.Dog.class)),
                        "{\"pets\":[[\"dog\",{\"woof\":\"\"}]]}",
                        "/pets"),
                arguments(
                        "object identity",
                        PLAIN,
                        IdentityHolder.class,
                        path(property("pet", IdentityHolder.class), property("bark", Pal.class)),
                        "{\"pet\":{\"@id\":1,\"woof\":\"\"}}",
                        "/pet"),
                arguments(
                        "object identity on a list element bound from an id reference",
                        PLAIN,
                        IdentityHolder.class,
                        path(property("pets", IdentityHolder.class), index(1), property("bark", Pal.class)),
                        "{\"pets\":[{\"@id\":1,\"woof\":\"\"},1]}",
                        "/pets"),
                arguments(
                        "type property sent in the wrapper-array form",
                        PLAIN,
                        PropertyHolder.class,
                        path(property("pet", PropertyHolder.class), property("bark", ByProperty.Dog.class)),
                        "{\"pet\":[\"dog\",{\"woof\":\"\"}]}",
                        "/pet"),
                arguments(
                        "single-element array unwrapped into the value",
                        UNWRAPS_SINGLE_VALUE_ARRAYS,
                        MuttHolder.class,
                        path(property("pet", MuttHolder.class), property("bark", Mutt.class)),
                        "{\"pet\":[{\"woof\":\"\"}]}",
                        "/pet"),
                // --- the member is declared on the base, so only the moving type information stops it ---
                arguments(
                        "wrapper object, member declared on the base and sent inside the wrapper",
                        PLAIN,
                        NamedWrapperObjectHolder.class,
                        path(
                                property("pet", NamedWrapperObjectHolder.class),
                                property("name", NamedByWrapperObject.Dog.class)),
                        "{\"pet\":{\"dog\":{\"name\":\"\"}}}",
                        "/pet"),
                arguments(
                        "wrapper array, member declared on the base and sent inside the array",
                        PLAIN,
                        NamedWrapperArrayHolder.class,
                        path(
                                property("pet", NamedWrapperArrayHolder.class),
                                property("name", NamedByWrapperArray.Dog.class)),
                        "{\"pet\":[\"dog\",{\"name\":\"\"}]}",
                        "/pet"),
                // --- an unlisted subtype may rename an inherited member: stop at the property ---
                arguments(
                        "unlisted runtime class renaming an inherited member",
                        PLAIN,
                        RenamingClassNameHolder.class,
                        path(
                                property("pet", RenamingClassNameHolder.class),
                                property("name", RenamingByClassName.Cat.class)),
                        "{\"pet\":{\"@class\":\"" + RENAMING_CAT_CLASS + "\",\"nom\":\"\"}}",
                        "/pet"),
                arguments(
                        "unlisted runtime class renaming an inherited member, base spelling also sent",
                        TOLERANT,
                        RenamingClassNameHolder.class,
                        path(
                                property("pet", RenamingClassNameHolder.class),
                                property("name", RenamingByClassName.Cat.class)),
                        "{\"pet\":{\"@class\":\"" + RENAMING_CAT_CLASS + "\",\"nom\":\"x\",\"name\":\"\"}}",
                        "/pet"),
                arguments(
                        "listed runtime class, member declared on the base",
                        PLAIN,
                        RenamingClassNameHolder.class,
                        path(
                                property("pet", RenamingClassNameHolder.class),
                                property("name", RenamingByClassName.Dog.class)),
                        "{\"pet\":{\"@class\":\"" + RENAMING_DOG_CLASS + "\",\"name\":\"\"}}",
                        "/pet/name"),
                // --- flat type information without a recorded runtime class: stop at the property ---
                arguments(
                        "type property, subtype renames an inherited member, no recorded runtime class",
                        PLAIN,
                        RenamingPropertyHolder.class,
                        path(property("pet", RenamingPropertyHolder.class), property("name", null)),
                        "{\"pet\":{\"type\":\"dog\",\"dog_name\":\"\"}}",
                        "/pet"),
                arguments(
                        "type property, subtype renames an inherited member, base spelling also sent, no recorded"
                                + " runtime class",
                        TOLERANT,
                        RenamingPropertyHolder.class,
                        path(property("pet", RenamingPropertyHolder.class), property("name", null)),
                        "{\"pet\":{\"type\":\"dog\",\"dog_name\":\"x\",\"name\":\"\"}}",
                        "/pet"),
                arguments(
                        "class-name type id, member declared on the base, no recorded runtime class",
                        PLAIN,
                        ClassNameHolder.class,
                        path(property("pet", ClassNameHolder.class), property("name", null)),
                        "{\"pet\":{\"@class\":\"" + DOG_CLASS + "\",\"name\":\"\"}}",
                        "/pet"),
                arguments(
                        "type property, subtype renames an inherited member, listed runtime class recorded",
                        PLAIN,
                        RenamingPropertyHolder.class,
                        path(
                                property("pet", RenamingPropertyHolder.class),
                                property("name", ByPropertyRenaming.Dog.class)),
                        "{\"pet\":{\"type\":\"dog\",\"dog_name\":\"\"}}",
                        "/pet/dog_name"),
                arguments(
                        "plain type without type information, no recorded runtime class",
                        PLAIN,
                        MuttHolder.class,
                        path(property("pet", MuttHolder.class), property("bark", null)),
                        "{\"pet\":{\"woof\":\"\"}}",
                        "/pet/woof"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("polymorphismRows")
    @DisplayName("flat type information resolves through the runtime class; other type information stops at the"
            + " property")
    void flatPolymorphismResolvesAndOtherTypeInformationTruncates(
            String inclusion, ObjectMapper mapper, Type rootType, PropertyPath path, String document, String expected) {
        // Given: a route mapper, its own name projection and a request document shaped as that mapper reads it
        JakartaPathPointers.Resolver session =
                JakartaPathPointers.resolver(mapper, JacksonFieldNameResolver.forMapper(mapper));
        Object parsed = Json.decodeValue(document);

        // When: the structured path, carrying the bound value's runtime class, resolves against the document
        String pointer = session.toPointer(path, rootType, parsed);

        // Then: the pointer is exactly the expected verified prefix and names a value present in the document
        assertAll(
                () -> assertDoesNotThrow(
                        () -> mapper.readValue(document, mapper.constructType(rootType)),
                        "the row's document must bind with the row's mapper"),
                () -> assertEquals(expected, pointer, "pointer for '" + inclusion + "'"),
                () -> assertResolves(document, pointer),
                () -> assertFalse(pointer.contains("ZZ"), "a client-chosen key reached the pointer"));
    }

    /** Asserts that a non-root pointer names a value present in the document. */
    private static void assertResolves(String document, String pointer) throws Exception {
        if (pointer.isEmpty()) {
            return;
        }
        JsonNode tree = PLAIN.valueToTree(PLAIN.readValue(document, Object.class));
        assertFalse(
                tree.at(JsonPointer.compile(pointer)).isMissingNode(),
                "pointer '" + pointer + "' must resolve in " + document);
    }
}
