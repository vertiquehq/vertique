// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.HidingMarker.BOTH;
import static dev.vertique.json.schema.HidingMarker.HIDDEN;
import static dev.vertique.json.schema.HidingMarker.SCHEMA_HIDDEN;
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
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.util.StdConverter;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * {@link AnnotationJsonSchemaGenerator#hiddenMembers(Type)} reports every member, and every type,
 * that an input- or output-direction generator describes for a type while it carries a hiding marker:
 * {@link Hidden}, {@code @Schema(hidden = true)}, or both. Each is a {@link HiddenMember} naming its
 * declaring type and member ({@code null} for the type itself), its {@link HidingMarker}, and whether
 * {@code @Schema(hidden = true)} declared on the property's own field, or on its getter when there is
 * no field, would make the generator leave the property out where it is described. Each is reported
 * once however often it is reached, in an unmodifiable list ordered by declaring type and then member
 * with the type-level entry first. A member the generator does not describe, because it honors the
 * member's {@code @Schema(hidden = true)} or Jackson does not bind or serialize the member, is not
 * reported. A {@code @JsonUnwrapped} member is described through its flattened content, and reported
 * under its own name. Only a victools-defaults generator refuses, before the type grammar is checked.
 * The call keeps {@code generateCanonical}'s output, type grammar, bounded failure contract,
 * restore-on-failure behavior, and per-instance lock, and the gate's engine still enforces a reported
 * member's constraint.
 *
 * <p>Every expected entry is a hand-written literal: its marker is read off the fixture's
 * declarations, and its flag off the position the fixture's property is described at.
 *
 * <p>A shape matrix closes the report over the shapes a document can describe a member or type
 * through — records, creators, builders, subtypes named or registered, abstract member types, enum
 * constants, unwrapped content, containers at any depth, generics, inheritance, overriding getters,
 * any-setters, annotation bundles, mix-ins, and single-accessor properties — in each direction: every
 * row's report, reduced to declaring type, member, and marker, equals exactly the marked declarations
 * of its fixture types, creator parameters included, that the row's own document describes, as an
 * oracle reading the document, the fixture types, and the profile mapper's mix-in registrations
 * independently of the generator computes them. Two further oracles, neither sharing that one's
 * carrier model, require every row's report to cover each described property that swagger-core's
 * model resolver, on the row profile's mapper, omits because of either marker, and each described
 * property and type that the profile mapper's merged annotation view sees as carrying either marker.
 * No oracle computes the flag.
 */
class HiddenMemberReportTest {

    /** Bounded wait for an event the lock-ordering row requires; a timeout fails the row. */
    private static final long AWAIT_SECONDS = 10L;

    private static final JsonMapperProfile PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test"), new ObjectMapper());

    /** A profile whose mapper registers {@link RegisteredSubtypeShape.PremiumAccount} as a subtype by name. */
    private static final JsonMapperProfile REGISTERED_SUBTYPE_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-registered-subtype"), registeredSubtypeMapper());

    /** A profile whose mapper registers {@link MixInShape.RootMixIn} as the mix-in of {@link MixInShape.Root}. */
    private static final JsonMapperProfile MIX_IN_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-mix-in"), mixInMapper());

    /** A profile whose mapper registers {@link MixInTypeShape.StowedMixIn} for {@link MixInTypeShape.Stowed}. */
    private static final JsonMapperProfile MIX_IN_TYPE_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-mix-in-type"), mixInTypeMapper());

    /** A profile whose mapper registers {@link MixInRootShape.RootMixIn} for {@link MixInRootShape.Root}. */
    private static final JsonMapperProfile MIX_IN_ROOT_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-mix-in-root"), mixInRootMapper());

    /** A profile whose mapper registers a mix-in for the superclass {@link MixInSuperclassShape.Parent}. */
    private static final JsonMapperProfile MIX_IN_SUPERCLASS_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-mix-in-superclass"), mixInSuperclassMapper());

    /** A profile whose mapper registers a mix-in for the setter-bound {@link SetterBoundMixInShape.Panel}. */
    private static final JsonMapperProfile SETTER_BOUND_MIX_IN_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-setter-bound-mix-in"), setterBoundMixInMapper());

    /**
     * A profile declaring a schema type override, in both directions, for {@link OverriddenBaseShape.Buoy}:
     * the fragment's own property name is none of the shape's, so a document the override shaped would
     * name it instead of the base's content.
     */
    private static final JsonMapperProfile OVERRIDDEN_BASE_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-overridden-base"),
            new ObjectMapper(),
            List.of(JsonSchemaTypeOverride.both(
                    OverriddenBaseShape.Buoy.class,
                    JsonSchemaFragment.parse("{\"type\":\"object\","
                            + "\"properties\":{\"buoyOverride\":{\"type\":\"string\"}},"
                            + "\"additionalProperties\":false}"))));

    /** A profile whose mapper registers mix-ins inheriting {@code @Hidden} from their own superclasses. */
    private static final JsonMapperProfile MIX_IN_ANCESTOR_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-mix-in-ancestor"), mixInAncestorMapper());

    /** A profile whose mapper registers a mix-in for {@link InheritedMixInMethodShape.Ancestor}. */
    private static final JsonMapperProfile INHERITED_MIX_IN_METHOD_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-inherited-mix-in-method"), inheritedMixInMethodMapper());

    /** A profile whose mapper registers {@link EnumMixInShape.TierMixIn} for {@link EnumMixInShape.Tier}. */
    private static final JsonMapperProfile ENUM_MIX_IN_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-enum-mix-in"), enumMixInMapper());

    /**
     * A profile whose mapper registers {@link MixInSchemaSetterShape.RootMixIn} as the mix-in of {@link
     * MixInSchemaSetterShape.Root}.
     */
    private static final JsonMapperProfile SETTER_SCHEMA_MIX_IN_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-setter-schema-mix-in"), setterSchemaMixInMapper());

    /**
     * A profile whose mapper registers {@link SubclassMixInHiddenShape.RootMixIn} as the mix-in of {@link
     * SubclassMixInHiddenShape.Root}.
     */
    private static final JsonMapperProfile SUBCLASS_MIX_IN_HIDDEN_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-subclass-mix-in-hidden"), subclassMixInHiddenMapper());

    /**
     * A profile whose mapper registers {@link SubclassMixInSchemaShape.RootMixIn} as the mix-in of {@link
     * SubclassMixInSchemaShape.Root}.
     */
    private static final JsonMapperProfile SUBCLASS_MIX_IN_SCHEMA_PROFILE = JsonMapperProfiles.of(
            JsonProfileId.of("hidden-member-report-test-subclass-mix-in-schema"), subclassMixInSchemaMapper());

    /**
     * A profile whose mapper registers {@link MixInTargetShape.MixInTargetMixIn} as the mix-in of {@link
     * MixInTargetShape.MixInTarget}.
     */
    private static final JsonMapperProfile MIX_IN_TARGET_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-mix-in-target"), mixInTargetMapper());

    /**
     * A profile whose mapper registers {@link MixInBothShape.MixInBothTargetMixIn} as the mix-in of {@link
     * MixInBothShape.MixInBothTarget}.
     */
    private static final JsonMapperProfile MIX_IN_BOTH_PROFILE =
            JsonMapperProfiles.of(JsonProfileId.of("hidden-member-report-test-mix-in-both"), mixInBothMapper());

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

    /**
     * What an input-direction generator reports for {@link HiddenOnlyBody}, in the report's order: the
     * field-bound {@code debug} and {@code flag} are hideable by {@code @Schema(hidden = true)} on their
     * field; the setter-bound {@code override} is not, and neither is a type.
     */
    private static final List<HiddenMember> BODY_REPORT = List.of(
            hidden(HiddenOnlyBody.class, "debug", HIDDEN, true),
            hidden(HiddenOnlyBody.class, "getOverride", HIDDEN, false),
            hidden(HiddenType.class, null, HIDDEN, false),
            hidden(HiddenType.class, "flag", HIDDEN, true));

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
    private static final List<HiddenMember> RESPONSE_REPORT = List.of(
            hidden(HiddenOnlyResponse.class, "debug", HIDDEN, true),
            hidden(HiddenType.class, null, HIDDEN, false),
            hidden(HiddenType.class, "flag", HIDDEN, true));

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

    /** What either direction reports for {@link HiddenUnwrappedField}: the unwrapped field, never hideable. */
    private static final List<HiddenMember> UNWRAPPED_FIELD_REPORT =
            List.of(hidden(HiddenUnwrappedField.class, "address", HIDDEN, false));

    /** What either direction reports for {@link HiddenUnwrappedGetter}: the unwrapped getter, never hideable. */
    private static final List<HiddenMember> UNWRAPPED_GETTER_REPORT =
            List.of(hidden(HiddenUnwrappedGetter.class, "getAddress", HIDDEN, false));

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

    /** Nothing carries a hiding marker. */
    static final class PlainBody {
        public String name;
    }

    /**
     * A type carrying {@code @Schema(hidden = true)} beside {@code @Hidden}, which neither generator
     * honors on a class, reached through a member: it is described wherever it is reached. Held in its own
     * shape so the matrix reads it too.
     */
    static final class HidesTypeTwiceShape {
        /** Reaches {@link DoublyHiddenType} through a member. */
        static final class HidesTypeTwice {
            public DoublyHiddenType inner;
        }

        @Hidden
        @Schema(hidden = true)
        static final class DoublyHiddenType {
            public String value;
        }
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

    /**
     * What an input-direction generator reports for {@link Order}: {@link Note} is reached as a map value;
     * every member is a field-bound public field.
     */
    private static final List<HiddenMember> INPUT_ORDER_REPORT = List.of(
            hidden(Line.class, "cost", HIDDEN, true),
            hidden(Note.class, "author", HIDDEN, true),
            hidden(Order.class, "trace", HIDDEN, true));

    /**
     * What an output-direction generator reports for {@link Order}: its schema describes a map member
     * without describing the map's values, so {@link Note} is not reached.
     */
    private static final List<HiddenMember> OUTPUT_ORDER_REPORT =
            List.of(hidden(Line.class, "cost", HIDDEN, true), hidden(Order.class, "trace", HIDDEN, true));

    /**
     * A generic type the generator describes once per type argument it is reached under, while its one
     * marked member stays the same member each time.
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
    private static final List<HiddenMember> BOXES_REPORT = List.of(hidden(Box.class, "value", HIDDEN, true));

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

    /**
     * What either direction reports for {@link RecordShape}: a component once, under its own name, hideable
     * on its field (input binds it through the canonical constructor, which has a backing field). {@code
     * @Hidden} has no parameter target, so no canonical-constructor parameter carries it.
     */
    private static final List<HiddenMember> RECORD_REPORT = List.of(
            hidden(RecordShape.Detail.class, null, HIDDEN, false),
            hidden(RecordShape.Detail.class, "detailSecret", HIDDEN, true),
            hidden(RecordShape.Root.class, "recordSecret", HIDDEN, true));

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

    /**
     * What either direction reports for {@link CreatorShape}: each property has a backing field, so a
     * constructor creator's parameter is hideable on it; no parameter carries a marker.
     */
    private static final List<HiddenMember> CREATOR_REPORT = List.of(
            hidden(CreatorShape.Root.class, "creatorSecret", HIDDEN, true),
            hidden(CreatorShape.Root.class, "getCreatorToken", HIDDEN, true));

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

    /**
     * What an input-direction generator reports for {@link BuilderShape}: the builder method too; a
     * builder-bound property is described through its builder method, so neither is hideable.
     */
    private static final List<HiddenMember> BUILDER_INPUT_REPORT = List.of(
            hidden(BuilderShape.Root.class, "builderOther", HIDDEN, false),
            hidden(BuilderShape.Root.Builder.class, "withBuilderSecret", HIDDEN, false));

    /** What an output-direction generator reports for {@link BuilderShape}: the built type's field. */
    private static final List<HiddenMember> BUILDER_OUTPUT_REPORT =
            List.of(hidden(BuilderShape.Root.class, "builderOther", HIDDEN, true));

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
    private static final List<HiddenMember> SUBTYPE_REPORT = List.of(
            hidden(SubtypeShape.Animal.class, "animalTag", HIDDEN, true),
            hidden(SubtypeShape.Cat.class, null, HIDDEN, false),
            hidden(SubtypeShape.Dog.class, "bark", HIDDEN, true));

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
    private static final List<HiddenMember> POLYMORPHIC_BASE_REPORT =
            List.of(hidden(PolymorphicBaseShape.Vehicle.class, null, HIDDEN, false));

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

    /**
     * What either direction reports for {@link RegisteredSubtypeShape}: the base's member, a public field
     * the schema library describes itself.
     */
    private static final List<HiddenMember> REGISTERED_SUBTYPE_REPORT =
            List.of(hidden(RegisteredSubtypeShape.Account.class, "accountSecret", HIDDEN, true));

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
    private static final List<HiddenMember> ABSTRACT_MEMBER_REPORT =
            List.of(hidden(AbstractMemberShape.Gadget.class, "gadgetSecret", HIDDEN, true));

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
    private static final List<HiddenMember> ENUM_REPORT = List.of(
            hidden(EnumShape.Level.class, null, HIDDEN, false),
            hidden(EnumShape.Role.class, "INTERNAL_SUPERUSER", HIDDEN, false));

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
    private static final List<HiddenMember> UNWRAPPED_CONTENT_REPORT = List.of(
            hidden(UnwrappedShape.Parcel.class, null, HIDDEN, false),
            hidden(UnwrappedShape.Parcel.class, "parcelSecret", HIDDEN, true),
            hidden(UnwrappedShape.Root.class, "stamp", HIDDEN, false));

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
    private static final List<HiddenMember> CONTAINER_INPUT_REPORT = List.of(
            hidden(ContainerShape.ArrayItem.class, "arraySecret", HIDDEN, true),
            hidden(ContainerShape.ListItem.class, null, HIDDEN, false),
            hidden(ContainerShape.ListItem.class, "listSecret", HIDDEN, true),
            hidden(ContainerShape.MapItem.class, "mapSecret", HIDDEN, true),
            hidden(ContainerShape.NestedItem.class, "nestedSecret", HIDDEN, true),
            hidden(ContainerShape.OptionalItem.class, null, HIDDEN, false),
            hidden(ContainerShape.OptionalItem.class, "optionalSecret", HIDDEN, true),
            hidden(ContainerShape.SetItem.class, "setSecret", HIDDEN, true));

    /** What an output-direction generator reports for {@link ContainerShape}: its schema describes no map values. */
    private static final List<HiddenMember> CONTAINER_OUTPUT_REPORT = List.of(
            hidden(ContainerShape.ArrayItem.class, "arraySecret", HIDDEN, true),
            hidden(ContainerShape.ListItem.class, null, HIDDEN, false),
            hidden(ContainerShape.ListItem.class, "listSecret", HIDDEN, true),
            hidden(ContainerShape.OptionalItem.class, null, HIDDEN, false),
            hidden(ContainerShape.OptionalItem.class, "optionalSecret", HIDDEN, true),
            hidden(ContainerShape.SetItem.class, "setSecret", HIDDEN, true));

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
    private static final List<HiddenMember> GENERIC_REPORT = List.of(
            hidden(GenericShape.Envelope.class, "envelopeTag", HIDDEN, true),
            hidden(GenericShape.Payload.class, "payloadSecret", HIDDEN, true));

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

    /**
     * What an input-direction generator reports for {@link InheritanceShape}: each member under its
     * declaring type; the setter-bound {@code parentCode} is not hideable.
     */
    private static final List<HiddenMember> INHERITANCE_INPUT_REPORT = List.of(
            hidden(InheritanceShape.Child.class, "childSecret", HIDDEN, true),
            hidden(InheritanceShape.Parent.class, "getParentCode", HIDDEN, false),
            hidden(InheritanceShape.Parent.class, "parentSecret", HIDDEN, true));

    /** What an output-direction generator reports for {@link InheritanceShape}: each member under its declaring type. */
    private static final List<HiddenMember> INHERITANCE_OUTPUT_REPORT = List.of(
            hidden(InheritanceShape.Child.class, "childSecret", HIDDEN, true),
            hidden(InheritanceShape.Parent.class, "getParentCode", HIDDEN, true),
            hidden(InheritanceShape.Parent.class, "parentSecret", HIDDEN, true));

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

    /**
     * What an input-direction generator reports for {@link OverriddenGetterShape}: each overridden getter
     * that declares it; both properties are setter-bound, so neither is hideable.
     */
    private static final List<HiddenMember> OVERRIDDEN_GETTER_INPUT_REPORT = List.of(
            hidden(OverriddenGetterShape.Base.class, "getBaseLabel", HIDDEN, false),
            hidden(OverriddenGetterShape.Nicknamed.class, "getNickname", HIDDEN, false));

    /** What an output-direction generator reports for {@link OverriddenGetterShape}: each overridden getter that declares it. */
    private static final List<HiddenMember> OVERRIDDEN_GETTER_OUTPUT_REPORT = List.of(
            hidden(OverriddenGetterShape.Base.class, "getBaseLabel", HIDDEN, true),
            hidden(OverriddenGetterShape.Nicknamed.class, "getNickname", HIDDEN, true));

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
    private static final List<HiddenMember> ANY_SETTER_INPUT_REPORT =
            List.of(hidden(AnySetterShape.Root.class, "putExtra", HIDDEN, false));

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
    private static final List<HiddenMember> BUNDLE_REPORT = List.of(
            hidden(BundleShape.Root.class, "bundleDebug", HIDDEN, true),
            hidden(BundleShape.Sealed.class, null, HIDDEN, false));

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

    /**
     * What an input-direction generator reports for {@link AccessorShape}: all three accessors; the two
     * setterless getters are hideable, the setter-only property is not.
     */
    private static final List<HiddenMember> ACCESSOR_INPUT_REPORT = List.of(
            hidden(AccessorShape.Root.class, "getGetterSecret", HIDDEN, true),
            hidden(AccessorShape.Root.class, "getGetterTags", HIDDEN, true),
            hidden(AccessorShape.Root.class, "setSetterSecret", HIDDEN, false));

    /** What an output-direction generator reports for {@link AccessorShape}: the one property it describes. */
    private static final List<HiddenMember> ACCESSOR_OUTPUT_REPORT =
            List.of(hidden(AccessorShape.Root.class, "getGetterSecret", HIDDEN, true));

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

    /**
     * What an input-direction generator reports for {@link SetterCarrierShape}: the setter, by its own
     * name; the property is bound through the setter, so it is not hideable.
     */
    private static final List<HiddenMember> SETTER_CARRIER_INPUT_REPORT =
            List.of(hidden(SetterCarrierShape.Root.class, "setSecret", HIDDEN, false));

    /** What an output-direction generator reports for {@link SetterCarrierShape}: the setter, by its own name. */
    private static final List<HiddenMember> SETTER_CARRIER_OUTPUT_REPORT =
            List.of(hidden(SetterCarrierShape.Root.class, "setSecret", HIDDEN, true));

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
    private static final List<HiddenMember> CASE_INSENSITIVE_REPORT = List.of(
            hidden(CaseInsensitiveShape.Folded.class, null, HIDDEN, false),
            hidden(CaseInsensitiveShape.Folded.class, "foldedSecret", HIDDEN, true));

    /** A root type carrying {@code @Hidden}. */
    static final class HiddenRootShape {
        @Hidden
        static final class Root {
            public String rootValue;
        }
    }

    /** What either direction reports for {@link HiddenRootShape}: the root type. */
    private static final List<HiddenMember> HIDDEN_ROOT_REPORT =
            List.of(hidden(HiddenRootShape.Root.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> SUBCLASS_OVERRIDE_REPORT = List.of(
            hidden(SubclassOverrideShape.AbstractOverrider.class, "getAbstractLabel", HIDDEN, true),
            hidden(SubclassOverrideShape.Overrider.class, "getDeclaredLabel", HIDDEN, true));

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
    private static final List<HiddenMember> LIBRARY_MEMBER_BASE_REPORT =
            List.of(hidden(LibraryMemberBaseShape.Crane.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> DEEP_POLYMORPHIC_BASE_INPUT_REPORT = List.of(
            hidden(DeepPolymorphicBaseShape.Barge.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Coach.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Ferry.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Glider.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Tram.class, null, HIDDEN, false));

    /** What an output-direction generator reports for {@link DeepPolymorphicBaseShape}: no map values are described. */
    private static final List<HiddenMember> DEEP_POLYMORPHIC_BASE_OUTPUT_REPORT = List.of(
            hidden(DeepPolymorphicBaseShape.Coach.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Ferry.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Glider.class, null, HIDDEN, false),
            hidden(DeepPolymorphicBaseShape.Tram.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> MIX_IN_REPORT = List.of(
            hidden(MixInShape.Root.class, "getMixToken", HIDDEN, true),
            hidden(MixInShape.Root.class, "mixSecret", HIDDEN, true));

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
    private static final List<HiddenMember> LIST_ELEMENT_BASE_REPORT =
            List.of(hidden(ListElementBaseShape.Tanker.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> MIX_IN_TYPE_REPORT =
            List.of(hidden(MixInTypeShape.Stowed.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> CONTAINER_ELEMENT_BASE_REPORT =
            List.of(hidden(ContainerElementBaseShape.Crate.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> MIX_IN_ROOT_REPORT =
            List.of(hidden(MixInRootShape.Root.class, null, HIDDEN, false));

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
    private static final List<HiddenMember> MIX_IN_SUPERCLASS_REPORT =
            List.of(hidden(MixInSuperclassShape.Parent.class, "parentField", HIDDEN, true));

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

    /**
     * What an input-direction generator reports for {@link SetterBoundShape.DirectRoot}: each setter; the
     * schema library describes the panel's properties through their fields.
     */
    private static final List<HiddenMember> SETTER_BOUND_DIRECT_INPUT_REPORT = List.of(
            hidden(SetterBoundShape.DirectPanel.class, "setDirectSecret", HIDDEN, true),
            hidden(SetterBoundShape.DirectPanel.class, "setDirectTags", HIDDEN, true));

    /** What an input-direction generator reports for {@link SetterBoundShape.BundleRoot}: each setter. */
    private static final List<HiddenMember> SETTER_BOUND_BUNDLE_INPUT_REPORT = List.of(
            hidden(SetterBoundShape.BundlePanel.class, "setBundledSecret", HIDDEN, true),
            hidden(SetterBoundShape.BundlePanel.class, "setBundledTags", HIDDEN, true));

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
    private static final List<HiddenMember> SETTER_BOUND_MIX_IN_INPUT_REPORT = List.of(
            hidden(SetterBoundMixInShape.Panel.class, "setMixedSecret", HIDDEN, true),
            hidden(SetterBoundMixInShape.Panel.class, "setMixedTags", HIDDEN, true));

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
    private static final List<HiddenMember> RENAMED_SETTER_INPUT_REPORT = List.of(
            hidden(RenamedSetterShape.Vault.class, "setSecret", HIDDEN, true),
            hidden(RenamedSetterShape.Vault.class, "setSecretTags", HIDDEN, true));

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
    private static final List<HiddenMember> OVERRIDDEN_BASE_REPORT =
            List.of(hidden(OverriddenBaseShape.Buoy.class, null, HIDDEN, false));

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
     * What an input-direction generator reports for {@link GenericSetterShape}: each generic declaration
     * carrying {@code @Hidden}, under its own name and declaring type, as for an overridden getter. The
     * concrete {@code Account}'s {@code id} is setter-bound, so not hideable; the schema library describes
     * the abstract {@code LibraryAccount}'s {@code key} through its field.
     */
    private static final List<HiddenMember> GENERIC_SETTER_INPUT_REPORT = List.of(
            hidden(GenericSetterShape.Entity.class, "setId", HIDDEN, false),
            hidden(GenericSetterShape.Keyed.class, "setKey", HIDDEN, true));

    /** What an output-direction generator reports for {@link GenericSetterShape}: each generic declaration. */
    private static final List<HiddenMember> GENERIC_SETTER_OUTPUT_REPORT = List.of(
            hidden(GenericSetterShape.Entity.class, "setId", HIDDEN, true),
            hidden(GenericSetterShape.Keyed.class, "setKey", HIDDEN, true));

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
    private static final List<HiddenMember> MIX_IN_ANCESTOR_REPORT = List.of(
            hidden(MixInAncestorShape.Safe.class, null, HIDDEN, false),
            hidden(MixInAncestorShape.Vault.class, "vaultSecret", HIDDEN, true));

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

    /**
     * What an input-direction generator reports for {@link InheritedMixInMethodShape}: the subclass's
     * getter; the property is setter-bound, so not hideable.
     */
    private static final List<HiddenMember> INHERITED_MIX_IN_METHOD_INPUT_REPORT =
            List.of(hidden(InheritedMixInMethodShape.Heir.class, "getHeirSecret", HIDDEN, false));

    /** What an output-direction generator reports for {@link InheritedMixInMethodShape}: the subclass's getter. */
    private static final List<HiddenMember> INHERITED_MIX_IN_METHOD_OUTPUT_REPORT =
            List.of(hidden(InheritedMixInMethodShape.Heir.class, "getHeirSecret", HIDDEN, true));

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
    private static final List<HiddenMember> UNWRAPPED_LIBRARY_INPUT_REPORT =
            List.of(hidden(UnwrappedInputShape.Holder.class, "holderMailbox", HIDDEN, false));

    /** What an input-direction generator reports for {@link UnwrappedInputShape.GetterOnlyRoot}. */
    private static final List<HiddenMember> UNWRAPPED_GETTER_ONLY_INPUT_REPORT =
            List.of(hidden(UnwrappedInputShape.GetterOnlyRoot.class, "getGetterOnlyPostcard", HIDDEN, false));

    /** What an input-direction generator reports for {@link UnwrappedInputShape.ReadOnlyRoot}. */
    private static final List<HiddenMember> UNWRAPPED_READ_ONLY_INPUT_REPORT =
            List.of(hidden(UnwrappedInputShape.ReadOnlyRoot.class, "readOnlyLabel", HIDDEN, false));

    /**
     * A setter-bound property whose private field carries both {@code @Hidden} and {@code @Schema(hidden =
     * true)}: the input direction describes it (its setter path does not honor {@code @Schema(hidden)}),
     * and reports the field with both markers.
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
    private static final List<HiddenMember> ENUM_MIX_IN_REPORT =
            List.of(hidden(EnumMixInShape.Tier.class, "SECRET", HIDDEN, false));

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
    private static final List<HiddenMember> SUPER_BUILDER_INPUT_REPORT =
            List.of(hidden(SuperBuilderShape.BaseBuilder.class, "withTicketCode", HIDDEN, false));

    /**
     * A setter-bound property of an abstract member type without type information, which the schema
     * library describes itself, whose setter carries both {@code @Hidden} and {@code @Schema(hidden =
     * true)}: the input direction describes it through its field, and reports the setter with both
     * markers.
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
     * true)}: neither generator honors a setter's marker, so both directions describe the property and
     * report the setter with both markers.
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
     * true)} alone: both directions report each declaration by its own marker.
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
     * directions report each declaration by its own marker.
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
     * {@code @Hidden} alone: both directions report each declaration by its own marker, under its own
     * declaring type.
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
     * {@code @Schema(hidden = true)} alone: both directions report the generic declaration and the
     * override, each by its own marker.
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
     * both markers: both directions report the generic declaration and the override, each by its own
     * marker.
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
     * describes it, so neither reports it.
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
     * described getter: both directions report the field.
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
     * same setter, the mix-in the mapper registers for the setter's own class: neither generator honors a
     * setter's marker, so both directions report the setter with both markers.
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
     * A field carrying both markers whose getter unwraps the property: both directions describe the
     * flattened content, so both report the field, an unwrapped member, with both markers.
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
     * alone and whose setter carries {@code @Schema(hidden = true)}: the input direction reports both;
     * the output direction does not serialize the property.
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
     * {@code @Schema(hidden = true)}: the input direction reports both; the output direction does not
     * serialize the property.
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
     * @Schema(hidden = true)} alone: the input direction reports each declaration by its own marker.
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
     * carrying {@code @Hidden} alone: the input direction reports each declaration by its own marker,
     * under its own declaring type.
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
     * the input direction reports each declaration by its own marker.
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
     * @Hidden} for the same setter: the declaration is read with the mix-in of its own class only, so
     * both directions report the base setter with {@code @Schema(hidden = true)} alone.
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
     * true)} for the same setter: the declaration is read with the mix-in of its own class only, so both
     * directions report the base setter with {@code @Hidden} alone.
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

    // ---------------------------------------------------------------- ignored-marker fixtures
    //
    // One shape per position a generator ignores a marker at, so each fixture's property names are unique
    // within the shape the matrix reads, and each is also a matrix row. Every flag below is read off the
    // position the property is described at: true where @Schema(hidden = true) declared directly on the
    // property's own field, or on its getter when the mapper sees no field, would leave it out there.

    /** {@code @Schema(hidden = true)} alone on the getter of a setter-bound property. */
    static final class GetterMarkedShape {
        static final class GetterMarkedBean {
            private String secret;

            @Schema(hidden = true)
            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /**
     * Input: the property is bound through its setter, whose path ignores the getter's marker, and the
     * setter path ignores a field marker too. Output: the generator honors the getter's marker.
     */
    private static final List<HiddenMember> GETTER_MARKED_INPUT_REPORT =
            List.of(hidden(GetterMarkedShape.GetterMarkedBean.class, "getSecret", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on the private field of a property with a getter and a setter. */
    static final class FieldMarkedShape {
        static final class FieldMarkedBean {
            public String name;

            @Schema(hidden = true)
            private String secret;

            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /** Input: bound through the setter, whose path ignores the field's marker. Output: honored. */
    private static final List<HiddenMember> FIELD_MARKED_INPUT_REPORT =
            List.of(hidden(FieldMarkedShape.FieldMarkedBean.class, "secret", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on the private field of a property with a setter and no getter. */
    static final class GetterlessFieldMarkedShape {
        static final class GetterlessFieldMarked {
            public String name;

            @Schema(hidden = true)
            private String secret;

            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /** Input: bound through the setter, whose path ignores the field's marker. Output: never serialized. */
    private static final List<HiddenMember> GETTERLESS_FIELD_MARKED_INPUT_REPORT =
            List.of(hidden(GetterlessFieldMarkedShape.GetterlessFieldMarked.class, "secret", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on the setter of a property with a getter. */
    static final class SetterMarkedShape {
        static final class SetterMarkedBean {
            private String secret;

            public String getSecret() {
                return secret;
            }

            @Schema(hidden = true)
            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /** Input: bound through the setter, whose path ignores a field marker, so not hideable there. */
    private static final List<HiddenMember> SETTER_MARKED_INPUT_REPORT =
            List.of(hidden(SetterMarkedShape.SetterMarkedBean.class, "setSecret", SCHEMA_HIDDEN, false));

    /** Output: described through the field and getter, which a field marker would hide. */
    private static final List<HiddenMember> SETTER_MARKED_OUTPUT_REPORT =
            List.of(hidden(SetterMarkedShape.SetterMarkedBean.class, "setSecret", SCHEMA_HIDDEN, true));

    /** {@code @Schema(hidden = true)} alone on the first parameter of a {@code @JsonCreator} constructor. */
    static final class CreatorMarkedShape {
        static final class CreatorMarked {
            private final String secret;
            private final String name;

            @JsonCreator
            CreatorMarked(
                    @Schema(hidden = true) @JsonProperty("secret") String secret, @JsonProperty("name") String name) {
                this.secret = secret;
                this.name = name;
            }

            public String getSecret() {
                return secret;
            }

            public String getName() {
                return name;
            }
        }
    }

    /**
     * Either direction: a constructor creator's parameter with a backing field is described through that
     * field on input, and the output property through its field and getter; a field marker hides both.
     */
    private static final List<HiddenMember> CREATOR_MARKED_REPORT =
            List.of(hidden(CreatorMarkedShape.CreatorMarked.class, "<init>#0", SCHEMA_HIDDEN, true));

    /** {@code @Schema(hidden = true)} alone on the second parameter of a {@code @JsonCreator} static factory. */
    static final class FactoryMarkedShape {
        static final class FactoryMarked {
            private final String secret;
            private final String name;

            private FactoryMarked(String name, String secret) {
                this.name = name;
                this.secret = secret;
            }

            @JsonCreator
            static FactoryMarked of(
                    @JsonProperty("name") String name, @Schema(hidden = true) @JsonProperty("secret") String secret) {
                return new FactoryMarked(name, secret);
            }

            public String getSecret() {
                return secret;
            }

            public String getName() {
                return name;
            }
        }
    }

    /** Input: a static factory's parameter is described through the setter path, even with a backing field. */
    private static final List<HiddenMember> FACTORY_MARKED_INPUT_REPORT =
            List.of(hidden(FactoryMarkedShape.FactoryMarked.class, "of#1", SCHEMA_HIDDEN, false));

    /** Output: described through the field and getter, which a field marker would hide. */
    private static final List<HiddenMember> FACTORY_MARKED_OUTPUT_REPORT =
            List.of(hidden(FactoryMarkedShape.FactoryMarked.class, "of#1", SCHEMA_HIDDEN, true));

    /**
     * {@code @Schema(hidden = true)} alone on a {@code @JsonAnySetter} parameter of a {@code @JsonCreator}
     * constructor, which the input direction describes as the type's extras.
     */
    static final class CreatorAnyShape {
        static final class CreatorAny {
            private final String name;
            private final Map<String, Integer> extras;

            @JsonCreator
            CreatorAny(
                    @JsonProperty("name") String name,
                    @JsonAnySetter @Schema(hidden = true) Map<String, Integer> extras) {
                this.name = name;
                this.extras = extras;
            }

            public String getName() {
                return name;
            }
        }
    }

    /** Input: an any-setter creator parameter, described as the type's extras, never hideable. */
    private static final List<HiddenMember> CREATOR_ANY_INPUT_REPORT =
            List.of(hidden(CreatorAnyShape.CreatorAny.class, "<init>#1", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on a {@code @JsonUnwrapped} member. */
    static final class UnwrappedMarkedShape {
        static final class UnwrappedMarked {
            public String name;

            @JsonUnwrapped
            @Schema(hidden = true)
            public Address address;
        }
    }

    /** Either direction: an unwrapped member is described through its content, never hideable. */
    private static final List<HiddenMember> UNWRAPPED_MARKED_REPORT =
            List.of(hidden(UnwrappedMarkedShape.UnwrappedMarked.class, "address", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on an enum constant a member's enum declares. */
    static final class TierShape {
        static final class TierHolder {
            public Tier tier;
        }

        enum Tier {
            VISIBLE,
            @Schema(hidden = true)
            SECRET
        }
    }

    /** Either direction: an enum constant, never hideable. */
    private static final List<HiddenMember> TIER_REPORT =
            List.of(hidden(TierShape.Tier.class, "SECRET", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on a class reached through a member. */
    static final class ClassMarkedShape {
        static final class ClassMarkedHolder {
            public ClassMarked nested;
        }

        @Schema(hidden = true)
        static final class ClassMarked {
            public String markedValue;
        }
    }

    /** Either direction: a type, never hideable. */
    private static final List<HiddenMember> CLASS_MARKED_REPORT =
            List.of(hidden(ClassMarkedShape.ClassMarked.class, null, SCHEMA_HIDDEN, false));

    /**
     * {@code @Schema(hidden = true)} on a class that {@link InheritedMarkShape.InheritedMarked} extends.
     * {@code @Schema} is {@code @Inherited}, so the subclass carries it too. Held outside any shape: its
     * inherited property is described wherever the subclass is, while no document describes it as a type
     * of its own.
     */
    @Schema(hidden = true)
    static class InheritedMarkBase {
        public String inheritedBaseValue;
    }

    /** {@code @Schema(hidden = true)} inherited, not declared, by a class, described as a root and through a member. */
    static final class InheritedMarkShape {
        static final class InheritedMarkHolder {
            public InheritedMarked inheritedNested;
        }

        static final class InheritedMarked extends InheritedMarkBase {
            public String inheritedMarkedValue;
        }
    }

    /** Either direction: the subclass, as a type carrying the inherited marker, never hideable. */
    private static final List<HiddenMember> INHERITED_MARK_REPORT =
            List.of(hidden(InheritedMarkShape.InheritedMarked.class, null, SCHEMA_HIDDEN, false));

    /**
     * {@code @Schema(hidden = true)} declared on a class and inherited by its subclass, both described in one
     * document through members of one holder.
     */
    static final class InheritedBesideBaseShape {
        static final class BesideHolder {
            public BesideBase besideBase;
            public BesideSub besideSub;
        }

        @Schema(hidden = true)
        static class BesideBase {
            public String besideBaseValue;
        }

        static final class BesideSub extends BesideBase {
            public String besideSubValue;
        }
    }

    /** Either direction: the declaring class and its subclass, each as a type, never hideable. */
    private static final List<HiddenMember> INHERITED_BESIDE_BASE_REPORT = List.of(
            hidden(InheritedBesideBaseShape.BesideBase.class, null, SCHEMA_HIDDEN, false),
            hidden(InheritedBesideBaseShape.BesideSub.class, null, SCHEMA_HIDDEN, false));

    /**
     * {@code @Schema(hidden = true)} on a public field only through the mix-in {@link #MIX_IN_TARGET_PROFILE}'s
     * mapper registers for its type, which itself declares nothing.
     */
    static final class MixInTargetShape {
        static final class MixInTarget {
            public String secret;
            public String name;
        }

        /** Never described itself: the profile mapper merges its annotations into {@link MixInTarget}'s. */
        abstract static class MixInTargetMixIn {
            @Schema(hidden = true)
            public String secret;
        }
    }

    /** Either direction: a field-bound public field, which a marker declared on the field itself would hide. */
    private static final List<HiddenMember> MIX_IN_TARGET_REPORT =
            List.of(hidden(MixInTargetShape.MixInTarget.class, "secret", SCHEMA_HIDDEN, true));

    /** {@code @Schema(hidden = true)} alone on a {@code @JsonPOJOBuilder} builder method. */
    static final class BuiltMarkedShape {
        @JsonDeserialize(builder = BuiltMarked.Builder.class)
        static final class BuiltMarked {
            private final String secret;
            private final String name;

            BuiltMarked(String secret, String name) {
                this.secret = secret;
                this.name = name;
            }

            public String getSecret() {
                return secret;
            }

            public String getName() {
                return name;
            }

            @JsonPOJOBuilder(withPrefix = "")
            static final class Builder {
                private String secret;
                private String name;

                @Schema(hidden = true)
                public Builder secret(String value) {
                    secret = value;
                    return this;
                }

                public Builder name(String value) {
                    name = value;
                    return this;
                }

                public BuiltMarked build() {
                    return new BuiltMarked(secret, name);
                }
            }
        }
    }

    /** Input: a builder-bound property is described through its builder method, never hideable. */
    private static final List<HiddenMember> BUILT_MARKED_INPUT_REPORT =
            List.of(hidden(BuiltMarkedShape.BuiltMarked.Builder.class, "secret", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} alone on a map member whose value position carries a constraint. */
    static final class MapOverlayMarkedShape {
        static final class MapOverlayMarked {
            public String name;

            @Schema(hidden = true)
            public Map<String, @Size(max = 3) String> secret;
        }
    }

    /** Input: rendered through its value constraints, a path that ignores the marker. Output: honored. */
    private static final List<HiddenMember> MAP_OVERLAY_MARKED_INPUT_REPORT =
            List.of(hidden(MapOverlayMarkedShape.MapOverlayMarked.class, "secret", SCHEMA_HIDDEN, false));

    /** {@code @Schema(hidden = true)} on a public field only through a Jackson annotation bundle. */
    static final class BundleMarkedShape {
        /** A Jackson annotation bundle carrying {@code @Schema(hidden = true)}. */
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
        @JacksonAnnotationsInside
        @Schema(hidden = true)
        @interface SchemaHiddenBundle {}

        static final class BundleMarked {
            @SchemaHiddenBundle
            public String secret;
        }
    }

    /** Either direction: a field-bound public field, which a marker declared on the field itself would hide. */
    private static final List<HiddenMember> BUNDLE_MARKED_REPORT =
            List.of(hidden(BundleMarkedShape.BundleMarked.class, "secret", SCHEMA_HIDDEN, true));

    /**
     * {@code @Schema(hidden = true)} alone on a public field bound to a bean case-insensitively through its
     * own {@code @JsonFormat}: the input direction describes the member inline, a path that ignores the
     * marker.
     */
    static final class CaseInsensitiveMemberShape {
        static final class CaseInsensitiveMemberMarked {
            public String name;

            @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            @Schema(hidden = true)
            public Folding secret;
        }

        static final class Folding {
            public String foldingValue;
        }
    }

    /** Input: described inline, a path that ignores the marker. Output: honored. */
    private static final List<HiddenMember> CASE_INSENSITIVE_MEMBER_INPUT_REPORT = List.of(
            hidden(CaseInsensitiveMemberShape.CaseInsensitiveMemberMarked.class, "secret", SCHEMA_HIDDEN, false));

    /**
     * {@code @Schema(hidden = true)} alone on a public field bound through a {@code @JsonDeserialize}
     * converter: the input direction describes the member as the converter's delegate type, a path that
     * ignores the marker.
     */
    static final class ConverterMemberShape {
        static final class ConverterMemberMarked {
            public String name;

            @JsonDeserialize(converter = Trimming.class)
            @Schema(hidden = true)
            public String secret;
        }

        static final class Trimming extends StdConverter<String, String> {
            @Override
            public String convert(String value) {
                return value.trim();
            }
        }
    }

    /** Input: described as the converter's delegate type, a path that ignores the marker. Output: honored. */
    private static final List<HiddenMember> CONVERTER_MEMBER_INPUT_REPORT =
            List.of(hidden(ConverterMemberShape.ConverterMemberMarked.class, "secret", SCHEMA_HIDDEN, false));

    // ---------------------------------------------------------------- both-marker fixtures
    //
    // The positions above that @Hidden can mark, each carrying @Hidden and @Schema(hidden = true) together.
    // A setter beside a getter is SetterBothMarkersGetterShape; a class is HidesTypeTwiceShape.

    /** Both markers on the getter of a setter-bound property. */
    static final class GetterBothShape {
        static final class GetterBothBean {
            private String secret;

            @Hidden
            @Schema(hidden = true)
            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    /** Both markers on a {@code @JsonUnwrapped} member. */
    static final class UnwrappedBothShape {
        static final class UnwrappedBoth {
            public String name;

            @JsonUnwrapped
            @Hidden
            @Schema(hidden = true)
            public Address address;
        }
    }

    /** Both markers on an enum constant a member's enum declares. */
    static final class TierBothShape {
        static final class TierHolder {
            public Tier tier;
        }

        enum Tier {
            VISIBLE,
            @Hidden
            @Schema(hidden = true)
            SECRET
        }
    }

    /**
     * Both markers on a public field only through the mix-in {@link #MIX_IN_BOTH_PROFILE}'s mapper registers
     * for its type, which itself declares nothing.
     */
    static final class MixInBothShape {
        static final class MixInBothTarget {
            public String secret;
            public String name;
        }

        /** Never described itself: the profile mapper merges its annotations into {@link MixInBothTarget}'s. */
        abstract static class MixInBothTargetMixIn {
            @Hidden
            @Schema(hidden = true)
            public String secret;
        }
    }

    // ---------------------------------------------------------------- honored-marker fixtures

    /** {@code @Schema(hidden = true)} alone on a public field beside an unmarked one. */
    static final class PublicSchemaHiddenField {
        public String name;

        @Schema(hidden = true)
        public String secret;
    }

    /** {@code @Schema(hidden = true)} on a record component. */
    record SchemaHiddenRecord(@Schema(hidden = true) String secret, String name) {}

    /** {@code @JsonIgnore} beside {@code @Schema(hidden = true)}: Jackson neither binds nor serializes it. */
    static final class IgnoredAndSchemaHidden {
        public String name;

        @JsonIgnore
        @Schema(hidden = true)
        public String secret;
    }

    // ---------------------------------------------------------------- field-or-getter twins
    //
    // Each twin is its original with @Schema(hidden = true) declared directly on the property's own field,
    // or on its getter when the mapper sees no field, and with no mix-in or bundle: whether the twin's
    // document leaves the property out is what the original's flag must say.

    /** A private final list read through a setterless getter carrying {@code @Hidden}. */
    static final class GetterOnlyItems {
        private final List<String> items = new ArrayList<>();

        @Hidden
        public List<String> getItems() {
            return items;
        }
    }

    /** {@link GetterOnlyItems}, its getter also carrying {@code @Schema(hidden = true)}. */
    static final class GetterOnlyItemsTwin {
        private final List<String> items = new ArrayList<>();

        @Hidden
        @Schema(hidden = true)
        public List<String> getItems() {
            return items;
        }
    }

    /** {@link HiddenOnlyBody}, its field {@code debug} also carrying {@code @Schema(hidden = true)}. */
    static final class HiddenOnlyBodyDebugTwin {
        public String name;

        @Hidden
        @Schema(hidden = true)
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

    /** {@link HiddenOnlyBody}, the field of its setter-bound {@code override} carrying {@code @Schema(hidden = true)}. */
    static final class HiddenOnlyBodyOverrideTwin {
        public String name;

        @Hidden
        @Size(max = 3)
        public String debug;

        @Schema(hidden = true)
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

    /** {@link CreatorMarkedShape.CreatorMarked}, the marker on the backing field instead of the parameter. */
    static final class CreatorMarkedTwin {
        @Schema(hidden = true)
        private final String secret;

        private final String name;

        @JsonCreator
        CreatorMarkedTwin(@JsonProperty("secret") String secret, @JsonProperty("name") String name) {
            this.secret = secret;
            this.name = name;
        }

        public String getSecret() {
            return secret;
        }

        public String getName() {
            return name;
        }
    }

    /** {@link FactoryMarkedShape.FactoryMarked}, the marker on the backing field instead of the parameter. */
    static final class FactoryMarkedTwin {
        @Schema(hidden = true)
        private final String secret;

        private final String name;

        private FactoryMarkedTwin(String name, String secret) {
            this.name = name;
            this.secret = secret;
        }

        @JsonCreator
        static FactoryMarkedTwin of(@JsonProperty("name") String name, @JsonProperty("secret") String secret) {
            return new FactoryMarkedTwin(name, secret);
        }

        public String getSecret() {
            return secret;
        }

        public String getName() {
            return name;
        }
    }

    /** {@link BuiltMarkedShape.BuiltMarked}, the marker on the built type's field instead of the builder method. */
    @JsonDeserialize(builder = BuiltMarkedTwin.Builder.class)
    static final class BuiltMarkedTwin {
        @Schema(hidden = true)
        private final String secret;

        private final String name;

        BuiltMarkedTwin(String secret, String name) {
            this.secret = secret;
            this.name = name;
        }

        public String getSecret() {
            return secret;
        }

        public String getName() {
            return name;
        }

        @JsonPOJOBuilder(withPrefix = "")
        static final class Builder {
            private String secret;
            private String name;

            public Builder secret(String value) {
                secret = value;
                return this;
            }

            public Builder name(String value) {
                name = value;
                return this;
            }

            public BuiltMarkedTwin build() {
                return new BuiltMarkedTwin(secret, name);
            }
        }
    }

    /** {@link MapOverlayMarkedShape.MapOverlayMarked}, whose marker already sits on the field itself. */
    static final class MapOverlayMarkedTwin {
        public String name;

        @Schema(hidden = true)
        public Map<String, @Size(max = 3) String> secret;
    }

    /** {@link SetterMarkedShape.SetterMarkedBean}, the marker on the private field instead of the setter. */
    static final class SetterMarkedBeanTwin {
        @Schema(hidden = true)
        private String secret;

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    /** {@link MixInTargetShape.MixInTarget}, the marker declared on the field itself, with no mix-in. */
    static final class MixInTargetTwin {
        @Schema(hidden = true)
        public String secret;

        public String name;
    }

    /** {@link BundleMarkedShape.BundleMarked}, the marker declared on the field itself, with no bundle. */
    static final class BundleMarkedTwin {
        @Schema(hidden = true)
        public String secret;
    }

    // ---------------------------------------------------------------- merge and order fixtures

    /**
     * One marked declaration reached at two positions: {@code Base}'s public field {@code code} carries
     * {@code @Hidden}, and {@code Sub} adds a setter, so on input {@code Base}'s property is bound through
     * the field and {@code Sub}'s through the setter. The input direction reaches a holder's members in
     * its deserializer's property order, not in declaration order, so {@code Holder} and {@code
     * ReversedHolder}, whose members share their names, reach the positions in the same order. {@code
     * FieldFirstHolder} and {@code SetterFirstHolder} share their member names and swap the members'
     * types, so whichever that order is, one of them reaches the field-bound position first and the other
     * the setter-bound one.
     */
    static final class MergeShape {
        static class Base {
            @Hidden
            public String code;
        }

        static final class Sub extends Base {
            public void setCode(String code) {
                this.code = code;
            }
        }

        static final class Holder {
            public Base base;
            public Sub sub;
        }

        static final class ReversedHolder {
            public Sub sub;
            public Base base;
        }

        static final class FieldFirstHolder {
            public Base first;
            public Sub second;
        }

        static final class SetterFirstHolder {
            public Sub first;
            public Base second;
        }

        /** A type carrying {@code @Hidden} beside two members carrying it, declared out of name order. */
        @Hidden
        static final class Flagged {
            @Hidden
            public String beta;

            @Hidden
            public String alpha;
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName(
            "A body member or type carrying @Hidden is reported by an input-direction generator with its marker and"
                    + " flag, ordered by declaring type then member with the type-level entry first, while generateCanonical"
                    + " still describes it, an unwrapped member through its flattened content, unchanged by the call")
    void reportsMembersAndTypesCarryingHidden() {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        String before = generator.generateCanonical(HiddenOnlyBody.class);
        List<HiddenMember> actual = generator.hiddenMembers(HiddenOnlyBody.class);
        String after = generator.generateCanonical(HiddenOnlyBody.class);
        Set<String> published = publishedPropertyNames(before);

        String unwrappedFieldBefore = generator.generateCanonical(HiddenUnwrappedField.class);
        List<HiddenMember> unwrappedFieldReport = generator.hiddenMembers(HiddenUnwrappedField.class);
        String unwrappedFieldAfter = generator.generateCanonical(HiddenUnwrappedField.class);
        String unwrappedGetterBefore = generator.generateCanonical(HiddenUnwrappedGetter.class);
        List<HiddenMember> unwrappedGetterReport = generator.hiddenMembers(HiddenUnwrappedGetter.class);
        String unwrappedGetterAfter = generator.generateCanonical(HiddenUnwrappedGetter.class);

        assertAll(
                () -> assertEquals(BODY_REPORT, actual),
                () -> assertTrue(
                        published.containsAll(Set.of("debug", "override", "nested", "flag")),
                        () -> "the input schema must still describe every reported member: " + before),
                () -> assertEquals(before, after, "hiddenMembers must not change what generateCanonical publishes"),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> actual.add(hidden(HiddenOnlyBody.class, "name", HIDDEN, true)),
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
                        "hiddenMembers must not change what generateCanonical publishes"),
                () -> assertEquals(UNWRAPPED_GETTER_REPORT, unwrappedGetterReport),
                () -> assertEquals(
                        UNWRAPPED_PROPERTIES,
                        publishedPropertyNames(unwrappedGetterBefore),
                        () -> "the input schema must describe the unwrapped getter's flattened content: "
                                + unwrappedGetterBefore),
                () -> assertEquals(
                        unwrappedGetterBefore,
                        unwrappedGetterAfter,
                        "hiddenMembers must not change what generateCanonical publishes"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nothingToReportCases")
    @DisplayName("Nothing is reported where the generator already hides the member, Jackson does not bind it, or"
            + " nothing carries a hiding marker")
    void reportsNothingTheGeneratorDoesNotDescribe(String label, Class<?> type) {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        assertEquals(List.of(), generator.hiddenMembers(type), label);
    }

    private static Stream<Arguments> nothingToReportCases() {
        return Stream.of(
                Arguments.of(
                        "@Hidden beside @Schema(hidden = true) on one field: the generator hides the member",
                        BothMarkers.class),
                Arguments.of(
                        "@Schema(hidden = true) alone on one field: the generator hides the member",
                        SchemaHiddenOnly.class),
                Arguments.of(
                        "@Hidden on the field and @Schema(hidden = true) on its getter: the generator reads the two"
                                + " together and hides the property",
                        SplitMarkers.class),
                Arguments.of(
                        "@JsonIgnore beside @Hidden: Jackson does not bind the member, so the generator does not"
                                + " describe it",
                        IgnoredAndHidden.class),
                Arguments.of("a plain DTO: nothing carries a hiding marker", PlainBody.class));
    }

    @Test
    @DisplayName("Every marked member reachable through nested members, list elements, map values, and a generic"
            + " type reached under two type arguments is reported once, in declaring-type-then-member order")
    void reportsEveryReachableMarkedMemberOnce() {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        List<HiddenMember> orderReport = generator.hiddenMembers(Order.class);
        String orderSchema = generator.generateCanonical(Order.class);
        Set<String> orderProperties = publishedPropertyNames(orderSchema);
        List<HiddenMember> boxesReport = generator.hiddenMembers(Boxes.class);
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
            String label, Supplier<AnnotationJsonSchemaGenerator> factory, Type type, List<HiddenMember> answer) {
        AnnotationJsonSchemaGenerator generator = factory.get();

        if (answer == null) {
            assertThrows(IllegalStateException.class, () -> generator.hiddenMembers(type), label);
        } else {
            assertEquals(answer, generator.hiddenMembers(type), label);
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
    @DisplayName("hiddenMembers keeps generateCanonical's type grammar, bounded failure contract, restore-on-failure"
            + " behavior, and per-instance lock, and the gate's engine still enforces a reported member's"
            + " constraint")
    void keepsTheInputSchemaAndFailureContract() throws Exception {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        // Failure kind: outside generateCanonical's accepted Type grammar.
        JsonSchemaGenerationException typeVariableFailure =
                assertThrows(JsonSchemaGenerationException.class, () -> generator.hiddenMembers(typeVariable()));
        assertTrue(
                typeVariableFailure.getMessage().contains("unresolved type variable"),
                typeVariableFailure.getMessage());

        // Reuse: the aborted call leaves no residual state on this instance — its later results equal a
        // fresh instance's.
        List<HiddenMember> reusedReport = generator.hiddenMembers(HiddenOnlyBody.class);
        String reusedSchema = generator.generateCanonical(HiddenOnlyBody.class);
        AnnotationJsonSchemaGenerator fresh = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);
        assertAll(
                () -> assertEquals(fresh.hiddenMembers(HiddenOnlyBody.class), reusedReport),
                () -> assertEquals(fresh.generateCanonical(HiddenOnlyBody.class), reusedSchema));

        // Verdicts: the gate's engine still enforces the reported member's @Size(max = 3).
        assertAll(
                () -> assertFalse(
                        isValid(reusedSchema, new JsonObject().put("debug", "abcd")),
                        () -> "a four-character debug must be refused by " + reusedSchema),
                () -> assertTrue(
                        isValid(reusedSchema, new JsonObject().put("debug", "abc")),
                        () -> "a three-character debug must be accepted by " + reusedSchema));

        // Lock ordering: a report waiting behind another type's generation on the same instance holds only
        // its own type's members. The held type's marked member is real: a recording open while its
        // generation describes it takes it.
        assertEquals(
                List.of(hidden(HeldHidden.class, "held", HIDDEN, true)),
                AnnotationJsonSchemaGenerator.forInputProfile(PROFILE).hiddenMembers(HeldHidden.class));

        GatingIntrospector gate = new GatingIntrospector();
        AnnotationJsonSchemaGenerator gated = AnnotationJsonSchemaGenerator.forInputProfile(JsonMapperProfiles.of(
                JsonProfileId.of("hidden-member-report-test-gated"),
                JsonMapper.builder().annotationIntrospector(gate).build()));
        FutureTask<String> heldGeneration = new FutureTask<>(() -> gated.generateCanonical(HeldHidden.class));
        FutureTask<List<HiddenMember>> report = new FutureTask<>(() -> gated.hiddenMembers(HiddenOnlyBody.class));
        Thread heldCaller = new Thread(heldGeneration, "hidden-member-report-test-held-generation");
        Thread reportCaller = new Thread(report, "hidden-member-report-test-report");
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

        List<HiddenMember> reported = report.get(AWAIT_SECONDS, SECONDS);
        String heldSchema = heldGeneration.get(AWAIT_SECONDS, SECONDS);
        assertAll(
                () -> assertEquals(BODY_REPORT, reported),
                () -> assertTrue(
                        publishedPropertyNames(heldSchema).contains("held"),
                        () -> "the held generation must describe its marked member: " + heldSchema),
                () -> assertTrue(gate.releasedByTheTest, "the gate timed out instead of being released by the test"));
    }

    @Test
    @DisplayName("An output-direction generator reports response members and types carrying @Hidden, an unwrapped"
            + " member described through its flattened content included, never a member the output schema leaves"
            + " out, and the call leaves the output schema unchanged")
    void reportsOutputMembersAndTypesCarryingHidden() {
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE);

        String before = generator.generateCanonical(HiddenOnlyResponse.class);
        List<HiddenMember> actual = generator.hiddenMembers(HiddenOnlyResponse.class);
        String after = generator.generateCanonical(HiddenOnlyResponse.class);

        List<HiddenMember> orderReport = generator.hiddenMembers(Order.class);
        String orderSchema = generator.generateCanonical(Order.class);
        Set<String> orderProperties = publishedPropertyNames(orderSchema);
        List<HiddenMember> boxesReport = generator.hiddenMembers(Boxes.class);
        List<HiddenMember> getterReport = generator.hiddenMembers(GetterHiddenResponse.class);
        String getterSchema = generator.generateCanonical(GetterHiddenResponse.class);

        String unwrappedFieldBefore = generator.generateCanonical(HiddenUnwrappedField.class);
        List<HiddenMember> unwrappedFieldReport = generator.hiddenMembers(HiddenUnwrappedField.class);
        String unwrappedFieldAfter = generator.generateCanonical(HiddenUnwrappedField.class);
        String unwrappedGetterBefore = generator.generateCanonical(HiddenUnwrappedGetter.class);
        List<HiddenMember> unwrappedGetterReport = generator.hiddenMembers(HiddenUnwrappedGetter.class);
        String unwrappedGetterAfter = generator.generateCanonical(HiddenUnwrappedGetter.class);

        assertAll(
                () -> assertEquals(RESPONSE_REPORT, actual),
                () -> assertEquals(
                        Set.of("name", "debug", "nested", "value", "flag"),
                        publishedPropertyNames(before),
                        () -> "the output schema must describe name, debug, nested, nested.value, and nested.flag,"
                                + " and neither both nor ignored: " + before),
                () -> assertEquals(before, after, "hiddenMembers must not change what generateCanonical publishes"),
                // Reachability in the output direction: nested members and list elements, not map values.
                () -> assertEquals(OUTPUT_ORDER_REPORT, orderReport),
                () -> assertTrue(
                        OUTPUT_ORDER_REPORT.stream().allMatch(entry -> orderProperties.contains(entry.member())),
                        () -> "every reported member must be a described property of " + orderSchema),
                // A generic type reached under two type arguments: one entry.
                () -> assertEquals(BOXES_REPORT, boxesReport),
                // A private field whose getter alone carries @Hidden: the getter, by its own name.
                () -> assertEquals(
                        List.of(hidden(GetterHiddenResponse.class, "getSecret", HIDDEN, true)), getterReport),
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
                        "hiddenMembers must not change what generateCanonical publishes"),
                () -> assertEquals(UNWRAPPED_GETTER_REPORT, unwrappedGetterReport),
                () -> assertEquals(
                        UNWRAPPED_PROPERTIES,
                        publishedPropertyNames(unwrappedGetterBefore),
                        () -> "the output schema must describe the unwrapped getter's flattened content: "
                                + unwrappedGetterBefore),
                () -> assertEquals(
                        unwrappedGetterBefore,
                        unwrappedGetterAfter,
                        "hiddenMembers must not change what generateCanonical publishes"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapeMatrix")
    @DisplayName("For every shape a document describes a member or type through, in each direction, the report is"
            + " exactly the marked members and types the document describes, with their markers and flags, covers"
            + " what swagger-core omits and what the profile mapper's merged view sees as marked, and the call leaves"
            + " the document unchanged")
    void reportsEveryDescribedMarkedMemberInBothDirections(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Class<?> shape,
            Type root,
            List<HiddenMember> expected) {
        AnnotationJsonSchemaGenerator generator = direction.generator(profile);

        String before = generator.generateCanonical(root);
        List<HiddenMember> actual = generator.hiddenMembers(root);
        String after = generator.generateCanonical(root);

        assertAll(
                () -> assertEquals(
                        expected,
                        actual,
                        () -> label + ": the report must list every marked member and type the document describes,"
                                + " with its marker and flag: " + before),
                // Closure: the row's list, without its flags, is exactly what the document describes of its
                // fixture types' marked declarations, so no described declaration is left out of the expectation.
                () -> assertEquals(
                        markedDeclarations(expected),
                        describedMarkedDeclarations(before, shape, direction, profile.mapper()),
                        () -> label + ": the expected list must be exactly the marked declarations of the shape's"
                                + " types that the document describes: " + before),
                // Differential: what swagger-core, on the row profile's mapper, omits because of either marker.
                () -> assertEquals(
                        List.of(),
                        uncoveredSwaggerCoreOmissions(before, root, profile.mapper(), actual),
                        () -> label + ": every property the document describes that swagger-core's model omits"
                                + " because of a hiding marker must be reported: " + before),
                // Merged view: what Jackson's merged annotations on the profile mapper see as marked.
                () -> assertEquals(
                        List.of(),
                        uncoveredMergedViewCarriers(before, shape, direction, profile.mapper(), actual),
                        () -> label + ": every property and type the document describes that the profile mapper's"
                                + " merged view sees as carrying a hiding marker must be reported: " + before),
                () -> assertEquals(before, after, "hiddenMembers must not change what generateCanonical publishes"));
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
                        INHERITANCE_INPUT_REPORT),
                shape(
                        "output: inherited members",
                        Direction.OUTPUT,
                        InheritanceShape.class,
                        InheritanceShape.Root.class,
                        INHERITANCE_OUTPUT_REPORT),
                shape(
                        "input: getters overriding a superclass or interface getter that carries @Hidden",
                        Direction.INPUT,
                        OverriddenGetterShape.class,
                        OverriddenGetterShape.Root.class,
                        OVERRIDDEN_GETTER_INPUT_REPORT),
                shape(
                        "output: getters overriding a superclass or interface getter that carries @Hidden",
                        Direction.OUTPUT,
                        OverriddenGetterShape.class,
                        OverriddenGetterShape.Root.class,
                        OVERRIDDEN_GETTER_OUTPUT_REPORT),
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
                        SETTER_CARRIER_INPUT_REPORT),
                shape(
                        "output: a public field whose setter alone carries @Hidden",
                        Direction.OUTPUT,
                        SetterCarrierShape.class,
                        SetterCarrierShape.Root.class,
                        SETTER_CARRIER_OUTPUT_REPORT),
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
                        GENERIC_SETTER_INPUT_REPORT),
                shape(
                        "output: generic @Hidden setters overridden with a concrete parameter type, on a concrete"
                                + " and an abstract member type",
                        Direction.OUTPUT,
                        GenericSetterShape.class,
                        GenericSetterShape.Root.class,
                        GENERIC_SETTER_OUTPUT_REPORT),
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
                        INHERITED_MIX_IN_METHOD_INPUT_REPORT),
                shape(
                        "output: a superclass's mix-in marking a getter only the subclass declares",
                        Direction.OUTPUT,
                        INHERITED_MIX_IN_METHOD_PROFILE,
                        InheritedMixInMethodShape.class,
                        InheritedMixInMethodShape.Root.class,
                        INHERITED_MIX_IN_METHOD_OUTPUT_REPORT),
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
                        List.of(hidden(SetterBothMarkersShape.Root.class, "bothSecret", BOTH, false))),
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
                        List.of(hidden(SetterBothMarkersLibraryShape.Locker.class, "setLockerSecret", BOTH, true))),
                shape(
                        "input: a property with a getter whose setter carries both markers",
                        Direction.INPUT,
                        SetterBothMarkersGetterShape.class,
                        SetterBothMarkersGetterShape.Root.class,
                        List.of(hidden(SetterBothMarkersGetterShape.Root.class, "setPin", BOTH, false))),
                shape(
                        "output: a property with a getter whose setter carries both markers",
                        Direction.OUTPUT,
                        SetterBothMarkersGetterShape.class,
                        SetterBothMarkersGetterShape.Root.class,
                        List.of(hidden(SetterBothMarkersGetterShape.Root.class, "setPin", BOTH, true))),
                shape(
                        "input: sub override @Schema(hidden), base @Hidden",
                        Direction.INPUT,
                        OverrideSchemaOnSubShape.class,
                        OverrideSchemaOnSubShape.Root.class,
                        List.of(
                                hidden(OverrideSchemaOnSubShape.Base.class, "setPin", HIDDEN, false),
                                hidden(OverrideSchemaOnSubShape.Root.class, "setPin", SCHEMA_HIDDEN, false))),
                shape(
                        "output: sub override @Schema(hidden), base @Hidden",
                        Direction.OUTPUT,
                        OverrideSchemaOnSubShape.class,
                        OverrideSchemaOnSubShape.Root.class,
                        List.of(
                                hidden(OverrideSchemaOnSubShape.Base.class, "setPin", HIDDEN, true),
                                hidden(OverrideSchemaOnSubShape.Root.class, "setPin", SCHEMA_HIDDEN, true))),
                shape(
                        "input: sub override both, base @Hidden",
                        Direction.INPUT,
                        OverrideBothOnSubShape.class,
                        OverrideBothOnSubShape.Root.class,
                        List.of(
                                hidden(OverrideBothOnSubShape.Base.class, "setPin", HIDDEN, false),
                                hidden(OverrideBothOnSubShape.Root.class, "setPin", BOTH, false))),
                shape(
                        "output: sub override both, base @Hidden",
                        Direction.OUTPUT,
                        OverrideBothOnSubShape.class,
                        OverrideBothOnSubShape.Root.class,
                        List.of(
                                hidden(OverrideBothOnSubShape.Base.class, "setPin", HIDDEN, true),
                                hidden(OverrideBothOnSubShape.Root.class, "setPin", BOTH, true))),
                shape(
                        "input: base @Schema(hidden), sub override @Hidden",
                        Direction.INPUT,
                        SchemaOnBaseHiddenOnSubShape.class,
                        SchemaOnBaseHiddenOnSubShape.Root.class,
                        List.of(
                                hidden(SchemaOnBaseHiddenOnSubShape.Base.class, "setPin", SCHEMA_HIDDEN, false),
                                hidden(SchemaOnBaseHiddenOnSubShape.Root.class, "setPin", HIDDEN, false))),
                shape(
                        "output: base @Schema(hidden), sub override @Hidden",
                        Direction.OUTPUT,
                        SchemaOnBaseHiddenOnSubShape.class,
                        SchemaOnBaseHiddenOnSubShape.Root.class,
                        List.of(
                                hidden(SchemaOnBaseHiddenOnSubShape.Base.class, "setPin", SCHEMA_HIDDEN, true),
                                hidden(SchemaOnBaseHiddenOnSubShape.Root.class, "setPin", HIDDEN, true))),
                shape(
                        "input: generic @Hidden, concrete override @Schema(hidden)",
                        Direction.INPUT,
                        GenericOverrideSchemaShape.class,
                        GenericOverrideSchemaShape.Root.class,
                        List.of(
                                hidden(GenericOverrideSchemaShape.Root.class, "setPin", SCHEMA_HIDDEN, false),
                                hidden(GenericOverrideSchemaShape.Settable.class, "setPin", HIDDEN, false))),
                shape(
                        "output: generic @Hidden, concrete override @Schema(hidden)",
                        Direction.OUTPUT,
                        GenericOverrideSchemaShape.class,
                        GenericOverrideSchemaShape.Root.class,
                        List.of(
                                hidden(GenericOverrideSchemaShape.Root.class, "setPin", SCHEMA_HIDDEN, true),
                                hidden(GenericOverrideSchemaShape.Settable.class, "setPin", HIDDEN, true))),
                shape(
                        "input: generic @Hidden, concrete override both",
                        Direction.INPUT,
                        GenericOverrideBothShape.class,
                        GenericOverrideBothShape.Root.class,
                        List.of(
                                hidden(GenericOverrideBothShape.Root.class, "setPin", BOTH, false),
                                hidden(GenericOverrideBothShape.Settable.class, "setPin", HIDDEN, false))),
                shape(
                        "output: generic @Hidden, concrete override both",
                        Direction.OUTPUT,
                        GenericOverrideBothShape.class,
                        GenericOverrideBothShape.Root.class,
                        List.of(
                                hidden(GenericOverrideBothShape.Root.class, "setPin", BOTH, true),
                                hidden(GenericOverrideBothShape.Settable.class, "setPin", HIDDEN, true))),
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
                        List.of(hidden(SetterlessHiddenFieldShape.Root.class, "tags", HIDDEN, true))),
                shape(
                        "input: unwrapped getter @Schema(description), field both",
                        Direction.INPUT,
                        UnwrappedGetterBothFieldShape.class,
                        UnwrappedGetterBothFieldShape.Root.class,
                        List.of(hidden(UnwrappedGetterBothFieldShape.Root.class, "inner", BOTH, false))),
                shape(
                        "output: unwrapped getter @Schema(description), field both",
                        Direction.OUTPUT,
                        UnwrappedGetterBothFieldShape.class,
                        UnwrappedGetterBothFieldShape.Root.class,
                        List.of(hidden(UnwrappedGetterBothFieldShape.Root.class, "inner", BOTH, false))),
                shape(
                        "input: library type, @Hidden private field, setter @Schema(hidden)",
                        Direction.INPUT,
                        SetterSchemaLibraryHiddenFieldShape.class,
                        SetterSchemaLibraryHiddenFieldShape.Root.class,
                        List.of(
                                hidden(SetterSchemaLibraryHiddenFieldShape.Locker.class, "lockerSecret", HIDDEN, true),
                                hidden(
                                        SetterSchemaLibraryHiddenFieldShape.Locker.class,
                                        "setLockerSecret",
                                        SCHEMA_HIDDEN,
                                        true))),
                shape(
                        "input: concrete type, @Hidden private field, setter @Schema(hidden)",
                        Direction.INPUT,
                        SetterSchemaConcreteHiddenFieldShape.class,
                        SetterSchemaConcreteHiddenFieldShape.Root.class,
                        List.of(
                                hidden(SetterSchemaConcreteHiddenFieldShape.Root.class, "secret", HIDDEN, false),
                                hidden(
                                        SetterSchemaConcreteHiddenFieldShape.Root.class,
                                        "setSecret",
                                        SCHEMA_HIDDEN,
                                        false))),
                shape(
                        "input: mix-in @Schema(hidden) on @Hidden setter",
                        Direction.INPUT,
                        SETTER_SCHEMA_MIX_IN_PROFILE,
                        MixInSchemaSetterShape.class,
                        MixInSchemaSetterShape.Root.class,
                        List.of(hidden(MixInSchemaSetterShape.Root.class, "setPin", BOTH, false))),
                shape(
                        "output: mix-in @Schema(hidden) on @Hidden setter",
                        Direction.OUTPUT,
                        SETTER_SCHEMA_MIX_IN_PROFILE,
                        MixInSchemaSetterShape.class,
                        MixInSchemaSetterShape.Root.class,
                        List.of(hidden(MixInSchemaSetterShape.Root.class, "setPin", BOTH, true))),
                shape(
                        "input: setter-only, base @Hidden, override @Schema(hidden)",
                        Direction.INPUT,
                        SetterOnlyOverrideSchemaShape.class,
                        SetterOnlyOverrideSchemaShape.Root.class,
                        List.of(
                                hidden(SetterOnlyOverrideSchemaShape.Base.class, "setPin", HIDDEN, false),
                                hidden(SetterOnlyOverrideSchemaShape.Root.class, "setPin", SCHEMA_HIDDEN, false))),
                shape(
                        "input: setter-only, base @Schema(hidden), override @Hidden",
                        Direction.INPUT,
                        SetterOnlyOverrideHiddenShape.class,
                        SetterOnlyOverrideHiddenShape.Root.class,
                        List.of(
                                hidden(SetterOnlyOverrideHiddenShape.Base.class, "setPin", SCHEMA_HIDDEN, false),
                                hidden(SetterOnlyOverrideHiddenShape.Root.class, "setPin", HIDDEN, false))),
                shape(
                        "input: setter-only, base @Hidden, override both",
                        Direction.INPUT,
                        SetterOnlyOverrideBothShape.class,
                        SetterOnlyOverrideBothShape.Root.class,
                        List.of(
                                hidden(SetterOnlyOverrideBothShape.Base.class, "setPin", HIDDEN, false),
                                hidden(SetterOnlyOverrideBothShape.Root.class, "setPin", BOTH, false))),
                shape(
                        "input: base @Schema(hidden) setter, subclass mix-in @Hidden",
                        Direction.INPUT,
                        SUBCLASS_MIX_IN_HIDDEN_PROFILE,
                        SubclassMixInHiddenShape.class,
                        SubclassMixInHiddenShape.Root.class,
                        List.of(hidden(SubclassMixInHiddenShape.Base.class, "setPin", SCHEMA_HIDDEN, false))),
                shape(
                        "output: base @Schema(hidden) setter, subclass mix-in @Hidden",
                        Direction.OUTPUT,
                        SUBCLASS_MIX_IN_HIDDEN_PROFILE,
                        SubclassMixInHiddenShape.class,
                        SubclassMixInHiddenShape.Root.class,
                        List.of(hidden(SubclassMixInHiddenShape.Base.class, "setPin", SCHEMA_HIDDEN, true))),
                shape(
                        "input: base @Hidden setter, subclass mix-in @Schema(hidden)",
                        Direction.INPUT,
                        SUBCLASS_MIX_IN_SCHEMA_PROFILE,
                        SubclassMixInSchemaShape.class,
                        SubclassMixInSchemaShape.Root.class,
                        List.of(hidden(SubclassMixInSchemaShape.Base.class, "setPin", HIDDEN, false))),
                shape(
                        "output: base @Hidden setter, subclass mix-in @Schema(hidden)",
                        Direction.OUTPUT,
                        SUBCLASS_MIX_IN_SCHEMA_PROFILE,
                        SubclassMixInSchemaShape.class,
                        SubclassMixInSchemaShape.Root.class,
                        List.of(hidden(SubclassMixInSchemaShape.Base.class, "setPin", HIDDEN, true))),
                shape(
                        "output: setterless getter @Schema(description), field @Hidden",
                        Direction.OUTPUT,
                        SetterlessHiddenFieldShape.class,
                        SetterlessHiddenFieldShape.Root.class,
                        List.of(hidden(SetterlessHiddenFieldShape.Root.class, "tags", HIDDEN, true))),
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
                        List.of()),
                shape(
                        "input: @Schema(hidden) on a setter-bound property's getter",
                        Direction.INPUT,
                        GetterMarkedShape.class,
                        GetterMarkedShape.GetterMarkedBean.class,
                        GETTER_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a JavaBean getter",
                        Direction.OUTPUT,
                        GetterMarkedShape.class,
                        GetterMarkedShape.GetterMarkedBean.class,
                        List.of()),
                shape(
                        "input: @Schema(hidden) on a setter-bound private field",
                        Direction.INPUT,
                        FieldMarkedShape.class,
                        FieldMarkedShape.FieldMarkedBean.class,
                        FIELD_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a JavaBean private field",
                        Direction.OUTPUT,
                        FieldMarkedShape.class,
                        FieldMarkedShape.FieldMarkedBean.class,
                        List.of()),
                shape(
                        "input: @Schema(hidden) on a getterless setter-bound private field",
                        Direction.INPUT,
                        GetterlessFieldMarkedShape.class,
                        GetterlessFieldMarkedShape.GetterlessFieldMarked.class,
                        GETTERLESS_FIELD_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a getterless private field",
                        Direction.OUTPUT,
                        GetterlessFieldMarkedShape.class,
                        GetterlessFieldMarkedShape.GetterlessFieldMarked.class,
                        List.of()),
                shape(
                        "input: @Schema(hidden) on a setter beside a getter",
                        Direction.INPUT,
                        SetterMarkedShape.class,
                        SetterMarkedShape.SetterMarkedBean.class,
                        SETTER_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a setter beside a getter",
                        Direction.OUTPUT,
                        SetterMarkedShape.class,
                        SetterMarkedShape.SetterMarkedBean.class,
                        SETTER_MARKED_OUTPUT_REPORT),
                shape(
                        "input: @Schema(hidden) on a constructor creator parameter",
                        Direction.INPUT,
                        CreatorMarkedShape.class,
                        CreatorMarkedShape.CreatorMarked.class,
                        CREATOR_MARKED_REPORT),
                shape(
                        "output: @Schema(hidden) on a constructor creator parameter",
                        Direction.OUTPUT,
                        CreatorMarkedShape.class,
                        CreatorMarkedShape.CreatorMarked.class,
                        CREATOR_MARKED_REPORT),
                shape(
                        "input: @Schema(hidden) on a static factory creator parameter",
                        Direction.INPUT,
                        FactoryMarkedShape.class,
                        FactoryMarkedShape.FactoryMarked.class,
                        FACTORY_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a static factory creator parameter",
                        Direction.OUTPUT,
                        FactoryMarkedShape.class,
                        FactoryMarkedShape.FactoryMarked.class,
                        FACTORY_MARKED_OUTPUT_REPORT),
                shape(
                        "input: @Schema(hidden) on an any-setter creator parameter whose extras are described",
                        Direction.INPUT,
                        CreatorAnyShape.class,
                        CreatorAnyShape.CreatorAny.class,
                        CREATOR_ANY_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on an any-setter creator parameter, which serialization never uses",
                        Direction.OUTPUT,
                        CreatorAnyShape.class,
                        CreatorAnyShape.CreatorAny.class,
                        List.of()),
                shape(
                        "input: @Schema(hidden) on an unwrapped member",
                        Direction.INPUT,
                        UnwrappedMarkedShape.class,
                        UnwrappedMarkedShape.UnwrappedMarked.class,
                        UNWRAPPED_MARKED_REPORT),
                shape(
                        "output: @Schema(hidden) on an unwrapped member",
                        Direction.OUTPUT,
                        UnwrappedMarkedShape.class,
                        UnwrappedMarkedShape.UnwrappedMarked.class,
                        UNWRAPPED_MARKED_REPORT),
                shape(
                        "input: @Schema(hidden) on an enum constant",
                        Direction.INPUT,
                        TierShape.class,
                        TierShape.TierHolder.class,
                        TIER_REPORT),
                shape(
                        "output: @Schema(hidden) on an enum constant",
                        Direction.OUTPUT,
                        TierShape.class,
                        TierShape.TierHolder.class,
                        TIER_REPORT),
                shape(
                        "input: @Schema(hidden) on a class",
                        Direction.INPUT,
                        ClassMarkedShape.class,
                        ClassMarkedShape.ClassMarkedHolder.class,
                        CLASS_MARKED_REPORT),
                shape(
                        "output: @Schema(hidden) on a class",
                        Direction.OUTPUT,
                        ClassMarkedShape.class,
                        ClassMarkedShape.ClassMarkedHolder.class,
                        CLASS_MARKED_REPORT),
                shape(
                        "input: @Schema(hidden) inherited by a root class",
                        Direction.INPUT,
                        InheritedMarkShape.class,
                        InheritedMarkShape.InheritedMarked.class,
                        INHERITED_MARK_REPORT),
                shape(
                        "output: @Schema(hidden) inherited by a root class",
                        Direction.OUTPUT,
                        InheritedMarkShape.class,
                        InheritedMarkShape.InheritedMarked.class,
                        INHERITED_MARK_REPORT),
                shape(
                        "input: @Schema(hidden) inherited by a class reached through a member",
                        Direction.INPUT,
                        InheritedMarkShape.class,
                        InheritedMarkShape.InheritedMarkHolder.class,
                        INHERITED_MARK_REPORT),
                shape(
                        "output: @Schema(hidden) inherited by a class reached through a member",
                        Direction.OUTPUT,
                        InheritedMarkShape.class,
                        InheritedMarkShape.InheritedMarkHolder.class,
                        INHERITED_MARK_REPORT),
                shape(
                        "input: @Schema(hidden) on a class and inherited by its subclass, both described",
                        Direction.INPUT,
                        InheritedBesideBaseShape.class,
                        InheritedBesideBaseShape.BesideHolder.class,
                        INHERITED_BESIDE_BASE_REPORT),
                shape(
                        "output: @Schema(hidden) on a class and inherited by its subclass, both described",
                        Direction.OUTPUT,
                        InheritedBesideBaseShape.class,
                        InheritedBesideBaseShape.BesideHolder.class,
                        INHERITED_BESIDE_BASE_REPORT),
                shape(
                        "input: mix-in @Schema(hidden) on a public field",
                        Direction.INPUT,
                        MIX_IN_TARGET_PROFILE,
                        MixInTargetShape.class,
                        MixInTargetShape.MixInTarget.class,
                        MIX_IN_TARGET_REPORT),
                shape(
                        "output: mix-in @Schema(hidden) on a public field",
                        Direction.OUTPUT,
                        MIX_IN_TARGET_PROFILE,
                        MixInTargetShape.class,
                        MixInTargetShape.MixInTarget.class,
                        MIX_IN_TARGET_REPORT),
                shape(
                        "input: @Schema(hidden) on a builder method",
                        Direction.INPUT,
                        BuiltMarkedShape.class,
                        BuiltMarkedShape.BuiltMarked.class,
                        BUILT_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a builder method, never described",
                        Direction.OUTPUT,
                        BuiltMarkedShape.class,
                        BuiltMarkedShape.BuiltMarked.class,
                        List.of()),
                shape(
                        "input: @Schema(hidden) on a map member with value constraints",
                        Direction.INPUT,
                        MapOverlayMarkedShape.class,
                        MapOverlayMarkedShape.MapOverlayMarked.class,
                        MAP_OVERLAY_MARKED_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a map member with value constraints",
                        Direction.OUTPUT,
                        MapOverlayMarkedShape.class,
                        MapOverlayMarkedShape.MapOverlayMarked.class,
                        List.of()),
                shape(
                        "input: bundled @Schema(hidden) on a public field",
                        Direction.INPUT,
                        BundleMarkedShape.class,
                        BundleMarkedShape.BundleMarked.class,
                        BUNDLE_MARKED_REPORT),
                shape(
                        "output: bundled @Schema(hidden) on a public field",
                        Direction.OUTPUT,
                        BundleMarkedShape.class,
                        BundleMarkedShape.BundleMarked.class,
                        BUNDLE_MARKED_REPORT),
                shape(
                        "input: @Schema(hidden) on a member bound case-insensitively inline",
                        Direction.INPUT,
                        CaseInsensitiveMemberShape.class,
                        CaseInsensitiveMemberShape.CaseInsensitiveMemberMarked.class,
                        CASE_INSENSITIVE_MEMBER_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a member bound case-insensitively",
                        Direction.OUTPUT,
                        CaseInsensitiveMemberShape.class,
                        CaseInsensitiveMemberShape.CaseInsensitiveMemberMarked.class,
                        List.of()),
                shape(
                        "input: @Schema(hidden) on a member bound through a converter",
                        Direction.INPUT,
                        ConverterMemberShape.class,
                        ConverterMemberShape.ConverterMemberMarked.class,
                        CONVERTER_MEMBER_INPUT_REPORT),
                shape(
                        "output: @Schema(hidden) on a member bound through a converter",
                        Direction.OUTPUT,
                        ConverterMemberShape.class,
                        ConverterMemberShape.ConverterMemberMarked.class,
                        List.of()),
                shape(
                        "input: both markers on a setter-bound property's getter",
                        Direction.INPUT,
                        GetterBothShape.class,
                        GetterBothShape.GetterBothBean.class,
                        List.of(hidden(GetterBothShape.GetterBothBean.class, "getSecret", BOTH, false))),
                shape(
                        "output: both markers on a JavaBean getter",
                        Direction.OUTPUT,
                        GetterBothShape.class,
                        GetterBothShape.GetterBothBean.class,
                        List.of()),
                shape(
                        "input: both markers on an unwrapped member",
                        Direction.INPUT,
                        UnwrappedBothShape.class,
                        UnwrappedBothShape.UnwrappedBoth.class,
                        List.of(hidden(UnwrappedBothShape.UnwrappedBoth.class, "address", BOTH, false))),
                shape(
                        "output: both markers on an unwrapped member",
                        Direction.OUTPUT,
                        UnwrappedBothShape.class,
                        UnwrappedBothShape.UnwrappedBoth.class,
                        List.of(hidden(UnwrappedBothShape.UnwrappedBoth.class, "address", BOTH, false))),
                shape(
                        "input: both markers on an enum constant",
                        Direction.INPUT,
                        TierBothShape.class,
                        TierBothShape.TierHolder.class,
                        List.of(hidden(TierBothShape.Tier.class, "SECRET", BOTH, false))),
                shape(
                        "output: both markers on an enum constant",
                        Direction.OUTPUT,
                        TierBothShape.class,
                        TierBothShape.TierHolder.class,
                        List.of(hidden(TierBothShape.Tier.class, "SECRET", BOTH, false))),
                shape(
                        "input: both markers on a class",
                        Direction.INPUT,
                        HidesTypeTwiceShape.class,
                        HidesTypeTwiceShape.HidesTypeTwice.class,
                        List.of(hidden(HidesTypeTwiceShape.DoublyHiddenType.class, null, BOTH, false))),
                shape(
                        "output: both markers on a class",
                        Direction.OUTPUT,
                        HidesTypeTwiceShape.class,
                        HidesTypeTwiceShape.HidesTypeTwice.class,
                        List.of(hidden(HidesTypeTwiceShape.DoublyHiddenType.class, null, BOTH, false))),
                shape(
                        "input: mix-in both markers on a public field",
                        Direction.INPUT,
                        MIX_IN_BOTH_PROFILE,
                        MixInBothShape.class,
                        MixInBothShape.MixInBothTarget.class,
                        List.of(hidden(MixInBothShape.MixInBothTarget.class, "secret", BOTH, true))),
                shape(
                        "output: mix-in both markers on a public field",
                        Direction.OUTPUT,
                        MIX_IN_BOTH_PROFILE,
                        MixInBothShape.class,
                        MixInBothShape.MixInBothTarget.class,
                        List.of(hidden(MixInBothShape.MixInBothTarget.class, "secret", BOTH, true))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ignoredSchemaHiddenPositions")
    @DisplayName("@Schema(hidden = true) alone, at each position a generator ignores it, is reported with its marker"
            + " and flag while the document still describes the member, and nothing is reported in a direction that"
            + " leaves the member out; the call leaves the document unchanged")
    void reportsSchemaHiddenWhereTheGeneratorIgnoresIt(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Type root,
            List<HiddenMember> expected,
            String property) {
        assertPositionReport(label, direction, profile, root, expected, property);
    }

    /**
     * One row per position and direction: (label, direction, profile, root, expected report, and the name the
     * document must describe when the report is not empty, or must leave out when it is; {@code null} when
     * neither applies).
     */
    private static Stream<Arguments> ignoredSchemaHiddenPositions() {
        return Stream.of(
                position(
                        "input: the getter of a setter-bound property",
                        Direction.INPUT,
                        GetterMarkedShape.GetterMarkedBean.class,
                        "secret",
                        GETTER_MARKED_INPUT_REPORT),
                position(
                        "output: the getter of a JavaBean property, honored",
                        Direction.OUTPUT,
                        GetterMarkedShape.GetterMarkedBean.class,
                        "secret",
                        List.of()),
                position(
                        "input: the private field of a setter-bound property",
                        Direction.INPUT,
                        FieldMarkedShape.FieldMarkedBean.class,
                        "secret",
                        FIELD_MARKED_INPUT_REPORT),
                position(
                        "output: the private field of a JavaBean property, honored",
                        Direction.OUTPUT,
                        FieldMarkedShape.FieldMarkedBean.class,
                        "secret",
                        List.of()),
                position(
                        "input: the private field of a getterless setter-bound property",
                        Direction.INPUT,
                        GetterlessFieldMarkedShape.GetterlessFieldMarked.class,
                        "secret",
                        GETTERLESS_FIELD_MARKED_INPUT_REPORT),
                position(
                        "output: the private field of a getterless property, never serialized",
                        Direction.OUTPUT,
                        GetterlessFieldMarkedShape.GetterlessFieldMarked.class,
                        "secret",
                        List.of()),
                position(
                        "input: a setter beside a getter",
                        Direction.INPUT,
                        SetterMarkedShape.SetterMarkedBean.class,
                        "secret",
                        SETTER_MARKED_INPUT_REPORT),
                position(
                        "output: a setter beside a getter",
                        Direction.OUTPUT,
                        SetterMarkedShape.SetterMarkedBean.class,
                        "secret",
                        SETTER_MARKED_OUTPUT_REPORT),
                position(
                        "input: a constructor creator parameter",
                        Direction.INPUT,
                        CreatorMarkedShape.CreatorMarked.class,
                        "secret",
                        CREATOR_MARKED_REPORT),
                position(
                        "output: a constructor creator parameter",
                        Direction.OUTPUT,
                        CreatorMarkedShape.CreatorMarked.class,
                        "secret",
                        CREATOR_MARKED_REPORT),
                position(
                        "input: a static factory creator parameter",
                        Direction.INPUT,
                        FactoryMarkedShape.FactoryMarked.class,
                        "secret",
                        FACTORY_MARKED_INPUT_REPORT),
                position(
                        "output: a static factory creator parameter",
                        Direction.OUTPUT,
                        FactoryMarkedShape.FactoryMarked.class,
                        "secret",
                        FACTORY_MARKED_OUTPUT_REPORT),
                // An any-setter's extras have no property name, so there is no name to check; the shape
                // matrix's closure oracle checks that the input document describes the extras.
                position(
                        "input: an any-setter creator parameter, described as the type's extras",
                        Direction.INPUT,
                        CreatorAnyShape.CreatorAny.class,
                        null,
                        CREATOR_ANY_INPUT_REPORT),
                position(
                        "output: an any-setter creator parameter, which serialization never uses",
                        Direction.OUTPUT,
                        CreatorAnyShape.CreatorAny.class,
                        null,
                        List.of()),
                position(
                        "input: an unwrapped member, described through its content",
                        Direction.INPUT,
                        UnwrappedMarkedShape.UnwrappedMarked.class,
                        "street",
                        UNWRAPPED_MARKED_REPORT),
                position(
                        "output: an unwrapped member, described through its content",
                        Direction.OUTPUT,
                        UnwrappedMarkedShape.UnwrappedMarked.class,
                        "street",
                        UNWRAPPED_MARKED_REPORT),
                position("input: an enum constant", Direction.INPUT, TierShape.TierHolder.class, "SECRET", TIER_REPORT),
                position(
                        "output: an enum constant",
                        Direction.OUTPUT,
                        TierShape.TierHolder.class,
                        "SECRET",
                        TIER_REPORT),
                position(
                        "input: a class reached through a member",
                        Direction.INPUT,
                        ClassMarkedShape.ClassMarkedHolder.class,
                        "markedValue",
                        CLASS_MARKED_REPORT),
                position(
                        "output: a class reached through a member",
                        Direction.OUTPUT,
                        ClassMarkedShape.ClassMarkedHolder.class,
                        "markedValue",
                        CLASS_MARKED_REPORT),
                position(
                        "input: a root class inheriting the marker",
                        Direction.INPUT,
                        InheritedMarkShape.InheritedMarked.class,
                        "inheritedMarkedValue",
                        INHERITED_MARK_REPORT),
                position(
                        "output: a root class inheriting the marker",
                        Direction.OUTPUT,
                        InheritedMarkShape.InheritedMarked.class,
                        "inheritedMarkedValue",
                        INHERITED_MARK_REPORT),
                position(
                        "input: a class inheriting the marker, reached through a member",
                        Direction.INPUT,
                        InheritedMarkShape.InheritedMarkHolder.class,
                        "inheritedMarkedValue",
                        INHERITED_MARK_REPORT),
                position(
                        "output: a class inheriting the marker, reached through a member",
                        Direction.OUTPUT,
                        InheritedMarkShape.InheritedMarkHolder.class,
                        "inheritedMarkedValue",
                        INHERITED_MARK_REPORT),
                position(
                        "input: a public field marked through the mapper's mix-in",
                        Direction.INPUT,
                        MIX_IN_TARGET_PROFILE,
                        MixInTargetShape.MixInTarget.class,
                        "secret",
                        MIX_IN_TARGET_REPORT),
                position(
                        "output: a public field marked through the mapper's mix-in",
                        Direction.OUTPUT,
                        MIX_IN_TARGET_PROFILE,
                        MixInTargetShape.MixInTarget.class,
                        "secret",
                        MIX_IN_TARGET_REPORT),
                position(
                        "input: a builder method",
                        Direction.INPUT,
                        BuiltMarkedShape.BuiltMarked.class,
                        "secret",
                        BUILT_MARKED_INPUT_REPORT),
                // The built type's own getter still describes the property on output; only the builder
                // method is absent there, so there is no name to check.
                position(
                        "output: a builder method, which only binds on input",
                        Direction.OUTPUT,
                        BuiltMarkedShape.BuiltMarked.class,
                        null,
                        List.of()),
                position(
                        "input: a map member rendered through its value constraints",
                        Direction.INPUT,
                        MapOverlayMarkedShape.MapOverlayMarked.class,
                        "secret",
                        MAP_OVERLAY_MARKED_INPUT_REPORT),
                position(
                        "output: a map member with value constraints, honored",
                        Direction.OUTPUT,
                        MapOverlayMarkedShape.MapOverlayMarked.class,
                        "secret",
                        List.of()),
                position(
                        "input: a public field marked through a Jackson annotation bundle",
                        Direction.INPUT,
                        BundleMarkedShape.BundleMarked.class,
                        "secret",
                        BUNDLE_MARKED_REPORT),
                position(
                        "output: a public field marked through a Jackson annotation bundle",
                        Direction.OUTPUT,
                        BundleMarkedShape.BundleMarked.class,
                        "secret",
                        BUNDLE_MARKED_REPORT),
                position(
                        "input: a member its own @JsonFormat binds case-insensitively, described inline",
                        Direction.INPUT,
                        CaseInsensitiveMemberShape.CaseInsensitiveMemberMarked.class,
                        "secret",
                        CASE_INSENSITIVE_MEMBER_INPUT_REPORT),
                position(
                        "output: a member its own @JsonFormat binds case-insensitively, honored",
                        Direction.OUTPUT,
                        CaseInsensitiveMemberShape.CaseInsensitiveMemberMarked.class,
                        "secret",
                        List.of()),
                position(
                        "input: a member bound through a converter, described as its delegate type",
                        Direction.INPUT,
                        ConverterMemberShape.ConverterMemberMarked.class,
                        "secret",
                        CONVERTER_MEMBER_INPUT_REPORT),
                position(
                        "output: a member bound through a converter, honored",
                        Direction.OUTPUT,
                        ConverterMemberShape.ConverterMemberMarked.class,
                        "secret",
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bothMarkerPositions")
    @DisplayName("@Hidden beside @Schema(hidden = true), at each position a generator ignores them, is reported"
            + " once as BOTH with the position's flag, and nothing is reported where the generator honors them;"
            + " the call leaves the document unchanged")
    void reportsBothMarkersWhereTheGeneratorIgnoresThem(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Type root,
            List<HiddenMember> expected,
            String property) {
        assertPositionReport(label, direction, profile, root, expected, property);
    }

    /** The same row shape as {@link #ignoredSchemaHiddenPositions}, each flag written for its own position. */
    private static Stream<Arguments> bothMarkerPositions() {
        return Stream.of(
                position(
                        "input: the getter of a setter-bound property",
                        Direction.INPUT,
                        GetterBothShape.GetterBothBean.class,
                        "secret",
                        List.of(hidden(GetterBothShape.GetterBothBean.class, "getSecret", BOTH, false))),
                position(
                        "output: the getter of a JavaBean property, honored",
                        Direction.OUTPUT,
                        GetterBothShape.GetterBothBean.class,
                        "secret",
                        List.of()),
                position(
                        "input: a setter beside a getter",
                        Direction.INPUT,
                        SetterBothMarkersGetterShape.Root.class,
                        "pin",
                        List.of(hidden(SetterBothMarkersGetterShape.Root.class, "setPin", BOTH, false))),
                position(
                        "output: a setter beside a getter",
                        Direction.OUTPUT,
                        SetterBothMarkersGetterShape.Root.class,
                        "pin",
                        List.of(hidden(SetterBothMarkersGetterShape.Root.class, "setPin", BOTH, true))),
                position(
                        "input: an unwrapped member, described through its content",
                        Direction.INPUT,
                        UnwrappedBothShape.UnwrappedBoth.class,
                        "street",
                        List.of(hidden(UnwrappedBothShape.UnwrappedBoth.class, "address", BOTH, false))),
                position(
                        "output: an unwrapped member, described through its content",
                        Direction.OUTPUT,
                        UnwrappedBothShape.UnwrappedBoth.class,
                        "street",
                        List.of(hidden(UnwrappedBothShape.UnwrappedBoth.class, "address", BOTH, false))),
                position(
                        "input: an enum constant",
                        Direction.INPUT,
                        TierBothShape.TierHolder.class,
                        "SECRET",
                        List.of(hidden(TierBothShape.Tier.class, "SECRET", BOTH, false))),
                position(
                        "output: an enum constant",
                        Direction.OUTPUT,
                        TierBothShape.TierHolder.class,
                        "SECRET",
                        List.of(hidden(TierBothShape.Tier.class, "SECRET", BOTH, false))),
                position(
                        "input: a class reached through a member",
                        Direction.INPUT,
                        HidesTypeTwiceShape.HidesTypeTwice.class,
                        "value",
                        List.of(hidden(HidesTypeTwiceShape.DoublyHiddenType.class, null, BOTH, false))),
                position(
                        "output: a class reached through a member",
                        Direction.OUTPUT,
                        HidesTypeTwiceShape.HidesTypeTwice.class,
                        "value",
                        List.of(hidden(HidesTypeTwiceShape.DoublyHiddenType.class, null, BOTH, false))),
                position(
                        "input: a public field marked through the mapper's mix-in",
                        Direction.INPUT,
                        MIX_IN_BOTH_PROFILE,
                        MixInBothShape.MixInBothTarget.class,
                        "secret",
                        List.of(hidden(MixInBothShape.MixInBothTarget.class, "secret", BOTH, true))),
                position(
                        "output: a public field marked through the mapper's mix-in",
                        Direction.OUTPUT,
                        MIX_IN_BOTH_PROFILE,
                        MixInBothShape.MixInBothTarget.class,
                        "secret",
                        List.of(hidden(MixInBothShape.MixInBothTarget.class, "secret", BOTH, true))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("honoredMarkers")
    @DisplayName("Nothing is reported for a member the generator leaves out of the document: one whose marker it"
            + " honors, one Jackson does not bind or serialize, and nothing on a DTO without a marker")
    void reportsNothingTheGeneratorLeavesOut(String label, Direction direction, Class<?> type) {
        AnnotationJsonSchemaGenerator generator = direction.generator(PROFILE);

        String document = generator.generateCanonical(type);
        List<HiddenMember> report = generator.hiddenMembers(type);

        assertAll(
                () -> assertEquals(List.of(), report, label),
                () -> assertFalse(
                        describedNames(document).contains("secret"),
                        () -> label + ": the document must leave secret out: " + document));
    }

    /** One row per fixture and direction: (label, direction, type); every marked member is named secret. */
    private static Stream<Arguments> honoredMarkers() {
        return Stream.of(
                Arguments.of(
                        "input: @Schema(hidden = true) on a public field, honored by the field path",
                        Direction.INPUT,
                        PublicSchemaHiddenField.class),
                Arguments.of(
                        "output: @Schema(hidden = true) on a public field, honored",
                        Direction.OUTPUT,
                        PublicSchemaHiddenField.class),
                Arguments.of(
                        "input: @Hidden beside @Schema(hidden = true) on a public field, honored by the field path",
                        Direction.INPUT,
                        BothMarkers.class),
                Arguments.of(
                        "output: @Hidden beside @Schema(hidden = true) on a public field, honored",
                        Direction.OUTPUT,
                        BothMarkers.class),
                Arguments.of(
                        "input: @Schema(hidden = true) alone on the only public field, honored by the field path",
                        Direction.INPUT,
                        SchemaHiddenOnly.class),
                Arguments.of(
                        "output: @Schema(hidden = true) alone on the only public field, honored",
                        Direction.OUTPUT,
                        SchemaHiddenOnly.class),
                Arguments.of(
                        "input: @Hidden on a field-bound field, @Schema(hidden = true) on its getter, honored because"
                                + " the field path reads the two together",
                        Direction.INPUT,
                        SplitMarkers.class),
                Arguments.of(
                        "output: @Hidden on the field, @Schema(hidden = true) on its getter, honored",
                        Direction.OUTPUT,
                        SplitMarkers.class),
                Arguments.of(
                        "input: @Schema(hidden = true) on a record component, honored through its backing field",
                        Direction.INPUT,
                        SchemaHiddenRecord.class),
                Arguments.of(
                        "output: @Schema(hidden = true) on a record component, honored",
                        Direction.OUTPUT,
                        SchemaHiddenRecord.class),
                Arguments.of(
                        "output: @Schema(hidden = true) on a JavaBean getter, honored",
                        Direction.OUTPUT,
                        GetterMarkedShape.GetterMarkedBean.class),
                Arguments.of(
                        "input: @JsonIgnore beside @Schema(hidden = true): not bound, so never described, whatever"
                                + " the marker",
                        Direction.INPUT,
                        IgnoredAndSchemaHidden.class),
                Arguments.of(
                        "output: @JsonIgnore beside @Schema(hidden = true): not serialized, so never described,"
                                + " whatever the marker",
                        Direction.OUTPUT,
                        IgnoredAndSchemaHidden.class),
                Arguments.of(
                        "input: @JsonIgnore beside @Hidden: not bound, so never described, whatever the marker",
                        Direction.INPUT,
                        IgnoredAndHidden.class),
                Arguments.of(
                        "output: @JsonIgnore beside @Hidden: not serialized, so never described, whatever the marker",
                        Direction.OUTPUT,
                        IgnoredAndHidden.class),
                Arguments.of(
                        "input: a plain DTO: no declaration carries a marker, so there is nothing to report",
                        Direction.INPUT,
                        PlainBody.class),
                Arguments.of(
                        "output: a plain DTO: no declaration carries a marker, so there is nothing to report",
                        Direction.OUTPUT,
                        PlainBody.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldOrGetterTwins")
    @DisplayName("A reported property's flag is true exactly when its twin, marked @Schema(hidden = true) on its own"
            + " field or, without a field, on its getter, is left out of the twin's document; a type, an enum"
            + " constant, and an unwrapped member are never hideable")
    void hideableFlagMatchesTheFieldOrGetterFix(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Type original,
            HiddenMember entry,
            Class<?> twin,
            String property) {
        List<HiddenMember> report = direction.generator(profile).hiddenMembers(original);
        HiddenMember reported = report.stream()
                .filter(candidate -> candidate.declaringType().equals(entry.declaringType())
                        && Objects.equals(candidate.member(), entry.member()))
                .findFirst()
                .orElse(null);

        assertAll(
                () -> assertEquals(
                        entry, reported, () -> label + ": the original's report must hold this entry: " + report),
                () -> {
                    if (twin != null) {
                        String twinDocument = direction.generator(PROFILE).generateCanonical(twin);
                        assertEquals(
                                entry.hideableBySchemaHidden(),
                                !describedNames(twinDocument).contains(property),
                                () -> label + ": the twin's document must leave " + property
                                        + " out exactly when the flag is true: " + twinDocument);
                    }
                });
    }

    /**
     * One row per reported entry and direction: (label, direction, the original's profile, original, the
     * expected entry with its literal flag, twin, and the twin's property). A row without a twin is a type,
     * an enum constant, or an unwrapped member, which has no field-or-getter fix.
     */
    private static Stream<Arguments> fieldOrGetterTwins() {
        return Stream.of(
                twin(
                        "input: a field-bound public field carrying @Hidden",
                        Direction.INPUT,
                        PROFILE,
                        HiddenOnlyBody.class,
                        hidden(HiddenOnlyBody.class, "debug", HIDDEN, true),
                        HiddenOnlyBodyDebugTwin.class,
                        "debug"),
                twin(
                        "input: the getter of a setter-bound property carrying @Hidden",
                        Direction.INPUT,
                        PROFILE,
                        HiddenOnlyBody.class,
                        hidden(HiddenOnlyBody.class, "getOverride", HIDDEN, false),
                        HiddenOnlyBodyOverrideTwin.class,
                        "override"),
                twin(
                        "input: a setterless getter of a private final list carrying @Hidden",
                        Direction.INPUT,
                        PROFILE,
                        GetterOnlyItems.class,
                        hidden(GetterOnlyItems.class, "getItems", HIDDEN, true),
                        GetterOnlyItemsTwin.class,
                        "items"),
                twin(
                        "input: a constructor creator parameter with a backing field",
                        Direction.INPUT,
                        PROFILE,
                        CreatorMarkedShape.CreatorMarked.class,
                        hidden(CreatorMarkedShape.CreatorMarked.class, "<init>#0", SCHEMA_HIDDEN, true),
                        CreatorMarkedTwin.class,
                        "secret"),
                twin(
                        "input: a static factory creator parameter, described through the setter path",
                        Direction.INPUT,
                        PROFILE,
                        FactoryMarkedShape.FactoryMarked.class,
                        hidden(FactoryMarkedShape.FactoryMarked.class, "of#1", SCHEMA_HIDDEN, false),
                        FactoryMarkedTwin.class,
                        "secret"),
                twin(
                        "input: a builder method",
                        Direction.INPUT,
                        PROFILE,
                        BuiltMarkedShape.BuiltMarked.class,
                        hidden(BuiltMarkedShape.BuiltMarked.Builder.class, "secret", SCHEMA_HIDDEN, false),
                        BuiltMarkedTwin.class,
                        "secret"),
                twin(
                        "input: a map member rendered through its value constraints",
                        Direction.INPUT,
                        PROFILE,
                        MapOverlayMarkedShape.MapOverlayMarked.class,
                        hidden(MapOverlayMarkedShape.MapOverlayMarked.class, "secret", SCHEMA_HIDDEN, false),
                        MapOverlayMarkedTwin.class,
                        "secret"),
                twin(
                        "input: a setter beside a getter",
                        Direction.INPUT,
                        PROFILE,
                        SetterMarkedShape.SetterMarkedBean.class,
                        hidden(SetterMarkedShape.SetterMarkedBean.class, "setSecret", SCHEMA_HIDDEN, false),
                        SetterMarkedBeanTwin.class,
                        "secret"),
                twin(
                        "input: a public field marked through the mapper's mix-in",
                        Direction.INPUT,
                        MIX_IN_TARGET_PROFILE,
                        MixInTargetShape.MixInTarget.class,
                        hidden(MixInTargetShape.MixInTarget.class, "secret", SCHEMA_HIDDEN, true),
                        MixInTargetTwin.class,
                        "secret"),
                twin(
                        "input: a public field marked through a Jackson annotation bundle",
                        Direction.INPUT,
                        PROFILE,
                        BundleMarkedShape.BundleMarked.class,
                        hidden(BundleMarkedShape.BundleMarked.class, "secret", SCHEMA_HIDDEN, true),
                        BundleMarkedTwin.class,
                        "secret"),
                twin(
                        "output: the getter of a JavaBean property carrying @Hidden",
                        Direction.OUTPUT,
                        PROFILE,
                        HiddenOnlyBody.class,
                        hidden(HiddenOnlyBody.class, "getOverride", HIDDEN, true),
                        HiddenOnlyBodyOverrideTwin.class,
                        "override"),
                twin(
                        "output: a setter beside a getter",
                        Direction.OUTPUT,
                        PROFILE,
                        SetterMarkedShape.SetterMarkedBean.class,
                        hidden(SetterMarkedShape.SetterMarkedBean.class, "setSecret", SCHEMA_HIDDEN, true),
                        SetterMarkedBeanTwin.class,
                        "secret"),
                twin(
                        "output: a constructor creator parameter",
                        Direction.OUTPUT,
                        PROFILE,
                        CreatorMarkedShape.CreatorMarked.class,
                        hidden(CreatorMarkedShape.CreatorMarked.class, "<init>#0", SCHEMA_HIDDEN, true),
                        CreatorMarkedTwin.class,
                        "secret"),
                twin(
                        "output: a static factory creator parameter",
                        Direction.OUTPUT,
                        PROFILE,
                        FactoryMarkedShape.FactoryMarked.class,
                        hidden(FactoryMarkedShape.FactoryMarked.class, "of#1", SCHEMA_HIDDEN, true),
                        FactoryMarkedTwin.class,
                        "secret"),
                twin(
                        "output: a public field marked through the mapper's mix-in",
                        Direction.OUTPUT,
                        MIX_IN_TARGET_PROFILE,
                        MixInTargetShape.MixInTarget.class,
                        hidden(MixInTargetShape.MixInTarget.class, "secret", SCHEMA_HIDDEN, true),
                        MixInTargetTwin.class,
                        "secret"),
                twin(
                        "input: an unwrapped member carrying @Schema(hidden = true), never hideable",
                        Direction.INPUT,
                        PROFILE,
                        UnwrappedMarkedShape.UnwrappedMarked.class,
                        hidden(UnwrappedMarkedShape.UnwrappedMarked.class, "address", SCHEMA_HIDDEN, false),
                        null,
                        null),
                twin(
                        "output: an unwrapped member carrying @Schema(hidden = true), never hideable",
                        Direction.OUTPUT,
                        PROFILE,
                        UnwrappedMarkedShape.UnwrappedMarked.class,
                        hidden(UnwrappedMarkedShape.UnwrappedMarked.class, "address", SCHEMA_HIDDEN, false),
                        null,
                        null),
                twin(
                        "input: an unwrapped member carrying both markers, never hideable",
                        Direction.INPUT,
                        PROFILE,
                        UnwrappedBothShape.UnwrappedBoth.class,
                        hidden(UnwrappedBothShape.UnwrappedBoth.class, "address", BOTH, false),
                        null,
                        null),
                twin(
                        "input: an enum constant carrying @Schema(hidden = true), never hideable",
                        Direction.INPUT,
                        PROFILE,
                        TierShape.TierHolder.class,
                        hidden(TierShape.Tier.class, "SECRET", SCHEMA_HIDDEN, false),
                        null,
                        null),
                twin(
                        "output: an enum constant carrying both markers, never hideable",
                        Direction.OUTPUT,
                        PROFILE,
                        TierBothShape.TierHolder.class,
                        hidden(TierBothShape.Tier.class, "SECRET", BOTH, false),
                        null,
                        null),
                twin(
                        "input: a class carrying @Schema(hidden = true), never hideable",
                        Direction.INPUT,
                        PROFILE,
                        ClassMarkedShape.ClassMarkedHolder.class,
                        hidden(ClassMarkedShape.ClassMarked.class, null, SCHEMA_HIDDEN, false),
                        null,
                        null),
                twin(
                        "output: a class carrying both markers, never hideable",
                        Direction.OUTPUT,
                        PROFILE,
                        HidesTypeTwiceShape.HidesTypeTwice.class,
                        hidden(HidesTypeTwiceShape.DoublyHiddenType.class, null, BOTH, false),
                        null,
                        null));
    }

    @Test
    @DisplayName("One entry per declaration however it is reached, its flag the conjunction over its positions;"
            + " the type-level entry sorts first; the list is unmodifiable and equal across calls and instances;"
            + " each construction mode answers, a victools-defaults generator refuses, and a report waits for the"
            + " generator's lock")
    void keepsTheOrderMergeAndGeneratorContract() throws Exception {
        // Base.code is described field-bound on Base (hideable) and setter-bound on Sub (not hideable): one
        // entry, whose flag holds at both positions only as their conjunction.
        List<HiddenMember> merged = List.of(hidden(MergeShape.Base.class, "code", HIDDEN, false));
        AnnotationJsonSchemaGenerator generator = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);

        String holderSchema = generator.generateCanonical(MergeShape.Holder.class);
        List<HiddenMember> holder = generator.hiddenMembers(MergeShape.Holder.class);
        List<HiddenMember> holderAgain = generator.hiddenMembers(MergeShape.Holder.class);
        List<HiddenMember> reversed = generator.hiddenMembers(MergeShape.ReversedHolder.class);
        List<HiddenMember> reversedAgain = generator.hiddenMembers(MergeShape.ReversedHolder.class);
        List<HiddenMember> fieldFirst = generator.hiddenMembers(MergeShape.FieldFirstHolder.class);
        List<HiddenMember> setterFirst = generator.hiddenMembers(MergeShape.SetterFirstHolder.class);
        AnnotationJsonSchemaGenerator fresh = AnnotationJsonSchemaGenerator.forInputProfile(PROFILE);
        List<HiddenMember> flagged = generator.hiddenMembers(MergeShape.Flagged.class);

        assertAll(
                // Merge: one entry, whichever holder. The swapped-type pair reaches the field-bound position
                // first in one holder and the setter-bound one first in the other, so the flag is the
                // conjunction whichever order the document reaches the positions in.
                () -> assertEquals(merged, holder),
                () -> assertEquals(merged, reversed),
                () -> assertEquals(merged, fieldFirst),
                () -> assertEquals(merged, setterFirst),
                () -> assertTrue(
                        publishedPropertyNames(holderSchema).containsAll(Set.of("base", "sub", "code")),
                        () -> "the input schema must describe both positions of code: " + holderSchema),
                // Determinism: repeated calls and a fresh instance.
                () -> assertEquals(holder, holderAgain),
                () -> assertEquals(reversed, reversedAgain),
                () -> assertEquals(holder, fresh.hiddenMembers(MergeShape.Holder.class)),
                () -> assertEquals(reversed, fresh.hiddenMembers(MergeShape.ReversedHolder.class)),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> holder.add(hidden(MergeShape.Sub.class, "setCode", HIDDEN, false)),
                        "the report is unmodifiable"),
                // Order: by declaring type, then member, the type-level entry first.
                () -> assertEquals(
                        List.of(
                                hidden(MergeShape.Flagged.class, null, HIDDEN, false),
                                hidden(MergeShape.Flagged.class, "alpha", HIDDEN, true),
                                hidden(MergeShape.Flagged.class, "beta", HIDDEN, true)),
                        flagged),
                // Grammar: an unresolved type variable fails with the bounded generation exception.
                () -> assertThrows(JsonSchemaGenerationException.class, () -> generator.hiddenMembers(typeVariable())),
                // Answering rule: a victools-defaults generator refuses, before the grammar check.
                () -> assertThrows(
                        IllegalStateException.class, () -> AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                                .hiddenMembers(MergeShape.Holder.class)),
                () -> assertThrows(
                        IllegalStateException.class, () -> AnnotationJsonSchemaGenerator.withVictoolsDefaults()
                                .hiddenMembers(typeVariable())),
                // Every other construction mode answers for its own direction.
                () -> assertEquals(
                        merged,
                        AnnotationJsonSchemaGenerator.forInputProfile(PROFILE, MetadataTestValidators.plain())
                                .hiddenMembers(MergeShape.Holder.class)),
                // Output: both positions are described through the field, so the conjunction stays true.
                () -> assertEquals(
                        List.of(hidden(MergeShape.Base.class, "code", HIDDEN, true)),
                        AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE).hiddenMembers(MergeShape.Holder.class)),
                () -> assertEquals(
                        List.of(hidden(MergeShape.Base.class, "code", HIDDEN, true)),
                        AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE)
                                .hiddenMembers(MergeShape.FieldFirstHolder.class)),
                () -> assertEquals(
                        List.of(hidden(MergeShape.Base.class, "code", HIDDEN, true)),
                        AnnotationJsonSchemaGenerator.forOutputProfile(PROFILE)
                                .hiddenMembers(MergeShape.SetterFirstHolder.class)));

        // Lock ordering: a report waiting behind another type's generation on the same instance still
        // yields its own merged entry, once.
        GatingIntrospector gate = new GatingIntrospector();
        AnnotationJsonSchemaGenerator gated = AnnotationJsonSchemaGenerator.forInputProfile(JsonMapperProfiles.of(
                JsonProfileId.of("hidden-member-report-test-gated-merge"),
                JsonMapper.builder().annotationIntrospector(gate).build()));
        FutureTask<String> heldGeneration = new FutureTask<>(() -> gated.generateCanonical(HeldHidden.class));
        FutureTask<List<HiddenMember>> report =
                new FutureTask<>(() -> gated.hiddenMembers(MergeShape.ReversedHolder.class));
        Thread heldCaller = new Thread(heldGeneration, "hidden-member-report-test-held-merge-generation");
        Thread reportCaller = new Thread(report, "hidden-member-report-test-merge-report");
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

        List<HiddenMember> reported = report.get(AWAIT_SECONDS, SECONDS);
        heldGeneration.get(AWAIT_SECONDS, SECONDS);
        assertAll(
                () -> assertEquals(merged, reported),
                () -> assertTrue(gate.releasedByTheTest, "the gate timed out instead of being released by the test"));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * An expected entry, written by hand: {@code declaringType}'s binary name, {@code member} ({@code null}
     * for the type itself), the marker the declaration carries, and whether {@code @Schema(hidden = true)}
     * on the property's own field, or on its getter when there is none, would leave it out where the row's
     * document describes it.
     */
    private static HiddenMember hidden(
            Class<?> declaringType, String member, HidingMarker marker, boolean hideableBySchemaHidden) {
        return new HiddenMember(declaringType.getName(), member, marker, hideableBySchemaHidden);
    }

    /** A declaration the closure oracle reads: declaring type, member, and marker, without the flag. */
    private record MarkedDeclaration(String declaringType, String member, HidingMarker marker) {}

    /** {@code entries} without their flags, the projection the closure oracle compares. */
    private static Set<MarkedDeclaration> markedDeclarations(List<HiddenMember> entries) {
        Set<MarkedDeclaration> declarations = new HashSet<>();
        for (HiddenMember entry : entries) {
            declarations.add(new MarkedDeclaration(entry.declaringType(), entry.member(), entry.marker()));
        }
        return declarations;
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
            String label, Direction direction, Class<?> shape, Type root, List<HiddenMember> expected) {
        return shape(label, direction, PROFILE, shape, root, expected);
    }

    private static Arguments shape(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Class<?> shape,
            Type root,
            List<HiddenMember> expected) {
        return Arguments.of(label, direction, profile, shape, root, expected);
    }

    /** A position row over the plain profile. */
    private static Arguments position(
            String label, Direction direction, Type root, String property, List<HiddenMember> expected) {
        return position(label, direction, PROFILE, root, property, expected);
    }

    private static Arguments position(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Type root,
            String property,
            List<HiddenMember> expected) {
        return Arguments.of(label, direction, profile, root, expected, property);
    }

    private static Arguments twin(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Type original,
            HiddenMember entry,
            Class<?> twin,
            String property) {
        return Arguments.of(label, direction, profile, original, entry, twin, property);
    }

    /**
     * A position row's assertions: the report equals {@code expected}; the document describes {@code property}
     * when the report is not empty and leaves it out when it is (unchecked when {@code property} is {@code
     * null}); and the call leaves the document unchanged.
     */
    private static void assertPositionReport(
            String label,
            Direction direction,
            JsonMapperProfile profile,
            Type root,
            List<HiddenMember> expected,
            String property) {
        AnnotationJsonSchemaGenerator generator = direction.generator(profile);

        String before = generator.generateCanonical(root);
        List<HiddenMember> actual = generator.hiddenMembers(root);
        String after = generator.generateCanonical(root);

        assertAll(
                () -> assertEquals(expected, actual, () -> label + ": " + before),
                () -> {
                    if (property != null) {
                        assertEquals(
                                !expected.isEmpty(),
                                describedNames(before).contains(property),
                                () -> label + ": the document must " + (expected.isEmpty() ? "leave " : "describe ")
                                        + property + (expected.isEmpty() ? " out" : "") + ": " + before);
                    }
                },
                () -> assertEquals(before, after, "hiddenMembers must not change what generateCanonical publishes"));
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

    private static ObjectMapper mixInTargetMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInTargetShape.MixInTarget.class, MixInTargetShape.MixInTargetMixIn.class);
        return mapper;
    }

    private static ObjectMapper mixInBothMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.addMixIn(MixInBothShape.MixInBothTarget.class, MixInBothShape.MixInBothTargetMixIn.class);
        return mapper;
    }

    /**
     * The shape matrix's reflection oracle: every member, creator parameter, and type nested in {@code
     * shape} that carries a hiding marker — {@link Hidden}, {@code {@literal @}Schema(hidden = true)}, or
     * both, each declared directly or through a Jackson annotation bundle — and that {@code document}
     * describes in {@code direction}, with the marker it carries. It never computes whether the marker
     * would be honored elsewhere.
     *
     * <p>Mix-ins are read as the mapper merges them: a type also carries what its mix-in class and that
     * class's superclasses declare; a field, what a same-named field of that chain declares; a method,
     * what a method of the same name and parameter types declares in the mix-in chain of its type or of
     * any supertype. {@code @Hidden} is carried when any of them declares it; the nearest {@code @Schema}
     * decides whether {@code @Schema(hidden = true)} is, and a type none of whose own or mix-in
     * declarations has one takes the {@code @Schema} it inherits from a superclass ({@code @Schema} is
     * {@code @Inherited}, {@code @Hidden} is not); a mix-in class, or a superclass of one, is never a
     * carrier. An overridden method is read by itself, under its own name and declaring type, whether
     * its override keeps its raw parameter types or, for a generic declaration, bridges to different ones;
     * an override carries only what it declares. A creator parameter is read by itself, directly and
     * through bundles. It reads only the document, the fixture types, and the mapper's mix-in
     * registrations, never the generator:
     *
     * <ul>
     *   <li>a field, getter, setter, or record accessor is described when the document names its
     *       property ({@code @JsonProperty} or the accessor name without its prefix) under a {@code
     *       properties} object — for an accessor {@code @JsonProperty} renames, also when the document
     *       names it by the accessor name without its prefix, its property's internal name;
     *   <li>a parameter of a {@code @JsonCreator} constructor or static factory, or of a record's canonical
     *       constructor, is described when the document names its property ({@code @JsonProperty}, the
     *       record component's name, or the parameter's own name) and named {@code <init>#i} for a
     *       constructor, or the factory's name followed by {@code #i}, {@code i} its zero-based index;
     *   <li>an enum constant is described when a document {@code enum} or {@code const} lists it;
     *   <li>a builder method is described, in the input direction only, when the document names the
     *       property it sets;
     *   <li>a {@code @JsonUnwrapped} member is described when its type is, and so is a field whose
     *       same-named accessor carries {@code @JsonUnwrapped};
     *   <li>an any-setter, a method or a creator parameter, is described when a schema naming its type's
     *       properties describes extras with an {@code additionalProperties} schema; a creator parameter
     *       is named as above;
     *   <li>a type is described when the document names one of its own properties or constants.
     * </ul>
     */
    private static Set<MarkedDeclaration> describedMarkedDeclarations(
            String document, Class<?> shape, Direction direction, ObjectMapper mapper) {
        JsonNode tree = readDocument(document);
        Set<String> names = new HashSet<>();
        collectDescribedNames(tree, names);
        Set<Class<?>> types = shapeTypes(shape);
        Set<Class<?>> mixIns = mixInClasses(mapper, types);
        Set<MarkedDeclaration> carriers = new HashSet<>();
        for (Class<?> type : types) {
            if (mixIns.contains(type)) {
                continue;
            }
            HidingMarker typeMarker = typeMarker(type, typeMixIns(mapper, type));
            if (typeMarker != null && isDescribed(type, names)) {
                carriers.add(new MarkedDeclaration(type.getName(), null, typeMarker));
            }
            for (Field field : type.getDeclaredFields()) {
                HidingMarker fieldMarker =
                        isPropertyField(field) ? marker(field, fieldMixIns(mapper, type, field)) : null;
                if (fieldMarker != null) {
                    boolean described = unwrapped(type, field)
                            ? isDescribed(field.getType(), names)
                            : names.contains(propertyName(field));
                    if (described) {
                        carriers.add(new MarkedDeclaration(type.getName(), field.getName(), fieldMarker));
                    }
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                HidingMarker methodMarker = marker(method, methodMixIns(mapper, type, method));
                if (methodMarker == null) {
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
                    carriers.add(new MarkedDeclaration(type.getName(), method.getName(), methodMarker));
                }
            }
            for (Executable creator : creators(type)) {
                Parameter[] parameters = creator.getParameters();
                for (int index = 0; index < parameters.length; index++) {
                    HidingMarker parameterMarker = marker(parameters[index], List.of());
                    if (parameterMarker == null) {
                        continue;
                    }
                    boolean described;
                    if (parameters[index].isAnnotationPresent(JsonAnySetter.class)) {
                        described = describesExtras(tree, type);
                    } else {
                        String property = parameterPropertyName(type, creator, index);
                        described = property != null && names.contains(property);
                    }
                    if (described) {
                        carriers.add(new MarkedDeclaration(
                                type.getName(), creatorName(creator) + "#" + index, parameterMarker));
                    }
                }
            }
        }
        return carriers;
    }

    /**
     * {@code type}'s creators: each constructor or static method carrying an enabled {@link JsonCreator},
     * and a record's canonical constructor.
     */
    private static List<Executable> creators(Class<?> type) {
        List<Executable> creators = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isSynthetic() && (isCreator(constructor) || isCanonical(type, constructor))) {
                creators.add(constructor);
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && Modifier.isStatic(method.getModifiers()) && isCreator(method)) {
                creators.add(method);
            }
        }
        return creators;
    }

    private static boolean isCreator(Executable executable) {
        JsonCreator creator = executable.getDeclaredAnnotation(JsonCreator.class);
        return creator != null && creator.mode() != JsonCreator.Mode.DISABLED;
    }

    /** Whether {@code constructor} is the canonical constructor of the record {@code type}. */
    private static boolean isCanonical(Class<?> type, Constructor<?> constructor) {
        if (!type.isRecord()) {
            return false;
        }
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (components.length != parameterTypes.length) {
            return false;
        }
        for (int index = 0; index < components.length; index++) {
            if (components[index].getType() != parameterTypes[index]) {
                return false;
            }
        }
        return true;
    }

    /** A creator's name in a reported member: {@code <init>} for a constructor, else the method's name. */
    private static String creatorName(Executable creator) {
        return creator instanceof Constructor<?> ? "<init>" : creator.getName();
    }

    /**
     * The property a creator parameter binds: its {@code @JsonProperty}, a record component's name for a
     * canonical constructor, else the parameter's own name when compiled in; {@code null} otherwise.
     */
    private static String parameterPropertyName(Class<?> type, Executable creator, int index) {
        Parameter parameter = creator.getParameters()[index];
        JsonProperty renamed = parameter.getDeclaredAnnotation(JsonProperty.class);
        if (renamed != null && !renamed.value().isEmpty()) {
            return renamed.value();
        }
        if (creator instanceof Constructor<?> constructor && isCanonical(type, constructor)) {
            return type.getRecordComponents()[index].getName();
        }
        return parameter.isNamePresent() ? parameter.getName() : null;
    }

    /** Whether {@code field}, or a same-named accessor {@code type} declares, carries {@code @JsonUnwrapped}. */
    private static boolean unwrapped(Class<?> type, Field field) {
        if (field.isAnnotationPresent(JsonUnwrapped.class)) {
            return true;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic()
                    && !Modifier.isStatic(method.getModifiers())
                    && method.isAnnotationPresent(JsonUnwrapped.class)
                    && implicitName(method).equals(field.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The differential oracle: each property that swagger-core's model resolver omits from its model
     * because of a hiding marker, {@code @Hidden} or {@code @Schema(hidden = true)}, and that {@code
     * document} describes, which {@code report} does not cover. The resolver is built on a copy of the row profile's {@code mapper}, so its mix-ins and
     * subtypes apply (the resolver registers a module on the mapper it is given, which the profile's own
     * mapper must never see), and reads {@code root} through a fresh {@link ModelConverters}.
     *
     * <p>"Because of a hiding marker" is swagger-core's own verdict, re-asked once: its property predicate
     * ({@code ModelResolver#ignore}) omits the property, and omits it no longer when its hidden check
     * ({@code hasHiddenAnnotation}) answers {@code false}. A property it omits for {@code @JsonIgnore} or
     * an ignoral list is therefore not one. See {@link #describesProperty} and {@link #covers} for
     * "describes" and "covers".
     */
    private static List<String> uncoveredSwaggerCoreOmissions(
            String document, Type root, ObjectMapper mapper, List<HiddenMember> report) {
        HiddenRecordingModelResolver resolver = new HiddenRecordingModelResolver(mapper.copy());
        ModelConverters converters = new ModelConverters();
        converters.addConverter(resolver);
        converters.readAll(root);
        Set<String> names = describedNames(document);
        Set<String> uncovered = new TreeSet<>();
        for (BeanPropertyDefinition property : resolver.omittedForMarker) {
            if (describesProperty(property, names) && !covers(report, property)) {
                uncovered.add(propertyLabel(property));
            }
        }
        return List.copyOf(uncovered);
    }

    /**
     * swagger-core's model resolver, recording each property its {@code ignore} predicate omits because
     * of a hiding marker: omitted as swagger-core decides, and kept when its hidden check is turned off.
     */
    private static final class HiddenRecordingModelResolver extends ModelResolver {
        private final List<BeanPropertyDefinition> omittedForMarker = new ArrayList<>();
        private boolean markersDisregarded;

        HiddenRecordingModelResolver(ObjectMapper mapper) {
            super(mapper);
        }

        @Override
        protected boolean hasHiddenAnnotation(Annotated annotated) {
            return !markersDisregarded && super.hasHiddenAnnotation(annotated);
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
                markersDisregarded = true;
                try {
                    if (!super.ignore(member, xmlAccessorTypeAnnotation, propName, propertiesToIgnore, propDef)) {
                        omittedForMarker.add(propDef);
                    }
                } finally {
                    markersDisregarded = false;
                }
            }
            return ignored;
        }
    }

    /**
     * The merged-view oracle: each property and type nested in {@code shape} that the row profile's
     * {@code mapper} — its deserialization view in the input direction, its serialization view in the
     * output direction, with mix-ins, bundles, and overridden declarations merged as Jackson merges them —
     * sees as carrying a hiding marker, {@code @Hidden} or {@code @Schema(hidden = true)}, that {@code
     * document} describes, and that {@code report} does not cover. It checks coverage by name only: the
     * merged view merges declarations the report reads one by one, so it never checks a marker or a flag.
     *
     * <p>A property carries a marker when any of its field, getter, setter, or creator parameter has
     * either ({@code hasAnnotation}, {@code getAnnotation}); a type, when {@code
     * AnnotatedClassResolver.resolveWithoutSuperTypes} has either, and it is described when the document
     * names one of its own properties or constants, and covered only by an entry for the type itself.
     * Mix-in classes and their superclasses are not read as types of the shape.
     */
    private static List<String> uncoveredMergedViewCarriers(
            String document, Class<?> shape, Direction direction, ObjectMapper mapper, List<HiddenMember> report) {
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
            if ((resolved.hasAnnotation(Hidden.class) || schemaHidden(resolved.getAnnotation(Schema.class)))
                    && isDescribed(type, names)
                    && report.stream()
                            .noneMatch(entry -> entry.member() == null
                                    && entry.declaringType().equals(type.getName()))) {
                uncovered.add("type " + type.getName());
            }
            BeanDescription description = direction == Direction.INPUT
                    ? mapper.getDeserializationConfig().introspect(mapper.constructType(type))
                    : mapper.getSerializationConfig().introspect(mapper.constructType(type));
            for (BeanPropertyDefinition property : description.findProperties()) {
                if (mergedMarked(property) && describesProperty(property, names) && !covers(report, property)) {
                    uncovered.add(propertyLabel(property));
                }
            }
        }
        return List.copyOf(uncovered);
    }

    /** Whether any of {@code property}'s accessors has {@code @Hidden} or {@code @Schema(hidden = true)}. */
    private static boolean mergedMarked(BeanPropertyDefinition property) {
        for (AnnotatedMember accessor : accessors(property)) {
            if (accessor.hasAnnotation(Hidden.class) || schemaHidden(accessor.getAnnotation(Schema.class))) {
                return true;
            }
        }
        return false;
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
     * field, getter, or setter by its own name, or its creator parameter as {@code <init>#i} or the
     * factory's name followed by {@code #i}, declared in that accessor's declaring type, in a supertype of
     * it (an overridden declaration), or in a subtype of it (an override the mapper merges into).
     */
    private static boolean covers(List<HiddenMember> report, BeanPropertyDefinition property) {
        for (AnnotatedMember accessor : accessors(property)) {
            Class<?> declaring = accessor.getDeclaringClass();
            String name = memberName(accessor);
            for (HiddenMember entry : report) {
                if (name.equals(entry.member())) {
                    Class<?> reported = loadFixture(entry.declaringType());
                    if (reported.isAssignableFrom(declaring) || declaring.isAssignableFrom(reported)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** An accessor's name in a reported member: its own name, or a creator parameter's {@code creator#index}. */
    private static String memberName(AnnotatedMember accessor) {
        if (accessor instanceof AnnotatedParameter parameter) {
            String creator = parameter.getOwner() instanceof AnnotatedConstructor
                    ? "<init>"
                    : parameter.getOwner().getName();
            return creator + "#" + parameter.getIndex();
        }
        return accessor.getName();
    }

    private static String propertyLabel(BeanPropertyDefinition property) {
        List<String> members = new ArrayList<>();
        for (AnnotatedMember accessor : accessors(property)) {
            members.add(accessor.getDeclaringClass().getSimpleName() + "." + memberName(accessor));
        }
        return "property " + property.getName() + " " + members;
    }

    private static Class<?> loadFixture(String binaryName) {
        try {
            return Class.forName(binaryName, false, HiddenMemberReportTest.class.getClassLoader());
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

    private static boolean schemaHidden(Schema schema) {
        return schema != null && schema.hidden();
    }

    /**
     * The marker {@code element} carries with its mix-in counterparts' annotations merged over its own,
     * or {@code null} when it carries none: {@code @Hidden} when it or any counterpart declares it,
     * directly or through a bundle; {@code @Schema(hidden = true)} when the nearest {@code @Schema} —
     * the first counterpart's, else its own, directly or through a bundle — says hidden.
     */
    private static HidingMarker marker(AnnotatedElement element, List<? extends AnnotatedElement> mixIns) {
        return marker(element, mixIns, nearestSchema(element, mixIns));
    }

    /**
     * The marker the type {@code type} carries: as {@link #marker(AnnotatedElement, List)} reads it, except
     * that when neither {@code type} nor its mix-in counterparts declare a {@code @Schema}, directly or
     * through a bundle, the {@code @Schema} it inherits from a superclass decides, as {@link
     * Class#getAnnotation} reads it. {@code @Hidden} is not inherited, so only a declared one counts.
     */
    private static HidingMarker typeMarker(Class<?> type, List<Class<?>> mixIns) {
        Schema schema = nearestSchema(type, mixIns);
        return marker(type, mixIns, schema != null ? schema : type.getAnnotation(Schema.class));
    }

    /** The first counterpart's {@code @Schema}, else {@code element}'s own, directly or through a bundle. */
    private static Schema nearestSchema(AnnotatedElement element, List<? extends AnnotatedElement> mixIns) {
        for (AnnotatedElement mixIn : mixIns) {
            Schema schema = findSchema(mixIn, new HashSet<>());
            if (schema != null) {
                return schema;
            }
        }
        return findSchema(element, new HashSet<>());
    }

    /** The marker {@code element} carries given the {@code @Schema} that decides for it, {@code null} if none. */
    private static HidingMarker marker(
            AnnotatedElement element, List<? extends AnnotatedElement> mixIns, Schema schema) {
        boolean hidden = carriesHidden(element, new HashSet<>());
        for (AnnotatedElement mixIn : mixIns) {
            hidden |= carriesHidden(mixIn, new HashSet<>());
        }
        boolean schemaHidden = schemaHidden(schema);
        if (hidden && schemaHidden) {
            return BOTH;
        }
        if (hidden) {
            return HIDDEN;
        }
        return schemaHidden ? SCHEMA_HIDDEN : null;
    }

    /** The {@code @Schema} {@code element} declares, directly or else through a Jackson annotation bundle. */
    private static Schema findSchema(AnnotatedElement element, Set<Class<?>> visitedBundles) {
        Schema direct = element.getDeclaredAnnotation(Schema.class);
        if (direct != null) {
            return direct;
        }
        for (Annotation annotation : element.getDeclaredAnnotations()) {
            Class<? extends Annotation> annotationType = annotation.annotationType();
            if (annotationType.isAnnotationPresent(JacksonAnnotationsInside.class)
                    && visitedBundles.add(annotationType)) {
                Schema bundled = findSchema(annotationType, visitedBundles);
                if (bundled != null) {
                    return bundled;
                }
            }
        }
        return null;
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
            return HiddenMemberReportTest.class
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
     * with the marked member not yet described.
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
