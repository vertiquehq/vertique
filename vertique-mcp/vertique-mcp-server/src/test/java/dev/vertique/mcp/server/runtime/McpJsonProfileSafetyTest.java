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
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.jsontype.impl.StdTypeResolverBuilder;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
 * <p>Nine rows isolate nine boundaries, one each:
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
 *   <li>{@link #shouldApplyEverySafetyRulePerMapperSideUnderSplitIntrospectors()} — a mapper with
 *       independent serialization and deserialization introspectors is checked on both sides, so
 *       unsafe metadata visible to only one of them is still rejected. This row also owns the two
 *       shapes only a per-side check sees at all: a class-level resolver installed through
 *       {@code findTypeResolver} alone, and an allowlist one side declares that the other side's
 *       resolved mapping does not agree with.</li>
 *   <li>{@link #shouldRejectRawMemberDeclarationsReachableFromToolTypes()} — a raw generic
 *       declaration on a reachable DTO member erases its contents to {@code Object} and is a bounded
 *       composition failure, array declarations included, while an explicit {@code List<Object>} and
 *       resolved array declarations stay accepted.</li>
 *   <li>{@link #shouldCrossCheckLiveClassLevelHandlerMechanismAgainstReportedMetadata()} — the
 *       class-level gate reads the mechanism of the type handler the mapper actually builds, so
 *       sanctioned {@code Id.NAME} metadata cannot mask an {@code Id.CLASS} or {@code Id.CUSTOM}
 *       resolver, and a resolver that fails while being built or resolved is a bounded failure.</li>
 *   <li>{@link #shouldGateClassLevelPolymorphismOnContainerRootsAndMembers()} — a container, map,
 *       array or reference type is validated as its own polymorphic base before its contents are
 *       enqueued, so a resolver installed on the container class itself is rejected.</li>
 * </ol>
 */
@DisplayName("MCP JSON profile safety — T002 contract matrix")
class McpJsonProfileSafetyTest {

    // --- Matrix ---

    /**
     * The nine named rows of the T002 profile-safety matrix.
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
                        McpJsonProfileSafetyTest::shouldBoundMemberLevelAllowlistsAndTraverseTheirSubtypes),
                new MatrixRow(
                        "shouldApplyEverySafetyRulePerMapperSideUnderSplitIntrospectors",
                        McpJsonProfileSafetyTest::shouldApplyEverySafetyRulePerMapperSideUnderSplitIntrospectors),
                new MatrixRow(
                        "shouldRejectRawMemberDeclarationsReachableFromToolTypes",
                        McpJsonProfileSafetyTest::shouldRejectRawMemberDeclarationsReachableFromToolTypes),
                new MatrixRow(
                        "shouldCrossCheckLiveClassLevelHandlerMechanismAgainstReportedMetadata",
                        McpJsonProfileSafetyTest
                                ::shouldCrossCheckLiveClassLevelHandlerMechanismAgainstReportedMetadata),
                new MatrixRow(
                        "shouldGateClassLevelPolymorphismOnContainerRootsAndMembers",
                        McpJsonProfileSafetyTest::shouldGateClassLevelPolymorphismOnContainerRootsAndMembers));
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

        assertThatThrownBy(() -> validator.validate(safeProfile, McpJsonProfileSafetyTestFixture.rawArrayRoot(), null))
                .as("a raw generic array root is a bounded composition failure, exactly like the raw element"
                        + " declaration it hides")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("raw")
                .hasMessageContaining("java.util.List");
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

    // --- Row 6: every rule applied per mapper side under split introspectors ---

    /**
     * A mapper configured through {@link ObjectMapper#setAnnotationIntrospectors} uses one
     * introspector for serialization and another for deserialization, so unsafe metadata can be
     * visible to only one side. Both sides are checked with their own config/introspector pair: a
     * class-level and a member-level mechanism seen only by the deserialization introspector are
     * rejected exactly like the mirrored serialization-only case, while split introspectors that both
     * see only safe metadata stay accepted.
     *
     * <p>Two shapes exist only because the check runs per side. A class-level resolver installed
     * through {@code findTypeResolver} alone carries no {@code @JsonTypeInfo} for a metadata-shaped
     * check to see, yet Jackson's serializer and deserializer factories install it; and an allowlist
     * that only one side declares is the case the <em>resolved mapping</em> comparison exists for,
     * because the other side resolves no subtypes at all. Both are asserted on each side.
     */
    private static void shouldApplyEverySafetyRulePerMapperSideUnderSplitIntrospectors() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyClassPolymorphismProfile(),
                        SplitIntrospectorCarrier.class,
                        null))
                .as("class-level metadata visible only to the deserialization introspector is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("CLASS")
                .hasMessageContaining("SplitBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyMemberPolymorphismProfile(),
                        SplitIntrospectorCarrier.class,
                        null))
                .as("member-level metadata visible only to the deserialization introspector is rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("CLASS")
                .hasMessageContaining("value");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyClassPolymorphismProfile(),
                        SplitIntrospectorCarrier.class,
                        null))
                .as("the mirrored serialization-only visibility stays rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("CLASS")
                .hasMessageContaining("SplitBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyClassResolverProfile(),
                        ResolverInstalledCarrier.class,
                        null))
                .as("a class-level type resolver installed through findTypeResolver alone is rejected on the"
                        + " serialization side, even though no @JsonTypeInfo exists to see")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("resolver")
                .hasMessageContaining("ResolverInstalledDto");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyClassResolverProfile(),
                        ResolverInstalledCarrier.class,
                        null))
                .as("the mirrored deserialization-side visibility of the same installed resolver stays rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("resolver")
                .hasMessageContaining("ResolverInstalledDto");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyNamedAllowlistProfile(),
                        SplitAllowlistCarrier.class,
                        null))
                .as("an allowlist only the serialization side declares is compared against the deserialization"
                        + " side's resolved mapping, which resolves nothing")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("deserialization-facing subtype mapping")
                .hasMessageContaining("SplitAllowlistBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyNamedAllowlistProfile(),
                        SplitAllowlistCarrier.class,
                        null))
                .as("the mirrored case is caught by the serialization-facing comparison alone")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("serialization-facing subtype mapping")
                .hasMessageNotContaining("deserialization-facing")
                .hasMessageContaining("SplitAllowlistBase");

        assertThatCode(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.splitSafeIntrospectorProfile(),
                        SafeCarrier.class,
                        ClosedShapeCarrier.class))
                .as("split introspectors that both see only safe metadata are accepted, not double-reported")
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.splitSafeIntrospectorProfile(),
                        ResolverInstalledCarrier.class,
                        null))
                .as("a plain bean with no installed resolver stays accepted, so the class-level resolver gate"
                        + " rejects the installation and not the type")
                .doesNotThrowAnyException();
    }

    // --- Row 7: raw member declarations reachable from a tool type ---

    /**
     * A raw generic declaration on a reachable DTO member erases its contents to {@code Object}, so
     * Jackson resolves it to an unconstrained container that the walk would otherwise traverse as a
     * silent leaf. It is a bounded composition failure naming the reachable path and the property. An
     * explicitly declared {@code List<Object>} is the application's own choice and stays accepted.
     *
     * <p>An array hides the same erasure behind a different reflective shape: {@code List[]} is a
     * plain array {@code Class}, not a {@code GenericArrayType}, so it is invisible to a walk that
     * inspects only parameterized types and generic arrays. {@code String[]} and
     * {@code List<String>[]} are the controls that keep the array descent from rejecting resolved
     * declarations.
     */
    private static void shouldRejectRawMemberDeclarationsReachableFromToolTypes() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();
        JsonMapperProfile safeProfile = McpJsonProfileSafetyTestFixture.safeClosedProfile();

        assertThatThrownBy(() -> validator.validate(safeProfile, RawListCarrier.class, null))
                .as("a raw List declared on a reachable DTO member is a bounded composition failure")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("raw")
                .hasMessageContaining("java.util.List")
                .hasMessageContaining("values");

        assertThatThrownBy(() -> validator.validate(safeProfile, RawMapCarrier.class, null))
                .as("a raw Map declared on a reachable DTO member is a bounded composition failure")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("raw")
                .hasMessageContaining("java.util.Map")
                .hasMessageContaining("entries");

        assertThatThrownBy(() -> validator.validate(safeProfile, RawArrayCarrier.class, null))
                .as("a raw generic array declared on a reachable DTO member is a bounded composition failure:"
                        + " List[] is an array Class, not a GenericArrayType, so only a walk that descends into"
                        + " an array class's component type sees the erasure")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("raw")
                .hasMessageContaining("java.util.List")
                .hasMessageContaining("batches");

        assertThatCode(() -> validator.validate(safeProfile, ObjectListCarrier.class, null))
                .as("an explicitly declared List<Object> is the application's own choice and stays accepted")
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(safeProfile, TypedArrayCarrier.class, null))
                .as("String[] and List<String>[] are resolved array declarations and stay accepted")
                .doesNotThrowAnyException();
    }

    // --- Row 8: the live class-level handler's own mechanism ---

    /**
     * Reported {@code @JsonTypeInfo} metadata and the resolver Jackson actually installs are two
     * independent signals, and a custom introspector can report the sanctioned {@code Id.NAME} shape
     * with a finite {@code @JsonSubTypes} allowlist while {@code findTypeResolver} hands the serializer
     * and deserializer factories an {@code Id.CLASS} or {@code Id.CUSTOM} builder. Every metadata-shaped
     * rule then passes while the mapper installs the unsafe mechanism, so the class-level gate builds
     * the handler per side and cross-checks the mechanism the live handler's own
     * {@code TypeIdResolver} reports.
     *
     * <p>Building the handler is also the only way a builder that fails during construction is
     * exercised at all; it converts to a bounded composition failure per side, as does a failure in the
     * subtype resolution the build feeds on — the two report distinctly so a fail-closed message names
     * the step that actually failed.
     *
     * <p>A {@code null} handler stays the {@code Id.NONE} suppression signal: Jackson's own marker
     * builder disables polymorphism and builds no handler, so it is accepted rather than read as an
     * installed mechanism. The mirrored control — {@code Id.NONE} metadata paired with a live unsafe
     * resolver — is rejected by the no-metadata rule; it is asserted here as the metadata-reporting
     * companion of row 6's introspector that reports no metadata at all.
     */
    private static void shouldCrossCheckLiveClassLevelHandlerMechanismAgainstReportedMetadata() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();

        assertThatCode(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.reportedNameMetadataProfile(), MaskedCarrier.class, null))
                .as("reported Id.NAME metadata whose live handler is also Id.NAME is the accepted shape,"
                        + " so the cross-check gates the mechanism and not the reporting introspector")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyMaskedClassResolverProfile(),
                        MaskedCarrier.class,
                        null))
                .as("sanctioned Id.NAME metadata cannot mask an Id.CLASS resolver the serialization side"
                        + " actually installs")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("reports Id.NAME metadata but installs class-level type handling")
                .hasMessageContaining("Id.CLASS")
                .hasMessageContaining("MaskedBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyMaskedClassResolverProfile(),
                        MaskedCarrier.class,
                        null))
                .as("the mirrored deserialization-side masking stays rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("reports Id.NAME metadata but installs class-level type handling")
                .hasMessageContaining("Id.CLASS")
                .hasMessageContaining("MaskedBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyMaskedCustomResolverProfile(),
                        MaskedCarrier.class,
                        null))
                .as("an Id.CUSTOM resolver masked behind Id.NAME metadata is rejected on the serialization side")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("reports Id.NAME metadata but installs class-level type handling")
                .hasMessageContaining("Id.CUSTOM")
                .hasMessageContaining("MaskedBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyMaskedCustomResolverProfile(),
                        MaskedCarrier.class,
                        null))
                .as("the mirrored deserialization-side Id.CUSTOM masking stays rejected")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("reports Id.NAME metadata but installs class-level type handling")
                .hasMessageContaining("Id.CUSTOM")
                .hasMessageContaining("MaskedBase");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyThrowingNameResolverProfile(),
                        MaskedCarrier.class,
                        null))
                .as("an Id.NAME builder that throws while being built is a bounded composition failure,"
                        + " not an unhandled runtime exception")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("class-level type resolver construction failed")
                .hasMessageContaining("IllegalStateException");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyThrowingNameResolverProfile(),
                        MaskedCarrier.class,
                        null))
                .as("the mirrored deserialization-side build failure is bounded too")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("class-level type resolver construction failed")
                .hasMessageContaining("IllegalStateException");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyThrowingSubtypeResolutionProfile(),
                        MaskedCarrier.class,
                        null))
                .as("a serialization-side subtype-resolution failure reports as subtype resolution, not as"
                        + " resolver introspection")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("class-level subtype resolution failed")
                .hasMessageNotContaining("resolver introspection failed")
                .hasMessageContaining("IllegalStateException");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyThrowingSubtypeResolutionProfile(),
                        MaskedCarrier.class,
                        null))
                .as("the mirrored deserialization-side subtype-resolution failure is bounded and named too")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("class-level subtype resolution failed")
                .hasMessageNotContaining("resolver introspection failed")
                .hasMessageContaining("IllegalStateException");

        assertThatCode(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.suppressedTypingProfile(), SuppressedTypingCarrier.class, null))
                .as("Jackson's Id.NONE marker builder builds no handler, and a null handler stays the"
                        + " suppression signal rather than an installed mechanism")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.suppressedMetadataWithLiveResolverProfile(),
                        SuppressedTypingCarrier.class,
                        null))
                .as("Id.NONE metadata paired with a live Id.CLASS resolver is rejected: the metadata reports"
                        + " polymorphism as disabled while the mapper installs it")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("no @JsonTypeInfo metadata")
                .hasMessageContaining("SuppressedTypingDto");
    }

    // --- Row 9: class-level polymorphism on container, map, array and reference types ---

    /**
     * Jackson resolves a container's own type handling by asking {@code findTypeResolver} for the
     * container raw class — {@code java.util.List}, {@code java.util.Map}, the array class, the
     * reference class — both for a root value and for a bean property, and installs whatever it
     * returns. Validating only the contents of such a type therefore leaves a resolver installed on the
     * container itself live, so the class-level rules run for every non-primitive, non-enum reachable
     * type before its contents are enqueued.
     *
     * <p>Each container branch of the walk is proved separately, on both mapper sides for the
     * collection branch, because each reaches {@code findTypeResolver} under a different raw class. The
     * safe controls keep the gate from rejecting ordinary containers, which carry no class-level type
     * information at all.
     */
    private static void shouldGateClassLevelPolymorphismOnContainerRootsAndMembers() {
        McpJsonProfileSafetyValidator validator = new McpJsonProfileSafetyValidator();

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyContainerResolverProfile(List.class),
                        McpJsonProfileSafetyTestFixture.containerListRoot(),
                        null))
                .as("a resolver installed on the collection class itself is rejected when the collection is a"
                        + " declared root, not silently skipped in favour of its contents")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("installs a class-level type resolver reporting no @JsonTypeInfo metadata")
                .hasMessageContaining("java.util.List");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.deserializationOnlyContainerResolverProfile(List.class),
                        ContainerMemberCarrier.class,
                        null))
                .as("the same resolver is rejected when the collection is reached as a member, on the mirrored"
                        + " mapper side")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("installs a class-level type resolver reporting no @JsonTypeInfo metadata")
                .hasMessageContaining("java.util.List");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyContainerResolverProfile(Map.class),
                        MapMemberCarrier.class,
                        null))
                .as("the map branch is gated before its key and value types are enqueued")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("installs a class-level type resolver reporting no @JsonTypeInfo metadata")
                .hasMessageContaining("java.util.Map");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyContainerResolverProfile(
                                ContainerItem[].class),
                        ArrayMemberCarrier.class,
                        null))
                .as("the array branch is gated on the array class itself")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("installs a class-level type resolver reporting no @JsonTypeInfo metadata")
                .hasMessageContaining("ContainerItem");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyContainerResolverProfile(Optional.class),
                        ReferenceMemberCarrier.class,
                        null))
                .as("the reference branch is gated before the referenced type is enqueued")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("installs a class-level type resolver reporting no @JsonTypeInfo metadata")
                .hasMessageContaining("java.util.Optional");

        assertThatThrownBy(() -> validator.validate(
                        McpJsonProfileSafetyTestFixture.serializationOnlyContainerResolverProfile(
                                AtomicReference.class),
                        AtomicReferenceMemberCarrier.class,
                        null))
                .as("the atomic-reference branch is gated before the referenced type is enqueued")
                .isInstanceOf(JsonProfileConfigurationException.class)
                .hasMessageContaining("installs a class-level type resolver reporting no @JsonTypeInfo metadata")
                .hasMessageContaining("java.util.concurrent.atomic.AtomicReference");

        JsonMapperProfile safeProfile = McpJsonProfileSafetyTestFixture.safeClosedProfile();
        assertThatCode(() -> validator.validate(safeProfile, McpJsonProfileSafetyTestFixture.containerListRoot(), null))
                .as("an ordinary collection root carries no class-level type information and stays accepted")
                .doesNotThrowAnyException();

        assertThatCode(() -> validator.validate(safeProfile, ContainerMemberCarrier.class, null))
                .as("ordinary collection, map, array and reference members stay accepted under the gate")
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(safeProfile, MapMemberCarrier.class, ArrayMemberCarrier.class))
                .as("ordinary map and array members stay accepted under the gate")
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(
                        safeProfile, ReferenceMemberCarrier.class, AtomicReferenceMemberCarrier.class))
                .as("ordinary reference members stay accepted under the gate")
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

        /** Split introspectors where only the deserialization side sees class-level polymorphism. */
        static JsonMapperProfile deserializationOnlyClassPolymorphismProfile() {
            return splitIntrospectorProfile(
                    "deser-only-class",
                    new JacksonAnnotationIntrospector(),
                    new ScopedClassNameIntrospector(AnnotatedClass.class));
        }

        /** Split introspectors where only the deserialization side sees member-level polymorphism. */
        static JsonMapperProfile deserializationOnlyMemberPolymorphismProfile() {
            return splitIntrospectorProfile(
                    "deser-only-member",
                    new JacksonAnnotationIntrospector(),
                    new ScopedClassNameIntrospector(AnnotatedMember.class));
        }

        /** The mirrored control: only the serialization side sees the same class-level polymorphism. */
        static JsonMapperProfile serializationOnlyClassPolymorphismProfile() {
            return splitIntrospectorProfile(
                    "ser-only-class",
                    new ScopedClassNameIntrospector(AnnotatedClass.class),
                    new JacksonAnnotationIntrospector());
        }

        /** Split introspectors where only the serialization side installs a class-level type resolver. */
        static JsonMapperProfile serializationOnlyClassResolverProfile() {
            return splitIntrospectorProfile(
                    "ser-only-resolver", new TypeResolverInstallingIntrospector(), new JacksonAnnotationIntrospector());
        }

        /** The mirrored case: only the deserialization side installs the same class-level type resolver. */
        static JsonMapperProfile deserializationOnlyClassResolverProfile() {
            return splitIntrospectorProfile(
                    "deser-only-resolver",
                    new JacksonAnnotationIntrospector(),
                    new TypeResolverInstallingIntrospector());
        }

        /** Split introspectors where only the serialization side reports a closed Id.NAME allowlist. */
        static JsonMapperProfile serializationOnlyNamedAllowlistProfile() {
            return splitIntrospectorProfile(
                    "ser-only-allowlist", new ScopedNamedAllowlistIntrospector(), new JacksonAnnotationIntrospector());
        }

        /** The mirrored case: only the deserialization side reports the same closed Id.NAME allowlist. */
        static JsonMapperProfile deserializationOnlyNamedAllowlistProfile() {
            return splitIntrospectorProfile(
                    "deser-only-allowlist",
                    new JacksonAnnotationIntrospector(),
                    new ScopedNamedAllowlistIntrospector());
        }

        /** The positive control: two independent introspectors that both see only safe metadata. */
        static JsonMapperProfile splitSafeIntrospectorProfile() {
            return splitIntrospectorProfile(
                    "split-safe", new JacksonAnnotationIntrospector(), new JacksonAnnotationIntrospector());
        }

        private static JsonMapperProfile splitIntrospectorProfile(
                String id, AnnotationIntrospector serialization, AnnotationIntrospector deserialization) {
            ObjectMapper mapper = new ObjectMapper().setAnnotationIntrospectors(serialization, deserialization);
            return new StubProfile(id, mapper);
        }

        // --- Row 8: reported Id.NAME metadata versus the live class-level handler ---

        /** Both sides report the sanctioned shape and install the matching live {@code Id.NAME} handler. */
        static JsonMapperProfile reportedNameMetadataProfile() {
            return maskedProfile("reported-name", null, null);
        }

        /** Only the serialization side hands the factories an {@code Id.CLASS} builder. */
        static JsonMapperProfile serializationOnlyMaskedClassResolverProfile() {
            return maskedProfile("ser-only-masked-class", McpJsonProfileSafetyTest::classNameResolverBuilder, null);
        }

        /** The mirrored case: only the deserialization side hands over the {@code Id.CLASS} builder. */
        static JsonMapperProfile deserializationOnlyMaskedClassResolverProfile() {
            return maskedProfile("deser-only-masked-class", null, McpJsonProfileSafetyTest::classNameResolverBuilder);
        }

        /** Only the serialization side hands the factories an {@code Id.CUSTOM} builder. */
        static JsonMapperProfile serializationOnlyMaskedCustomResolverProfile() {
            return maskedProfile("ser-only-masked-custom", McpJsonProfileSafetyTest::customIdResolverBuilder, null);
        }

        /** The mirrored case: only the deserialization side hands over the {@code Id.CUSTOM} builder. */
        static JsonMapperProfile deserializationOnlyMaskedCustomResolverProfile() {
            return maskedProfile("deser-only-masked-custom", null, McpJsonProfileSafetyTest::customIdResolverBuilder);
        }

        /** Only the serialization side hands over an {@code Id.NAME} builder that throws while building. */
        static JsonMapperProfile serializationOnlyThrowingNameResolverProfile() {
            return maskedProfile("ser-only-throwing-name", ThrowingNameTypeResolverBuilder::new, null);
        }

        /** The mirrored case: only the deserialization side hands over the throwing builder. */
        static JsonMapperProfile deserializationOnlyThrowingNameResolverProfile() {
            return maskedProfile("deser-only-throwing-name", null, ThrowingNameTypeResolverBuilder::new);
        }

        /** Only the serialization side fails while resolving the subtype mapping the build feeds on. */
        static JsonMapperProfile serializationOnlyThrowingSubtypeResolutionProfile() {
            return splitIntrospectorProfile(
                    "ser-only-throwing-subtypes",
                    new MaskedNameMetadataIntrospector(null, true),
                    new MaskedNameMetadataIntrospector(null, false));
        }

        /** The mirrored case: only the deserialization side fails while resolving the subtype mapping. */
        static JsonMapperProfile deserializationOnlyThrowingSubtypeResolutionProfile() {
            return splitIntrospectorProfile(
                    "deser-only-throwing-subtypes",
                    new MaskedNameMetadataIntrospector(null, false),
                    new MaskedNameMetadataIntrospector(null, true));
        }

        /** Both sides install Jackson's {@code Id.NONE} marker builder, which builds no handler at all. */
        static JsonMapperProfile suppressedTypingProfile() {
            return splitIntrospectorProfile(
                    "suppressed-typing",
                    new SuppressedTypingIntrospector(false),
                    new SuppressedTypingIntrospector(false));
        }

        /** Both sides report {@code Id.NONE} metadata while installing a live {@code Id.CLASS} resolver. */
        static JsonMapperProfile suppressedMetadataWithLiveResolverProfile() {
            return splitIntrospectorProfile(
                    "suppressed-metadata-live-resolver",
                    new SuppressedTypingIntrospector(true),
                    new SuppressedTypingIntrospector(true));
        }

        private static JsonMapperProfile maskedProfile(
                String id,
                @Nullable Supplier<TypeResolverBuilder<?>> serialization,
                @Nullable Supplier<TypeResolverBuilder<?>> deserialization) {
            return splitIntrospectorProfile(
                    id,
                    new MaskedNameMetadataIntrospector(serialization, false),
                    new MaskedNameMetadataIntrospector(deserialization, false));
        }

        // --- Row 9: class-level resolvers keyed to a container raw class ---

        /** Only the serialization side installs an {@code Id.CLASS} resolver on the container class. */
        static JsonMapperProfile serializationOnlyContainerResolverProfile(Class<?> containerClass) {
            return splitIntrospectorProfile(
                    "ser-only-container-resolver",
                    new ContainerClassResolverIntrospector(containerClass),
                    new JacksonAnnotationIntrospector());
        }

        /** The mirrored case: only the deserialization side installs it on the container class. */
        static JsonMapperProfile deserializationOnlyContainerResolverProfile(Class<?> containerClass) {
            return splitIntrospectorProfile(
                    "deser-only-container-resolver",
                    new JacksonAnnotationIntrospector(),
                    new ContainerClassResolverIntrospector(containerClass));
        }

        /** A resolved {@code List<ContainerItem>} root, taken from a declared field. */
        static Type containerListRoot() {
            try {
                return ContainerRootHolder.class.getDeclaredField("listRoot").getGenericType();
            } catch (NoSuchFieldException e) {
                throw new AssertionError("missing container-root fixture field", e);
            }
        }

        /** A raw {@code List} root, taken from a declared field so the erasure is genuine. */
        static Type rawRoot() {
            return genericTypeOf("rawValues");
        }

        /**
         * A raw generic <em>array</em> root. A {@code List[]} is a plain {@code Class}, not a
         * {@code GenericArrayType}, so only a walk that descends into an array class's component type
         * sees the erased element declaration at all.
         */
        static Type rawArrayRoot() {
            return genericTypeOf("rawArrayValues");
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
        private List[] rawArrayValues;
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

    /** A plain base that only one mapper side's introspector reports as polymorphic. */
    private interface SplitBase {}

    private record SplitIntrospectorCarrier(SplitBase value) {}

    /**
     * Reports class-name polymorphism for {@link SplitBase} in exactly one annotated scope.
     *
     * <p>Installed on one mapper side only through {@link ObjectMapper#setAnnotationIntrospectors},
     * it models the effective metadata a real split-introspector mapper can expose to serialization
     * or deserialization alone. Scoping to {@link AnnotatedClass} exercises the class-level gate and
     * scoping to {@link AnnotatedMember} exercises the member-level one, since Jackson's
     * {@code _findTypeResolver} installs a member resolver exactly when the member reports type info.
     */
    private static final class ScopedClassNameIntrospector extends JacksonAnnotationIntrospector {

        private static final JsonTypeInfo.Value CLASS_NAME_TYPE_INFO =
                JsonTypeInfo.Value.from(ClassNamePolymorphismMixIn.class.getAnnotation(JsonTypeInfo.class));

        private final Class<? extends Annotated> scope;

        private ScopedClassNameIntrospector(Class<? extends Annotated> scope) {
            this.scope = scope;
        }

        @Override
        public JsonTypeInfo.Value findPolymorphicTypeInfo(MapperConfig<?> config, Annotated annotated) {
            if (scope.isInstance(annotated) && SplitBase.class.equals(annotated.getRawType())) {
                return CLASS_NAME_TYPE_INFO;
            }
            return super.findPolymorphicTypeInfo(config, annotated);
        }
    }

    /**
     * A plain bean carrying no {@code @JsonTypeInfo} at all, used as the base a custom introspector
     * installs a class-level type resolver on through {@code findTypeResolver} alone.
     */
    private record ResolverInstalledDto(String value) {}

    private record ResolverInstalledCarrier(ResolverInstalledDto argument0) {}

    /**
     * Installs a class-name class-level type resolver for {@link ResolverInstalledDto} by overriding
     * only {@code findTypeResolver}.
     *
     * <p>This is the shape Jackson's own {@code Basic{Serializer,Deserializer}Factory} honours for
     * class-level resolution: they ask {@code findTypeResolver} and install whatever it returns. An
     * introspector — or a module carrying one — that overrides it without also reporting
     * {@code findPolymorphicTypeInfo} therefore reopens the whole subtype space while every
     * {@code @JsonTypeInfo}-shaped check sees nothing at all.
     */
    private static final class TypeResolverInstallingIntrospector extends JacksonAnnotationIntrospector {

        @Override
        public TypeResolverBuilder<?> findTypeResolver(
                MapperConfig<?> config, AnnotatedClass annotated, JavaType baseType) {
            if (ResolverInstalledDto.class.equals(annotated.getRawType())) {
                return new StdTypeResolverBuilder()
                        .init(Id.CLASS, null)
                        .inclusion(JsonTypeInfo.As.PROPERTY)
                        .typeProperty("@class");
            }
            return super.findTypeResolver(config, annotated, baseType);
        }
    }

    /** A plain base that only one mapper side's introspector reports a closed allowlist for. */
    private interface SplitAllowlistBase {}

    private record SplitAllowlistFirst(String first) implements SplitAllowlistBase {}

    private record SplitAllowlistCarrier(SplitAllowlistBase value) {}

    /** Supplies the accepted {@code Id.NAME} shape the split-allowlist introspector reports. */
    @JsonTypeInfo(use = Id.NAME, property = "kind")
    private interface NamePolymorphismMarker {}

    /**
     * Reports a safe, finite {@code Id.NAME} allowlist for {@link SplitAllowlistBase} in the class
     * scope only.
     *
     * <p>Installed on one mapper side, it produces the case the per-side <em>resolved mapping</em>
     * comparison exists for: the side that declares the allowlist resolves it, and the other side
     * resolves nothing at all. Because the declared shape is the accepted one, the walk reaches the
     * mapping comparison instead of failing earlier on the type-id mechanism.
     */
    private static final class ScopedNamedAllowlistIntrospector extends JacksonAnnotationIntrospector {

        private static final JsonTypeInfo.Value NAME_TYPE_INFO =
                JsonTypeInfo.Value.from(NamePolymorphismMarker.class.getAnnotation(JsonTypeInfo.class));

        @Override
        public JsonTypeInfo.Value findPolymorphicTypeInfo(MapperConfig<?> config, Annotated annotated) {
            if (annotated instanceof AnnotatedClass && SplitAllowlistBase.class.equals(annotated.getRawType())) {
                return NAME_TYPE_INFO;
            }
            return super.findPolymorphicTypeInfo(config, annotated);
        }

        @Override
        public List<NamedType> findSubtypes(Annotated annotated) {
            if (SplitAllowlistBase.class.equals(annotated.getRawType())) {
                return List.of(new NamedType(SplitAllowlistFirst.class, "first"));
            }
            return super.findSubtypes(annotated);
        }
    }

    /** A DTO one hop from the carrier whose raw member declaration erases its contents to Object. */
    @SuppressWarnings("rawtypes")
    private record RawListDto(List values) {}

    private record RawListCarrier(RawListDto argument0) {}

    /** The same erasure through a raw map declaration. */
    @SuppressWarnings("rawtypes")
    private record RawMapDto(Map entries) {}

    private record RawMapCarrier(RawMapDto argument0) {}

    /** The same erasure hidden inside an array declaration: {@code List[]}, not {@code List<?>[]}. */
    @SuppressWarnings("rawtypes")
    private record RawArrayDto(List[] batches) {}

    private record RawArrayCarrier(RawArrayDto argument0) {}

    /** The positive control: {@code Object} contents the application declared deliberately. */
    private record ObjectListDto(List<Object> values) {}

    private record ObjectListCarrier(ObjectListDto argument0) {}

    /** The array controls: neither a non-generic component nor a resolved generic one is erased. */
    private record TypedArrayDto(String[] labels, List<String>[] batches) {}

    private record TypedArrayCarrier(TypedArrayDto argument0) {}

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

    // --- Row 8 graph: reported metadata versus the live class-level handler ---

    /** A plain base a custom introspector reports the sanctioned {@code Id.NAME} shape for. */
    private interface MaskedBase {}

    private record MaskedFirst(String first) implements MaskedBase {}

    private record MaskedCarrier(MaskedBase value) {}

    /** A plain DTO used to prove the {@code Id.NONE} suppression signal on both sides. */
    private record SuppressedTypingDto(String value) {}

    private record SuppressedTypingCarrier(SuppressedTypingDto argument0) {}

    /** Supplies the {@code Id.NONE} metadata the suppression fixtures report. */
    @JsonTypeInfo(use = Id.NONE)
    private interface NonePolymorphismMarker {}

    /** The unsafe class-name builder a masking introspector hands the factories. */
    private static TypeResolverBuilder<?> classNameResolverBuilder() {
        return new StdTypeResolverBuilder()
                .init(Id.CLASS, null)
                .inclusion(JsonTypeInfo.As.PROPERTY)
                .typeProperty("@class");
    }

    /** The unsafe custom-mechanism builder a masking introspector hands the factories. */
    private static TypeResolverBuilder<?> customIdResolverBuilder() {
        return new StdTypeResolverBuilder()
                .init(Id.CUSTOM, new ApplicationTypeIdResolver())
                .inclusion(JsonTypeInfo.As.PROPERTY)
                .typeProperty("kind");
    }

    /**
     * Reports the sanctioned {@code Id.NAME} shape and a finite allowlist for {@link MaskedBase} in the
     * class scope, while optionally handing the factories a different live resolver for that same
     * class, or failing the subtype resolution the resolver build feeds on.
     *
     * <p>Both mapper sides report the same metadata and the same allowlist, so the resolved-mapping
     * comparison agrees and the only remaining difference is the mechanism of the handler each side
     * actually builds. That is what isolates the live cross-check from every metadata-shaped rule.
     */
    private static final class MaskedNameMetadataIntrospector extends JacksonAnnotationIntrospector {

        private static final JsonTypeInfo.Value NAME_TYPE_INFO =
                JsonTypeInfo.Value.from(NamePolymorphismMarker.class.getAnnotation(JsonTypeInfo.class));

        @Nullable
        private final Supplier<TypeResolverBuilder<?>> maskedResolver;

        private final boolean failSubtypeResolution;

        private MaskedNameMetadataIntrospector(
                @Nullable Supplier<TypeResolverBuilder<?>> maskedResolver, boolean failSubtypeResolution) {
            this.maskedResolver = maskedResolver;
            this.failSubtypeResolution = failSubtypeResolution;
        }

        @Override
        public JsonTypeInfo.Value findPolymorphicTypeInfo(MapperConfig<?> config, Annotated annotated) {
            if (annotated instanceof AnnotatedClass && MaskedBase.class.equals(annotated.getRawType())) {
                return NAME_TYPE_INFO;
            }
            return super.findPolymorphicTypeInfo(config, annotated);
        }

        @Override
        public List<NamedType> findSubtypes(Annotated annotated) {
            if (MaskedBase.class.equals(annotated.getRawType())) {
                if (failSubtypeResolution) {
                    throw new IllegalStateException("subtype resolution is broken");
                }
                return List.of(new NamedType(MaskedFirst.class, "first"));
            }
            return super.findSubtypes(annotated);
        }

        @Override
        public TypeResolverBuilder<?> findTypeResolver(
                MapperConfig<?> config, AnnotatedClass annotated, JavaType baseType) {
            if (maskedResolver != null && MaskedBase.class.equals(annotated.getRawType())) {
                return maskedResolver.get();
            }
            return super.findTypeResolver(config, annotated, baseType);
        }
    }

    /** An {@code Id.NAME} builder that fails while the mapper builds its handler, on either side. */
    private static final class ThrowingNameTypeResolverBuilder extends StdTypeResolverBuilder {

        private ThrowingNameTypeResolverBuilder() {
            init(Id.NAME, null);
            inclusion(JsonTypeInfo.As.PROPERTY);
            typeProperty("kind");
        }

        @Override
        public TypeSerializer buildTypeSerializer(
                SerializationConfig config, JavaType baseType, Collection<NamedType> subtypes) {
            throw new IllegalStateException("type serializer construction is broken");
        }

        @Override
        public TypeDeserializer buildTypeDeserializer(
                DeserializationConfig config, JavaType baseType, Collection<NamedType> subtypes) {
            throw new IllegalStateException("type deserializer construction is broken");
        }
    }

    /**
     * Installs Jackson's polymorphism-disabling {@code Id.NONE} marker builder on
     * {@link SuppressedTypingDto}, optionally reporting {@code Id.NONE} metadata alongside a live
     * {@code Id.CLASS} resolver instead.
     */
    private static final class SuppressedTypingIntrospector extends JacksonAnnotationIntrospector {

        private static final JsonTypeInfo.Value NONE_TYPE_INFO =
                JsonTypeInfo.Value.from(NonePolymorphismMarker.class.getAnnotation(JsonTypeInfo.class));

        private final boolean installLiveResolver;

        private SuppressedTypingIntrospector(boolean installLiveResolver) {
            this.installLiveResolver = installLiveResolver;
        }

        @Override
        public JsonTypeInfo.Value findPolymorphicTypeInfo(MapperConfig<?> config, Annotated annotated) {
            if (installLiveResolver
                    && annotated instanceof AnnotatedClass
                    && SuppressedTypingDto.class.equals(annotated.getRawType())) {
                return NONE_TYPE_INFO;
            }
            return super.findPolymorphicTypeInfo(config, annotated);
        }

        @Override
        public TypeResolverBuilder<?> findTypeResolver(
                MapperConfig<?> config, AnnotatedClass annotated, JavaType baseType) {
            if (SuppressedTypingDto.class.equals(annotated.getRawType())) {
                return installLiveResolver ? classNameResolverBuilder() : StdTypeResolverBuilder.noTypeInfoBuilder();
            }
            return super.findTypeResolver(config, annotated, baseType);
        }
    }

    // --- Row 9 graph: class-level resolvers keyed to a container raw class ---

    /** The element type every container fixture carries; itself always safe. */
    private record ContainerItem(String name) {}

    private record ContainerMemberCarrier(List<ContainerItem> values) {}

    private record MapMemberCarrier(Map<String, ContainerItem> entries) {}

    private record ArrayMemberCarrier(ContainerItem[] batches) {}

    private record ReferenceMemberCarrier(Optional<ContainerItem> maybe) {}

    private record AtomicReferenceMemberCarrier(AtomicReference<ContainerItem> holder) {}

    /** Declares the resolved container root the container-scope gate must validate as a root. */
    @SuppressWarnings("unused")
    private static final class ContainerRootHolder {
        private List<ContainerItem> listRoot;
    }

    /**
     * Installs a class-name class-level type resolver keyed to one container raw class.
     *
     * <p>Jackson asks {@code findTypeResolver} for the container raw class itself — {@code
     * java.util.List}, {@code java.util.Map}, the array class, the reference class — when it
     * constructs the type serializer or deserializer for a root value or a bean property, and installs
     * whatever comes back. A walk that validates only the contents of such a type therefore leaves the
     * container's own resolver live.
     */
    private static final class ContainerClassResolverIntrospector extends JacksonAnnotationIntrospector {

        private final Class<?> containerClass;

        private ContainerClassResolverIntrospector(Class<?> containerClass) {
            this.containerClass = containerClass;
        }

        @Override
        public TypeResolverBuilder<?> findTypeResolver(
                MapperConfig<?> config, AnnotatedClass annotated, JavaType baseType) {
            if (containerClass.equals(annotated.getRawType())) {
                return classNameResolverBuilder();
            }
            return super.findTypeResolver(config, annotated, baseType);
        }
    }

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
