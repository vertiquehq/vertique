// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.util.StdConverter;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that the JSON pointer resolver stops at every binding it cannot verify in mapper-only mode,
 * the mode a module without the route's field-name projection resolves in: no projection and no
 * document, so only members the mapper writes through a setter, a creator parameter or a record
 * component are named.
 *
 * <p>Members the mapper does not write, beans and properties whose binding Jackson reshapes, any-setter
 * and unwrapped members, scalar creators and field-only members stop the pointer. Sibling members that
 * bind through a setter or a record component resolve in the same rows, so a guard that truncates too
 * much fails as well as one that truncates too little. Each row's document is the one the mapper reads
 * for the violated value; the row checks that the fixture binds it, and resolution never sees it.
 *
 * <p>No validator or Vert.x context is required.
 */
class JakartaPathPointersBindingGuardsTest {

    // --- Fixtures ---

    /** A plain setter-bound bean. */
    public static class Child {
        private String name;
        private String note;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    /** The refined type some properties bind instead of {@link Child}. */
    public static class SpecialChild extends Child {}

    /** A property the mapper only writes out ({@code id}) beside a setter-bound one. */
    public static class ReadOnlyBean {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        private String id;

        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** A computed getter and a fieldless getter-only collection beside a setter-bound property. */
    public static class GetterOnlyBean {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getLabel() {
            return "computed";
        }

        public List<String> getTags() {
            return new ArrayList<>();
        }
    }

    /** A member the mapper ignores. */
    public static class IgnoredBean {
        @JsonIgnore
        public String secret;

        public String name;
    }

    /** A class-level ignore list naming a property that still has a setter. */
    @JsonIgnoreProperties({"secret"})
    public static class IgnoredByClass {
        private String secret;
        private String name;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** A bean the mapper reads from a JSON array. */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({"x", "y"})
    public static class Point {
        private String x;
        private String y;

        public String getX() {
            return x;
        }

        public void setX(String x) {
            this.x = x;
        }

        public String getY() {
            return y;
        }

        public void setY(String y) {
            this.y = y;
        }
    }

    /** An object-shaped bean that a property annotation or a config override reads from an array. */
    @JsonPropertyOrder({"left", "right"})
    public static class Pair {
        private String left;
        private String right;

        public String getLeft() {
            return left;
        }

        public void setLeft(String left) {
            this.left = left;
        }

        public String getRight() {
            return right;
        }

        public void setRight(String right) {
            this.right = right;
        }
    }

    /** Written as its amount and read through a delegating creator; {@code currency} is never read. */
    public static class Money {
        private final String amount;
        private String currency;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public Money(String amount) {
            this.amount = amount;
        }

        @JsonValue
        public String getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }
    }

    /** A default constructor beside a single-string constructor: read from an object or a string. */
    public static class Tag {
        private String name;

        public Tag() {}

        public Tag(String raw) {
            this.name = raw;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** Bound through a builder with {@code with} prefixed methods. */
    @JsonDeserialize(builder = Built.Builder.class)
    public static class Built {
        private final String name;

        private Built(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        /** The builder the mapper fills. */
        public static class Builder {
            private String name;

            public Builder withName(String name) {
                this.name = name;
                return this;
            }

            public Built build() {
                return new Built(name);
            }
        }
    }

    /** Bound through a builder with unprefixed methods, the shape a generated builder has. */
    @JsonDeserialize(builder = Generated.GeneratedBuilder.class)
    public static class Generated {
        private final String name;

        Generated(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        /** The builder the mapper fills. */
        @JsonPOJOBuilder(withPrefix = "")
        public static class GeneratedBuilder {
            private String name;

            public GeneratedBuilder name(String name) {
                this.name = name;
                return this;
            }

            public Generated build() {
                return new Generated(name);
            }
        }
    }

    /** Reads whatever it likes into a {@link Custom}. */
    public static class CustomDeserializer extends JsonDeserializer<Custom> {
        @Override
        public Custom deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            Custom custom = new Custom();
            custom.setX(node.path("x").asText(null));
            return custom;
        }
    }

    /** A bean bound by a class-level custom deserializer. */
    @JsonDeserialize(using = CustomDeserializer.class)
    public static class Custom {
        private String x;

        public String getX() {
            return x;
        }

        public void setX(String x) {
            this.x = x;
        }
    }

    /** Reads whatever it likes into a {@link Child}. */
    public static class ChildDeserializer extends JsonDeserializer<Child> {
        @Override
        public Child deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            Child child = new Child();
            child.setName(node.path("name").asText(null));
            return child;
        }
    }

    /** Builds a {@link Child} from the map the mapper reads. */
    public static class ChildConverter extends StdConverter<Map<String, Object>, Child> {
        @Override
        public Child convert(Map<String, Object> value) {
            Child child = new Child();
            child.setName((String) value.get("name"));
            return child;
        }
    }

    /** Rewrites every map key it reads. */
    public static class UpperCaseKeys extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, DeserializationContext context) {
            return key.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Collects every unknown key into a map field that also has a getter and a setter, so only the
     * any-setter itself tells that the mapper never binds a key named {@code extras} into it.
     */
    public static class Open {
        private String name;

        @JsonAnySetter
        private Map<String, Object> extras = new LinkedHashMap<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, Object> getExtras() {
            return extras;
        }

        public void setExtras(Map<String, Object> extras) {
            this.extras = extras;
        }
    }

    /** The bean other fixtures unwrap. */
    public record Address(String street, String city) {}

    /** Unwraps {@link Address} under the {@code addr_} prefix. */
    public static class Customer {
        private String name;

        @JsonUnwrapped(prefix = "addr_")
        private Address address;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }
    }

    /** Unwraps {@link Address} without a prefix. */
    public static class Resident {
        private String name;

        @JsonUnwrapped
        private Address address;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }
    }

    /** Bound through a public field. */
    public static class PublicFields {
        public String name;
    }

    /** Bound through a private field that has a getter and no setter. */
    public static class GetterField {
        private String name;

        public String getName() {
            return name;
        }
    }

    /** A single-component record. */
    public record Item(String name) {}

    /** A single-component record whose component is renamed. */
    public record Review(@JsonProperty("display_name") String name) {}

    /** Beans whose class decides how the mapper binds them. */
    public record ClassShapes(
            ReadOnlyBean readOnly,
            GetterOnlyBean getterOnly,
            IgnoredBean ignored,
            IgnoredByClass ignoredByClass,
            Point point,
            Money money,
            Tag tag,
            Built built,
            Generated generated,
            Custom custom,
            Open open,
            Customer customer,
            Resident resident,
            PublicFields publicFields,
            GetterField getterField,
            List<Item> items,
            JsonNode node) {}

    /** Properties whose own annotations decide how the mapper binds them. */
    public record PropertyShapes(
            @JsonIgnoreProperties({"name"}) Child partial,
            @JsonFormat(shape = JsonFormat.Shape.ARRAY) Pair pair,

            @JsonDeserialize(using = ChildDeserializer.class)
            Child viaUsing,

            @JsonDeserialize(contentUsing = ChildDeserializer.class)
            List<Child> viaContentUsing,

            @JsonDeserialize(converter = ChildConverter.class)
            Child converted,

            @JsonDeserialize(as = SpecialChild.class) Child refined,
            @JsonDeserialize(contentAs = SpecialChild.class) List<Child> refinedItems,
            @JsonDeserialize(keyUsing = UpperCaseKeys.class) Map<String, Child> byKey) {}

    /** Holds a {@link Pair} that a config override reads from an array. */
    public record PairHolder(Pair pair) {}

    /** A type the mapper never binds, by its own annotation. */
    @JsonIgnoreType
    public static class Secret {
        public String x;
    }

    /** A plain type; one mapper below never binds it, through a config override. */
    public static class Hidden {
        public String x;
    }

    /** Setter-bound members whose types the mapper may ignore, beside a plain member. */
    public static class IgnoredTypes {
        private Secret secret;
        private Hidden hidden;
        private String name;

        public Secret getSecret() {
            return secret;
        }

        public void setSecret(Secret secret) {
            this.secret = secret;
        }

        public Hidden getHidden() {
            return hidden;
        }

        public void setHidden(Hidden hidden) {
            this.hidden = hidden;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** The parent side of a managed and back reference pair. */
    public static class Parent {
        @JsonManagedReference
        private Kid kid;

        public Kid getKid() {
            return kid;
        }

        public void setKid(Kid kid) {
            this.kid = kid;
        }
    }

    /** The child side: the mapper sets {@code parent} to the holder and never reads it from input. */
    public static class Kid {
        private String name;

        @JsonBackReference
        private Parent parent;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Parent getParent() {
            return parent;
        }

        public void setParent(Parent parent) {
            this.parent = parent;
        }
    }

    /** A type-property base whose listed subtype binds the inherited member under another name. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(@JsonSubTypes.Type(value = Animal.Dog.class, name = "dog"))
    public abstract static class Animal {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        /** The listed subtype; it renames the member it inherits. */
        public static class Dog extends Animal {
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

    /** Holds a type-property value. */
    public static class AnimalHolder {
        private Animal pet;

        public Animal getPet() {
            return pet;
        }

        public void setPet(Animal pet) {
            this.pet = pet;
        }
    }

    /** A payload member bound through a private field with a getter and no setter. */
    public static class CommentPayload {
        private String comment;

        public String getComment() {
            return comment;
        }
    }

    // --- Mappers ---

    /** The process mapper, which a module without a projection names with. */
    private static final ObjectMapper PROCESS = VertiqueJson.mapper();

    /** Reads {@link Pair} from a JSON array through a config override instead of an annotation. */
    private static final ObjectMapper PAIR_AS_ARRAY = JsonMapper.builder()
            .withConfigOverride(Pair.class, o -> o.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.ARRAY)))
            .build();

    /** Ignores undeclared members. */
    private static final ObjectMapper TOLERANT = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Never binds {@link Hidden}, through a config override; ignores undeclared members. */
    private static final ObjectMapper HIDES_TYPE = JsonMapper.builder()
            .withConfigOverride(Hidden.class, o -> o.setIsIgnoredType(true))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Reads a single value where any collection is declared, through a config override. */
    private static final ObjectMapper SINGLE_VALUES_FOR_COLLECTIONS = JsonMapper.builder()
            .withConfigOverride(
                    Collection.class,
                    o -> o.setFormat(
                            JsonFormat.Value.empty().withFeature(JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)))
            .build();

    /** Reads a one-element array where a single value is declared. */
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

    private static Opaque opaque() {
        return new Opaque();
    }

    static Stream<Arguments> reshapingRows() {
        Class<ClassShapes> c = ClassShapes.class;
        Class<PropertyShapes> p = PropertyShapes.class;
        return Stream.of(
                // --- members the mapper does not write stop at their bean ---
                arguments(
                        "read-only property stops at its bean",
                        PROCESS,
                        c,
                        path(property("readOnly", c), property("id", ReadOnlyBean.class)),
                        "{\"readOnly\":{\"id\":\"x\"}}",
                        "/readOnly"),
                arguments(
                        "setter-bound sibling of a read-only property resolves",
                        PROCESS,
                        c,
                        path(property("readOnly", c), property("name", ReadOnlyBean.class)),
                        "{\"readOnly\":{\"name\":\"\"}}",
                        "/readOnly/name"),
                arguments(
                        "computed getter-only property stops at its bean",
                        PROCESS,
                        c,
                        path(property("getterOnly", c), property("label", GetterOnlyBean.class)),
                        "{\"getterOnly\":{\"name\":\"n\"}}",
                        "/getterOnly"),
                arguments(
                        "fieldless getter-only collection stops at its bean",
                        PROCESS,
                        c,
                        path(property("getterOnly", c), property("tags", GetterOnlyBean.class), index(0)),
                        "{\"getterOnly\":{\"tags\":[\"x\"]}}",
                        "/getterOnly"),
                arguments(
                        "ignored property stops at its bean",
                        PROCESS,
                        c,
                        path(property("ignored", c), property("secret", IgnoredBean.class)),
                        "{\"ignored\":{\"secret\":\"x\"}}",
                        "/ignored"),
                arguments(
                        "name ignored by its class stops at its bean although it has a setter",
                        PROCESS,
                        c,
                        path(property("ignoredByClass", c), property("secret", IgnoredByClass.class)),
                        "{\"ignoredByClass\":{\"secret\":\"x\"}}",
                        "/ignoredByClass"),
                arguments(
                        "sibling of a name ignored by its class resolves",
                        PROCESS,
                        c,
                        path(property("ignoredByClass", c), property("name", IgnoredByClass.class)),
                        "{\"ignoredByClass\":{\"name\":\"\"}}",
                        "/ignoredByClass/name"),
                arguments(
                        "name ignored by the holding property stops at that property",
                        PROCESS,
                        p,
                        path(property("partial", p), property("name", Child.class)),
                        "{\"partial\":{\"name\":\"x\"}}",
                        "/partial"),
                arguments(
                        "name the holding property does not ignore resolves",
                        PROCESS,
                        p,
                        path(property("partial", p), property("note", Child.class)),
                        "{\"partial\":{\"note\":\"\"}}",
                        "/partial/note"),
                // --- reshaped beans stop at the property holding them ---
                arguments(
                        "array-shaped class stops at the property holding it",
                        PROCESS,
                        c,
                        path(property("point", c), property("x", Point.class)),
                        "{\"point\":[\"\",\"y\"]}",
                        "/point"),
                arguments(
                        "array-shaped class at the root stops at the root",
                        PROCESS,
                        Point.class,
                        path(property("x", Point.class)),
                        "[\"\",\"y\"]",
                        ""),
                arguments(
                        "array-shaped property stops at the property",
                        PROCESS,
                        p,
                        path(property("pair", p), property("left", Pair.class)),
                        "{\"pair\":[\"\",\"r\"]}",
                        "/pair"),
                arguments(
                        "array shape from a config override stops at the property",
                        PAIR_AS_ARRAY,
                        PairHolder.class,
                        path(property("pair", PairHolder.class), property("left", Pair.class)),
                        "{\"pair\":[\"\",\"r\"]}",
                        "/pair"),
                arguments(
                        "delegating creator stops at the property holding the bean",
                        PROCESS,
                        c,
                        path(property("money", c), property("currency", Money.class)),
                        "{\"money\":\"10\"}",
                        "/money"),
                arguments(
                        "scalar creator stops at the property without a document",
                        PROCESS,
                        c,
                        path(property("tag", c), property("name", Tag.class)),
                        "{\"tag\":{\"name\":\"\"}}",
                        "/tag"),
                arguments(
                        "builder-bound bean stops at the property holding it",
                        PROCESS,
                        c,
                        path(property("built", c), property("name", Built.class)),
                        "{\"built\":{\"name\":\"\"}}",
                        "/built"),
                arguments(
                        "builder-bound root stops at the root",
                        PROCESS,
                        Built.class,
                        path(property("name", Built.class)),
                        "{\"name\":\"\"}",
                        ""),
                arguments(
                        "bean bound through a builder without method prefixes stops at the property holding it",
                        PROCESS,
                        c,
                        path(property("generated", c), property("name", Generated.class)),
                        "{\"generated\":{\"name\":\"\"}}",
                        "/generated"),
                arguments(
                        "class-level custom deserializer stops at the property holding the bean",
                        PROCESS,
                        c,
                        path(property("custom", c), property("x", Custom.class)),
                        "{\"custom\":{\"x\":\"\"}}",
                        "/custom"),
                // --- property-level deserialization annotations stop at the property ---
                arguments(
                        "property-level custom deserializer stops at the property",
                        PROCESS,
                        p,
                        path(property("viaUsing", p), property("name", Child.class)),
                        "{\"viaUsing\":{\"name\":\"\"}}",
                        "/viaUsing"),
                arguments(
                        "content deserializer stops at the collection property",
                        PROCESS,
                        p,
                        path(property("viaContentUsing", p), index(0), property("name", Child.class)),
                        "{\"viaContentUsing\":[{\"name\":\"\"}]}",
                        "/viaContentUsing"),
                arguments(
                        "converter stops at the property",
                        PROCESS,
                        p,
                        path(property("converted", p), property("name", Child.class)),
                        "{\"converted\":{\"name\":\"\"}}",
                        "/converted"),
                arguments(
                        "refined bound type stops at the property",
                        PROCESS,
                        p,
                        path(property("refined", p), property("name", SpecialChild.class)),
                        "{\"refined\":{\"name\":\"\"}}",
                        "/refined"),
                arguments(
                        "refined content type stops at the collection property",
                        PROCESS,
                        p,
                        path(property("refinedItems", p), index(0), property("name", SpecialChild.class)),
                        "{\"refinedItems\":[{\"name\":\"\"}]}",
                        "/refinedItems"),
                arguments(
                        "key deserializer leaves the map boundary where it is",
                        PROCESS,
                        p,
                        path(property("byKey", p), opaque(), property("name", Child.class)),
                        "{\"byKey\":{\"ZZKEY\":{\"name\":\"\"}}}",
                        "/byKey"),
                // --- collected and promoted members stop at the enclosing bean ---
                arguments(
                        "any-setter backing member stops at the bean",
                        PROCESS,
                        c,
                        path(property("open", c), property("extras", Open.class)),
                        "{\"open\":{\"ZZKEY\":\"x\"}}",
                        "/open"),
                arguments(
                        "entry of an any-setter backing member stops at the bean",
                        PROCESS,
                        c,
                        path(property("open", c), property("extras", Open.class), opaque()),
                        "{\"open\":{\"ZZKEY\":\"x\"}}",
                        "/open"),
                arguments(
                        "setter-bound sibling of an any-setter resolves",
                        PROCESS,
                        c,
                        path(property("open", c), property("name", Open.class)),
                        "{\"open\":{\"name\":\"\"}}",
                        "/open/name"),
                arguments(
                        "member unwrapped with a prefix stops at the enclosing bean",
                        PROCESS,
                        c,
                        path(
                                property("customer", c),
                                property("address", Customer.class),
                                property("street", Address.class)),
                        "{\"customer\":{\"addr_street\":\"\"}}",
                        "/customer"),
                arguments(
                        "member unwrapped with a prefix into the root stops at the root",
                        PROCESS,
                        Customer.class,
                        path(property("address", Customer.class), property("street", Address.class)),
                        "{\"addr_street\":\"\"}",
                        ""),
                arguments(
                        "member unwrapped without a prefix stops at the enclosing bean",
                        PROCESS,
                        c,
                        path(
                                property("resident", c),
                                property("address", Resident.class),
                                property("street", Address.class)),
                        "{\"resident\":{\"street\":\"\"}}",
                        "/resident"),
                arguments(
                        "setter-bound sibling of an unwrapped member resolves",
                        PROCESS,
                        c,
                        path(property("customer", c), property("name", Customer.class)),
                        "{\"customer\":{\"name\":\"\"}}",
                        "/customer/name"),
                arguments(
                        "a JsonNode property is never descended",
                        PROCESS,
                        c,
                        path(property("node", c), property("nodeType", null)),
                        "{\"node\":{\"nodeType\":\"\"}}",
                        "/node"),
                // --- field-only members stop at their bean; creator-bound members resolve ---
                arguments(
                        "public field stops at its bean",
                        PROCESS,
                        c,
                        path(property("publicFields", c), property("name", PublicFields.class)),
                        "{\"publicFields\":{\"name\":\"\"}}",
                        "/publicFields"),
                arguments(
                        "private field with a getter stops at its bean",
                        PROCESS,
                        c,
                        path(property("getterField", c), property("name", GetterField.class)),
                        "{\"getterField\":{\"name\":\"\"}}",
                        "/getterField"),
                arguments(
                        "single-component record in a list resolves",
                        PROCESS,
                        c,
                        path(property("items", c), index(0), property("name", Item.class)),
                        "{\"items\":[{\"name\":\"\"}]}",
                        "/items/0/name"),
                arguments(
                        "renamed single-component record resolves",
                        PROCESS,
                        Review.class,
                        path(property("name", Review.class)),
                        "{\"display_name\":\"\"}",
                        "/display_name"),
                // --- members of a type the mapper never binds, and back references, stop at their bean ---
                arguments(
                        "member whose type is ignored by its annotation stops at its bean",
                        TOLERANT,
                        IgnoredTypes.class,
                        path(property("secret", IgnoredTypes.class)),
                        "{\"secret\":{\"x\":\"v\"}}",
                        ""),
                arguments(
                        "member below a type ignored by its annotation stops at the holding bean",
                        TOLERANT,
                        IgnoredTypes.class,
                        path(property("secret", IgnoredTypes.class), property("x", Secret.class)),
                        "{\"secret\":{\"x\":\"v\"}}",
                        ""),
                arguments(
                        "member whose type a config override ignores stops at its bean",
                        HIDES_TYPE,
                        IgnoredTypes.class,
                        path(property("hidden", IgnoredTypes.class)),
                        "{\"hidden\":{\"x\":\"v\"}}",
                        ""),
                arguments(
                        "setter-bound sibling of members of ignored types resolves",
                        HIDES_TYPE,
                        IgnoredTypes.class,
                        path(property("name", IgnoredTypes.class)),
                        "{\"name\":\"\"}",
                        "/name"),
                arguments(
                        "back reference stops at its bean",
                        TOLERANT,
                        Parent.class,
                        path(property("kid", Parent.class), property("parent", Kid.class)),
                        "{\"kid\":{\"name\":\"a\",\"parent\":{}}}",
                        "/kid"),
                arguments(
                        "setter-bound sibling of a back reference resolves",
                        TOLERANT,
                        Parent.class,
                        path(property("kid", Parent.class), property("name", Kid.class)),
                        "{\"kid\":{\"name\":\"\"}}",
                        "/kid/name"),
                // --- positions the mapper may move without a document to check them against ---
                arguments(
                        "single values accepted for every collection by a config override stop at the list",
                        SINGLE_VALUES_FOR_COLLECTIONS,
                        c,
                        path(property("items", c), index(0), property("name", Item.class)),
                        "{\"items\":{\"name\":\"\"}}",
                        "/items"),
                arguments(
                        "root bean read from a one-element array stops at the root",
                        UNWRAPS_SINGLE_VALUE_ARRAYS,
                        Child.class,
                        path(property("name", Child.class)),
                        "[{\"name\":\"\"}]",
                        ""),
                arguments(
                        "nested bean under a root that may be read from a one-element array stops at the root",
                        UNWRAPS_SINGLE_VALUE_ARRAYS,
                        PairHolder.class,
                        path(property("pair", PairHolder.class), property("left", Pair.class)),
                        "{\"pair\":[{\"left\":\"\"}]}",
                        ""),
                // --- flat type information: the recorded runtime class decides; without one, stop ---
                arguments(
                        "type property without a recorded runtime class stops at the property",
                        PROCESS,
                        AnimalHolder.class,
                        path(property("pet", AnimalHolder.class), property("name", null)),
                        "{\"pet\":{\"type\":\"dog\",\"dog_name\":\"\"}}",
                        "/pet"),
                arguments(
                        "type property with the recorded runtime class renaming an inherited member resolves",
                        PROCESS,
                        AnimalHolder.class,
                        path(property("pet", AnimalHolder.class), property("name", Animal.Dog.class)),
                        "{\"pet\":{\"type\":\"dog\",\"dog_name\":\"\"}}",
                        "/pet/dog_name"),
                arguments(
                        "plain type without a recorded runtime class resolves",
                        PROCESS,
                        PairHolder.class,
                        path(property("pair", PairHolder.class), property("left", null)),
                        "{\"pair\":{\"left\":\"\"}}",
                        "/pair/left"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reshapingRows")
    @DisplayName("bindings the resolver cannot verify truncate the pointer without a projection")
    void reshapingBindingsTruncateWithoutAProjection(
            String shape, ObjectMapper mapper, Type rootType, PropertyPath path, String document, String expected) {
        // Given: a mapper-only session and the document the mapper reads for the violated value
        JakartaPathPointers.Resolver session = JakartaPathPointers.resolver(mapper, null);

        // When: the structured path resolves without a projection and without a document
        String pointer = session.toPointer(path, rootType, null);

        // Then: the fixture binds the document, and the pointer is exactly the expected verified prefix
        assertAll(
                () -> assertDoesNotThrow(
                        () -> mapper.readValue(document, mapper.constructType(rootType)),
                        "the row's document must bind with the row's mapper"),
                () -> assertEquals(expected, pointer, "pointer for '" + shape + "'"),
                () -> assertFalse(pointer.contains("ZZ"), "a client-chosen key reached the pointer"));
    }

    @Test
    @DisplayName("the path-string entry point stops at a value with type information and names a plain bean's member")
    void thePathStringEntryPointStopsAtAValueWithTypeInformation() {
        // Given: a type-property value whose subtype renames the member it inherits, and a plain bean; a path
        // string records no runtime class
        String document = "{\"pet\":{\"type\":\"dog\",\"dog_name\":\"\"}}";

        // When: their violation path strings are converted with the process mapper
        String polymorphic = JakartaPathPointers.toPointer("pet.name", AnimalHolder.class, PROCESS);
        String plain = JakartaPathPointers.toPointer("pair.left", PairHolder.class, PROCESS);

        // Then: the value with type information stops at its property; the plain bean's member resolves
        assertAll(
                () -> assertDoesNotThrow(
                        () -> PROCESS.readValue(document, AnimalHolder.class),
                        "the document must bind with the process mapper"),
                () -> assertEquals("/pet", polymorphic, "type-property value without a runtime class"),
                () -> assertEquals("/pair/left", plain, "plain bean member"));
    }

    @Test
    @DisplayName("the path-string entry point stops at a field-only member and names a record component")
    void thePathStringEntryPointStopsAtAFieldOnlyMember() {
        // Given: a payload member bound through a private field with a getter, and a list of records

        // When: their violation path strings are converted with the process mapper
        String fieldOnly = JakartaPathPointers.toPointer("comment", CommentPayload.class, PROCESS);
        String recordComponent = JakartaPathPointers.toPointer("items[0].name", ClassShapes.class, PROCESS);

        // Then: the field-only member stops at the root and the record component resolves
        assertAll(
                () -> assertEquals("", fieldOnly, "field-only payload member"),
                () -> assertEquals("/items/0/name", recordComponent, "record component in a list"));
    }
}
