// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedClassResolver;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.JsonMapperProfiles;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchema;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import io.vertx.json.schema.Validator;
import jakarta.validation.constraints.Size;
import jakarta.xml.bind.annotation.XmlAccessorType;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link AnnotationJsonSchemaGenerator#hiddenOnlyMembers(Type)} reports every member, and every type,
 * that an input- or output-direction generator describes for a type while it carries {@link Hidden}
 * without {@code @Schema(hidden = true)}, the only hiding marker the generators honor. Each is a
 * {@link HiddenOnlyMember} naming its declaring type and member ({@code null} for the type itself),
 * reported once however often it is reached, in an unmodifiable list ordered by declaring type and
 * then member with the type-level entry first. A member the generator does not describe, because
 * {@code @Schema(hidden = true)} hides it or Jackson does not bind or serialize it, is not reported. A
 * {@code @JsonUnwrapped} member is described through its flattened content, and reported under its own
 * name. Only a victools-defaults generator refuses, before the type grammar is checked. The call keeps
 * {@code generateCanonical}'s output, type grammar, bounded failure contract, restore-on-failure
 * behavior, and per-instance lock, and the gate's engine still enforces a {@code @Hidden}-only
 * member's constraint.
 *
 * <p>A shape matrix closes the report over the shapes a document can describe a member or type
 * through — records, creators, builders, subtypes named or registered, abstract member types, enum
 * constants, unwrapped content, containers at any depth, generics, inheritance, overriding getters,
 * any-setters, annotation bundles, mix-ins, and single-accessor properties — in each direction: every
 * row's report equals exactly the {@code @Hidden}-only carriers of its fixture types that the row's own
 * document describes, as an oracle reading the document, the fixture types, and the profile mapper's
 * mix-in registrations independently of the generator computes them. Two further oracles, neither
 * sharing that one's carrier model, require every row's report to cover each described property that
 * swagger-core's model resolver, on the row profile's mapper, omits because of {@code @Hidden} alone,
 * and each described property and type that the profile mapper's merged annotation view sees as
 * carrying {@code @Hidden} and not {@code @Schema(hidden = true)}.
 */
class HiddenOnlyMemberReportTest {

    /** Bounded wait for an event the lock-ordering row requires; a timeout fails the row. */
    private static final long AWAIT_SECONDS = 10L;

    private static final JsonMapperProfile PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-only-member-report-test"), new ObjectMapper());

    /** A profile whose mapper registers {@link RegisteredSubtypeShape.PremiumAccount} as a subtype by name. */
    private static final JsonMapperProfile REGISTERED_SUBTYPE_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-registered-subtype"), registeredSubtypeMapper());

    /** A profile whose mapper registers {@link MixInShape.RootMixIn} as the mix-in of {@link MixInShape.Root}. */
    private static final JsonMapperProfile MIX_IN_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-only-member-report-test-mix-in"), mixInMapper());

    /** A profile whose mapper registers {@link MixInTypeShape.StowedMixIn} for {@link MixInTypeShape.Stowed}. */
    private static final JsonMapperProfile MIX_IN_TYPE_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-only-member-report-test-mix-in-type"), mixInTypeMapper());

    /** A profile whose mapper registers {@link MixInRootShape.RootMixIn} for {@link MixInRootShape.Root}. */
    private static final JsonMapperProfile MIX_IN_ROOT_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-only-member-report-test-mix-in-root"), mixInRootMapper());

    /** A profile whose mapper registers a mix-in for the superclass {@link MixInSuperclassShape.Parent}. */
    private static final JsonMapperProfile MIX_IN_SUPERCLASS_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-mix-in-superclass"), mixInSuperclassMapper());

    /** A profile whose mapper registers a mix-in for the setter-bound {@link SetterBoundMixInShape.Panel}. */
    private static final JsonMapperProfile SETTER_BOUND_MIX_IN_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-setter-bound-mix-in"), setterBoundMixInMapper());

    /**
     * A profile declaring a schema type override, in both directions, for {@link OverriddenBaseShape.Buoy}:
     * the fragment's own property name is none of the shape's, so a document the override shaped would
     * name it instead of the base's content.
     */
    private static final JsonMapperProfile OVERRIDDEN_BASE_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-overridden-base"),
            new ObjectMapper(),
            List.of(JsonSchemaTypeOverride.both(
                    OverriddenBaseShape.Buoy.class,
                    JsonSchemaFragment.parse("{\"type\":\"object\","
                            + "\"properties\":{\"buoyOverride\":{\"type\":\"string\"}},"
                            + "\"additionalProperties\":false}"))));

    /** A profile whose mapper registers mix-ins inheriting {@code @Hidden} from their own superclasses. */
    private static final JsonMapperProfile MIX_IN_ANCESTOR_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-mix-in-ancestor"), mixInAncestorMapper());

    /** A profile whose mapper registers a mix-in for {@link InheritedMixInMethodShape.Ancestor}. */
    private static final JsonMapperProfile INHERITED_MIX_IN_METHOD_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-inherited-mix-in-method"), inheritedMixInMethodMapper());

    /** A profile whose mapper registers {@link EnumMixInShape.TierMixIn} for {@link EnumMixInShape.Tier}. */
    private static final JsonMapperProfile ENUM_MIX_IN_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-only-member-report-test-enum-mix-in"), enumMixInMapper());

    /**
     * A profile whose mapper registers {@link MixInSchemaSetterShape.RootMixIn} as the mix-in of {@link
     * MixInSchemaSetterShape.Root}.
     */
    private static final JsonMapperProfile SETTER_SCHEMA_MIX_IN_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-setter-schema-mix-in"), setterSchemaMixInMapper());

    /**
     * A profile whose mapper registers {@link SubclassMixInHiddenShape.RootMixIn} as the mix-in of {@link
     * SubclassMixInHiddenShape.Root}.
     */
    private static final JsonMapperProfile SUBCLASS_MIX_IN_HIDDEN_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-subclass-mix-in-hidden"), subclassMixInHiddenMapper());

    /**
     * A profile whose mapper registers {@link SubclassMixInSchemaShape.RootMixIn} as the mix-in of {@link
     * SubclassMixInSchemaShape.Root}.
     */
    private static final JsonMapperProfile SUBCLASS_MIX_IN_SCHEMA_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-only-member-report-test-subclass-mix-in-schema"), subclassMixInSchemaMapper());

    // ---------------------------------------------------------------- body and response fixtures

    /**
     * A type carrying {@code @Hidden} alone: the type itself is reported, and so is its own
     * {@code @Hidden} member {@code flag}; {@code value} carries nothing and is not reported.
     */
    @Hidden
    static final class HiddenType {
        public String value;

        @Hidden
        public String flag;
    }

    /**
     * A case-sensitively bound request body without an any-setter. {@code @Hidden} alone sits on a
     * constrained public field ({@code debug}), on the getter of a setter-bound property
     * ({@code override}, reported under the getter's own name), and, through {@code nested}, on a
     * type and one of its members.
     */
    static final class HiddenOnlyBody {
        public String name;

        @Hidden
        @Size(max = 3)
        public String debug;

        private String override;

        public HiddenType nested;

        @Hidden
        public String getOverride() {
            return override;
        }

        public void setOverride(String override) {
            this.override = override;
        }
    }

    /** What an input-direction generator reports for {@link HiddenOnlyBody}, in the report's order. */
    private static final List<HiddenOnlyMember> BODY_REPORT = List.of(
            hidden(HiddenOnlyBody.class, "debug"),
            hidden(HiddenOnlyBody.class, "getOverride"),
            hidden(HiddenType.class, null),
            hidden(HiddenType.class, "flag"));

    /**
     * A response DTO: {@code debug} carries {@code @Hidden} alone, so it is published and reported;
     * {@code both} also carries {@code @Schema(hidden = true)}, so the output generator leaves it out
     * and it is not reported; Jackson never serializes {@code ignored}, so it is neither published nor
     * reported; {@code nested} reaches {@link HiddenType}.
     */
    static final class HiddenOnlyResponse {
        public String name;

        @Hidden
        public String debug;

        @Hidden
        @Schema(hidden = true)
        public String both;

        @JsonIgnore
        @Hidden
        public String ignored;

        public HiddenType nested;
    }

    /** What an output-direction generator reports for {@link HiddenOnlyResponse}, in the report's order. */
    private static final List<HiddenOnlyMember> RESPONSE_REPORT = List.of(
            hidden(HiddenOnlyResponse.class, "debug"),
            hidden(HiddenType.class, null),
            hidden(HiddenType.class, "flag"));

    /**
     * A private field serialized through its getter, which alone carries {@code @Hidden}: the output
     * generator describes the property, and the getter is reported under its own name.
     */
    static final class GetterHiddenResponse {
        private String secret;

        @Hidden
        public String getSecret() {
            return secret;
        }
    }

    // ---------------------------------------------------------------- unwrapped-member fixtures

    /** The content an unwrapped member flattens into its parent; nothing here carries {@code @Hidden}. */
    static final class Address {
        public String street;
        public String city;
    }

    /**
     * A public field carrying {@code @Hidden} alone beside {@code @JsonUnwrapped}: both directions
     * describe its flattened content, {@code street} and {@code city}, so the field is reported under
     * its own name although no property carries that name.
     */
    static final class HiddenUnwrappedField {
        public String name;

        @JsonUnwrapped
        @Hidden
        public Address address;
    }

    /**
     * The getter of a setter-bound property carrying {@code @Hidden} alone beside {@code
     * @JsonUnwrapped}: both directions describe the flattened content, and the getter is reported under
     * its own name.
     */
    static final class HiddenUnwrappedGetter {
        public String name;

        private Address address;

        @JsonUnwrapped
        @Hidden
        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }
    }

    /** What either direction reports for {@link HiddenUnwrappedField}: the unwrapped field. */
    private static final List<HiddenOnlyMember> UNWRAPPED_FIELD_REPORT =
            List.of(hidden(HiddenUnwrappedField.class, "address"));

    /** What either direction reports for {@link HiddenUnwrappedGetter}: the unwrapped getter. */
    private static final List<HiddenOnlyMember> UNWRAPPED_GETTER_REPORT =
            List.of(hidden(HiddenUnwrappedGetter.class, "getAddress"));

    /** What either direction describes for each unwrapped root: the flattened content, no {@code address}. */
    private static final Set<String> UNWRAPPED_PROPERTIES = Set.of("name", "street", "city");

    // ---------------------------------------------------------------- nothing-to-report fixtures

    /** {@code @Hidden} beside {@code @Schema(hidden = true)} on one public field: the generator hides it. */
    static final class BothMarkers {
        @Hidden
        @Schema(hidden = true)
        public String secret;
    }

    /** {@code @Schema(hidden = true)} alone: the generator hides the field, and nothing carries {@code @Hidden}. */
    static final class SchemaHiddenOnly {
        @Schema(hidden = true)
        public String secret;
    }

    /**
     * {@code @Hidden} on the field and {@code @Schema(hidden = true)} on its getter, with no setter, so
     * Jackson binds the field; the generator reads the field and its getter together and hides the
     * property.
     */
    static final class SplitMarkers {
        @Hidden
        public String secret;

        @Schema(hidden = true)
        public String getSecret() {
            return secret;
        }
    }

    /** {@code @JsonIgnore} keeps Jackson from binding the field, so the generator never describes it. */
    static final class IgnoredAndHidden {
        @JsonIgnore
        @Hidden
        public String secret;
    }

    /** Nothing carries {@code @Hidden}. */
    static final class PlainBody {
        public String name;
    }

    /** A type carrying {@code @Schema(hidden = true)} beside {@code @Hidden}, so it is not hidden only by {@code @Hidden}. */
    @Hidden
    @Schema(hidden = true)
    static final class DoublyHiddenType {
        public String value;
    }

    /** Reaches {@link DoublyHiddenType} through a member. */
    static final class HidesTypeTwice {
        public DoublyHiddenType inner;
    }

    // ---------------------------------------------------------------- reachability fixtures

    /** Declares {@code @Hidden} alone on {@code cost}; {@link Order} reaches it twice. */
    static final class Line {
        @Hidden
        public String cost;
    }

    /** Declares {@code @Hidden} alone on {@code author}; {@link Order} reaches it only as a map value. */
    static final class Note {
        @Hidden
        public String author;
    }

    /**
     * The root: its own {@code @Hidden} {@code trace}; {@link Note} reached only as a map value and
     * declared first, so the order in which a walk first meets the members differs from the report's
     * order; and {@link Line} reached through a member and through a list element.
     */
    static final class Order {
        public Map<String, Note> notes;

        @Hidden
        public String trace;

        public Line primary;
        public List<Line> lines;
    }

    /** What an input-direction generator reports for {@link Order}: {@link Note} is reached as a map value. */
    private static final List<HiddenOnlyMember> INPUT_ORDER_REPORT =
            List.of(hidden(Line.class, "cost"), hidden(Note.class, "author"), hidden(Order.class, "trace"));

    /**
     * What an output-direction generator reports for {@link Order}: its schema describes a map member
     * without describing the map's values, so {@link Note} is not reached.
     */
    private static final List<HiddenOnlyMember> OUTPUT_ORDER_REPORT =
            List.of(hidden(Line.class, "cost"), hidden(Order.class, "trace"));

    /**
     * A generic type the generator describes once per type argument it is reached under, while its one
     * hidden-only member stays the same member each time.
     */
    static final class Box<T> {
        @Hidden
        public T value;
    }

    /** Reaches {@link Box} as {@code Box<String>} and as {@code Box<Integer>}. */
    static final class Boxes {
        public Box<String> ofString;
        public Box<Integer> ofInteger;
    }

    /** What either direction reports for {@link Boxes}: the one member, once. */
    private static final List<HiddenOnlyMember> BOXES_REPORT = List.of(hidden(Box.class, "value"));

    // ---------------------------------------------------------------- lock-ordering fixture

    /**
     * Generated by the caller the lock-ordering row holds inside the generator's lock: its one member
     * carries {@code @Hidden} alone, so a report recording while that generation describes it would
     * take it.
     */
    static final class HeldHidden {
        @Hidden
        public String held;
    }

    // ---------------------------------------------------------------- shape-matrix fixtures
    //
    // Each shape's fixture types are nested in one holder, whose nested types are all the matrix's
    // oracle reads carriers from. Property names are unique within a holder, so a property name a
    // document describes identifies its carriers.

    /** A record component carrying {@code @Hidden} alone, and a record type carrying it reached through a component. */
    static final class RecordShape {
        record Root(@Hidden String recordSecret, String recordName, Detail recordDetail) {}

        @Hidden
        record Detail(@Hidden String detailSecret, String detailName) {}
    }

    /** What either direction reports for {@link RecordShape}: a component once, under its own name. */
    private static final List<HiddenOnlyMember> RECORD_REPORT = List.of(
            hidden(RecordShape.Detail.class, null),
            hidden(RecordShape.Detail.class, "detailSecret"),
            hidden(RecordShape.Root.class, "recordSecret"));

    /** Properties bound through {@code @JsonCreator} parameters, {@code @Hidden} alone on a field and on a getter. */
    static final class CreatorShape {
        static final class Root {
            @Hidden
            private final String creatorSecret;

            private final String creatorToken;
            private final String creatorName;

            @JsonCreator
            Root(
                    @JsonProperty("creatorSecret") String creatorSecret,
                    @JsonProperty("creatorToken") String creatorToken,
                    @JsonProperty("creatorName") String creatorName) {
                this.creatorSecret = creatorSecret;
                this.creatorToken = creatorToken;
                this.creatorName = creatorName;
            }

            public String getCreatorSecret() {
                return creatorSecret;
            }

            @Hidden
            public String getCreatorToken() {
                return creatorToken;
            }

            public String getCreatorName() {
                return creatorName;
            }
        }
    }

    /** What either direction reports for {@link CreatorShape}. */
    private static final List<HiddenOnlyMember> CREATOR_REPORT = List.of(
            hidden(CreatorShape.Root.class, "creatorSecret"), hidden(CreatorShape.Root.class, "getCreatorToken"));

    /**
     * A type bound through a builder: {@code @Hidden} alone on a builder method, which binds only on
     * input, and on a field of the built type, which both directions describe.
     */
    static final class BuilderShape {
        @JsonDeserialize(builder = Root.Builder.class)
        static final class Root {
            private final String builderSecret;

            @Hidden
            private final String builderOther;

            Root(String builderSecret, String builderOther) {
                this.builderSecret = builderSecret;
                this.builderOther = builderOther;
            }

            public String getBuilderSecret() {
                return builderSecret;
            }

            public String getBuilderOther() {
                return builderOther;
            }

            @JsonPOJOBuilder(withPrefix = "with")
            static final class Builder {
                private String builderSecret;
                private String builderOther;

                @Hidden
                public Builder withBuilderSecret(String value) {
                    builderSecret = value;
                    return this;
                }

                public Builder withBuilderOther(String value) {
                    builderOther = value;
                    return this;
                }

                public Root build() {
                    return new Root(builderSecret, builderOther);
                }
            }
        }
    }

    /** What an input-direction generator reports for {@link BuilderShape}: the builder method too. */
    private static final List<HiddenOnlyMember> BUILDER_INPUT_REPORT = List.of(
            hidden(BuilderShape.Root.class, "builderOther"),
            hidden(BuilderShape.Root.Builder.class, "withBuilderSecret"));

    /** What an output-direction generator reports for {@link BuilderShape}: the built type's field. */
    private static final List<HiddenOnlyMember> BUILDER_OUTPUT_REPORT =
            List.of(hidden(BuilderShape.Root.class, "builderOther"));

    /** Subtypes named by {@code @JsonSubTypes}: a base member, a subtype, and a subtype's member carry {@code @Hidden}. */
    static final class SubtypeShape {
        static final class Root {
            public Animal animal;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({
            @JsonSubTypes.Type(value = Cat.class, name = "cat"),
            @JsonSubTypes.Type(value = Dog.class, name = "dog")
        })
        abstract static class Animal {
            @Hidden
            public String animalTag;

            public String animalName;
        }

        @Hidden
        static final class Cat extends Animal {
            public String purr;
        }

        static final class Dog extends Animal {
            @Hidden
            public String bark;
        }
    }

    /** What either direction reports for {@link SubtypeShape}. */
    private static final List<HiddenOnlyMember> SUBTYPE_REPORT = List.of(
            hidden(SubtypeShape.Animal.class, "animalTag"),
            hidden(SubtypeShape.Cat.class, null),
            hidden(SubtypeShape.Dog.class, "bark"));

    /**
     * A polymorphic base carrying only a type-level {@code @Hidden}, reached through a member and through
     * a list element; the document describes it through its one subtype.
     */
    static final class PolymorphicBaseShape {
        static final class Root {
            public Vehicle vehicle;
            public List<Vehicle> fleet;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = Car.class, name = "car")})
        @Hidden
        abstract static class Vehicle {
            public String vehicleMake;
        }

        static final class Car extends Vehicle {
            public String carDoors;
        }
    }

    /** What either direction reports for {@link PolymorphicBaseShape}: the base type. */
    private static final List<HiddenOnlyMember> POLYMORPHIC_BASE_REPORT =
            List.of(hidden(PolymorphicBaseShape.Vehicle.class, null));

    /**
     * A polymorphic base whose subtype the profile mapper registers by name, not {@code @JsonSubTypes}:
     * the document describes the base's members, and describes the registered subtype not at all.
     */
    static final class RegisteredSubtypeShape {
        static final class Root {
            public Account account;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        abstract static class Account {
            @Hidden
            public String accountSecret;

            public String accountId;
        }

        @JsonTypeName("premium")
        static final class PremiumAccount extends Account {
            @Hidden
            public String premiumSecret;

            public String premiumTier;
        }
    }

    /** What either direction reports for {@link RegisteredSubtypeShape}: the base's member. */
    private static final List<HiddenOnlyMember> REGISTERED_SUBTYPE_REPORT =
            List.of(hidden(RegisteredSubtypeShape.Account.class, "accountSecret"));

    /** An abstract member type without type information, whose member carries {@code @Hidden} alone. */
    static final class AbstractMemberShape {
        static final class Root {
            public Gadget gadget;
        }

        abstract static class Gadget {
            @Hidden
            public String gadgetSecret;

            public String gadgetName;
        }
    }

    /** What either direction reports for {@link AbstractMemberShape}. */
    private static final List<HiddenOnlyMember> ABSTRACT_MEMBER_REPORT =
            List.of(hidden(AbstractMemberShape.Gadget.class, "gadgetSecret"));

    /** An enum constant carrying {@code @Hidden} alone, in a shared definition, and an enum type carrying it. */
    static final class EnumShape {
        static final class Root {
            public Role role;
            public List<Role> roles;
            public Level level;
        }

        enum Role {
            USER,
            ADMIN,
            @Hidden
            INTERNAL_SUPERUSER
        }

        @Hidden
        enum Level {
            LOW,
            HIGH
        }
    }

    /** What either direction reports for {@link EnumShape}: the constant under its own name. */
    private static final List<HiddenOnlyMember> ENUM_REPORT =
            List.of(hidden(EnumShape.Level.class, null), hidden(EnumShape.Role.class, "INTERNAL_SUPERUSER"));

    /**
     * Unwrapped content: a flattened type carrying {@code @Hidden} with a flattened member carrying it,
     * and an unwrapped member carrying it.
     */
    static final class UnwrappedShape {
        static final class Root {
            public String unwrapName;

            @JsonUnwrapped
            public Parcel parcel;

            @JsonUnwrapped
            @Hidden
            public Stamp stamp;
        }

        @Hidden
        static final class Parcel {
            public String parcelLabel;

            @Hidden
            public String parcelSecret;
        }

        static final class Stamp {
            public String stampCode;
        }
    }

    /** What either direction reports for {@link UnwrappedShape}. */
    private static final List<HiddenOnlyMember> UNWRAPPED_CONTENT_REPORT = List.of(
            hidden(UnwrappedShape.Parcel.class, null),
            hidden(UnwrappedShape.Parcel.class, "parcelSecret"),
            hidden(UnwrappedShape.Root.class, "stamp"));

    /** Types reached as list, set, optional, array, map-value, and nested map-value elements. */
    static final class ContainerShape {
        static final class Root {
            public List<ListItem> listItems;
            public Set<SetItem> setItems;
            public Optional<OptionalItem> optionalItem;
            public ArrayItem[] arrayItems;
            public Map<String, MapItem> mapItems;
            public Map<String, List<NestedItem>> nestedItems;
        }

        @Hidden
        static final class ListItem {
            public String listValue;

            @Hidden
            public String listSecret;
        }

        static final class SetItem {
            @Hidden
            public String setSecret;
        }

        @Hidden
        static final class OptionalItem {
            @Hidden
            public String optionalSecret;
        }

        static final class ArrayItem {
            @Hidden
            public String arraySecret;
        }

        static final class MapItem {
            @Hidden
            public String mapSecret;
        }

        static final class NestedItem {
            @Hidden
            public String nestedSecret;
        }
    }

    /** What an input-direction generator reports for {@link ContainerShape}: every element type. */
    private static final List<HiddenOnlyMember> CONTAINER_INPUT_REPORT = List.of(
            hidden(ContainerShape.ArrayItem.class, "arraySecret"),
            hidden(ContainerShape.ListItem.class, null),
            hidden(ContainerShape.ListItem.class, "listSecret"),
            hidden(ContainerShape.MapItem.class, "mapSecret"),
            hidden(ContainerShape.NestedItem.class, "nestedSecret"),
            hidden(ContainerShape.OptionalItem.class, null),
            hidden(ContainerShape.OptionalItem.class, "optionalSecret"),
            hidden(ContainerShape.SetItem.class, "setSecret"));

    /** What an output-direction generator reports for {@link ContainerShape}: its schema describes no map values. */
    private static final List<HiddenOnlyMember> CONTAINER_OUTPUT_REPORT = List.of(
            hidden(ContainerShape.ArrayItem.class, "arraySecret"),
            hidden(ContainerShape.ListItem.class, null),
            hidden(ContainerShape.ListItem.class, "listSecret"),
            hidden(ContainerShape.OptionalItem.class, null),
            hidden(ContainerShape.OptionalItem.class, "optionalSecret"),
            hidden(ContainerShape.SetItem.class, "setSecret"));

    /** A generic type's own member and its type argument's member carry {@code @Hidden} alone. */
    static final class GenericShape {
        static final class Root {
            public Envelope<Payload> envelope;
        }

        static final class Envelope<T> {
            public T envelopeContent;

            @Hidden
            public String envelopeTag;
        }

        static final class Payload {
            @Hidden
            public String payloadSecret;

            public String payloadName;
        }
    }

    /** What either direction reports for {@link GenericShape}. */
    private static final List<HiddenOnlyMember> GENERIC_REPORT = List.of(
            hidden(GenericShape.Envelope.class, "envelopeTag"), hidden(GenericShape.Payload.class, "payloadSecret"));

    /** Inherited members: a superclass field and getter, and the subclass's own field, carry {@code @Hidden} alone. */
    static final class InheritanceShape {
        static final class Root {
            public Child child;
        }

        static class Parent {
            @Hidden
            public String parentSecret;

            public String parentName;

            private String parentCode;

            @Hidden
            public String getParentCode() {
                return parentCode;
            }

            public void setParentCode(String parentCode) {
                this.parentCode = parentCode;
            }
        }

        static final class Child extends Parent {
            @Hidden
            public String childSecret;

            public String childName;
        }
    }

    /** What either direction reports for {@link InheritanceShape}: each member under its declaring type. */
    private static final List<HiddenOnlyMember> INHERITANCE_REPORT = List.of(
            hidden(InheritanceShape.Child.class, "childSecret"),
            hidden(InheritanceShape.Parent.class, "getParentCode"),
            hidden(InheritanceShape.Parent.class, "parentSecret"));

    /**
     * Getters whose {@code @Hidden} is declared only on the method they override: a superclass getter
     * and an interface getter. The profile mapper reads both overrides as carrying {@code @Hidden}.
     */
    static final class OverriddenGetterShape {
        static final class Root {
            public Derived derived;
        }

        interface Nicknamed {
            @Hidden
            String getNickname();
        }

        static class Base {
            private String baseLabel;

            @Hidden
            public String getBaseLabel() {
                return baseLabel;
            }

            public void setBaseLabel(String baseLabel) {
                this.baseLabel = baseLabel;
            }
        }

        static final class Derived extends Base implements Nicknamed {
            private String nickname;

            @Override
            public String getBaseLabel() {
                return super.getBaseLabel();
            }

            @Override
            public String getNickname() {
                return nickname;
            }

            public void setNickname(String nickname) {
                this.nickname = nickname;
            }
        }
    }

    /** What either direction reports for {@link OverriddenGetterShape}: each overridden getter that declares it. */
    private static final List<HiddenOnlyMember> OVERRIDDEN_GETTER_REPORT = List.of(
            hidden(OverriddenGetterShape.Base.class, "getBaseLabel"),
            hidden(OverriddenGetterShape.Nicknamed.class, "getNickname"));

    /** An any-setter carrying {@code @Hidden} alone, which the input direction describes as the type's extras. */
    static final class AnySetterShape {
        static final class Root {
            public String anyName;

            @Hidden
            @JsonAnySetter
            public void putExtra(String key, String value) {}
        }
    }

    /** What an input-direction generator reports for {@link AnySetterShape}: the any-setter. */
    private static final List<HiddenOnlyMember> ANY_SETTER_INPUT_REPORT =
            List.of(hidden(AnySetterShape.Root.class, "putExtra"));

    /** {@code @Hidden} carried through a Jackson annotation bundle, on a member and on a type. */
    static final class BundleShape {
        /** A Jackson annotation bundle carrying {@code @Hidden}. */
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
        @JacksonAnnotationsInside
        @Hidden
        @interface InternalOnly {}

        static final class Root {
            public String bundleName;

            @InternalOnly
            public String bundleDebug;

            public Sealed sealed;
        }

        @InternalOnly
        static final class Sealed {
            public String sealedValue;
        }
    }

    /** What either direction reports for {@link BundleShape}. */
    private static final List<HiddenOnlyMember> BUNDLE_REPORT =
            List.of(hidden(BundleShape.Root.class, "bundleDebug"), hidden(BundleShape.Sealed.class, null));

    /**
     * Properties with a single accessor carrying {@code @Hidden} alone: a setter-only property, a getter
     * over a private field, and a getter-only collection.
     */
    static final class AccessorShape {
        static final class Root {
            public String accessorName;
            private String setterSecret;
            private String getterSecret;

            @Hidden
            public void setSetterSecret(String setterSecret) {
                this.setterSecret = setterSecret;
            }

            @Hidden
            public String getGetterSecret() {
                return getterSecret;
            }

            @Hidden
            public List<String> getGetterTags() {
                return List.of();
            }
        }
    }

    /** What an input-direction generator reports for {@link AccessorShape}: all three accessors. */
    private static final List<HiddenOnlyMember> ACCESSOR_INPUT_REPORT = List.of(
            hidden(AccessorShape.Root.class, "getGetterSecret"),
            hidden(AccessorShape.Root.class, "getGetterTags"),
            hidden(AccessorShape.Root.class, "setSetterSecret"));

    /** What an output-direction generator reports for {@link AccessorShape}: the one property it describes. */
    private static final List<HiddenOnlyMember> ACCESSOR_OUTPUT_REPORT =
            List.of(hidden(AccessorShape.Root.class, "getGetterSecret"));

    /** A public field whose setter alone carries {@code @Hidden}. */
    static final class SetterCarrierShape {
        static final class Root {
            public String secret;

            @Hidden
            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /** What either direction reports for {@link SetterCarrierShape}: the setter, by its own name. */
    private static final List<HiddenOnlyMember> SETTER_CARRIER_REPORT =
            List.of(hidden(SetterCarrierShape.Root.class, "setSecret"));

    /** A type carrying {@code @Hidden} reached through a member bound case-insensitively. */
    static final class CaseInsensitiveShape {
        static final class Root {
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            public Folded folded;
        }

        @Hidden
        static final class Folded {
            @Hidden
            public String foldedSecret;

            public String foldedValue;
        }
    }

    /** What either direction reports for {@link CaseInsensitiveShape}. */
    private static final List<HiddenOnlyMember> CASE_INSENSITIVE_REPORT = List.of(
            hidden(CaseInsensitiveShape.Folded.class, null), hidden(CaseInsensitiveShape.Folded.class, "foldedSecret"));

    /** A root type carrying {@code @Hidden}. */
    static final class HiddenRootShape {
        @Hidden
        static final class Root {
            public String rootValue;
        }
    }

    /** What either direction reports for {@link HiddenRootShape}: the root type. */
    private static final List<HiddenOnlyMember> HIDDEN_ROOT_REPORT = List.of(hidden(HiddenRootShape.Root.class, null));

    /**
     * Getters that add {@code @Hidden} while overriding an unmarked getter of a superclass declaring the
     * property's field and getter: in a concrete subclass, and in an abstract subclass reached as a member
     * type without type information, which the schema library describes itself. The profile mapper links
     * each property to the overriding getter.
     */
    static final class SubclassOverrideShape {
        static final class Root {
            public Overrider overrider;
            public AbstractOverrider abstractOverrider;
        }

        static class Declarer {
            private String declaredLabel;

            public String getDeclaredLabel() {
                return declaredLabel;
            }
        }

        static final class Overrider extends Declarer {
            @Override
            @Hidden
            public String getDeclaredLabel() {
                return super.getDeclaredLabel();
            }
        }

        static class AbstractDeclarer {
            private String abstractLabel;

            public String getAbstractLabel() {
                return abstractLabel;
            }
        }

        abstract static class AbstractOverrider extends AbstractDeclarer {
            @Override
            @Hidden
            public String getAbstractLabel() {
                return super.getAbstractLabel();
            }
        }
    }

    /** What either direction reports for {@link SubclassOverrideShape}: each override, under its own name. */
    private static final List<HiddenOnlyMember> SUBCLASS_OVERRIDE_REPORT = List.of(
            hidden(SubclassOverrideShape.AbstractOverrider.class, "getAbstractLabel"),
            hidden(SubclassOverrideShape.Overrider.class, "getDeclaredLabel"));

    /**
     * A polymorphic base carrying only a type-level {@code @Hidden}, reached as a member of an abstract
     * member type without type information, which the schema library describes itself.
     */
    static final class LibraryMemberBaseShape {
        static final class Root {
            public Depot depot;
        }

        abstract static class Depot {
            public String depotName;
            public Crane crane;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = TowerCrane.class, name = "tower")})
        @Hidden
        abstract static class Crane {
            public String craneMake;
        }

        static final class TowerCrane extends Crane {
            public String towerHeight;
        }
    }

    /** What either direction reports for {@link LibraryMemberBaseShape}: the base type. */
    private static final List<HiddenOnlyMember> LIBRARY_MEMBER_BASE_REPORT =
            List.of(hidden(LibraryMemberBaseShape.Crane.class, null));

    /**
     * Polymorphic bases carrying only a type-level {@code @Hidden}, one per position below the first
     * container level or inside {@code Optional}: a list of lists, an optional, map values, an array of
     * lists, and an optional list.
     */
    static final class DeepPolymorphicBaseShape {
        static final class Root {
            public List<List<Tram>> tramRows;
            public Optional<Ferry> ferry;
            public Map<String, Barge> barges;
            public List<Glider>[] gliderRows;
            public Optional<List<Coach>> coachLines;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = CableTram.class, name = "cable")})
        @Hidden
        abstract static class Tram {
            public String tramLine;
        }

        static final class CableTram extends Tram {
            public String cableGauge;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = CarFerry.class, name = "car")})
        @Hidden
        abstract static class Ferry {
            public String ferryRoute;
        }

        static final class CarFerry extends Ferry {
            public String carDeck;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = RiverBarge.class, name = "river")})
        @Hidden
        abstract static class Barge {
            public String bargeLoad;
        }

        static final class RiverBarge extends Barge {
            public String riverName;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = SailGlider.class, name = "sail")})
        @Hidden
        abstract static class Glider {
            public String gliderSpan;
        }

        static final class SailGlider extends Glider {
            public String sailArea;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = ExpressCoach.class, name = "express")})
        @Hidden
        abstract static class Coach {
            public String coachSeats;
        }

        static final class ExpressCoach extends Coach {
            public String expressStops;
        }
    }

    /** What an input-direction generator reports for {@link DeepPolymorphicBaseShape}: every base. */
    private static final List<HiddenOnlyMember> DEEP_POLYMORPHIC_BASE_INPUT_REPORT = List.of(
            hidden(DeepPolymorphicBaseShape.Barge.class, null),
            hidden(DeepPolymorphicBaseShape.Coach.class, null),
            hidden(DeepPolymorphicBaseShape.Ferry.class, null),
            hidden(DeepPolymorphicBaseShape.Glider.class, null),
            hidden(DeepPolymorphicBaseShape.Tram.class, null));

    /** What an output-direction generator reports for {@link DeepPolymorphicBaseShape}: no map values are described. */
    private static final List<HiddenOnlyMember> DEEP_POLYMORPHIC_BASE_OUTPUT_REPORT = List.of(
            hidden(DeepPolymorphicBaseShape.Coach.class, null),
            hidden(DeepPolymorphicBaseShape.Ferry.class, null),
            hidden(DeepPolymorphicBaseShape.Glider.class, null),
            hidden(DeepPolymorphicBaseShape.Tram.class, null));

    /**
     * {@code @Hidden} merged into a type's members from the mix-in {@link #MIX_IN_PROFILE}'s mapper
     * registers for it — on the mix-in's field and on its getter — while the type itself declares
     * nothing. The profile mapper reads each target member as carrying {@code @Hidden}.
     */
    static final class MixInShape {
        static final class Root {
            public String mixName;
            public String mixSecret;
            private String mixToken;

            public String getMixToken() {
                return mixToken;
            }
        }

        /** Never described itself: the profile mapper merges its annotations into {@link Root}'s members. */
        abstract static class RootMixIn {
            @Hidden
            public String mixSecret;

            @Hidden
            public abstract String getMixToken();
        }
    }

    /** What either direction reports for {@link MixInShape}: each target member, under its own name and type. */
    private static final List<HiddenOnlyMember> MIX_IN_REPORT =
            List.of(hidden(MixInShape.Root.class, "getMixToken"), hidden(MixInShape.Root.class, "mixSecret"));

    /**
     * A polymorphic base carrying only a type-level {@code @Hidden}, reached only as a first-level list
     * element, from three roots none of which reaches it another way: a list member, a list that is a
     * generic type argument, and a list member of an abstract member type without type information.
     */
    static final class ListElementBaseShape {
        static final class ListRoot {
            public List<Tanker> tankers;
        }

        static final class GenericListRoot {
            public Hold<List<Tanker>> hold;
        }

        static final class AbstractListRoot {
            public Harbor harbor;
        }

        static final class Hold<T> {
            public T holdContent;
        }

        abstract static class Harbor {
            public String harborName;
            public List<Tanker> berths;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = OilTanker.class, name = "oil")})
        @Hidden
        abstract static class Tanker {
            public String tankerFlag;
        }

        static final class OilTanker extends Tanker {
            public String oilGrade;
        }
    }

    /** What either direction reports for each {@link ListElementBaseShape} root: the base type. */
    private static final List<HiddenOnlyMember> LIST_ELEMENT_BASE_REPORT =
            List.of(hidden(ListElementBaseShape.Tanker.class, null));

    /**
     * A type-level {@code @Hidden} merged into a member's type from the mix-in {@link #MIX_IN_TYPE_PROFILE}'s
     * mapper registers for it, while the type itself declares nothing. The profile mapper reads the
     * target type as carrying {@code @Hidden}.
     */
    static final class MixInTypeShape {
        static final class Root {
            public String stowName;
            public Stowed stowed;
        }

        static final class Stowed {
            public String stowedValue;
        }

        /** Never described itself: the profile mapper merges its type-level {@code @Hidden} into {@link Stowed}. */
        @Hidden
        abstract static class StowedMixIn {}
    }

    /** What either direction reports for {@link MixInTypeShape}: the target type. */
    private static final List<HiddenOnlyMember> MIX_IN_TYPE_REPORT = List.of(hidden(MixInTypeShape.Stowed.class, null));

    /**
     * A polymorphic base carrying only a type-level {@code @Hidden}, reached only as a first-level
     * element of a set, an array, and a collection, from three roots none of which reaches it another way.
     */
    static final class ContainerElementBaseShape {
        static final class SetRoot {
            public Set<Crate> crateSet;
        }

        static final class ArrayRoot {
            public Crate[] crateArray;
        }

        static final class CollectionRoot {
            public Collection<Crate> crateCollection;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = WoodenCrate.class, name = "wooden")})
        @Hidden
        abstract static class Crate {
            public String crateLabel;
        }

        static final class WoodenCrate extends Crate {
            public String woodType;
        }
    }

    /** What either direction reports for each {@link ContainerElementBaseShape} root: the base type. */
    private static final List<HiddenOnlyMember> CONTAINER_ELEMENT_BASE_REPORT =
            List.of(hidden(ContainerElementBaseShape.Crate.class, null));

    /**
     * A type-level {@code @Hidden} merged into the root type itself from the mix-in {@link
     * #MIX_IN_ROOT_PROFILE}'s mapper registers for it, while the root declares nothing.
     */
    static final class MixInRootShape {
        static final class Root {
            public String rootMixValue;
        }

        /** Never described itself: the profile mapper merges its type-level {@code @Hidden} into {@link Root}. */
        @Hidden
        abstract static class RootMixIn {}
    }

    /** What either direction reports for {@link MixInRootShape}: the root type. */
    private static final List<HiddenOnlyMember> MIX_IN_ROOT_REPORT = List.of(hidden(MixInRootShape.Root.class, null));

    /**
     * {@code @Hidden} merged into a superclass's field from the mix-in {@link #MIX_IN_SUPERCLASS_PROFILE}'s
     * mapper registers for that superclass, reached through a subclass; neither class declares anything.
     */
    static final class MixInSuperclassShape {
        static final class Root {
            public Child child;
        }

        static class Parent {
            public String parentField;
        }

        static final class Child extends Parent {
            public String childField;
        }

        /** Never described itself: the profile mapper merges its field's {@code @Hidden} into {@link Parent}'s. */
        abstract static class ParentMixIn {
            @Hidden
            public String parentField;
        }
    }

    /** What either direction reports for {@link MixInSuperclassShape}: the target field, under its declaring type. */
    private static final List<HiddenOnlyMember> MIX_IN_SUPERCLASS_REPORT =
            List.of(hidden(MixInSuperclassShape.Parent.class, "parentField"));

    /**
     * Setter-bound properties — a private field and a public setter, no getter — of abstract member types
     * without type information, which the schema library describes itself: a plain property and a list
     * property whose setter carries {@code @Hidden} directly, and a pair whose setter carries it through a
     * Jackson annotation bundle ({@link BundleShape.InternalOnly}). Each root reaches one panel.
     */
    static final class SetterBoundShape {
        static final class DirectRoot {
            public DirectPanel directPanel;
        }

        static final class BundleRoot {
            public BundlePanel bundlePanel;
        }

        abstract static class DirectPanel {
            private String directSecret;
            private List<String> directTags;

            @Hidden
            public void setDirectSecret(String directSecret) {
                this.directSecret = directSecret;
            }

            @Hidden
            public void setDirectTags(List<String> directTags) {
                this.directTags = directTags;
            }
        }

        abstract static class BundlePanel {
            private String bundledSecret;
            private List<String> bundledTags;

            @BundleShape.InternalOnly
            public void setBundledSecret(String bundledSecret) {
                this.bundledSecret = bundledSecret;
            }

            @BundleShape.InternalOnly
            public void setBundledTags(List<String> bundledTags) {
                this.bundledTags = bundledTags;
            }
        }
    }

    /** What an input-direction generator reports for {@link SetterBoundShape.DirectRoot}: each setter. */
    private static final List<HiddenOnlyMember> SETTER_BOUND_DIRECT_INPUT_REPORT = List.of(
            hidden(SetterBoundShape.DirectPanel.class, "setDirectSecret"),
            hidden(SetterBoundShape.DirectPanel.class, "setDirectTags"));

    /** What an input-direction generator reports for {@link SetterBoundShape.BundleRoot}: each setter. */
    private static final List<HiddenOnlyMember> SETTER_BOUND_BUNDLE_INPUT_REPORT = List.of(
            hidden(SetterBoundShape.BundlePanel.class, "setBundledSecret"),
            hidden(SetterBoundShape.BundlePanel.class, "setBundledTags"));

    /**
     * Setter-bound properties of an abstract member type without type information, a plain property and a
     * list property, whose setters carry {@code @Hidden} only through the mix-in {@link
     * #SETTER_BOUND_MIX_IN_PROFILE}'s mapper registers for the type.
     */
    static final class SetterBoundMixInShape {
        static final class Root {
            public Panel panel;
        }

        abstract static class Panel {
            private String mixedSecret;
            private List<String> mixedTags;

            public void setMixedSecret(String mixedSecret) {
                this.mixedSecret = mixedSecret;
            }

            public void setMixedTags(List<String> mixedTags) {
                this.mixedTags = mixedTags;
            }
        }

        /** Never described itself: the profile mapper merges its setters' {@code @Hidden} into {@link Panel}'s. */
        abstract static class PanelMixIn {
            @Hidden
            public abstract void setMixedSecret(String mixedSecret);

            @Hidden
            public abstract void setMixedTags(List<String> mixedTags);
        }
    }

    /** What an input-direction generator reports for {@link SetterBoundMixInShape}: each target setter. */
    private static final List<HiddenOnlyMember> SETTER_BOUND_MIX_IN_INPUT_REPORT = List.of(
            hidden(SetterBoundMixInShape.Panel.class, "setMixedSecret"),
            hidden(SetterBoundMixInShape.Panel.class, "setMixedTags"));

    /**
     * Setter-bound properties of an abstract member type without type information, a plain property and a
     * list property, whose {@code @Hidden} setters rename them with {@code @JsonProperty}: the profile
     * mapper binds each by its wire name, without its field, while the input document names it by its
     * internal name, through the field the schema library reads.
     */
    static final class RenamedSetterShape {
        static final class Root {
            public Vault vault;
        }

        abstract static class Vault {
            private String secret;
            private List<String> secretTags;

            @Hidden
            @JsonProperty("s")
            public void setSecret(String secret) {
                this.secret = secret;
            }

            @Hidden
            @JsonProperty("t")
            public void setSecretTags(List<String> secretTags) {
                this.secretTags = secretTags;
            }
        }
    }

    /** What an input-direction generator reports for {@link RenamedSetterShape}: each setter, by its own name. */
    private static final List<HiddenOnlyMember> RENAMED_SETTER_INPUT_REPORT = List.of(
            hidden(RenamedSetterShape.Vault.class, "setSecret"),
            hidden(RenamedSetterShape.Vault.class, "setSecretTags"));

    /**
     * A polymorphic base carrying only a type-level {@code @Hidden}, for which {@link
     * #OVERRIDDEN_BASE_PROFILE} declares a schema type override, reached only as a list element from one
     * root and only as a plain member from another.
     */
    static final class OverriddenBaseShape {
        static final class ListRoot {
            public List<Buoy> buoys;
        }

        static final class MemberRoot {
            public Buoy buoy;
        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @JsonSubTypes({@JsonSubTypes.Type(value = SparBuoy.class, name = "spar")})
        @Hidden
        abstract static class Buoy {
            public String buoyColor;
        }

        static final class SparBuoy extends Buoy {
            public String sparHeight;
        }
    }

    /** What a direction describing {@link OverriddenBaseShape.Buoy}'s own content reports: the base type. */
    private static final List<HiddenOnlyMember> OVERRIDDEN_BASE_REPORT =
            List.of(hidden(OverriddenBaseShape.Buoy.class, null));

    /**
     * Generic setters carrying {@code @Hidden}, each overridden with a concrete parameter type: by a
     * concrete member type, and by an abstract member type without type information, which the schema
     * library describes itself. The profile mapper merges the generic declaration's {@code @Hidden} into
     * the override, whose raw parameter type differs from the declaration's.
     */
    static final class GenericSetterShape {
        static final class Root {
            public Account account;
            public LibraryAccount libraryAccount;
        }

        abstract static class Entity<I> {
            @Hidden
            public abstract void setId(I id);
        }

        static final class Account extends Entity<Long> {
            private Long id;

            @Override
            public void setId(Long id) {
                this.id = id;
            }

            public Long getId() {
                return id;
            }
        }

        abstract static class Keyed<K> {
            @Hidden
            public abstract void setKey(K key);
        }

        abstract static class LibraryAccount extends Keyed<Long> {
            private Long key;

            @Override
            public void setKey(Long key) {
                this.key = key;
            }

            public Long getKey() {
                return key;
            }
        }
    }

    /**
     * What either direction reports for {@link GenericSetterShape}: each generic declaration carrying
     * {@code @Hidden}, under its own name and declaring type, as for an overridden getter.
     */
    private static final List<HiddenOnlyMember> GENERIC_SETTER_REPORT =
            List.of(hidden(GenericSetterShape.Entity.class, "setId"), hidden(GenericSetterShape.Keyed.class, "setKey"));

    /**
     * {@code @Hidden} declared on a mix-in's superclass, which the profile mapper merges into the target
     * along with the mix-in's own declarations: on a field of the superclass of {@link
     * #MIX_IN_ANCESTOR_PROFILE}'s mix-in for {@link Vault}, and on the superclass itself of its mix-in for
     * {@link Safe}.
     */
    static final class MixInAncestorShape {
        static final class Root {
            public Vault vault;
            public Safe safe;
        }

        static final class Vault {
            public String vaultName;
            public String vaultSecret;
        }

        static final class Safe {
            public String safeValue;
        }

        /** Never described itself: its field's {@code @Hidden} reaches {@link Vault} through {@link VaultMixIn}. */
        abstract static class VaultBaseMixIn {
            @Hidden
            public String vaultSecret;
        }

        abstract static class VaultMixIn extends VaultBaseMixIn {}

        /** Never described itself: its type-level {@code @Hidden} reaches {@link Safe} through {@link SafeMixIn}. */
        @Hidden
        abstract static class SafeBaseMixIn {}

        abstract static class SafeMixIn extends SafeBaseMixIn {}
    }

    /** What either direction reports for {@link MixInAncestorShape}: the target type and the target field. */
    private static final List<HiddenOnlyMember> MIX_IN_ANCESTOR_REPORT =
            List.of(hidden(MixInAncestorShape.Safe.class, null), hidden(MixInAncestorShape.Vault.class, "vaultSecret"));

    /**
     * A mix-in the profile mapper registers for a superclass, declaring a {@code @Hidden} getter that only
     * the subclass declares: the mapper merges it into the subclass's getter.
     */
    static final class InheritedMixInMethodShape {
        static final class Root {
            public Heir heir;
        }

        static class Ancestor {
            public String ancestorName;
        }

        static final class Heir extends Ancestor {
            private String heirSecret;

            public String getHeirSecret() {
                return heirSecret;
            }

            public void setHeirSecret(String heirSecret) {
                this.heirSecret = heirSecret;
            }
        }

        /** Registered for {@link Ancestor}, which does not declare the getter it marks. */
        abstract static class AncestorMixIn {
            @Hidden
            public abstract String getHeirSecret();
        }
    }

    /** What either direction reports for {@link InheritedMixInMethodShape}: the subclass's getter. */
    private static final List<HiddenOnlyMember> INHERITED_MIX_IN_METHOD_REPORT =
            List.of(hidden(InheritedMixInMethodShape.Heir.class, "getHeirSecret"));

    /**
     * {@code @JsonUnwrapped} members carrying {@code @Hidden} whose flattened content the input direction
     * describes: a field of an abstract member type without type information, which the schema library
     * describes itself, a getter-only member, and a {@code READ_ONLY} field. Each root reaches one.
     */
    static final class UnwrappedInputShape {
        static final class LibraryRoot {
            public Holder holder;
        }

        abstract static class Holder {
            public String holderName;

            @JsonUnwrapped
            @Hidden
            public Mailbox holderMailbox;
        }

        static final class Mailbox {
            public String mailboxStreet;
        }

        static final class GetterOnlyRoot {
            public String getterOnlyName;

            @JsonUnwrapped
            @Hidden
            public Postcard getGetterOnlyPostcard() {
                return new Postcard();
            }
        }

        static final class Postcard {
            public String postcardCity;
        }

        static final class ReadOnlyRoot {
            public String readOnlyName;

            @JsonUnwrapped
            @Hidden
            @JsonProperty(access = JsonProperty.Access.READ_ONLY)
            public Label readOnlyLabel;
        }

        static final class Label {
            public String labelText;
        }
    }

    /** What an input-direction generator reports for {@link UnwrappedInputShape.LibraryRoot}. */
    private static final List<HiddenOnlyMember> UNWRAPPED_LIBRARY_INPUT_REPORT =
            List.of(hidden(UnwrappedInputShape.Holder.class, "holderMailbox"));

    /** What an input-direction generator reports for {@link UnwrappedInputShape.GetterOnlyRoot}. */
    private static final List<HiddenOnlyMember> UNWRAPPED_GETTER_ONLY_INPUT_REPORT =
            List.of(hidden(UnwrappedInputShape.GetterOnlyRoot.class, "getGetterOnlyPostcard"));

    /** What an input-direction generator reports for {@link UnwrappedInputShape.ReadOnlyRoot}. */
    private static final List<HiddenOnlyMember> UNWRAPPED_READ_ONLY_INPUT_REPORT =
            List.of(hidden(UnwrappedInputShape.ReadOnlyRoot.class, "readOnlyLabel"));

    /**
     * A setter-bound property whose private field carries both {@code @Hidden} and {@code @Schema(hidden =
     * true)}: the input direction describes it (its setter path does not honor {@code @Schema(hidden)}),
     * and it is not reported, since it carries both markers.
     */
    static final class SetterBothMarkersShape {
        static final class Root {
            public String bothName;

            @Hidden
            @Schema(hidden = true)
            private String bothSecret;

            public void setBothSecret(String bothSecret) {
                this.bothSecret = bothSecret;
            }
        }
    }

    /**
     * An enum constant carrying {@code @Hidden} only through the enum mix-in {@link
     * #ENUM_MIX_IN_PROFILE}'s mapper registers for its enum, while the enum itself declares nothing.
     */
    static final class EnumMixInShape {
        static final class Root {
            public Tier tier;
        }

        enum Tier {
            VISIBLE,
            SECRET
        }

        /** Never described itself: the profile mapper merges its constant's {@code @Hidden} into {@link Tier}'s. */
        enum TierMixIn {
            @Hidden
            SECRET
        }
    }

    /** What either direction reports for {@link EnumMixInShape}: the target constant. */
    private static final List<HiddenOnlyMember> ENUM_MIX_IN_REPORT =
            List.of(hidden(EnumMixInShape.Tier.class, "SECRET"));

    /**
     * A type bound through a builder whose base builder declares a {@code @Hidden} builder method that the
     * builder overrides without it: the profile mapper merges the declaration's {@code @Hidden} into the
     * override, which binds on input.
     */
    static final class SuperBuilderShape {
        @JsonDeserialize(builder = Builder.class)
        static final class Ticket {
            private final String ticketCode;
            private final String ticketSeat;

            Ticket(String ticketCode, String ticketSeat) {
                this.ticketCode = ticketCode;
                this.ticketSeat = ticketSeat;
            }

            public String getTicketCode() {
                return ticketCode;
            }

            public String getTicketSeat() {
                return ticketSeat;
            }
        }

        @JsonPOJOBuilder(withPrefix = "with")
        static class BaseBuilder {
            protected String ticketCode;

            @Hidden
            public BaseBuilder withTicketCode(String value) {
                ticketCode = value;
                return this;
            }
        }

        @JsonPOJOBuilder(withPrefix = "with")
        static final class Builder extends BaseBuilder {
            private String ticketSeat;

            @Override
            public Builder withTicketCode(String value) {
                super.withTicketCode(value);
                return this;
            }

            public Builder withTicketSeat(String value) {
                ticketSeat = value;
                return this;
            }

            public Ticket build() {
                return new Ticket(ticketCode, ticketSeat);
            }
        }
    }

    /** What an input-direction generator reports for {@link SuperBuilderShape}: the declaring builder method. */
    private static final List<HiddenOnlyMember> SUPER_BUILDER_INPUT_REPORT =
            List.of(hidden(SuperBuilderShape.BaseBuilder.class, "withTicketCode"));

    /**
     * A setter-bound property of an abstract member type without type information, which the schema
     * library describes itself, whose setter carries both {@code @Hidden} and {@code @Schema(hidden =
     * true)}: it is not reported, since it carries both markers.
     */
    static final class SetterBothMarkersLibraryShape {
        static final class Root {
            public Locker locker;
        }

        abstract static class Locker {
            public String lockerName;
            private String lockerSecret;

            @Hidden
            @Schema(hidden = true)
            public void setLockerSecret(String lockerSecret) {
                this.lockerSecret = lockerSecret;
            }
        }
    }

    /**
     * A property with a getter whose setter carries both {@code @Hidden} and {@code @Schema(hidden =
     * true)}: it is not reported in either direction, since the setter carries both markers.
     */
    static final class SetterBothMarkersGetterShape {
        static final class Root {
            public String openLabel;
            private String pin;

            public String getPin() {
                return pin;
            }

            @Hidden
            @Schema(hidden = true)
            public void setPin(String pin) {
                this.pin = pin;
            }
        }
    }

    /**
     * A base setter carrying {@code @Hidden}, whose subclass override carries {@code @Schema(hidden =
     * true)} alone: both directions still report the base's {@code @Hidden} declaration.
     */
    static final class OverrideSchemaOnSubShape {
        static class Base {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Hidden
            public void setPin(String pin) {
                this.pin = pin;
            }
        }

        static final class Root extends Base {
            @Override
            @Schema(hidden = true)
            public void setPin(String pin) {
                super.setPin(pin);
            }
        }
    }

    /**
     * A base setter carrying {@code @Hidden}, whose subclass override carries both markers: both
     * directions still report the base's {@code @Hidden} declaration.
     */
    static final class OverrideBothOnSubShape {
        static class Base {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Hidden
            public void setPin(String pin) {
                this.pin = pin;
            }
        }

        static final class Root extends Base {
            @Override
            @Hidden
            @Schema(hidden = true)
            public void setPin(String pin) {
                super.setPin(pin);
            }
        }
    }

    /**
     * A base setter carrying {@code @Schema(hidden = true)}, overridden by a subclass setter carrying
     * {@code @Hidden} alone: both directions report the override, under its own declaring type.
     */
    static final class SchemaOnBaseHiddenOnSubShape {
        static class Base {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Schema(hidden = true)
            public void setPin(String pin) {
                this.pin = pin;
            }
        }

        static final class Root extends Base {
            @Override
            @Hidden
            public void setPin(String pin) {
                super.setPin(pin);
            }
        }
    }

    /**
     * A generic interface method carrying {@code @Hidden}, overridden with a concrete parameter type by
     * {@code @Schema(hidden = true)} alone: both directions still report the generic declaration.
     */
    static final class GenericOverrideSchemaShape {
        interface Settable<T> {
            @Hidden
            void setPin(T pin);
        }

        static final class Root implements Settable<String> {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Override
            @Schema(hidden = true)
            public void setPin(String pin) {
                this.pin = pin;
            }
        }
    }

    /**
     * A generic interface method carrying {@code @Hidden}, overridden with a concrete parameter type by
     * both markers: both directions still report the generic declaration.
     */
    static final class GenericOverrideBothShape {
        interface Settable<T> {
            @Hidden
            void setPin(T pin);
        }

        static final class Root implements Settable<String> {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Override
            @Hidden
            @Schema(hidden = true)
            public void setPin(String pin) {
                this.pin = pin;
            }
        }
    }

    /**
     * A setterless getter-bound property whose backing field carries both markers: neither direction
     * reports it.
     */
    static final class SetterlessBothFieldShape {
        static final class Root {
            public String openLabel;

            @Hidden
            @Schema(hidden = true)
            private List<String> tags = new ArrayList<>();

            @Schema(description = "tags")
            public List<String> getTags() {
                return tags;
            }
        }
    }

    /**
     * A setterless getter-bound property whose backing field carries {@code @Hidden} alone, with a
     * described getter: the input direction reports the field.
     */
    static final class SetterlessHiddenFieldShape {
        static final class Root {
            public String openLabel;

            @Hidden
            private List<String> tags = new ArrayList<>();

            @Schema(description = "tags")
            public List<String> getTags() {
                return tags;
            }
        }
    }

    /**
     * A setter carrying {@code @Hidden}, whose mix-in registers {@code @Schema(hidden = true)} for the
     * same setter: neither direction reports it, since the merged view carries both markers.
     */
    static final class MixInSchemaSetterShape {
        static final class Root {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Hidden
            public void setPin(String pin) {
                this.pin = pin;
            }
        }

        abstract static class RootMixIn {
            @Schema(hidden = true)
            abstract void setPin(String pin);
        }
    }

    /**
     * An unwrapped field carrying both markers: neither direction reports it, since the field carries
     * both markers even though its getter is unwrapped.
     */
    static final class UnwrappedGetterBothFieldShape {
        static final class Root {
            public String openLabel;

            @Hidden
            @Schema(hidden = true)
            private Inner inner = new Inner();

            @JsonUnwrapped
            @Schema(description = "inner")
            public Inner getInner() {
                return inner;
            }

            public void setInner(Inner inner) {
                this.inner = inner;
            }
        }

        static final class Inner {
            public String innerCode;
        }
    }

    /**
     * An abstract member type the schema library describes itself, whose field carries {@code @Hidden}
     * alone and whose setter carries {@code @Schema(hidden = true)}: the input direction reports the field.
     */
    static final class SetterSchemaLibraryHiddenFieldShape {
        static final class Root {
            public Locker locker;
        }

        abstract static class Locker {
            public String lockerName;

            @Hidden
            private String lockerSecret;

            @Schema(hidden = true)
            public void setLockerSecret(String lockerSecret) {
                this.lockerSecret = lockerSecret;
            }
        }
    }

    /**
     * A concrete type whose field carries {@code @Hidden} alone and whose setter carries
     * {@code @Schema(hidden = true)}: the input direction reports the field.
     */
    static final class SetterSchemaConcreteHiddenFieldShape {
        static final class Root {
            public String openLabel;

            @Hidden
            private String secret;

            @Schema(hidden = true)
            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /**
     * A setter-only base setter carrying {@code @Hidden}, whose subclass override carries {@code
     * @Schema(hidden = true)} alone: the input direction still reports the base's {@code @Hidden}
     * declaration.
     */
    static final class SetterOnlyOverrideSchemaShape {
        static class Base {
            public String openLabel;

            @Hidden
            public void setPin(String pin) {}
        }

        static final class Root extends Base {
            @Override
            @Schema(hidden = true)
            public void setPin(String pin) {}
        }
    }

    /**
     * A setter-only base setter carrying {@code @Schema(hidden = true)}, overridden by a subclass setter
     * carrying {@code @Hidden} alone: the input direction reports the override, under its own declaring
     * type.
     */
    static final class SetterOnlyOverrideHiddenShape {
        static class Base {
            public String openLabel;

            @Schema(hidden = true)
            public void setPin(String pin) {}
        }

        static final class Root extends Base {
            @Override
            @Hidden
            public void setPin(String pin) {}
        }
    }

    /**
     * A setter-only base setter carrying {@code @Hidden}, whose subclass override carries both markers:
     * the input direction still reports the base's {@code @Hidden} declaration.
     */
    static final class SetterOnlyOverrideBothShape {
        static class Base {
            public String openLabel;

            @Hidden
            public void setPin(String pin) {}
        }

        static final class Root extends Base {
            @Override
            @Hidden
            @Schema(hidden = true)
            public void setPin(String pin) {}
        }
    }

    /**
     * A base setter carrying {@code @Schema(hidden = true)}, whose subclass's mix-in registers {@code
     * @Hidden} for the same setter: neither direction reports it, since the merged view carries both
     * markers.
     */
    static final class SubclassMixInHiddenShape {
        static class Base {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Schema(hidden = true)
            public void setPin(String pin) {
                this.pin = pin;
            }
        }

        static final class Root extends Base {
            public String openLabel;
        }

        abstract static class RootMixIn {
            @Hidden
            abstract void setPin(String pin);
        }
    }

    /**
     * A base setter carrying {@code @Hidden}, whose subclass's mix-in registers {@code @Schema(hidden =
     * true)} for the same setter: both directions still report the base's {@code @Hidden} declaration.
     */
    static final class SubclassMixInSchemaShape {
        static class Base {
            private String pin;

            public String getPin() {
                return pin;
            }

            @Hidden
            public void setPin(String pin) {
                this.pin = pin;
            }
        }

        static final class Root extends Base {
            public String openLabel;
        }

        abstract static class RootMixIn {
            @Schema(hidden = true)
            abstract void setPin(String pin);
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("A body member or type hidden only by @Hidden is reported by an input-direction generator, ordered by"
            + " declaring type then member with the type-level entry first, while generateCanonical still describes"
            + " it, an unwrapped member through its flattened content, unchanged by the call")
    void reportsMembersAndTypesHiddenOnlyByHidden() {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        String before = generator.generateCanonical(HiddenOnlyBody.class);
        List<HiddenOnlyMember> actual = generator.hiddenOnlyMembers(HiddenOnlyBody.class);
        String after = generator.generateCanonical(HiddenOnlyBody.class);
        Set<String> published = publishedPropertyNames(before);

        String unwrappedFieldBefore = generator.generateCanonical(HiddenUnwrappedField.class);
        List<HiddenOnlyMember> unwrappedFieldReport = generator.hiddenOnlyMembers(HiddenUnwrappedField.class);
        String unwrappedFieldAfter = generator.generateCanonical(HiddenUnwrappedField.class);
        String unwrappedGetterBefore = generator.generateCanonical(HiddenUnwrappedGetter.class);
        List<HiddenOnlyMember> unwrappedGetterReport = generator.hiddenOnlyMembers(HiddenUnwrappedGetter.class);
        String unwrappedGetterAfter = generator.generateCanonical(HiddenUnwrappedGetter.class);

        assertAll(
                () -> assertEquals(BODY_REPORT, actual),
                () -> assertTrue(
                        published.containsAll(Set.of("debug", "override", "nested", "flag")),
                        () -> "the input schema must still describe every @Hidden-only member: " + before),
                () -> assertEquals(before, after, "hiddenOnlyMembers must not change what generateCanonical publishes"),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> actual.add(hidden(HiddenOnlyBody.class, "name")),
                        "the report is unmodifiable"),
                // @Hidden alone beside @JsonUnwrapped, on a field and on a getter: the input schema
                // describes the member only through its flattened content, and the member is reported.
                () -> assertEquals(UNWRAPPED_FIELD_REPORT, unwrappedFieldReport),
                () -> assertEquals(
                        UNWRAPPED_PROPERTIES,
                        publishedPropertyNames(unwrappedFieldBefore),
                        () -> "the input schema must describe the unwrapped field's flattened content: "
                                + unwrappedFieldBefore),
                () -> assertEquals(
                        unwrappedFieldBefore,
                        unwrappedFieldAfter,
                        "hiddenOnlyMembers must not change what generateCanonical publishes"),
                () -> assertEquals(UNWRAPPED_GETTER_REPORT, unwrappedGetterReport),
                () -> assertEquals(
                        UNWRAPPED_PROPERTIES,
                        publishedPropertyNames(unwrappedGetterBefore),
                        () -> "the input schema must describe the unwrapped getter's flattened content: "
                                + unwrappedGetterBefore),
                () -> assertEquals(
                        unwrappedGetterBefore,
                        unwrappedGetterAfter,
                        "hiddenOnlyMembers must not change what generateCanonical publishes"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nothingToReportCases")
    @DisplayName("Nothing is reported where the generator already hides the member, Jackson does not bind it, or"
            + " nothing carries @Hidden alone")
    void reportsNothingTheGeneratorDoesNotDescribeAsHiddenOnly(String label, Class<?> type) {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        assertEquals(List.of(), generator.hiddenOnlyMembers(type), label);
    }

    private static Stream<Arguments> nothingToReportCases() {
        return Stream.of(
                Arguments.of(
                        "@Hidden beside @Schema(hidden = true) on one field: the generator hides the member",
                        BothMarkers.class),
                Arguments.of(
                        "@Schema(hidden = true) alone: the generator hides the member, and nothing carries @Hidden",
                        SchemaHiddenOnly.class),
                Arguments.of(
                        "@Hidden on the field and @Schema(hidden = true) on its getter: the generator reads the two"
                                + " together and hides the property",
                        SplitMarkers.class),
                Arguments.of(
                        "@JsonIgnore beside @Hidden: Jackson does not bind the member, so the generator does not"
                                + " describe it",
                        IgnoredAndHidden.class),
                Arguments.of("a plain DTO: nothing carries @Hidden", PlainBody.class),
                Arguments.of(
                        "a member type carrying @Schema(hidden = true) beside @Hidden: the type is not hidden only by"
                                + " @Hidden",
                        HidesTypeTwice.class));
    }

    @Test
    @DisplayName("Every hidden-only member reachable through nested members, list elements, map values, and a generic"
            + " type reached under two type arguments is reported once, in declaring-type-then-member order")
    void reportsEveryReachableHiddenOnlyMemberOnce() {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        List<HiddenOnlyMember> orderReport = generator.hiddenOnlyMembers(Order.class);
        String orderSchema = generator.generateCanonical(Order.class);
        Set<String> orderProperties = publishedPropertyNames(orderSchema);
        List<HiddenOnlyMember> boxesReport = generator.hiddenOnlyMembers(Boxes.class);
        String boxesSchema = generator.generateCanonical(Boxes.class);

        assertAll(
                () -> assertEquals(INPUT_ORDER_REPORT, orderReport),
                () -> assertTrue(
                        INPUT_ORDER_REPORT.stream().allMatch(entry -> orderProperties.contains(entry.member())),
                        () -> "every reported member must be a described property of " + orderSchema),
                () -> assertEquals(BOXES_REPORT, boxesReport),
                () -> assertTrue(
                        publishedPropertyNames(boxesSchema).contains("value"),
                        () -> "the reported member must be a described property of " + boxesSchema));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("constructionModes")
    @DisplayName("Input- and output-direction generators answer for their own direction; only a victools-defaults"
            + " generator refuses, with IllegalStateException, before the type grammar is checked")
    void refusesOnlyAVictoolsDefaultsGenerator(
            String label, Supplier<AnnotationJsonSchemaGenerator> factory, Type type, List<HiddenOnlyMember> answer) {
        AnnotationJsonSchemaGenerator generator = factory.get();

        if (answer == null) {
            assertThrows(IllegalStateException.class, () -> generator.hiddenOnlyMembers(type), label);
        } else {
            assertEquals(answer, generator.hiddenOnlyMembers(type), label);
        }
    }

    /** One row per construction mode, plus the refusal's precedence over the type grammar; a null answer refuses. */
    private static Stream<Arguments> constructionModes() {
        return Stream.of(
                Arguments.of(
                        "a victools-defaults generator refuses",
                        (Supplier<AnnotationJsonSchemaGenerator>) AnnotationJsonSchemaGenerator::withVictoolsDefaults,
                        HiddenOnlyBody.class,
                        null),
                Arguments.of(
                        "a victools-defaults generator refuses a type outside the grammar before checking it",
                        (Supplier<AnnotationJsonSchemaGenerator>) AnnotationJsonSchemaGenerator::withVictoolsDefaults,
                        typeVariable(),
                        null),
                Arguments.of(
                        "a forInputProfile generator answers for the input direction",
                        (Supplier<AnnotationJsonSchemaGenerator>)
                                () -> AnnotationJsonSchemaGenerator.forInputProfile(PROFILE),
                        HiddenOnlyBody.class,
                        BODY_REPORT),
                Arguments.of(
                        "a forInputProfile generator with a Bean Validation validator answers for the input direction",
                        (Supplier<AnnotationJsonSchemaGenerator>) () ->
                                AnnotationJsonSchemaGenerator.forInputProfile(PROFILE, MetadataTestValidators.plain()),
                        HiddenOnlyBody.class,
                        BODY_REPORT),
                Arguments.of(
                        "a forOutputProfile generator answers for the output direction",
                        (Supplier<AnnotationJsonSchemaGenerator>)
                                () -> AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE),
                        HiddenOnlyResponse.class,
                        RESPONSE_REPORT));
    }

    @Test
    @DisplayName(
            "hiddenOnlyMembers keeps generateCanonical's type grammar, bounded failure contract, restore-on-failure"
                    + " behavior, and per-instance lock, and the gate's engine still enforces a @Hidden-only member's"
                    + " constraint")
    void keepsTheInputSchemaAndFailureContract() throws Exception {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        // Failure kind: outside generateCanonical's accepted Type grammar.
        JsonSchemaGenerationException typeVariableFailure =
                assertThrows(JsonSchemaGenerationException.class, () -> generator.hiddenOnlyMembers(typeVariable()));
        assertTrue(
                typeVariableFailure.getMessage().contains("unresolved type variable"),
                typeVariableFailure.getMessage());

        // Reuse: the aborted call leaves no residual state on this instance — its later results equal a
        // fresh instance's.
        List<HiddenOnlyMember> reusedReport = generator.hiddenOnlyMembers(HiddenOnlyBody.class);
        String reusedSchema = generator.generateCanonical(HiddenOnlyBody.class);
        AnnotationJsonSchemaGenerator fresh = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);
        assertAll(
                () -> assertEquals(fresh.hiddenOnlyMembers(HiddenOnlyBody.class), reusedReport),
                () -> assertEquals(fresh.generateCanonical(HiddenOnlyBody.class), reusedSchema));

        // Verdicts: the gate's engine still enforces the @Hidden-only member's @Size(max = 3).
        assertAll(
                () -> assertFalse(
                        isValid(reusedSchema, new JsonObject().put("debug", "abcd")),
                        () -> "a four-character debug must be refused by " + reusedSchema),
                () -> assertTrue(
                        isValid(reusedSchema, new JsonObject().put("debug", "abc")),
                        () -> "a three-character debug must be accepted by " + reusedSchema));

        // Lock ordering: a report waiting behind another type's generation on the same instance holds only
        // its own type's members. The held type's hidden-only member is real: a recording open while its
        // generation describes it takes it.
        assertEquals(
                List.of(hidden(HeldHidden.class, "held")),
                AnnotationJsonSchemaGenerator.forInputProfile(PROFILE).hiddenOnlyMembers(HeldHidden.class));

        GatingIntrospector gate = new GatingIntrospector();
        AnnotationJsonSchemaGenerator gated = AnnotationJsonSchemaGenerator.forInputProfile(JsonMapperProfiles.of(
                JsonProfileId.of("hidden-only-member-report-test-gated"),
                JsonMapper.builder().annotationIntrospector(gate).build()));
        FutureTask<String> heldGeneration = new FutureTask<>(() -> gated.generateCanonical(HeldHidden.class));
        FutureTask<List<HiddenOnlyMember>> report =
                new FutureTask<>(() -> gated.hiddenOnlyMembers(HiddenOnlyBody.class));
        Thread heldCaller = new Thread(heldGeneration, "hidden-only-member-report-test-held-generation");
        Thread reportCaller = new Thread(report, "hidden-only-member-report-test-report");
        heldCaller.setDaemon(true);
        reportCaller.setDaemon(true);
        try {
            heldCaller.start();
            assertTrue(
                    gate.entered.await(AWAIT_SECONDS, SECONDS),
                    "the held generation never reached the gate inside the generator's lock");
            reportCaller.start();
            awaitBlockedOnAMonitorHeldBy(reportCaller, heldCaller);
        } finally {
            gate.release.countDown();
        }

        List<HiddenOnlyMember> reported = report.get(AWAIT_SECONDS, SECONDS);
        String heldSchema = heldGeneration.get(AWAIT_SECONDS, SECONDS);
        assertAll(
                () -> assertEquals(BODY_REPORT, reported),
                () -> assertTrue(
                        publishedPropertyNames(heldSchema).contains("held"),
                        () -> "the held generation must describe its @Hidden-only member: " + heldSchema),
                () -> assertTrue(gate.releasedByTheTest, "the gate timed out instead of being released by the test"));
    }

    @Test
    @DisplayName("An output-direction generator reports response members and types hidden only by @Hidden, an"
            + " unwrapped member described through its flattened content included, never a member the output"
            + " schema leaves out, and the call leaves the output schema unchanged")
    void reportsOutputMembersAndTypesHiddenOnlyByHidden() {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE);

        String before = generator.generateCanonical(HiddenOnlyResponse.class);
        List<HiddenOnlyMember> actual = generator.hiddenOnlyMembers(HiddenOnlyResponse.class);
        String after = generator.generateCanonical(HiddenOnlyResponse.class);

        List<HiddenOnlyMember> orderReport = generator.hiddenOnlyMembers(Order.class);
        String orderSchema = generator.generateCanonical(Order.class);
        Set<String> orderProperties = publishedPropertyNames(orderSchema);
        List<HiddenOnlyMember> boxesReport = generator.hiddenOnlyMembers(Boxes.class);
        List<HiddenOnlyMember> getterReport = generator.hiddenOnlyMembers(GetterHiddenResponse.class);
        String getterSchema = generator.generateCanonical(GetterHiddenResponse.class);

        String unwrappedFieldBefore = generator.generateCanonical(HiddenUnwrappedField.class);
        List<HiddenOnlyMember> unwrappedFieldReport = generator.hiddenOnlyMembers(HiddenUnwrappedField.class);
        String unwrappedFieldAfter = generator.generateCanonical(HiddenUnwrappedField.class);
        String unwrappedGetterBefore = generator.generateCanonical(HiddenUnwrappedGetter.class);
        List<HiddenOnlyMember> unwrappedGetterReport = generator.hiddenOnlyMembers(HiddenUnwrappedGetter.class);
        String unwrappedGetterAfter = generator.generateCanonical(HiddenUnwrappedGetter.class);

        assertAll(
                () -> assertEquals(RESPONSE_REPORT, actual),
                () -> assertEquals(
                        Set.of("name", "debug", "nested", "value", "flag"),
                        publishedPropertyNames(before),
                        () -> "the output schema must describe name, debug, nested, nested.value, and nested.flag,"
                                + " and neither both nor ignored: " + before),
                () -> assertEquals(before, after, "hiddenOnlyMembers must not change what generateCanonical publishes"),
                // Reachability in the output direction: nested members and list elements, not map values.
                () -> assertEquals(OUTPUT_ORDER_REPORT, orderReport),
                () -> assertTrue(
                        OUTPUT_ORDER_REPORT.stream().allMatch(entry -> orderProperties.contains(entry.member())),
                        () -> "every reported member must be a described property of " + orderSchema),
                // A generic type reached under two type arguments: one entry.
                () -> assertEquals(BOXES_REPORT, boxesReport),
                // A private field whose getter alone carries @Hidden: the getter, by its own name.
                () -> assertEquals(List.of(hidden(GetterHiddenResponse.class, "getSecret")), getterReport),
                () -> assertTrue(
                        publishedPropertyNames(getterSchema).contains("secret"),
                        () -> "the output schema must describe the getter's property: " + getterSchema),
                // @Hidden alone beside @JsonUnwrapped, on a field and on a getter: the output schema
                // describes the member only through its flattened content, and the member is reported.
                () -> assertEquals(UNWRAPPED_FIELD_REPORT, unwrappedFieldReport),
                () -> assertEquals(
                        UNWRAPPED_PROPERTIES,
                        publishedPropertyNames(unwrappedFieldBefore),
                        () -> "the output schema must describe the unwrapped field's flattened content: "
                                + unwrappedFieldBefore),
                () -> assertEquals(
                        unwrappedFieldBefore,
                        unwrappedFieldAfter,
                        "hiddenOnlyMembers must not change what generateCanonical publishes"),
                () -> assertEquals(UNWRAPPED_GETTER_REPORT, unwrappedGetterReport),
                () -> assertEquals(
                        UNWRAPPED_PROPERTIES,
                        publishedPropertyNames(unwrappedGetterBefore),
                        () -> "the output schema must describe the unwrapped getter's flattened content: "
                                + unwrappedGetterBefore),
                () -> assertEquals(
                        unwrappedGetterBefore,
                        unwrappedGetterAfter,
                        "hiddenOnlyMembers must not change what generateCanonical publishes"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapeMatrix")
    @DisplayName("For every shape a document describes a member or type through, in each direction, the report is"
            + " exactly the @Hidden-only members and types the document describes, covers what swagger-core omits"
            + " and what the profile mapper's merged view sees as @Hidden, and the call leaves the document"
            + " unchanged")
    void reportsEveryDescribedHiddenOnlyMemberInBothDirections(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Class<?> shape,
            Type root,
            List<HiddenOnlyMember> expected) {
        AnnotationJsonSchemaGenerator generator = direction.generator(profile);

        String before = generator.generateCanonical(root);
        List<HiddenOnlyMember> actual = generator.hiddenOnlyMembers(root);
        String after = generator.generateCanonical(root);

        assertAll(
                () -> assertEquals(
                        expected,
                        actual,
                        () -> label + ": the report must list every @Hidden-only member and type the document"
                                + " describes: " + before),
                // Closure: the row's list is exactly what the document describes of its fixture types'
                // @Hidden-only carriers, so no described carrier is left out of the expectation.
                () -> assertEquals(
                        Set.copyOf(expected),
                        describedHiddenOnlyCarriers(before, shape, direction, profile.mapper()),
                        () -> label + ": the expected list must be exactly the @Hidden-only carriers of the"
                                + " shape's types that the document describes: " + before),
                // Differential: what swagger-core, on the row profile's mapper, omits because of @Hidden.
                () -> assertEquals(
                        List.of(),
                        uncoveredSwaggerCoreOmissions(before, root, profile.mapper(), actual),
                        () -> label + ": every property the document describes that swagger-core's model omits"
                                + " because of @Hidden alone must be reported: " + before),
                // Merged view: what Jackson's merged annotations on the profile mapper see as @Hidden.
                () -> assertEquals(
                        List.of(),
                        uncoveredMergedViewCarriers(before, shape, direction, profile.mapper(), actual),
                        () -> label + ": every property and type the document describes that the profile mapper's"
                                + " merged view sees as carrying @Hidden alone must be reported: " + before),
                () -> assertEquals(
                        before, after, "hiddenOnlyMembers must not change what generateCanonical publishes"));
    }

    /**
     * One row per shape and direction: (label, direction, profile, shape holder, root, expected report).
     * A direction that does not describe a shape's member expects it absent.
     */
    private static Stream<Arguments> shapeMatrix() {
        return Stream.of(
                shape(
                        "input: a record component and a record type",
                        Direction.INPUT,
                        RecordShape.class,
                        RecordShape.Root.class,
                        RECORD_REPORT),
                shape(
                        "output: a record component and a record type",
                        Direction.OUTPUT,
                        RecordShape.class,
                        RecordShape.Root.class,
                        RECORD_REPORT),
                shape(
                        "input: properties bound through @JsonCreator parameters",
                        Direction.INPUT,
                        CreatorShape.class,
                        CreatorShape.Root.class,
                        CREATOR_REPORT),
                shape(
                        "output: properties bound through @JsonCreator parameters",
                        Direction.OUTPUT,
                        CreatorShape.class,
                        CreatorShape.Root.class,
                        CREATOR_REPORT),
                shape(
                        "input: a builder method and a built type's field",
                        Direction.INPUT,
                        BuilderShape.class,
                        BuilderShape.Root.class,
                        BUILDER_INPUT_REPORT),
                shape(
                        "output: a built type's field, never a builder method",
                        Direction.OUTPUT,
                        BuilderShape.class,
                        BuilderShape.Root.class,
                        BUILDER_OUTPUT_REPORT),
                shape(
                        "input: @JsonSubTypes subtypes and their base",
                        Direction.INPUT,
                        SubtypeShape.class,
                        SubtypeShape.Root.class,
                        SUBTYPE_REPORT),
                shape(
                        "output: @JsonSubTypes subtypes and their base",
                        Direction.OUTPUT,
                        SubtypeShape.class,
                        SubtypeShape.Root.class,
                        SUBTYPE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached through a member",
                        Direction.INPUT,
                        PolymorphicBaseShape.class,
                        PolymorphicBaseShape.Root.class,
                        POLYMORPHIC_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached through a member",
                        Direction.OUTPUT,
                        PolymorphicBaseShape.class,
                        PolymorphicBaseShape.Root.class,
                        POLYMORPHIC_BASE_REPORT),
                shape(
                        "input: the base member of a subtype registered on the profile mapper",
                        Direction.INPUT,
                        REGISTERED_SUBTYPE_PROFILE,
                        RegisteredSubtypeShape.class,
                        RegisteredSubtypeShape.Root.class,
                        REGISTERED_SUBTYPE_REPORT),
                shape(
                        "output: the base member of a subtype registered on the profile mapper",
                        Direction.OUTPUT,
                        REGISTERED_SUBTYPE_PROFILE,
                        RegisteredSubtypeShape.class,
                        RegisteredSubtypeShape.Root.class,
                        REGISTERED_SUBTYPE_REPORT),
                shape(
                        "input: an abstract member type without type information",
                        Direction.INPUT,
                        AbstractMemberShape.class,
                        AbstractMemberShape.Root.class,
                        ABSTRACT_MEMBER_REPORT),
                shape(
                        "output: an abstract member type without type information",
                        Direction.OUTPUT,
                        AbstractMemberShape.class,
                        AbstractMemberShape.Root.class,
                        ABSTRACT_MEMBER_REPORT),
                shape(
                        "input: an enum constant and an enum type",
                        Direction.INPUT,
                        EnumShape.class,
                        EnumShape.Root.class,
                        ENUM_REPORT),
                shape(
                        "output: an enum constant and an enum type",
                        Direction.OUTPUT,
                        EnumShape.class,
                        EnumShape.Root.class,
                        ENUM_REPORT),
                shape(
                        "input: unwrapped content and an unwrapped member",
                        Direction.INPUT,
                        UnwrappedShape.class,
                        UnwrappedShape.Root.class,
                        UNWRAPPED_CONTENT_REPORT),
                shape(
                        "output: unwrapped content and an unwrapped member",
                        Direction.OUTPUT,
                        UnwrappedShape.class,
                        UnwrappedShape.Root.class,
                        UNWRAPPED_CONTENT_REPORT),
                shape(
                        "input: list, set, optional, array, map-value, and nested map-value elements",
                        Direction.INPUT,
                        ContainerShape.class,
                        ContainerShape.Root.class,
                        CONTAINER_INPUT_REPORT),
                shape(
                        "output: list, set, optional, and array elements; map values are not described",
                        Direction.OUTPUT,
                        ContainerShape.class,
                        ContainerShape.Root.class,
                        CONTAINER_OUTPUT_REPORT),
                shape(
                        "input: a generic type and its type argument",
                        Direction.INPUT,
                        GenericShape.class,
                        GenericShape.Root.class,
                        GENERIC_REPORT),
                shape(
                        "output: a generic type and its type argument",
                        Direction.OUTPUT,
                        GenericShape.class,
                        GenericShape.Root.class,
                        GENERIC_REPORT),
                shape(
                        "input: inherited members",
                        Direction.INPUT,
                        InheritanceShape.class,
                        InheritanceShape.Root.class,
                        INHERITANCE_REPORT),
                shape(
                        "output: inherited members",
                        Direction.OUTPUT,
                        InheritanceShape.class,
                        InheritanceShape.Root.class,
                        INHERITANCE_REPORT),
                shape(
                        "input: getters overriding a superclass or interface getter that carries @Hidden",
                        Direction.INPUT,
                        OverriddenGetterShape.class,
                        OverriddenGetterShape.Root.class,
                        OVERRIDDEN_GETTER_REPORT),
                shape(
                        "output: getters overriding a superclass or interface getter that carries @Hidden",
                        Direction.OUTPUT,
                        OverriddenGetterShape.class,
                        OverriddenGetterShape.Root.class,
                        OVERRIDDEN_GETTER_REPORT),
                shape(
                        "input: an any-setter whose extras are described",
                        Direction.INPUT,
                        AnySetterShape.class,
                        AnySetterShape.Root.class,
                        ANY_SETTER_INPUT_REPORT),
                shape(
                        "output: an any-setter, which serialization never uses",
                        Direction.OUTPUT,
                        AnySetterShape.class,
                        AnySetterShape.Root.class,
                        List.of()),
                shape(
                        "input: @Hidden through a Jackson annotation bundle on a member and a type",
                        Direction.INPUT,
                        BundleShape.class,
                        BundleShape.Root.class,
                        BUNDLE_REPORT),
                shape(
                        "output: @Hidden through a Jackson annotation bundle on a member and a type",
                        Direction.OUTPUT,
                        BundleShape.class,
                        BundleShape.Root.class,
                        BUNDLE_REPORT),
                shape(
                        "input: setter-only, getter-over-private-field, and getter-only collection properties",
                        Direction.INPUT,
                        AccessorShape.class,
                        AccessorShape.Root.class,
                        ACCESSOR_INPUT_REPORT),
                shape(
                        "output: a getter over a private field; setter-only and getter-only collection properties are not described",
                        Direction.OUTPUT,
                        AccessorShape.class,
                        AccessorShape.Root.class,
                        ACCESSOR_OUTPUT_REPORT),
                shape(
                        "input: a public field whose setter alone carries @Hidden",
                        Direction.INPUT,
                        SetterCarrierShape.class,
                        SetterCarrierShape.Root.class,
                        SETTER_CARRIER_REPORT),
                shape(
                        "output: a public field whose setter alone carries @Hidden",
                        Direction.OUTPUT,
                        SetterCarrierShape.class,
                        SetterCarrierShape.Root.class,
                        SETTER_CARRIER_REPORT),
                shape(
                        "input: a type reached through a case-insensitively bound member",
                        Direction.INPUT,
                        CaseInsensitiveShape.class,
                        CaseInsensitiveShape.Root.class,
                        CASE_INSENSITIVE_REPORT),
                shape(
                        "output: a type reached through a case-insensitively bound member",
                        Direction.OUTPUT,
                        CaseInsensitiveShape.class,
                        CaseInsensitiveShape.Root.class,
                        CASE_INSENSITIVE_REPORT),
                shape(
                        "input: a root type carrying @Hidden",
                        Direction.INPUT,
                        HiddenRootShape.class,
                        HiddenRootShape.Root.class,
                        HIDDEN_ROOT_REPORT),
                shape(
                        "output: a root type carrying @Hidden",
                        Direction.OUTPUT,
                        HiddenRootShape.class,
                        HiddenRootShape.Root.class,
                        HIDDEN_ROOT_REPORT),
                shape(
                        "input: getter overrides adding @Hidden in a concrete and an abstract subclass",
                        Direction.INPUT,
                        SubclassOverrideShape.class,
                        SubclassOverrideShape.Root.class,
                        SUBCLASS_OVERRIDE_REPORT),
                shape(
                        "output: getter overrides adding @Hidden in a concrete and an abstract subclass",
                        Direction.OUTPUT,
                        SubclassOverrideShape.class,
                        SubclassOverrideShape.Root.class,
                        SUBCLASS_OVERRIDE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached as a member of an abstract member type",
                        Direction.INPUT,
                        LibraryMemberBaseShape.class,
                        LibraryMemberBaseShape.Root.class,
                        LIBRARY_MEMBER_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached as a member of an abstract member type",
                        Direction.OUTPUT,
                        LibraryMemberBaseShape.class,
                        LibraryMemberBaseShape.Root.class,
                        LIBRARY_MEMBER_BASE_REPORT),
                shape(
                        "input: type-level @Hidden polymorphic bases in nested lists, an optional, map values, an"
                                + " array of lists, and an optional list",
                        Direction.INPUT,
                        DeepPolymorphicBaseShape.class,
                        DeepPolymorphicBaseShape.Root.class,
                        DEEP_POLYMORPHIC_BASE_INPUT_REPORT),
                shape(
                        "output: type-level @Hidden polymorphic bases in nested lists, an optional, an array of lists,"
                                + " and an optional list; map values are not described",
                        Direction.OUTPUT,
                        DeepPolymorphicBaseShape.class,
                        DeepPolymorphicBaseShape.Root.class,
                        DEEP_POLYMORPHIC_BASE_OUTPUT_REPORT),
                shape(
                        "input: @Hidden merged from a mix-in's field and getter",
                        Direction.INPUT,
                        MIX_IN_PROFILE,
                        MixInShape.class,
                        MixInShape.Root.class,
                        MIX_IN_REPORT),
                shape(
                        "output: @Hidden merged from a mix-in's field and getter",
                        Direction.OUTPUT,
                        MIX_IN_PROFILE,
                        MixInShape.class,
                        MixInShape.Root.class,
                        MIX_IN_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached only as a list element",
                        Direction.INPUT,
                        ListElementBaseShape.class,
                        ListElementBaseShape.ListRoot.class,
                        LIST_ELEMENT_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached only as a list element",
                        Direction.OUTPUT,
                        ListElementBaseShape.class,
                        ListElementBaseShape.ListRoot.class,
                        LIST_ELEMENT_BASE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached only as an element of a list that is a"
                                + " generic type argument",
                        Direction.INPUT,
                        ListElementBaseShape.class,
                        ListElementBaseShape.GenericListRoot.class,
                        LIST_ELEMENT_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached only as an element of a list that is a"
                                + " generic type argument",
                        Direction.OUTPUT,
                        ListElementBaseShape.class,
                        ListElementBaseShape.GenericListRoot.class,
                        LIST_ELEMENT_BASE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached only as a list element of an abstract"
                                + " member type",
                        Direction.INPUT,
                        ListElementBaseShape.class,
                        ListElementBaseShape.AbstractListRoot.class,
                        LIST_ELEMENT_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached only as a list element of an abstract"
                                + " member type",
                        Direction.OUTPUT,
                        ListElementBaseShape.class,
                        ListElementBaseShape.AbstractListRoot.class,
                        LIST_ELEMENT_BASE_REPORT),
                shape(
                        "input: a type-level @Hidden merged from a mix-in",
                        Direction.INPUT,
                        MIX_IN_TYPE_PROFILE,
                        MixInTypeShape.class,
                        MixInTypeShape.Root.class,
                        MIX_IN_TYPE_REPORT),
                shape(
                        "output: a type-level @Hidden merged from a mix-in",
                        Direction.OUTPUT,
                        MIX_IN_TYPE_PROFILE,
                        MixInTypeShape.class,
                        MixInTypeShape.Root.class,
                        MIX_IN_TYPE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached only as a set element",
                        Direction.INPUT,
                        ContainerElementBaseShape.class,
                        ContainerElementBaseShape.SetRoot.class,
                        CONTAINER_ELEMENT_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached only as a set element",
                        Direction.OUTPUT,
                        ContainerElementBaseShape.class,
                        ContainerElementBaseShape.SetRoot.class,
                        CONTAINER_ELEMENT_BASE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached only as an array element",
                        Direction.INPUT,
                        ContainerElementBaseShape.class,
                        ContainerElementBaseShape.ArrayRoot.class,
                        CONTAINER_ELEMENT_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached only as an array element",
                        Direction.OUTPUT,
                        ContainerElementBaseShape.class,
                        ContainerElementBaseShape.ArrayRoot.class,
                        CONTAINER_ELEMENT_BASE_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base reached only as a collection element",
                        Direction.INPUT,
                        ContainerElementBaseShape.class,
                        ContainerElementBaseShape.CollectionRoot.class,
                        CONTAINER_ELEMENT_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base reached only as a collection element",
                        Direction.OUTPUT,
                        ContainerElementBaseShape.class,
                        ContainerElementBaseShape.CollectionRoot.class,
                        CONTAINER_ELEMENT_BASE_REPORT),
                shape(
                        "input: a type-level @Hidden merged from a mix-in into the root type",
                        Direction.INPUT,
                        MIX_IN_ROOT_PROFILE,
                        MixInRootShape.class,
                        MixInRootShape.Root.class,
                        MIX_IN_ROOT_REPORT),
                shape(
                        "output: a type-level @Hidden merged from a mix-in into the root type",
                        Direction.OUTPUT,
                        MIX_IN_ROOT_PROFILE,
                        MixInRootShape.class,
                        MixInRootShape.Root.class,
                        MIX_IN_ROOT_REPORT),
                shape(
                        "input: @Hidden merged from a superclass's mix-in into a field a subclass inherits",
                        Direction.INPUT,
                        MIX_IN_SUPERCLASS_PROFILE,
                        MixInSuperclassShape.class,
                        MixInSuperclassShape.Root.class,
                        MIX_IN_SUPERCLASS_REPORT),
                shape(
                        "output: @Hidden merged from a superclass's mix-in into a field a subclass inherits",
                        Direction.OUTPUT,
                        MIX_IN_SUPERCLASS_PROFILE,
                        MixInSuperclassShape.class,
                        MixInSuperclassShape.Root.class,
                        MIX_IN_SUPERCLASS_REPORT),
                shape(
                        "input: setter-bound plain and list properties of an abstract member type, @Hidden on the"
                                + " setter",
                        Direction.INPUT,
                        SetterBoundShape.class,
                        SetterBoundShape.DirectRoot.class,
                        SETTER_BOUND_DIRECT_INPUT_REPORT),
                shape(
                        "input: setter-bound plain and list properties of an abstract member type, @Hidden through a"
                                + " bundle on the setter",
                        Direction.INPUT,
                        SetterBoundShape.class,
                        SetterBoundShape.BundleRoot.class,
                        SETTER_BOUND_BUNDLE_INPUT_REPORT),
                shape(
                        "input: setter-bound plain and list properties of an abstract member type, @Hidden on a"
                                + " mix-in's setter",
                        Direction.INPUT,
                        SETTER_BOUND_MIX_IN_PROFILE,
                        SetterBoundMixInShape.class,
                        SetterBoundMixInShape.Root.class,
                        SETTER_BOUND_MIX_IN_INPUT_REPORT),
                shape(
                        "input: setter-bound plain and list properties of an abstract member type, @Hidden on a"
                                + " setter @JsonProperty renames",
                        Direction.INPUT,
                        RenamedSetterShape.class,
                        RenamedSetterShape.Root.class,
                        RENAMED_SETTER_INPUT_REPORT),
                shape(
                        "input: a type-level @Hidden polymorphic base the profile overrides, reached only as a list"
                                + " element",
                        Direction.INPUT,
                        OVERRIDDEN_BASE_PROFILE,
                        OverriddenBaseShape.class,
                        OverriddenBaseShape.ListRoot.class,
                        OVERRIDDEN_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base the profile overrides, reached only as a list"
                                + " element",
                        Direction.OUTPUT,
                        OVERRIDDEN_BASE_PROFILE,
                        OverriddenBaseShape.class,
                        OverriddenBaseShape.ListRoot.class,
                        OVERRIDDEN_BASE_REPORT),
                shape(
                        "output: a type-level @Hidden polymorphic base the profile overrides, reached as a plain"
                                + " member",
                        Direction.OUTPUT,
                        OVERRIDDEN_BASE_PROFILE,
                        OverriddenBaseShape.class,
                        OverriddenBaseShape.MemberRoot.class,
                        OVERRIDDEN_BASE_REPORT),
                shape(
                        "input: generic @Hidden setters overridden with a concrete parameter type, on a concrete"
                                + " and an abstract member type",
                        Direction.INPUT,
                        GenericSetterShape.class,
                        GenericSetterShape.Root.class,
                        GENERIC_SETTER_REPORT),
                shape(
                        "output: generic @Hidden setters overridden with a concrete parameter type, on a concrete"
                                + " and an abstract member type",
                        Direction.OUTPUT,
                        GenericSetterShape.class,
                        GenericSetterShape.Root.class,
                        GENERIC_SETTER_REPORT),
                shape(
                        "input: @Hidden on a mix-in's superclass, on a field and on the type",
                        Direction.INPUT,
                        MIX_IN_ANCESTOR_PROFILE,
                        MixInAncestorShape.class,
                        MixInAncestorShape.Root.class,
                        MIX_IN_ANCESTOR_REPORT),
                shape(
                        "output: @Hidden on a mix-in's superclass, on a field and on the type",
                        Direction.OUTPUT,
                        MIX_IN_ANCESTOR_PROFILE,
                        MixInAncestorShape.class,
                        MixInAncestorShape.Root.class,
                        MIX_IN_ANCESTOR_REPORT),
                shape(
                        "input: a superclass's mix-in marking a getter only the subclass declares",
                        Direction.INPUT,
                        INHERITED_MIX_IN_METHOD_PROFILE,
                        InheritedMixInMethodShape.class,
                        InheritedMixInMethodShape.Root.class,
                        INHERITED_MIX_IN_METHOD_REPORT),
                shape(
                        "output: a superclass's mix-in marking a getter only the subclass declares",
                        Direction.OUTPUT,
                        INHERITED_MIX_IN_METHOD_PROFILE,
                        InheritedMixInMethodShape.class,
                        InheritedMixInMethodShape.Root.class,
                        INHERITED_MIX_IN_METHOD_REPORT),
                shape(
                        "input: an unwrapped @Hidden field of an abstract member type",
                        Direction.INPUT,
                        UnwrappedInputShape.class,
                        UnwrappedInputShape.LibraryRoot.class,
                        UNWRAPPED_LIBRARY_INPUT_REPORT),
                shape(
                        "input: an unwrapped @Hidden getter-only member",
                        Direction.INPUT,
                        UnwrappedInputShape.class,
                        UnwrappedInputShape.GetterOnlyRoot.class,
                        UNWRAPPED_GETTER_ONLY_INPUT_REPORT),
                shape(
                        "input: an unwrapped @Hidden READ_ONLY field",
                        Direction.INPUT,
                        UnwrappedInputShape.class,
                        UnwrappedInputShape.ReadOnlyRoot.class,
                        UNWRAPPED_READ_ONLY_INPUT_REPORT),
                shape(
                        "input: a setter-bound property whose private field carries both markers",
                        Direction.INPUT,
                        SetterBothMarkersShape.class,
                        SetterBothMarkersShape.Root.class,
                        List.of()),
                shape(
                        "input: an enum constant carrying @Hidden through an enum mix-in",
                        Direction.INPUT,
                        ENUM_MIX_IN_PROFILE,
                        EnumMixInShape.class,
                        EnumMixInShape.Root.class,
                        ENUM_MIX_IN_REPORT),
                shape(
                        "output: an enum constant carrying @Hidden through an enum mix-in",
                        Direction.OUTPUT,
                        ENUM_MIX_IN_PROFILE,
                        EnumMixInShape.class,
                        EnumMixInShape.Root.class,
                        ENUM_MIX_IN_REPORT),
                shape(
                        "input: a builder method overriding a @Hidden base-builder method",
                        Direction.INPUT,
                        SuperBuilderShape.class,
                        SuperBuilderShape.Ticket.class,
                        SUPER_BUILDER_INPUT_REPORT),
                shape(
                        "input: a setter-bound property of an abstract member type whose setter carries both markers",
                        Direction.INPUT,
                        SetterBothMarkersLibraryShape.class,
                        SetterBothMarkersLibraryShape.Root.class,
                        List.of()),
                shape(
                        "input: a property with a getter whose setter carries both markers",
                        Direction.INPUT,
                        SetterBothMarkersGetterShape.class,
                        SetterBothMarkersGetterShape.Root.class,
                        List.of()),
                shape(
                        "output: a property with a getter whose setter carries both markers",
                        Direction.OUTPUT,
                        SetterBothMarkersGetterShape.class,
                        SetterBothMarkersGetterShape.Root.class,
                        List.of()),
                shape(
                        "input: sub override @Schema(hidden), base @Hidden",
                        Direction.INPUT,
                        OverrideSchemaOnSubShape.class,
                        OverrideSchemaOnSubShape.Root.class,
                        List.of(hidden(OverrideSchemaOnSubShape.Base.class, "setPin"))),
                shape(
                        "output: sub override @Schema(hidden), base @Hidden",
                        Direction.OUTPUT,
                        OverrideSchemaOnSubShape.class,
                        OverrideSchemaOnSubShape.Root.class,
                        List.of(hidden(OverrideSchemaOnSubShape.Base.class, "setPin"))),
                shape(
                        "input: sub override both, base @Hidden",
                        Direction.INPUT,
                        OverrideBothOnSubShape.class,
                        OverrideBothOnSubShape.Root.class,
                        List.of(hidden(OverrideBothOnSubShape.Base.class, "setPin"))),
                shape(
                        "output: sub override both, base @Hidden",
                        Direction.OUTPUT,
                        OverrideBothOnSubShape.class,
                        OverrideBothOnSubShape.Root.class,
                        List.of(hidden(OverrideBothOnSubShape.Base.class, "setPin"))),
                shape(
                        "input: base @Schema(hidden), sub override @Hidden",
                        Direction.INPUT,
                        SchemaOnBaseHiddenOnSubShape.class,
                        SchemaOnBaseHiddenOnSubShape.Root.class,
                        List.of(hidden(SchemaOnBaseHiddenOnSubShape.Root.class, "setPin"))),
                shape(
                        "output: base @Schema(hidden), sub override @Hidden",
                        Direction.OUTPUT,
                        SchemaOnBaseHiddenOnSubShape.class,
                        SchemaOnBaseHiddenOnSubShape.Root.class,
                        List.of(hidden(SchemaOnBaseHiddenOnSubShape.Root.class, "setPin"))),
                shape(
                        "input: generic @Hidden, concrete override @Schema(hidden)",
                        Direction.INPUT,
                        GenericOverrideSchemaShape.class,
                        GenericOverrideSchemaShape.Root.class,
                        List.of(hidden(GenericOverrideSchemaShape.Settable.class, "setPin"))),
                shape(
                        "output: generic @Hidden, concrete override @Schema(hidden)",
                        Direction.OUTPUT,
                        GenericOverrideSchemaShape.class,
                        GenericOverrideSchemaShape.Root.class,
                        List.of(hidden(GenericOverrideSchemaShape.Settable.class, "setPin"))),
                shape(
                        "input: generic @Hidden, concrete override both",
                        Direction.INPUT,
                        GenericOverrideBothShape.class,
                        GenericOverrideBothShape.Root.class,
                        List.of(hidden(GenericOverrideBothShape.Settable.class, "setPin"))),
                shape(
                        "output: generic @Hidden, concrete override both",
                        Direction.OUTPUT,
                        GenericOverrideBothShape.class,
                        GenericOverrideBothShape.Root.class,
                        List.of(hidden(GenericOverrideBothShape.Settable.class, "setPin"))),
                shape(
                        "input: setterless getter @Schema(description), field both",
                        Direction.INPUT,
                        SetterlessBothFieldShape.class,
                        SetterlessBothFieldShape.Root.class,
                        List.of()),
                shape(
                        "output: setterless getter @Schema(description), field both",
                        Direction.OUTPUT,
                        SetterlessBothFieldShape.class,
                        SetterlessBothFieldShape.Root.class,
                        List.of()),
                shape(
                        "input: setterless getter @Schema(description), field @Hidden",
                        Direction.INPUT,
                        SetterlessHiddenFieldShape.class,
                        SetterlessHiddenFieldShape.Root.class,
                        List.of(hidden(SetterlessHiddenFieldShape.Root.class, "tags"))),
                shape(
                        "input: unwrapped getter @Schema(description), field both",
                        Direction.INPUT,
                        UnwrappedGetterBothFieldShape.class,
                        UnwrappedGetterBothFieldShape.Root.class,
                        List.of()),
                shape(
                        "output: unwrapped getter @Schema(description), field both",
                        Direction.OUTPUT,
                        UnwrappedGetterBothFieldShape.class,
                        UnwrappedGetterBothFieldShape.Root.class,
                        List.of()),
                shape(
                        "input: library type, @Hidden private field, setter @Schema(hidden)",
                        Direction.INPUT,
                        SetterSchemaLibraryHiddenFieldShape.class,
                        SetterSchemaLibraryHiddenFieldShape.Root.class,
                        List.of(hidden(SetterSchemaLibraryHiddenFieldShape.Locker.class, "lockerSecret"))),
                shape(
                        "input: concrete type, @Hidden private field, setter @Schema(hidden)",
                        Direction.INPUT,
                        SetterSchemaConcreteHiddenFieldShape.class,
                        SetterSchemaConcreteHiddenFieldShape.Root.class,
                        List.of(hidden(SetterSchemaConcreteHiddenFieldShape.Root.class, "secret"))),
                shape(
                        "input: mix-in @Schema(hidden) on @Hidden setter",
                        Direction.INPUT,
                        SETTER_SCHEMA_MIX_IN_PROFILE,
                        MixInSchemaSetterShape.class,
                        MixInSchemaSetterShape.Root.class,
                        List.of()),
                shape(
                        "output: mix-in @Schema(hidden) on @Hidden setter",
                        Direction.OUTPUT,
                        SETTER_SCHEMA_MIX_IN_PROFILE,
                        MixInSchemaSetterShape.class,
                        MixInSchemaSetterShape.Root.class,
                        List.of()),
                shape(
                        "input: setter-only, base @Hidden, override @Schema(hidden)",
                        Direction.INPUT,
                        SetterOnlyOverrideSchemaShape.class,
                        SetterOnlyOverrideSchemaShape.Root.class,
                        List.of(hidden(SetterOnlyOverrideSchemaShape.Base.class, "setPin"))),
                shape(
                        "input: setter-only, base @Schema(hidden), override @Hidden",
                        Direction.INPUT,
                        SetterOnlyOverrideHiddenShape.class,
                        SetterOnlyOverrideHiddenShape.Root.class,
                        List.of(hidden(SetterOnlyOverrideHiddenShape.Root.class, "setPin"))),
                shape(
                        "input: setter-only, base @Hidden, override both",
                        Direction.INPUT,
                        SetterOnlyOverrideBothShape.class,
                        SetterOnlyOverrideBothShape.Root.class,
                        List.of(hidden(SetterOnlyOverrideBothShape.Base.class, "setPin"))),
                shape(
                        "input: base @Schema(hidden) setter, subclass mix-in @Hidden",
                        Direction.INPUT,
                        SUBCLASS_MIX_IN_HIDDEN_PROFILE,
                        SubclassMixInHiddenShape.class,
                        SubclassMixInHiddenShape.Root.class,
                        List.of()),
                shape(
                        "output: base @Schema(hidden) setter, subclass mix-in @Hidden",
                        Direction.OUTPUT,
                        SUBCLASS_MIX_IN_HIDDEN_PROFILE,
                        SubclassMixInHiddenShape.class,
                        SubclassMixInHiddenShape.Root.class,
                        List.of()),
                shape(
                        "input: base @Hidden setter, subclass mix-in @Schema(hidden)",
                        Direction.INPUT,
                        SUBCLASS_MIX_IN_SCHEMA_PROFILE,
                        SubclassMixInSchemaShape.class,
                        SubclassMixInSchemaShape.Root.class,
                        List.of(hidden(SubclassMixInSchemaShape.Base.class, "setPin"))),
                shape(
                        "output: base @Hidden setter, subclass mix-in @Schema(hidden)",
                        Direction.OUTPUT,
                        SUBCLASS_MIX_IN_SCHEMA_PROFILE,
                        SubclassMixInSchemaShape.class,
                        SubclassMixInSchemaShape.Root.class,
                        List.of(hidden(SubclassMixInSchemaShape.Base.class, "setPin"))),
                shape(
                        "output: setterless getter @Schema(description), field @Hidden",
                        Direction.OUTPUT,
                        SetterlessHiddenFieldShape.class,
                        SetterlessHiddenFieldShape.Root.class,
                        List.of(hidden(SetterlessHiddenFieldShape.Root.class, "tags"))),
                shape(
                        "output: library type, @Hidden private field, setter @Schema(hidden)",
                        Direction.OUTPUT,
                        SetterSchemaLibraryHiddenFieldShape.class,
                        SetterSchemaLibraryHiddenFieldShape.Root.class,
                        List.of()),
                shape(
                        "output: concrete type, @Hidden private field, setter @Schema(hidden)",
                        Direction.OUTPUT,
                        SetterSchemaConcreteHiddenFieldShape.class,
                        SetterSchemaConcreteHiddenFieldShape.Root.class,
                        List.of()));
    }

    // ---------------------------------------------------------------- helpers

    private static HiddenOnlyMember hidden(Class<?> declaringType, String member) {
        return new HiddenOnlyMember(declaringType.getName(), member);
    }

    /** A generator direction, and how a row builds a generator for it. */
    private enum Direction {
        INPUT {
            @Override
            AnnotationJsonSchemaGenerator generator(JsonMapperProfile profile) {
                return AnnotationJsonSchemaGenerator.forInputProfile(profile);
            }
        },
        OUTPUT {
            @Override
            AnnotationJsonSchemaGenerator generator(JsonMapperProfile profile) {
                return AnnotationJsonSchemaGenerator.forOutputProfile(profile);
            }
        };

        abstract AnnotationJsonSchemaGenerator generator(JsonMapperProfile profile);
    }

    /** A shape-matrix row over the plain profile. */
    private static Arguments shape(
            String label, Direction direction, Class<?> shape, Type root, List<HiddenOnlyMember> expected) {
        return shape(label, direction, PROFILE, shape, root, expected);
    }

    private static Arguments shape(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Class<?> shape,
            Type root,
            List<HiddenOnlyMember> expected) {
        return Arguments.of(label, direction, profile, shape, root, expected);
    }

    private static ObjectMapper registeredSubtypeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerSubtypes(new NamedType(RegisteredSubtypeShape.PremiumAccount.class, "premium"));
        return mapper;
    }

    private static ObjectMapper mixInMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInShape.Root.class, MixInShape.RootMixIn.class);
        return mapper;
    }

    private static ObjectMapper mixInTypeMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInTypeShape.Stowed.class, MixInTypeShape.StowedMixIn.class);
        return mapper;
    }

    private static ObjectMapper mixInRootMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInRootShape.Root.class, MixInRootShape.RootMixIn.class);
        return mapper;
    }

    private static ObjectMapper mixInSuperclassMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInSuperclassShape.Parent.class, MixInSuperclassShape.ParentMixIn.class);
        return mapper;
    }

    private static ObjectMapper mixInAncestorMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInAncestorShape.Vault.class, MixInAncestorShape.VaultMixIn.class);
        mapper.addMixIn(MixInAncestorShape.Safe.class, MixInAncestorShape.SafeMixIn.class);
        return mapper;
    }

    private static ObjectMapper inheritedMixInMethodMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(InheritedMixInMethodShape.Ancestor.class, InheritedMixInMethodShape.AncestorMixIn.class);
        return mapper;
    }

    private static ObjectMapper enumMixInMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(EnumMixInShape.Tier.class, EnumMixInShape.TierMixIn.class);
        return mapper;
    }

    private static ObjectMapper setterBoundMixInMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(SetterBoundMixInShape.Panel.class, SetterBoundMixInShape.PanelMixIn.class);
        return mapper;
    }

    private static ObjectMapper setterSchemaMixInMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInSchemaSetterShape.Root.class, MixInSchemaSetterShape.RootMixIn.class);
        return mapper;
    }

    private static ObjectMapper subclassMixInHiddenMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(SubclassMixInHiddenShape.Root.class, SubclassMixInHiddenShape.RootMixIn.class);
        return mapper;
    }

    private static ObjectMapper subclassMixInSchemaMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(SubclassMixInSchemaShape.Root.class, SubclassMixInSchemaShape.RootMixIn.class);
        return mapper;
    }

    /**
     * The shape matrix's reflection oracle: every member and type nested in {@code shape} that carries
     * {@link Hidden}, directly or through a Jackson annotation bundle, without declaring {@code
     * {@literal @}Schema(hidden = true)}, and that {@code document} describes in {@code direction}.
     *
     * <p>Mix-ins are read as the mapper merges them: a type also carries what its mix-in class and that
     * class's superclasses declare; a field, what a same-named field of that chain declares; a method,
     * what a method of the same name and parameter types declares in the mix-in chain of its type or of
     * any supertype. The nearest mix-in's {@code @Schema} takes precedence; a mix-in class, or a
     * superclass of one, is never a carrier. An overridden method is read by itself, under its own name
     * and declaring type, whether its override keeps its raw parameter types or, for a generic
     * declaration, bridges to different ones; an override carries only what it declares. It reads only
     * the document, the fixture types, and the mapper's mix-in registrations, never the generator:
     *
     * <ul>
     *   <li>a field, getter, setter, or record accessor is described when the document names its
     *       property ({@code @JsonProperty} or the accessor name without its prefix) under a {@code
     *       properties} object — for an accessor {@code @JsonProperty} renames, also when the document
     *       names it by the accessor name without its prefix, its property's internal name;
     *   <li>an enum constant is described when a document {@code enum} or {@code const} lists it;
     *   <li>a builder method is described, in the input direction only, when the document names the
     *       property it sets;
     *   <li>a {@code @JsonUnwrapped} member is described when its type is;
     *   <li>an any-setter is described when a schema naming its type's properties describes extras with
     *       an {@code additionalProperties} schema;
     *   <li>a type is described when the document names one of its own properties or constants.
     * </ul>
     */
    private static Set<HiddenOnlyMember> describedHiddenOnlyCarriers(
            String document, Class<?> shape, Direction direction, ObjectMapper mapper) {
        JsonNode tree = readDocument(document);
        Set<String> names = new HashSet<>();
        collectDescribedNames(tree, names);
        Set<Class<?>> types = shapeTypes(shape);
        Set<Class<?>> mixIns = mixInClasses(mapper, types);
        Set<HiddenOnlyMember> carriers = new HashSet<>();
        for (Class<?> type : types) {
            if (mixIns.contains(type)) {
                continue;
            }
            if (hiddenOnly(type, typeMixIns(mapper, type)) && isDescribed(type, names)) {
                carriers.add(hidden(type, null));
            }
            for (Field field : type.getDeclaredFields()) {
                if (isPropertyField(field) && hiddenOnly(field, fieldMixIns(mapper, type, field))) {
                    boolean described = field.isAnnotationPresent(JsonUnwrapped.class)
                            ? isDescribed(field.getType(), names)
                            : names.contains(propertyName(field));
                    if (described) {
                        carriers.add(hidden(type, field.getName()));
                    }
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()
                        || Modifier.isStatic(method.getModifiers())
                        || !hiddenOnly(method, methodMixIns(mapper, type, method))) {
                    continue;
                }
                boolean described;
                if (method.isAnnotationPresent(JsonAnySetter.class)) {
                    described = describesExtras(tree, type);
                } else if (type.isAnnotationPresent(JsonPOJOBuilder.class)) {
                    described = direction == Direction.INPUT && names.contains(propertyName(method));
                } else if (method.isAnnotationPresent(JsonUnwrapped.class)) {
                    described = isDescribed(method.getReturnType(), names);
                } else {
                    // A renamed accessor's property may be described under its internal name, through
                    // the field the schema library reads instead of the accessor.
                    described = names.contains(propertyName(method)) || names.contains(implicitName(method));
                }
                if (described) {
                    carriers.add(hidden(type, method.getName()));
                }
            }
        }
        return carriers;
    }

    /**
     * The differential oracle: each property that swagger-core's model resolver omits from its model
     * because of {@code @Hidden} alone, and that {@code document} describes, which {@code report} does not
     * cover. The resolver is built on a copy of the row profile's {@code mapper}, so its mix-ins and
     * subtypes apply (the resolver registers a module on the mapper it is given, which the profile's own
     * mapper must never see), and reads {@code root} through a fresh {@link ModelConverters}.
     *
     * <p>"Because of {@code @Hidden} alone" is swagger-core's own verdict, re-asked once: its property
     * predicate ({@code ModelResolver#ignore}) omits the property, and omits it no longer when its hidden
     * check ({@code hasHiddenAnnotation}) is narrowed to {@code @Schema(hidden = true)}. A property it
     * omits for {@code @JsonIgnore}, an ignoral list, or {@code @Schema(hidden = true)} is therefore not
     * one. See {@link #describesProperty} and {@link #covers} for "describes" and "covers".
     */
    private static List<String> uncoveredSwaggerCoreOmissions(
            String document, Type root, ObjectMapper mapper, List<HiddenOnlyMember> report) {
        HiddenRecordingModelResolver resolver = new HiddenRecordingModelResolver(mapper.copy());
        ModelConverters converters = new ModelConverters();
        converters.addConverter(resolver);
        converters.readAll(root);
        Set<String> names = describedNames(document);
        Set<String> uncovered = new TreeSet<>();
        for (BeanPropertyDefinition property : resolver.omittedForHidden) {
            if (describesProperty(property, names) && !covers(report, property)) {
                uncovered.add(propertyLabel(property));
            }
        }
        return List.copyOf(uncovered);
    }

    /**
     * swagger-core's model resolver, recording each property its {@code ignore} predicate omits because
     * of {@code @Hidden} alone: omitted as swagger-core decides, and kept when its hidden check is
     * narrowed to {@code @Schema(hidden = true)}.
     */
    private static final class HiddenRecordingModelResolver extends ModelResolver {
        private final List<BeanPropertyDefinition> omittedForHidden = new ArrayList<>();
        private boolean hiddenDisregarded;

        HiddenRecordingModelResolver(ObjectMapper mapper) {
            super(mapper);
        }

        @Override
        protected boolean hasHiddenAnnotation(Annotated annotated) {
            return hiddenDisregarded
                    ? schemaHidden(annotated.getAnnotation(Schema.class))
                    : super.hasHiddenAnnotation(annotated);
        }

        @Override
        protected boolean ignore(
                Annotated member,
                XmlAccessorType xmlAccessorTypeAnnotation,
                String propName,
                Set<String> propertiesToIgnore,
                BeanPropertyDefinition propDef) {
            boolean ignored = super.ignore(member, xmlAccessorTypeAnnotation, propName, propertiesToIgnore, propDef);
            if (ignored && propDef != null) {
                hiddenDisregarded = true;
                try {
                    if (!super.ignore(member, xmlAccessorTypeAnnotation, propName, propertiesToIgnore, propDef)) {
                        omittedForHidden.add(propDef);
                    }
                } finally {
                    hiddenDisregarded = false;
                }
            }
            return ignored;
        }
    }

    /**
     * The merged-view oracle: each property and type nested in {@code shape} that the row profile's
     * {@code mapper} — its deserialization view in the input direction, its serialization view in the
     * output direction, with mix-ins, bundles, and overridden declarations merged as Jackson merges them —
     * sees as carrying {@code @Hidden} and not {@code @Schema(hidden = true)}, that {@code document}
     * describes, and that {@code report} does not cover.
     *
     * <p>A property carries {@code @Hidden} when any of its field, getter, setter, or creator parameter
     * has it ({@code hasAnnotation}) and none has {@code @Schema(hidden = true)}; a type, when {@code
     * AnnotatedClassResolver.resolveWithoutSuperTypes} has it and not {@code @Schema(hidden = true)}, and it
     * is described when the document names one of its own properties or constants, and covered only by
     * its own type-level entry. Mix-in classes and their superclasses are not read as types of the shape.
     */
    private static List<String> uncoveredMergedViewCarriers(
            String document, Class<?> shape, Direction direction, ObjectMapper mapper, List<HiddenOnlyMember> report) {
        Set<String> names = describedNames(document);
        MapperConfig<?> config =
                direction == Direction.INPUT ? mapper.getDeserializationConfig() : mapper.getSerializationConfig();
        Set<Class<?>> types = shapeTypes(shape);
        Set<Class<?>> mixIns = mixInClasses(mapper, types);
        Set<String> uncovered = new TreeSet<>();
        for (Class<?> type : types) {
            if (mixIns.contains(type) || type.isAnnotation()) {
                continue;
            }
            AnnotatedClass resolved = AnnotatedClassResolver.resolveWithoutSuperTypes(config, type);
            if (resolved.hasAnnotation(Hidden.class)
                    && !schemaHidden(resolved.getAnnotation(Schema.class))
                    && isDescribed(type, names)
                    && !report.contains(hidden(type, null))) {
                uncovered.add("type " + type.getName());
            }
            BeanDescription description = direction == Direction.INPUT
                    ? mapper.getDeserializationConfig().introspect(mapper.constructType(type))
                    : mapper.getSerializationConfig().introspect(mapper.constructType(type));
            for (BeanPropertyDefinition property : description.findProperties()) {
                if (mergedHiddenOnly(property) && describesProperty(property, names) && !covers(report, property)) {
                    uncovered.add(propertyLabel(property));
                }
            }
        }
        return List.copyOf(uncovered);
    }

    /** Whether any of {@code property}'s accessors has {@code @Hidden} and none {@code @Schema(hidden = true)}. */
    private static boolean mergedHiddenOnly(BeanPropertyDefinition property) {
        boolean hidden = false;
        for (AnnotatedMember accessor : accessors(property)) {
            if (schemaHidden(accessor.getAnnotation(Schema.class))) {
                return false;
            }
            hidden |= accessor.hasAnnotation(Hidden.class);
        }
        return hidden;
    }

    /** {@code property}'s field, getter, setter, and creator parameter, those it has. */
    private static List<AnnotatedMember> accessors(BeanPropertyDefinition property) {
        List<AnnotatedMember> accessors = new ArrayList<>();
        for (AnnotatedMember accessor : new AnnotatedMember[] {
            property.getField(), property.getGetter(), property.getSetter(), property.getConstructorParameter()
        }) {
            if (accessor != null) {
                accessors.add(accessor);
            }
        }
        return accessors;
    }

    /**
     * Whether the document describes {@code property}: it names the property's external or internal name
     * under a {@code properties} object, or, for a property an accessor unwraps, one of the unwrapped
     * type's own properties.
     */
    private static boolean describesProperty(BeanPropertyDefinition property, Set<String> names) {
        for (AnnotatedMember accessor : accessors(property)) {
            JsonUnwrapped unwrapped = accessor.getAnnotation(JsonUnwrapped.class);
            if (unwrapped != null && unwrapped.enabled()) {
                return isDescribed(property.getRawPrimaryType(), names);
            }
        }
        return names.contains(property.getName()) || names.contains(property.getInternalName());
    }

    /**
     * Whether {@code report} covers {@code property}: it holds an entry naming one of the property's
     * field, getter, or setter by its own name, declared in that accessor's declaring type, in a supertype
     * of it (an overridden declaration), or in a subtype of it (an override the mapper merges into).
     */
    private static boolean covers(List<HiddenOnlyMember> report, BeanPropertyDefinition property) {
        for (AnnotatedMember accessor : accessors(property)) {
            if (accessor instanceof AnnotatedParameter) {
                continue;
            }
            Class<?> declaring = accessor.getDeclaringClass();
            for (HiddenOnlyMember entry : report) {
                if (accessor.getName().equals(entry.member())) {
                    Class<?> reported = loadFixture(entry.declaringType());
                    if (reported.isAssignableFrom(declaring) || declaring.isAssignableFrom(reported)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String propertyLabel(BeanPropertyDefinition property) {
        List<String> members = new ArrayList<>();
        for (AnnotatedMember accessor : accessors(property)) {
            members.add(accessor.getDeclaringClass().getSimpleName() + "." + accessor.getName());
        }
        return "property " + property.getName() + " " + members;
    }

    private static Class<?> loadFixture(String binaryName) {
        try {
            return Class.forName(binaryName, false, HiddenOnlyMemberReportTest.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("a reported declaring type does not load: " + binaryName, missing);
        }
    }

    /** Every {@code properties} key, and every {@code enum} and {@code const} string, the document names. */
    private static Set<String> describedNames(String document) {
        Set<String> names = new HashSet<>();
        collectDescribedNames(readDocument(document), names);
        return names;
    }

    /** {@code shape}'s nested types, at every depth. */
    private static Set<Class<?>> shapeTypes(Class<?> shape) {
        Set<Class<?>> types = new HashSet<>();
        for (Class<?> nested : shape.getDeclaredClasses()) {
            types.add(nested);
            types.addAll(shapeTypes(nested));
        }
        return types;
    }

    /** Whether {@code element} carries {@code @Hidden}, directly or through a bundle, and does not declare {@code @Schema(hidden = true)}. */
    private static boolean hiddenOnly(AnnotatedElement element) {
        Schema schema = element.getDeclaredAnnotation(Schema.class);
        return carriesHidden(element, new HashSet<>()) && (schema == null || !schema.hidden());
    }

    /**
     * Whether {@code element}, with its mix-in counterpart's annotations merged over its own, carries
     * {@code @Hidden} without {@code @Schema(hidden = true)}; {@code mixIn} is {@code null} when no
     * mix-in declares a counterpart.
     */
    private static boolean schemaHidden(Schema schema) {
        return schema != null && schema.hidden();
    }

    private static boolean hiddenOnly(AnnotatedElement element, List<? extends AnnotatedElement> mixIns) {
        boolean carries = carriesHidden(element, new HashSet<>());
        Schema schema = null;
        for (AnnotatedElement mixIn : mixIns) {
            carries |= carriesHidden(mixIn, new HashSet<>());
            if (schema == null) {
                schema = mixIn.getDeclaredAnnotation(Schema.class);
            }
        }
        if (schema == null) {
            schema = element.getDeclaredAnnotation(Schema.class);
        }
        return carries && (schema == null || !schema.hidden());
    }

    /** The mix-in {@code mapper} registers for {@code type}, followed by its superclasses short of {@link Object}. */
    private static List<Class<?>> mixInChain(ObjectMapper mapper, Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> mixIn = mapper.findMixInClassFor(type);
                mixIn != null && mixIn != Object.class;
                mixIn = mixIn.getSuperclass()) {
            chain.add(mixIn);
        }
        return chain;
    }

    /** The mix-in classes {@code type} merges its own annotations from: its mix-in chain. */
    private static List<Class<?>> typeMixIns(ObjectMapper mapper, Class<?> type) {
        return mixInChain(mapper, type);
    }

    /** The fields of {@code field}'s name that {@code type}'s mix-in chain declares. */
    private static List<Field> fieldMixIns(ObjectMapper mapper, Class<?> type, Field field) {
        List<Field> counterparts = new ArrayList<>();
        for (Class<?> mixIn : mixInChain(mapper, type)) {
            try {
                counterparts.add(mixIn.getDeclaredField(field.getName()));
            } catch (NoSuchFieldException absent) {
                // this mix-in does not mark the field
            }
        }
        return counterparts;
    }

    /**
     * The methods of {@code method}'s name and parameter types that the mix-in chain of {@code type}, or
     * of any of its supertypes, declares: the mapper merges a supertype's mix-in method into a matching
     * method the subtype declares.
     */
    private static List<Method> methodMixIns(ObjectMapper mapper, Class<?> type, Method method) {
        List<Method> counterparts = new ArrayList<>();
        List<Class<?>> hierarchy = new ArrayList<>(List.of(type));
        hierarchy.addAll(supertypes(type));
        for (Class<?> owner : hierarchy) {
            for (Class<?> mixIn : mixInChain(mapper, owner)) {
                try {
                    counterparts.add(mixIn.getDeclaredMethod(method.getName(), method.getParameterTypes()));
                } catch (NoSuchMethodException absent) {
                    // this mix-in does not mark the method
                }
            }
        }
        return counterparts;
    }

    /** Every mix-in class, and its superclasses short of {@link Object}, registered for a type or a supertype. */
    private static Set<Class<?>> mixInClasses(ObjectMapper mapper, Set<Class<?>> types) {
        Set<Class<?>> mixIns = new HashSet<>();
        for (Class<?> type : types) {
            mixIns.addAll(mixInChain(mapper, type));
            for (Class<?> supertype : supertypes(type)) {
                mixIns.addAll(mixInChain(mapper, supertype));
            }
        }
        return mixIns;
    }

    /** {@code type}'s superclasses and interfaces, at every depth, {@link Object} excluded. */
    private static Set<Class<?>> supertypes(Class<?> type) {
        Set<Class<?>> supertypes = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> current = pending.removeFirst();
            List<Class<?>> parents = new ArrayList<>(List.of(current.getInterfaces()));
            if (current.getSuperclass() != null) {
                parents.add(current.getSuperclass());
            }
            for (Class<?> parent : parents) {
                if (parent != Object.class && supertypes.add(parent)) {
                    pending.add(parent);
                }
            }
        }
        return supertypes;
    }

    private static boolean carriesHidden(AnnotatedElement element, Set<Class<?>> visitedBundles) {
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType == Hidden.class) {
                return true;
            }
            if (annotationType.isAnnotationPresent(JacksonAnnotationsInside.class)
                    && visitedBundles.add(annotationType)
                    && carriesHidden(annotationType, visitedBundles)) {
                return true;
            }
        }
        return false;
    }

    /** An instance field, or an enum constant. */
    private static boolean isPropertyField(Field field) {
        return !field.isSynthetic() && (field.isEnumConstant() || !Modifier.isStatic(field.getModifiers()));
    }

    /** Whether the document names one of {@code type}'s own properties or constants. */
    private static boolean isDescribed(Class<?> type, Set<String> names) {
        return ownPropertyNames(type).stream().anyMatch(names::contains);
    }

    private static Set<String> ownPropertyNames(Class<?> type) {
        Set<String> own = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (isPropertyField(field)) {
                own.add(propertyName(field));
            }
        }
        return own;
    }

    private static String propertyName(Field field) {
        JsonProperty renamed = field.getDeclaredAnnotation(JsonProperty.class);
        return renamed != null && !renamed.value().isEmpty() ? renamed.value() : field.getName();
    }

    /** The property an accessor names: its {@code @JsonProperty}, or its {@link #implicitName}. */
    private static String propertyName(Method method) {
        JsonProperty renamed = method.getDeclaredAnnotation(JsonProperty.class);
        if (renamed != null && !renamed.value().isEmpty()) {
            return renamed.value();
        }
        return implicitName(method);
    }

    /** An accessor's name without a get, is, set, or with prefix, whatever {@code @JsonProperty} renames it to. */
    private static String implicitName(Method method) {
        String name = method.getName();
        for (String prefix : List.of("get", "is", "set", "with")) {
            if (name.length() > prefix.length()
                    && name.startsWith(prefix)
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return Character.toLowerCase(name.charAt(prefix.length())) + name.substring(prefix.length() + 1);
            }
        }
        return name;
    }

    /** Whether a schema whose {@code properties} name one of {@code type}'s own properties describes extras. */
    private static boolean describesExtras(JsonNode node, Class<?> type) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            JsonNode extras = node.get("additionalProperties");
            if (properties != null
                    && extras != null
                    && extras.isObject()
                    && ownPropertyNames(type).stream().anyMatch(properties::has)) {
                return true;
            }
        }
        for (JsonNode child : node) {
            if (describesExtras(child, type)) {
                return true;
            }
        }
        return false;
    }

    /** Every {@code properties} key, and every {@code enum} and {@code const} string, anywhere in {@code node}. */
    private static void collectDescribedNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(names::add);
            }
            JsonNode values = node.get("enum");
            if (values != null && values.isArray()) {
                values.forEach(value -> {
                    if (value.isTextual()) {
                        names.add(value.asText());
                    }
                });
            }
            JsonNode constant = node.get("const");
            if (constant != null && constant.isTextual()) {
                names.add(constant.asText());
            }
        }
        for (JsonNode child : node) {
            collectDescribedNames(child, names);
        }
    }

    private static JsonNode readDocument(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (JsonProcessingException notJson) {
            throw new AssertionError("the generated schema is not JSON: " + json, notJson);
        }
    }

    /** Reflected on to obtain an unresolved {@link java.lang.reflect.TypeVariable}. */
    @SuppressWarnings("unused")
    private static <T> T genericMethodForATypeVariableFixture() {
        return null;
    }

    private static Type typeVariable() {
        try {
            return HiddenOnlyMemberReportTest.class
                    .getDeclaredMethod("genericMethodForATypeVariableFixture")
                    .getGenericReturnType();
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("the type-variable fixture method is missing", missing);
        }
    }

    /**
     * Validates {@code instance} with the real gate's engine and options (Draft 2020-12, base URI
     * {@code https://vertique.local/}, {@link OutputFormat#Basic}), over a fresh copy of {@code schema}.
     */
    private static boolean isValid(String schema, JsonObject instance) {
        JsonSchemaOptions options = new JsonSchemaOptions()
                .setDraft(Draft.DRAFT202012)
                .setBaseUri("https://vertique.local/")
                .setOutputFormat(OutputFormat.Basic);
        Validator validator = Validator.create(JsonSchema.of(new JsonObject(schema)), options);
        return Boolean.TRUE.equals(validator.validate(instance).getValid());
    }

    /**
     * Waits, bounded, until {@code waiter} is blocked entering a monitor {@code owner} holds: the
     * generator's lock, which the report's call enters before it opens its recording.
     */
    private static void awaitBlockedOnAMonitorHeldBy(Thread waiter, Thread owner) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + SECONDS.toNanos(AWAIT_SECONDS);
        while (true) {
            Thread.State state = waiter.getState();
            ThreadInfo info = threads.getThreadInfo(waiter.threadId());
            boolean waiting = state == Thread.State.BLOCKED || state == Thread.State.WAITING;
            if (waiting && info != null && info.getLockOwnerId() == owner.threadId()) {
                return;
            }
            if (System.nanoTime() - deadline > 0) {
                fail(waiter.getName() + " never blocked on a monitor " + owner.getName() + " holds; its state is "
                        + state);
            }
            LockSupport.parkNanos(MILLISECONDS.toNanos(1));
        }
    }

    /**
     * The profile mapper's annotation introspector, parking the first name lookup of a {@link HeldHidden}
     * member until the test releases it. The input describer resolves a type's deserializer, and with it
     * that type's member names, while generating the type, inside the generator's lock and before it
     * describes any of the type's members, so the caller that reaches the gate is held inside the lock
     * with the hidden-only member not yet described.
     */
    private static final class GatingIntrospector extends JacksonAnnotationIntrospector {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private volatile boolean releasedByTheTest = true;

        @Override
        public PropertyName findNameForDeserialization(Annotated annotated) {
            parkOnce(annotated);
            return super.findNameForDeserialization(annotated);
        }

        @Override
        public PropertyName findNameForSerialization(Annotated annotated) {
            parkOnce(annotated);
            return super.findNameForSerialization(annotated);
        }

        private void parkOnce(Annotated annotated) {
            if (annotated instanceof AnnotatedMember member
                    && member.getDeclaringClass() == HeldHidden.class
                    && armed.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    releasedByTheTest = release.await(AWAIT_SECONDS * 2, SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    releasedByTheTest = false;
                }
            }
        }
    }

    /**
     * The keys of every {@code properties} object anywhere in {@code json}: the root's, every nested
     * schema's, and every {@code $defs} entry's.
     */
    private static Set<String> publishedPropertyNames(String json) {
        JsonNode document;
        try {
            document = new ObjectMapper().readTree(json);
        } catch (JsonProcessingException notJson) {
            throw new AssertionError("the generated schema is not JSON: " + json, notJson);
        }
        Set<String> names = new HashSet<>();
        collectPropertyNames(document, names);
        return names;
    }

    private static void collectPropertyNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            JsonNode properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                properties.fieldNames().forEachRemaining(names::add);
            }
        }
        for (JsonNode child : node) {
            collectPropertyNames(child, names);
        }
    }
}
