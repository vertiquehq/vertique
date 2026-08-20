// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-004 — the frozen T002 contract matrix for the closed remote-input profile safety validator.
 *
 * <p>The Given is a safe closed mapper plus one variant each for Jackson default typing, open subtype
 * discovery, a custom type-id resolver, a polymorphism-supplying mix-in, and a trusted custom
 * serializer/deserializer pair. Every row traverses one reachable type graph once with the validator.
 *
 * <p>Five rows isolate five boundaries, one each:
 *
 * <ol>
 *   <li>{@link #shouldTraverseMapperDerivedPrimitiveEnumContainerPropertyAndCyclicBoundaries()} — the
 *       mapper-derived traversal itself: primitives, enums, arrays, collections, maps, nested
 *       properties and a cyclic graph terminate and pass, while a raw or wildcard root is a bounded
 *       composition failure.</li>
 *   <li>{@link #shouldApplyClosedReachableTypeRulesToMapperAnnotationsMixinsAndSubtypes()} — the
 *       closed-graph rules applied through the mapper's own effective introspection, including
 *       mix-in-supplied metadata and the explicit {@code @JsonSubTypes} allowlist.</li>
 *   <li>{@link #shouldAllowTrustedCustomSerdeButRejectEveryNamedPolymorphismMechanism()} — trusted
 *       custom serde is accepted while default typing, open subtype discovery, and custom type-id
 *       resolvers are each rejected with their mechanism and type path.</li>
 *   <li>{@link #shouldRejectMemberLevelCustomResolversOnPropertiesAndContainerContent()} — a custom
 *       resolver declared on a property or on a container's content is rejected, including when it
 *       overrides an otherwise safe class-level base.</li>
 *   <li>{@link #shouldBoundMemberLevelAllowlistsAndTraverseTheirSubtypes()} — a member-declared
 *       {@code Id.NAME} allowlist is bounded exactly like a class-level one, and its subtypes are
 *       traversed instead of escaping the walk.</li>
 * </ol>
 */
@DisplayName("MCP JSON profile safety — T002 contract matrix")
class McpJsonProfileSafetyTest {

    // --- Matrix ---

    /**
     * The five named rows of the T002 profile-safety matrix.
     *
     * @return one row per boundary, named exactly as the proof contract lists it
     */
    static Stream<MatrixRow> t002ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldTraverseMapperDerivedPrimitiveEnumContainerPropertyAndCyclicBoundaries",
                        McpJsonProfileSafetyTest
                                ::shouldTraverseMapperDerivedPrimitiveEnumContainerPropertyAndCyclicBoundaries),
                new MatrixRow(
                        "shouldApplyClosedReachableTypeRulesToMapperAnnotationsMixinsAndSubtypes",
                        McpJsonProfileSafetyTest
                                ::shouldApplyClosedReachableTypeRulesToMapperAnnotationsMixinsAndSubtypes),
                new MatrixRow(
                        "shouldAllowTrustedCustomSerdeButRejectEveryNamedPolymorphismMechanism",
                        McpJsonProfileSafetyTest
                                ::shouldAllowTrustedCustomSerdeButRejectEveryNamedPolymorphismMechanism),
                new MatrixRow(
                        "shouldRejectMemberLevelCustomResolversOnPropertiesAndContainerContent",
                        McpJsonProfileSafetyTest
                                ::shouldRejectMemberLevelCustomResolversOnPropertiesAndContainerContent),
                new MatrixRow(
                        "shouldBoundMemberLevelAllowlistsAndTraverseTheirSubtypes",
                        McpJsonProfileSafetyTest::shouldBoundMemberLevelAllowlistsAndTraverseTheirSubtypes));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t002ContractMatrix")
    @DisplayName("enforces the T002 contract matrix")
    void shouldEnforceT002ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: mapper-derived traversal boundaries ---

    /**
     * The safe closed mapper accepts a graph reaching primitives, an enum, an array, a collection, a
     * map with a non-string value type, an optional, a nested DTO and a self-referential type; the
     * traversal terminates on the cycle instead of recursing. A raw or wildcard root type is a bounded
     * composition failure rather than a silently traversed graph.
     */
    private static void shouldTraverseMapperDerivedPrimitiveEnumContainerPropertyAndCyclicBoundaries() {
        JsonMapperProfile safeProfile = McpJsonProfileSafetyTestFixture.safeClosedProfile();
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();

        assertThatCode(() -> validator.validate(safeProfile, SafeCarrier.class, Nested.class))
                .as("the safe closed mapper passes over every traversal boundary, cycle included")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(safeProfile, McpJsonProfileSafetyTestFixture.rawRoot(), null))
                .as("a raw root type is a bounded composition failure")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("raw");

        assertThatThrownBy(() -> validator.validate(
                        safeProfile, SafeCarrier.class, McpJsonProfileSafetyTestFixture.wildcardRoot()))
                .as("a wildcard root type is a bounded composition failure")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("wildcard");
    }

    // --- Row 2: closed reachable-type rules through effective introspection ---

    /**
     * A closed {@code @JsonTypeInfo(use = Id.NAME)} base with an explicit, unique {@code @JsonSubTypes}
     * allowlist is accepted; metadata supplied by a mix-in is seen exactly as the mapper sees it and a
     * class-name discriminator is rejected naming the mechanism and the reachable type; duplicate
     * logical names in the allowlist are rejected naming the duplicate.
     */
    private static void shouldApplyClosedReachableTypeRulesToMapperAnnotationsMixinsAndSubtypes() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();

        assertThatCode(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.safeClosedProfile(), ClosedShapeCarrier.class, null))
                .as("an explicit, unique Id.NAME allowlist is the one accepted polymorphism shape")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.classNameMixInProfile(), MixedInCarrier.class, null))
                .as("mix-in-supplied class-name polymorphism is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("CLASS")
                .hasMessageContaining("MixedInBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.safeClosedProfile(), DuplicateNamedCarrier.class, null))
                .as("duplicate logical names in the allowlist are rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("duplicate");
    }

    // --- Row 3: trusted serde accepted, every named polymorphism mechanism rejected ---

    /**
     * Custom serializers and deserializers are trusted application code and stay accepted under the
     * bounded schema, while each named polymorphism mechanism — Jackson default typing, open subtype
     * discovery through programmatic registration, and a custom type-id resolver — is rejected with its
     * mechanism and type path.
     */
    private static void shouldAllowTrustedCustomSerdeButRejectEveryNamedPolymorphismMechanism() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();

        assertThatCode(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.trustedCustomSerdeProfile(), TrustedSerdeCarrier.class, null))
                .as("a trusted custom serializer/deserializer pair remains accepted")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.defaultTypingProfile(), SafeCarrier.class, null))
                .as("Jackson default typing is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("default typing")
                .hasMessageContaining("SafeCarrier");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.openSubtypeDiscoveryProfile(), OpenBaseCarrier.class, null))
                .as("open subtype discovery through programmatic registration is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("OpenBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.safeClosedProfile(), CustomResolvedCarrier.class, null))
                .as("a custom type-id resolver is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("resolver");
    }

    // --- Row 4: member-level custom resolvers on properties and container content ---

    /**
     * Jackson installs a member-declared type resolver ahead of the declared base type's class-level
     * one, so a custom {@code @JsonTypeIdResolver} on a property, on a property whose base type is
     * otherwise safely closed, or on a container's content each reopen the whole subtype space. All
     * three are rejected naming the property path and the custom-resolver mechanism.
     */
    private static void shouldRejectMemberLevelCustomResolversOnPropertiesAndContainerContent() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();
        JsonMapperProfile safeProfile = McpJsonProfileSafetyTestFixture.safeClosedProfile();

        assertThatThrownBy(() -> validator.validate(safeProfile, MemberCustomResolverCarrier.class, null))
                .as("a custom type-id resolver declared on a property is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("value")
                .hasMessageContaining("@JsonTypeIdResolver");

        assertThatThrownBy(() -> validator.validate(safeProfile, MemberOverriddenShapeCarrier.class, null))
                .as("a member resolver overriding a safe class-level base is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("shape")
                .hasMessageContaining("@JsonTypeIdResolver");

        assertThatThrownBy(() -> validator.validate(safeProfile, MemberContentResolverCarrier.class, null))
                .as("a custom type-id resolver declared on a container's content is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("values")
                .hasMessageContaining("@JsonTypeIdResolver");
    }

    // --- Row 5: member-declared allowlists are bounded and traversed ---

    /**
     * A member-declared {@code Id.NAME} allowlist is the one accepted member-level shape: its logical
     * names must be unique and its subtypes are enqueued into the walk, so an unsafe subtype reachable
     * only through the member allowlist is still rejected instead of escaping the traversal.
     */
    private static void shouldBoundMemberLevelAllowlistsAndTraverseTheirSubtypes() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();
        JsonMapperProfile safeProfile = McpJsonProfileSafetyTestFixture.safeClosedProfile();

        assertThatThrownBy(() -> validator.validate(safeProfile, MemberUnsafeSubtypeCarrier.class, null))
                .as("a member-allowlisted subtype carrying a class-name discriminator is traversed and rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("CLASS")
                .hasMessageContaining("MemberScopedUnsafe");

        assertThatThrownBy(() -> validator.validate(safeProfile, MemberDuplicateNameCarrier.class, null))
                .as("duplicate logical names in a member-declared allowlist are rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("duplicate");

        assertThatCode(() -> validator.validate(safeProfile, MemberClosedAllowlistCarrier.class, null))
                .as("a finite, unique member-declared Id.NAME allowlist of safe subtypes stays accepted")
                .doesNotThrowAnyException();
    }

    // --- Fixtures ---

    /** Framework wiring for the safety proof: the profile variants and the raw/wildcard root tokens. */
    private static final class McpJsonProfileSafetyTestFixture {

        private McpJsonProfileSafetyTestFixture() {}

        /**
         * The safe closed mapper of the Given.
         *
         * <p>Sensitivity proof: enabling Jackson default typing on this one mapper must turn row 1's
         * success into exactly one unsafe-polymorphism error naming the reachable type.
         */
        static JsonMapperProfile safeClosedProfile() {
            return new StubProfile("safe", new ObjectMapper());
        }

        /** The same closed mapper with Jackson default typing activated. */
        static JsonMapperProfile defaultTypingProfile() {
            ObjectMapper mapper = new ObjectMapper()
                    .activateDefaultTyping(BasicPolymorphicTypeValidator.builder()
                            .allowIfBaseType(Object.class)
                            .build());
            return new StubProfile("default-typing", mapper);
        }

        /** The closed mapper with a mix-in supplying class-name polymorphism to a plain base. */
        static JsonMapperProfile classNameMixInProfile() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.addMixIn(MixedInBase.class, ClassNamePolymorphismMixIn.class);
            return new StubProfile("class-name-mixin", mapper);
        }

        /** The closed mapper with subtypes registered programmatically instead of declared. */
        static JsonMapperProfile openSubtypeDiscoveryProfile() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerSubtypes(new NamedType(OpenImpl.class, "open-impl"));
            return new StubProfile("open-subtypes", mapper);
        }

        /** The closed mapper carrying a trusted custom serializer/deserializer pair. */
        static JsonMapperProfile trustedCustomSerdeProfile() {
            SimpleModule trusted = new SimpleModule();
            trusted.addSerializer(TrustedValue.class, new TrustedValueSerializer());
            trusted.addDeserializer(TrustedValue.class, new TrustedValueDeserializer());
            return new StubProfile("trusted-serde", new ObjectMapper().registerModule(trusted));
        }

        /** A raw {@code List} root, taken from a declared field so the erasure is genuine. */
        static Type rawRoot() {
            return genericTypeOf("rawValues");
        }

        /** A wildcard-bearing root, taken from a declared field so the wildcard survives. */
        static Type wildcardRoot() {
            return genericTypeOf("wildcardValues");
        }

        private static Type genericTypeOf(String fieldName) {
            try {
                return UnresolvedRootHolder.class.getDeclaredField(fieldName).getGenericType();
            } catch (NoSuchFieldException e) {
                throw new AssertionError("missing unresolved-root fixture field " + fieldName, e);
            }
        }
    }

    /** Declares the raw and wildcard root types the traversal must reject. */
    @SuppressWarnings({"rawtypes", "unused"})
    private static final class UnresolvedRootHolder {
        private List rawValues;
        private List<? extends Number> wildcardValues;
    }

    /** One profile variant of the Given, pairing an id with the mapper under proof. */
    private record StubProfile(String value, ObjectMapper objectMapper) implements JsonMapperProfile {

        @Override
        public JsonProfileId id() {
            return JsonProfileId.of(value);
        }

        @Override
        public ObjectMapper mapper() {
            return objectMapper;
        }
    }

    // --- Reachable type graphs ---

    /** The seasons enum reached through {@link SafeCarrier}. */
    private enum Season {
        SPRING,
        WINTER
    }

    /** A safe graph reaching primitives, an enum, containers, a nested DTO and a cycle. */
    private record SafeCarrier(
            int count,
            boolean flag,
            Season season,
            List<String> tags,
            Map<String, Integer> counts,
            String[] labels,
            Optional<String> note,
            Nested nested,
            Node cycle) {}

    /** A nested DTO, also used as a structured-output root. */
    private record Nested(String name, double weight) {}

    /** A self-referential type: the traversal must terminate on the second visit. */
    private record Node(String id, Node next) {}

    /** A closed, explicitly allowlisted polymorphic base. */
    @JsonTypeInfo(use = Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Circle.class, name = "circle"),
        @JsonSubTypes.Type(value = Square.class, name = "square")
    })
    private sealed interface Shape permits Circle, Square {}

    private record Circle(double radius) implements Shape {}

    private record Square(double side) implements Shape {}

    private record ClosedShapeCarrier(Shape shape) {}

    /** A plain base that only a mix-in makes polymorphic. */
    private interface MixedInBase {}

    private record MixedInCarrier(MixedInBase value) {}

    /** The mix-in supplying class-name polymorphism to {@link MixedInBase}. */
    @JsonTypeInfo(use = Id.CLASS)
    private interface ClassNamePolymorphismMixIn {}

    /** A base whose declared allowlist repeats one logical name. */
    @JsonTypeInfo(use = Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = FirstDuplicate.class, name = "dup"),
        @JsonSubTypes.Type(value = SecondDuplicate.class, name = "dup")
    })
    private interface DuplicateNamed {}

    private record FirstDuplicate(String a) implements DuplicateNamed {}

    private record SecondDuplicate(String b) implements DuplicateNamed {}

    private record DuplicateNamedCarrier(DuplicateNamed value) {}

    /** A base declaring name-based type info but no allowlist: its subtypes arrive by registration. */
    @JsonTypeInfo(use = Id.NAME, property = "kind")
    private interface OpenBase {}

    private record OpenImpl(String value) implements OpenBase {}

    private record OpenBaseCarrier(OpenBase value) {}

    /** A base resolving its type ids through application code. */
    @JsonTypeInfo(use = Id.NAME, property = "kind")
    @JsonTypeIdResolver(ApplicationTypeIdResolver.class)
    private interface CustomResolved {}

    private record CustomResolvedCarrier(CustomResolved value) {}

    /** The custom type-id resolver that must be rejected. */
    private static final class ApplicationTypeIdResolver extends TypeIdResolverBase {

        @Override
        public String idFromValue(Object value) {
            return "custom";
        }

        @Override
        public String idFromValueAndType(Object value, Class<?> suggestedType) {
            return "custom";
        }

        @Override
        public JavaType typeFromId(DatabindContext context, String id) {
            return context.constructType(CustomResolvedCarrier.class);
        }

        @Override
        public Id getMechanism() {
            return Id.CUSTOM;
        }
    }

    /** A plain base whose polymorphism is declared by the referring member, not by the class. */
    private interface MemberScoped {}

    private record MemberScopedFirst(String first) implements MemberScoped {}

    private record MemberScopedSecond(String second) implements MemberScoped {}

    /** A subtype reachable only through a member allowlist, carrying a class-name discriminator. */
    @JsonTypeInfo(use = Id.CLASS)
    private record MemberScopedUnsafe(String value) implements MemberScoped {}

    /** A property whose type ids are resolved by application code. */
    private record MemberCustomResolverCarrier(
            @JsonTypeInfo(use = Id.NAME, property = "kind") @JsonTypeIdResolver(ApplicationTypeIdResolver.class)
            MemberScoped value) {}

    /** A safely closed class-level base whose referring property overrides it with a custom resolver. */
    private record MemberOverriddenShapeCarrier(
            @JsonTypeInfo(use = Id.NAME, property = "kind") @JsonTypeIdResolver(ApplicationTypeIdResolver.class)
            Shape shape) {}

    /** A container property whose content type ids are resolved by application code. */
    private record MemberContentResolverCarrier(
            @JsonTypeInfo(use = Id.NAME, property = "kind") @JsonTypeIdResolver(ApplicationTypeIdResolver.class)
            List<MemberScoped> values) {}

    /** A member-declared allowlist whose subtype graph reaches an unsafe discriminator. */
    private record MemberUnsafeSubtypeCarrier(
            @JsonTypeInfo(use = Id.NAME, property = "kind")
            @JsonSubTypes({
                @JsonSubTypes.Type(value = MemberScopedFirst.class, name = "first"),
                @JsonSubTypes.Type(value = MemberScopedUnsafe.class, name = "unsafe")
            })
            MemberScoped value) {}

    /** A member-declared allowlist that repeats one logical name. */
    private record MemberDuplicateNameCarrier(
            @JsonTypeInfo(use = Id.NAME, property = "kind")
            @JsonSubTypes({
                @JsonSubTypes.Type(value = MemberScopedFirst.class, name = "dup"),
                @JsonSubTypes.Type(value = MemberScopedSecond.class, name = "dup")
            })
            MemberScoped value) {}

    /** The one accepted member-level shape: a finite, unique allowlist of safe subtypes. */
    private record MemberClosedAllowlistCarrier(
            @JsonTypeInfo(use = Id.NAME, property = "kind")
            @JsonSubTypes({
                @JsonSubTypes.Type(value = MemberScopedFirst.class, name = "first"),
                @JsonSubTypes.Type(value = MemberScopedSecond.class, name = "second")
            })
            MemberScoped value) {}

    /** A value carried by a trusted custom serializer/deserializer pair. */
    private record TrustedValue(String raw) {}

    private record TrustedSerdeCarrier(TrustedValue value, List<TrustedValue> history) {}

    private static final class TrustedValueSerializer extends StdSerializer<TrustedValue> {

        private TrustedValueSerializer() {
            super(TrustedValue.class);
        }

        @Override
        public void serialize(TrustedValue value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(value.raw());
        }
    }

    private static final class TrustedValueDeserializer extends StdDeserializer<TrustedValue> {

        private TrustedValueDeserializer() {
            super(TrustedValue.class);
        }

        @Override
        public TrustedValue deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return new TrustedValue(parser.getValueAsString());
        }
    }

    /** One named row of the contract matrix. */
    private record MatrixRow(String rowName, Executable proof) {

        @Override
        public String toString() {
            return rowName;
        }
    }
}
