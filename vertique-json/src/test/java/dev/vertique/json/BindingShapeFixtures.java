// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.util.StdConverter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bean shapes whose binding Jackson reshapes, shared by the field-name projection characterization
 * and the pointer guard tests of this package.
 *
 * <p>{@link ClassShapes} holds beans whose class decides the binding; {@link PropertyShapes} holds
 * properties whose own annotations decide it. Every fixture binds the documents the guard tests send.
 */
final class BindingShapeFixtures {

    private BindingShapeFixtures() {}

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
}
