// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver.PromotedField;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonFieldNameResolver}, the Jackson-backed wire &rarr; Java property-name
 * projection every Jackson-bound transport hands to the input-processing engine.
 *
 * <p>The behaviours pinned here are the ones a wrong implementation silently converts into a
 * dropped {@code @Canonicalize}/{@code @Sanitize}:
 *
 * <ul>
 *   <li>a projection is computed from {@code @JsonProperty}, from a property naming strategy, and
 *       from {@code @JsonAlias};</li>
 *   <li>a primary name always claims its key and an alias fills only keys no primary claims —
 *       matching Jackson's own binding, which accepts such a collision rather than rejecting it;</li>
 *   <li>an unprofiled route projects against {@link VertiqueJson#mapper()}, the process codec's
 *       mapper that actually materializes its body, while a profiled route uses its own;</li>
 *   <li>the identity short circuit is keyed on the <em>computed</em> projection, never on an
 *       inference about how the mapper is configured;</li>
 *   <li>the projection is <em>precomputed</em> at registration, so the request path neither
 *       introspects nor throws;</li>
 *   <li>two properties claiming the same {@code @JsonAlias} fail composition instead of binding in
 *       an order the projection and Jackson resolve differently.</li>
 *   <li>two Java fields Jackson merged into one property fail composition rather than projecting
 *       onto the field Jackson never writes (GH-380);</li>
 *   <li>a case-insensitive mapper projects a differently-cased key onto the property Jackson binds
 *       it to, instead of resolving it to no property at all (GH-375).</li>
 * </ul>
 */
class JacksonFieldNameResolverTest {

    // --- Fixtures ---

    /** DTO whose wire names already equal its Java names under a vanilla mapper. */
    public static class PlainDto {
        public String name;
        public String city;
    }

    /** DTO renamed by an explicit {@code @JsonProperty}. */
    public static class RenamedDto {
        @JsonProperty("user_name")
        public String userName;

        public String city;
    }

    /** DTO renamed only when the mapper applies a {@code SNAKE_CASE} naming strategy. */
    public static class StrategyDto {
        public String homePage;
        public String city;
    }

    /** DTO carrying an alias that no primary name claims. */
    public static class AliasDto {
        @JsonAlias({"alt_name"})
        public String name;
    }

    /**
     * DTO whose {@code beta} declares an alias colliding with {@code alpha}'s primary name, plus one
     * free alias. Jackson accepts this configuration and binds {@code alpha} (verified below).
     */
    public static class AliasCollisionDto {
        public String alpha;

        @JsonAlias({"alpha", "gamma"})
        public String beta;
    }

    /**
     * DTO whose two properties claim the same {@code @JsonAlias}. Jackson resolves such a collision in
     * hash order while a declaration-ordered projection resolves it in declaration order, so the
     * property the policy is selected for and the property Jackson binds can differ.
     */
    public static class DuplicateAliasDto {
        @JsonAlias({"shared"})
        public String alpha;

        @JsonAlias({"shared"})
        public String beta;
    }

    /** DTO declaring the same alias twice on ONE property — not a collision between two properties. */
    public static class RepeatedAliasDto {
        @JsonAlias({"alt", "alt"})
        public String name;
    }

    /** Mapper that renames nothing of its own. */
    private static ObjectMapper vanillaMapper() {
        return JsonMapper.builder().build();
    }

    /** Mapper applying a {@code SNAKE_CASE} property naming strategy. */
    private static ObjectMapper snakeCaseMapper() {
        return JsonMapper.builder()
                .propertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .build();
    }

    /**
     * Mapper that declares no naming strategy and no mix-ins, yet renames every field through a
     * registered {@link com.fasterxml.jackson.databind.AnnotationIntrospector} — the configuration
     * shape that makes "this mapper is vanilla" an unsound identity guard.
     *
     * @return a mapper whose introspector prefixes every field name with {@code w_}
     */
    private static ObjectMapper introspectorRenamingMapper() {
        return JsonMapper.builder()
                .annotationIntrospector(new JacksonAnnotationIntrospector() {
                    @Override
                    public PropertyName findNameForDeserialization(Annotated annotated) {
                        if (annotated instanceof AnnotatedField) {
                            return PropertyName.construct("w_" + annotated.getName());
                        }
                        return super.findNameForDeserialization(annotated);
                    }
                })
                .build();
    }

    /**
     * Mapper counting every property-name introspection Jackson performs, so a test can prove where
     * the bean introspection happens rather than inferring it from timing.
     *
     * @param introspections the counter incremented once per introspected accessor
     * @return a mapper that renames nothing but counts its own introspection work
     */
    private static ObjectMapper countingMapper(AtomicInteger introspections) {
        return JsonMapper.builder()
                .annotationIntrospector(new JacksonAnnotationIntrospector() {
                    @Override
                    public PropertyName findNameForDeserialization(Annotated annotated) {
                        introspections.incrementAndGet();
                        return super.findNameForDeserialization(annotated);
                    }
                })
                .build();
    }

    // --- Tests ---

    @Test
    @DisplayName("projects @JsonProperty, naming-strategy and @JsonAlias wire names onto Java names")
    void shouldProjectJsonPropertyNamingStrategyAndAliasNames() {
        JacksonFieldNameResolver vanilla = JacksonFieldNameResolver.forMapper(vanillaMapper());

        assertEquals(
                "userName",
                vanilla.logicalName(RenamedDto.class, "user_name"),
                "@JsonProperty must project the wire name onto the Java property name");
        assertEquals(
                "city", vanilla.logicalName(RenamedDto.class, "city"), "an unrenamed property projects onto itself");
        assertEquals(
                "totally_unknown",
                vanilla.logicalName(RenamedDto.class, "totally_unknown"),
                "the projection is total: an unrecognized wire name is returned unchanged");

        JacksonFieldNameResolver snake = JacksonFieldNameResolver.forMapper(snakeCaseMapper());
        assertEquals(
                "homePage",
                snake.logicalName(StrategyDto.class, "home_page"),
                "a SNAKE_CASE naming strategy must be projected");

        assertEquals(
                "name",
                vanilla.logicalName(AliasDto.class, "alt_name"),
                "@JsonAlias must project the alias onto the Java property name");
        assertEquals(
                "name",
                vanilla.logicalName(AliasDto.class, "name"),
                "the aliased property's own name still projects onto itself");
    }

    @Test
    @DisplayName("a primary name always claims its key; an alias fills only unclaimed keys")
    void shouldLetPrimaryNamesWinAndAliasesFillOnlyUnclaimedKeys() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

        assertEquals(
                "alpha",
                resolver.logicalName(AliasCollisionDto.class, "alpha"),
                "alpha's primary name claims the key even though beta declares it as an alias");
        assertEquals(
                "beta",
                resolver.logicalName(AliasCollisionDto.class, "gamma"),
                "an alias populates a key no primary name claims");

        // The precedence above is not a preference — it is what Jackson itself binds, so a projection
        // that resolved "alpha" to beta (or refused the configuration) would disagree with the codec.
        AliasCollisionDto bound = mapper.readValue("{\"alpha\":\"VALUE\"}", AliasCollisionDto.class);
        assertEquals("VALUE", bound.alpha, "Jackson binds the primary name");
        assertNull(bound.beta, "Jackson leaves the aliasing property unset");
    }

    @Test
    @DisplayName("an unprofiled route projects against the process codec's mapper; a profiled route against its own")
    void shouldResolveUnprofiledRoutesAgainstTheProcessCodecMapper() {
        JacksonFieldNameResolver processCodecRoute = JacksonFieldNameResolver.forRoute(null);

        assertSame(
                VertiqueJson.mapper(),
                processCodecRoute.mapper(),
                "an unprofiled route must introspect the mapper that materializes its body — the process codec's");
        assertEquals(
                "userName",
                processCodecRoute.logicalName(RenamedDto.class, "user_name"),
                "the process-codec projection honours @JsonProperty");
        assertEquals(
                "home_page",
                processCodecRoute.logicalName(StrategyDto.class, "home_page"),
                "the process codec's mapper declares no naming strategy, so home_page is not a known wire name");

        ObjectMapper profileMapper = snakeCaseMapper();
        JacksonFieldNameResolver profiledRoute = JacksonFieldNameResolver.forRoute(profileMapper);

        assertSame(profileMapper, profiledRoute.mapper(), "a profiled route must introspect its own profile mapper");
        assertEquals(
                "homePage",
                profiledRoute.logicalName(StrategyDto.class, "home_page"),
                "the profile mapper's naming strategy decides the projection for its own routes");
    }

    @Test
    @DisplayName("the identity short circuit is keyed on the computed projection, not on mapper configuration")
    void shouldShortCircuitPerFieldResolutionOnlyWhenTheProjectionIsIdentity() {
        JacksonFieldNameResolver vanilla = JacksonFieldNameResolver.forMapper(vanillaMapper());

        assertTrue(
                vanilla.isIdentityProjection(PlainDto.class),
                "a DTO whose wire names equal its Java names has an identity projection");
        assertFalse(
                vanilla.isIdentityProjection(RenamedDto.class),
                "a @JsonProperty rename makes the projection non-identity");

        JacksonFieldNameResolver snake = JacksonFieldNameResolver.forMapper(snakeCaseMapper());

        assertFalse(
                snake.isIdentityProjection(StrategyDto.class),
                "a multi-word property under SNAKE_CASE renames, so the projection is not identity");
        assertTrue(
                snake.isIdentityProjection(PlainDto.class),
                "the flag follows the COMPUTED projection: single-word properties are unchanged by "
                        + "SNAKE_CASE, so this type short-circuits even under a renaming mapper");

        JacksonFieldNameResolver introspected = JacksonFieldNameResolver.forMapper(introspectorRenamingMapper());

        assertFalse(
                introspected.isIdentityProjection(PlainDto.class),
                "a mapper with no naming strategy and no mix-ins can still rename through a registered "
                        + "AnnotationIntrospector, so 'this mapper is vanilla' is never the guard");
        assertEquals(
                "name",
                introspected.logicalName(PlainDto.class, "w_name"),
                "the introspector-renamed wire name projects onto the Java property name");

        assertEquals(
                "unknown_key",
                vanilla.logicalName(PlainDto.class, "unknown_key"),
                "an identity projection still returns an unrecognized wire name unchanged");
    }

    @Test
    @DisplayName("precompute warms a type's projection so the request path never introspects it")
    void shouldPrecomputeTheProjectionSoTheRequestPathNeverIntrospects() {
        // Unwarmed, the first logicalName call pays a full bean introspection — on an event-loop
        // thread, for the first request that touches the type. That is the cost warming removes.
        AtomicInteger lazyIntrospections = new AtomicInteger();
        JacksonFieldNameResolver lazy = JacksonFieldNameResolver.forMapper(countingMapper(lazyIntrospections));

        assertEquals(0, lazyIntrospections.get(), "constructing a resolver must not introspect anything");
        lazy.logicalName(RenamedDto.class, "user_name");
        assertTrue(lazyIntrospections.get() > 0, "an unwarmed type is introspected by the first logicalName call");

        AtomicInteger warmIntrospections = new AtomicInteger();
        JacksonFieldNameResolver warmed = JacksonFieldNameResolver.forMapper(countingMapper(warmIntrospections));

        warmed.precompute(RenamedDto.class);
        int afterRegistration = warmIntrospections.get();
        assertTrue(afterRegistration > 0, "precompute must resolve the projection at registration");

        assertEquals(
                "userName",
                warmed.logicalName(RenamedDto.class, "user_name"),
                "the warmed projection serves the request path");
        assertEquals("city", warmed.logicalName(RenamedDto.class, "city"), "and every other key of the same type");
        assertEquals(
                afterRegistration,
                warmIntrospections.get(),
                "a warmed type must be served from the precomputed projection, never re-introspected");
    }

    @Test
    @DisplayName("two properties claiming the same @JsonAlias fail composition, naming both and the alias")
    void shouldFailWhenTwoPropertiesClaimTheSameAlias() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> resolver.precompute(DuplicateAliasDto.class),
                "two properties claiming one alias key must fail composition rather than resolve it arbitrarily");

        String message = failure.getMessage();
        assertTrue(message.contains("shared"), "the failure must name the contested alias: " + message);
        assertTrue(message.contains("alpha"), "the failure must name the first claiming property: " + message);
        assertTrue(message.contains("beta"), "the failure must name the second claiming property: " + message);
        assertTrue(
                message.contains(DuplicateAliasDto.class.getName()),
                "the failure must name the owning type: " + message);

        // Why the configuration cannot simply be resolved in declaration order: Jackson resolves the
        // same collision through a HashMap, so it binds `beta` here while a declaration-ordered
        // projection would select `alpha`. Mirroring an undefined order is untestable; failing is not.
        DuplicateAliasDto bound = mapper.readValue("{\"shared\":\"VALUE\"}", DuplicateAliasDto.class);
        assertNull(bound.alpha, "Jackson does not bind the first-declared claimant");
        assertEquals("VALUE", bound.beta, "Jackson binds the claimant its own hash order selected");
    }

    @Test
    @DisplayName("the preserved alias arms: a primary collision and a repeated single-property alias both pass")
    void shouldNotFailForAnAliasCollidingWithAPrimaryNameOrRepeatedOnOneProperty() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        // Control 1 — an alias colliding with ANOTHER property's primary name is accepted: Jackson
        // binds the primary (verified in shouldLetPrimaryNamesWinAndAliasesFillOnlyUnclaimedKeys), so
        // refusing the configuration would reject an application Jackson runs correctly.
        assertDoesNotThrow(
                () -> resolver.precompute(AliasCollisionDto.class),
                "an alias colliding with a primary name is silently unclaimed, never a startup failure");
        assertEquals(
                "alpha",
                resolver.logicalName(AliasCollisionDto.class, "alpha"),
                "the primary still claims the contested key");

        // Control 2 — one property repeating one alias is not two claimants; the single-alias path is
        // unchanged.
        assertDoesNotThrow(
                () -> resolver.precompute(RepeatedAliasDto.class),
                "a single property repeating its own alias is not a collision between two properties");
        assertEquals(
                "name",
                resolver.logicalName(RepeatedAliasDto.class, "alt"),
                "the repeated alias still projects onto its own property");
    }

    // --- GH-380: two Java fields Jackson merged into one property ---

    /** DTO whose second field is renamed onto the first field's name; Jackson merges the two. */
    public static class MergedFieldsDto {
        public String first;

        @JsonProperty("first")
        public String second;
    }

    @Test
    @DisplayName("GH-380: a property Jackson merged across two fields fails composition, naming both")
    void shouldRejectAPropertyJacksonMergedAcrossTwoFields() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        // Jackson publishes ONE property here: its implicit name is `first` while binding writes the
        // field `second`, so nothing reaches the two-properties collision check and the projection
        // would be the identity map. Left alone, `second`'s declared policies never run for the key
        // Jackson writes into it, and `first` is never bound at all.
        ConfigurationException ex =
                assertThrows(ConfigurationException.class, () -> resolver.precompute(MergedFieldsDto.class));

        assertTrue(ex.getMessage().contains(MergedFieldsDto.class.getName()), "names the type: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("'first'"), "names the published property: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("'second'"), "names the field written: " + ex.getMessage());
    }

    @Test
    @DisplayName("GH-380: an ordinary renamed field still composes — the rejection is not a blanket rename ban")
    void shouldStillComposeAnOrdinaryRenamedField() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        assertDoesNotThrow(
                () -> resolver.precompute(RenamedDto.class),
                "a field renamed onto a name no other field claims is projected, not refused");
    }

    // --- GH-375: a mapper that matches wire keys case-insensitively ---

    /** DTO used against a case-insensitive mapper; its wire key may arrive in any case. */
    public static class CaseFoldedDto {
        public String secretValue;
    }

    private static ObjectMapper caseInsensitiveMapper() {
        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                .build();
    }

    @Test
    @DisplayName("GH-375: a case-insensitive mapper projects a differently-cased key onto its property")
    void shouldProjectADifferentlyCasedKeyWhenTheMapperFoldsCase() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(caseInsensitiveMapper());
        resolver.precompute(CaseFoldedDto.class);

        // Jackson binds every one of these into `secretValue`, so every one must select that field's
        // declared policies. Before the fix the projection matched exactly, so all but the first
        // resolved to no property and their declared @Sanitize was skipped.
        assertEquals("secretValue", resolver.logicalName(CaseFoldedDto.class, "secretValue"));
        assertEquals("secretValue", resolver.logicalName(CaseFoldedDto.class, "SECRETVALUE"));
        assertEquals("secretValue", resolver.logicalName(CaseFoldedDto.class, "SecretValue"));

        assertFalse(
                resolver.isIdentityProjection(CaseFoldedDto.class),
                "a case-folding mapper must never take the identity short circuit, or the folded"
                        + " lookup would be skipped");
    }

    @Test
    @DisplayName("GH-375: a differently-cased key against an exact-match mapper is still returned unchanged")
    void shouldLeaveADifferentlyCasedKeyAloneWhenTheMapperMatchesExactly() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(CaseFoldedDto.class);

        // Control: Jackson itself does not bind SECRETVALUE here, so folding it would attach the
        // field's policies to a key that never reaches the field. The projection stays total.
        assertEquals("SECRETVALUE", resolver.logicalName(CaseFoldedDto.class, "SECRETVALUE"));
    }

    // --- GH-376: keys the codec promotes out of an @JsonUnwrapped member ---

    /** Inner type whose fields arrive as keys of the enclosing object. */
    public static class Address {
        public String street;
        public String city;
    }

    /** Parent that unwraps {@link Address} with no prefix. */
    public static class UnwrappedDto {
        public String name;

        @JsonUnwrapped
        public Address address;
    }

    /** Parent that unwraps {@link Address} behind a prefix. */
    public static class PrefixedUnwrappedDto {
        @JsonUnwrapped(prefix = "addr_")
        public Address address;
    }

    /** Middle level that itself unwraps, so the transformers chain. */
    public static class Contact {
        @JsonUnwrapped(prefix = "home_")
        public Address address;
    }

    /** Parent whose unwrapped member unwraps again. */
    public static class NestedUnwrappedDto {
        @JsonUnwrapped(prefix = "c_")
        public Contact contact;
    }

    /** The shape Lombok emits: a private field reached through accessors. */
    public static class SetterBackedDto {
        private Address address;

        @JsonUnwrapped
        public Address getAddress() {
            return address;
        }

        public void setAddress(Address address) {
            this.address = address;
        }
    }

    /** Two inner types sharing a field name, disambiguated by prefixes as Jackson requires. */
    public static class Home {
        public String street;
    }

    /** The other one. */
    public static class Work {
        public String street;
    }

    /** Two prefixed unwrapped members whose inner types share a field name. */
    public static class TwoPrefixedDto {
        @JsonUnwrapped(prefix = "h_")
        public Home home;

        @JsonUnwrapped(prefix = "w_")
        public Work work;
    }

    /** An owner field whose Java name equals the promoted member's inner field name. */
    public static class ShadowingDto {
        @JsonProperty("owner_street")
        public String street;

        @JsonUnwrapped(prefix = "a_")
        public Home home;
    }

    /** Inner type whose field carries an alias. */
    public static class AliasedInner {
        @JsonAlias("alternate")
        public String street;
    }

    /** Parent unwrapping a type whose field carries an alias. */
    public static class AliasedUnwrappedDto {
        @JsonUnwrapped
        public AliasedInner inner;
    }

    @Test
    @DisplayName("GH-376: an unwrapped member's fields are reported as promoted keys, by wire name")
    void shouldReportUnwrappedMembersAsPromotedKeys() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(UnwrappedDto.class);

        Map<String, PromotedField> promoted = resolver.promotedFields(UnwrappedDto.class);
        assertEquals(
                new PromotedField(Address.class, "street", List.of("address")),
                promoted.get("street"),
                "keyed by the wire name, and carrying the path back to the enclosing member: " + promoted);
        assertEquals(new PromotedField(Address.class, "city", List.of("address")), promoted.get("city"));
        assertEquals(2, promoted.size(), "the parent's own property is not promoted: " + promoted);

        // A promoted key is NOT a name of this type, so the projection returns it unchanged and the
        // engine falls through to the promoted lookup.
        assertEquals("street", resolver.logicalName(UnwrappedDto.class, "street"));
        assertEquals(
                new PromotedField(Address.class, "street", List.of("address")),
                resolver.promotedField(UnwrappedDto.class, "street"));
    }

    @Test
    @DisplayName("GH-376: a setter-backed unwrapped member is promoted — the Lombok shape")
    void shouldPromoteASetterBackedUnwrappedMember() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(SetterBackedDto.class);

        // On a deserialization introspection the primary member is the MUTATOR, and a setter's type
        // is void — which introspects to no properties at all. Reading it instead of the property's
        // own type silently promoted nothing for every DTO with a setter.
        assertEquals(
                new PromotedField(Address.class, "street", List.of("address")),
                resolver.promotedField(SetterBackedDto.class, "street"),
                "a setter-backed member promotes exactly as a field-backed one does");
    }

    @Test
    @DisplayName("GH-376: a prefix on @JsonUnwrapped is applied to the promoted key")
    void shouldApplyThePrefixToPromotedKeys() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(PrefixedUnwrappedDto.class);

        assertEquals(
                new PromotedField(Address.class, "street", List.of("address")),
                resolver.promotedField(PrefixedUnwrappedDto.class, "addr_street"),
                "Jackson binds addr_street, so that is the key the projection must resolve");
        assertNull(
                resolver.promotedField(PrefixedUnwrappedDto.class, "street"),
                "the unprefixed key is not what the codec binds");
    }

    @Test
    @DisplayName("GH-376: nested unwrapping chains the transformers and records both levels")
    void shouldChainTransformersThroughNestedUnwrapping() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(NestedUnwrappedDto.class);

        assertEquals(
                new PromotedField(Address.class, "street", List.of("contact", "address")),
                resolver.promotedField(NestedUnwrappedDto.class, "c_home_street"),
                "two levels of prefix, applied outermost-first, and both enclosing members recorded");
    }

    @Test
    @DisplayName("GH-376: two prefixed members sharing an inner field name both compose")
    void shouldComposeTwoPrefixedMembersSharingAnInnerFieldName() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        // Jackson binds h_street and w_street unambiguously, so refusing this would break a working
        // application. Keying the promoted map by the inner Java name used to collide here.
        assertDoesNotThrow(() -> resolver.precompute(TwoPrefixedDto.class));
        assertEquals(
                new PromotedField(Home.class, "street", List.of("home")),
                resolver.promotedField(TwoPrefixedDto.class, "h_street"));
        assertEquals(
                new PromotedField(Work.class, "street", List.of("work")),
                resolver.promotedField(TwoPrefixedDto.class, "w_street"));
    }

    @Test
    @DisplayName("GH-376: a promoted key does not shadow a same-named field of the owner")
    void shouldNotLetAPromotedKeyShadowAnOwnerField() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(ShadowingDto.class);

        // Two distinct wire keys must stay distinct. Injecting the promoted key into the projection
        // collapsed both onto `street`, so the owner's metadata won and the inner field's policies
        // never ran while the owner's were applied to a value never declared for them.
        assertEquals(
                "street",
                resolver.logicalName(ShadowingDto.class, "owner_street"),
                "the owner's own renamed field still projects onto its Java name");
        assertEquals(
                "a_street",
                resolver.logicalName(ShadowingDto.class, "a_street"),
                "the promoted key is returned unchanged, so the owner's metadata misses it");
        assertEquals(
                new PromotedField(Home.class, "street", List.of("home")),
                resolver.promotedField(ShadowingDto.class, "a_street"));
        assertNull(
                resolver.promotedField(ShadowingDto.class, "owner_street"), "the owner's own key was never promoted");
    }

    @Test
    @DisplayName("GH-376: an owner property claiming a promoted key keeps it, because that is what Jackson binds")
    void shouldLeaveAContestedKeyToTheOwnersOwnProperty() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(UnwrappedDto.class);

        // `name` is the owner's own property; nothing promotes it.
        assertNull(resolver.promotedField(UnwrappedDto.class, "name"));
    }

    @Test
    @DisplayName("GH-376: an alias on an unwrapped member's field is promoted too")
    void shouldPromoteAnInnerFieldsAlias() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(AliasedUnwrappedDto.class);

        PromotedField expected = new PromotedField(AliasedInner.class, "street", List.of("inner"));
        assertEquals(expected, resolver.promotedField(AliasedUnwrappedDto.class, "street"));
        assertEquals(
                expected,
                resolver.promotedField(AliasedUnwrappedDto.class, "alternate"),
                "Jackson binds the alias into the same field, so it selects the same policies");
    }

    @Test
    @DisplayName("GH-376: a type with no unwrapped member reports no promoted keys")
    void shouldReportNoPromotedKeysForAnOrdinaryType() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());
        resolver.precompute(PlainDto.class);

        assertTrue(resolver.promotedFields(PlainDto.class).isEmpty(), "nothing is promoted here");
        assertTrue(resolver.isIdentityProjection(PlainDto.class), "and the ordinary short circuit is unaffected");
    }

    // --- ADR-0247 Amendment 3: claims on one key resolve the way Jackson binds them ---

    /** Middle level with a field of its own that its unwrapped member also declares. */
    public static class ContactWithOwnStreet {
        public String street;

        @JsonUnwrapped
        public Address address;
    }

    /** Parent whose unwrapped member has both an own field and an unwrapped member sharing a name. */
    public static class NestedOwnFieldDto {
        @JsonUnwrapped(prefix = "c_")
        public ContactWithOwnStreet contact;
    }

    /** Inner type whose alias names a sibling's primary. */
    public static class InnerAliasingSibling {
        public String alpha;

        @JsonAlias("alpha")
        public String beta;
    }

    /** Parent unwrapping a type whose alias collides with a sibling primary. */
    public static class AliasPrecedenceDto {
        @JsonUnwrapped
        public InnerAliasingSibling inner;
    }

    /** Parent unwrapping an aliased type behind a prefix. */
    public static class PrefixedAliasDto {
        @JsonUnwrapped(prefix = "a_")
        public AliasedInner inner;
    }

    /** Two bare unwrapped members whose inner types share a field name. */
    public static class TwoBareDto {
        @JsonUnwrapped
        public Home home;

        @JsonUnwrapped
        public Work work;
    }

    /** Parent whose nested unwrapping collides two levels down. */
    public static class NestedCollisionDto {
        @JsonUnwrapped(prefix = "n_")
        public TwoBareDto two;
    }

    @Test
    @DisplayName("a bean's own field claims a key before its unwrapped member does, at every depth")
    void shouldLetTheShallowerClaimWinSilently() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

        assertDoesNotThrow(() -> resolver.precompute(NestedOwnFieldDto.class), "Jackson binds this shape");
        assertEquals(
                new PromotedField(ContactWithOwnStreet.class, "street", List.of("contact")),
                resolver.promotedField(NestedOwnFieldDto.class, "c_street"),
                "the middle level's own property claims the key");
        assertEquals(
                new PromotedField(Address.class, "city", List.of("contact", "address")),
                resolver.promotedField(NestedOwnFieldDto.class, "c_city"),
                "the deeper member still supplies the keys nothing above it claims");

        // The projection must agree with what Jackson actually binds, not with itself.
        NestedOwnFieldDto bound = mapper.readValue("{\"c_street\":\"V\",\"c_city\":\"C\"}", NestedOwnFieldDto.class);
        assertEquals("V", bound.contact.street);
        assertEquals("C", bound.contact.address.city, "the deeper member is materialized");
        assertNull(bound.contact.address.street, "and its same-named field is not fed");
    }

    @Test
    @DisplayName("within one level an inner primary claims its key before an inner alias, as on the owner")
    void shouldLetAnInnerPrimaryWinOverAnInnerAlias() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

        assertDoesNotThrow(() -> resolver.precompute(AliasPrecedenceDto.class), "Jackson binds this shape");
        assertEquals(
                new PromotedField(InnerAliasingSibling.class, "alpha", List.of("inner")),
                resolver.promotedField(AliasPrecedenceDto.class, "alpha"));

        InnerAliasingSibling bound = mapper.readValue("{\"alpha\":\"V\"}", AliasPrecedenceDto.class).inner;
        assertEquals("V", bound.alpha, "Jackson binds the primary");
        assertNull(bound.beta, "and leaves the alias holder alone");
    }

    @Test
    @DisplayName("an inner alias is not promoted under a prefix, because Jackson does not bind it there")
    void shouldNotPromoteAnAliasUnderATransformer() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);
        resolver.precompute(PrefixedAliasDto.class);

        assertEquals(
                new PromotedField(AliasedInner.class, "street", List.of("inner")),
                resolver.promotedField(PrefixedAliasDto.class, "a_street"));
        assertNull(
                resolver.promotedField(PrefixedAliasDto.class, "a_alternate"),
                "a key the mapper never binds must not carry a policy");

        assertNull(
                mapper.readValue("{\"a_alternate\":\"X\"}", PrefixedAliasDto.class).inner.street,
                "Jackson applies the prefix to primary names only");
    }

    @Test
    @DisplayName("two members at one depth claiming one key fail composition, naming the owner being composed")
    void shouldRefuseTwoMembersAtOneDepthClaimingOneKey() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        ConfigurationException direct =
                assertThrows(ConfigurationException.class, () -> resolver.precompute(TwoBareDto.class));
        assertTrue(direct.getMessage().contains(TwoBareDto.class.getName()), direct.getMessage());
        assertTrue(direct.getMessage().contains(Home.class.getName() + ".street"), direct.getMessage());
        assertTrue(direct.getMessage().contains(Work.class.getName() + ".street"), direct.getMessage());

        // Two levels down the failure still names the type whose projection is being composed —
        // the one the application author registered — not the intermediate class.
        ConfigurationException nested =
                assertThrows(ConfigurationException.class, () -> resolver.precompute(NestedCollisionDto.class));
        assertTrue(nested.getMessage().contains(NestedCollisionDto.class.getName()), nested.getMessage());
        assertTrue(nested.getMessage().contains("n_street"), nested.getMessage());
    }

    // --- ADR-0247 Amendment 3: the Java names the mapper binds into ---

    /** A field the mapper reaches only through accessors of a different implicit name. */
    public static class AccessorNamedDto {
        private String streetName;

        public String getStreet() {
            return streetName;
        }

        public void setStreet(String street) {
            this.streetName = street;
        }
    }

    /** A field the mapper never binds beside one it does. */
    public static class IgnoredFieldDto {
        @JsonIgnore
        public String hidden;

        public String shown;
    }

    @Test
    @DisplayName("boundJavaNames reports the property names the mapper binds into, not the field names")
    void shouldReportTheJavaNamesTheMapperBindsInto() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        assertEquals(
                Set.of("street"),
                resolver.boundJavaNames(AccessorNamedDto.class),
                "the mapper derives 'street' from the accessors and never learns about 'streetName'");
        assertEquals(Set.of("shown"), resolver.boundJavaNames(IgnoredFieldDto.class), "an ignored field is unbound");
        assertEquals(
                Set.of("userName", "city"),
                resolver.boundJavaNames(RenamedDto.class),
                "a renamed field is bound under its Java name, which is what the engine keys");
        assertEquals(
                Set.of("street", "city"),
                resolver.boundJavaNames(Address.class),
                "an identity projection still enumerates what it binds");
    }

    /** Leaf of the deeper branch. */
    public static class DeepLeaf {
        public String x;
    }

    /** Middle of the deeper branch. */
    public static class DeepBranch {
        @JsonUnwrapped
        public DeepLeaf leaf;
    }

    /** The shallow sibling branch, sharing the leaf's field name. */
    public static class ShallowBranch {
        public String x;
    }

    /** Two sibling branches claiming one key at different depths. */
    public static class CrossBranchDto {
        @JsonUnwrapped
        public DeepBranch deep;

        @JsonUnwrapped
        public ShallowBranch shallow;
    }

    @Test
    @DisplayName(
            "two members on different branches claiming one key fail whatever their depths, because Jackson feeds both")
    void shouldRefuseACrossBranchClaimAtDifferentDepths() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

        // Depth alone must not decide: the shallower bean consumes a key only for the members
        // unwrapped INTO it, and a sibling branch never sees that consumption.
        CrossBranchDto bound = mapper.readValue("{\"x\":\"V\"}", CrossBranchDto.class);
        assertEquals("V", bound.shallow.x, "Jackson feeds the shallow sibling");
        assertEquals("V", bound.deep.leaf.x, "and the deeper branch's leaf, from the same value");

        ConfigurationException ex =
                assertThrows(ConfigurationException.class, () -> resolver.precompute(CrossBranchDto.class));
        assertTrue(ex.getMessage().contains(CrossBranchDto.class.getName()), ex.getMessage());
        assertTrue(ex.getMessage().contains(DeepLeaf.class.getName() + ".x"), ex.getMessage());
        assertTrue(ex.getMessage().contains(ShallowBranch.class.getName() + ".x"), ex.getMessage());
    }

    /** Immutable DTO built through a creator whose parameter is named only on the wire. */
    public static class CreatorDto {
        private final String streetName;

        @JsonCreator
        public CreatorDto(@JsonProperty("street_name") String s) {
            this.streetName = s;
        }

        public String getStreetName() {
            return streetName;
        }
    }

    /** The same DTO with the field carrying the parameter's wire name, so Jackson links the two. */
    public static class LinkedCreatorDto {
        @JsonProperty("street_name")
        private final String streetName;

        @JsonCreator
        public LinkedCreatorDto(@JsonProperty("street_name") String s) {
            this.streetName = s;
        }

        public String getStreetName() {
            return streetName;
        }
    }

    /** A record: creator parameters are linked to components by name. */
    public record RecordDto(@JsonProperty("street_name") String streetName) {}

    /** A creator parameter named after the field it writes, with no accessor Jackson could link. */
    public static class FluentCreatorDto {
        private final String streetName;

        @JsonCreator
        public FluentCreatorDto(@JsonProperty("streetName") String streetName) {
            this.streetName = streetName;
        }

        public String streetName() {
            return streetName;
        }
    }

    @Test
    @DisplayName("a creator parameter the mapper cannot tie to a field is reported as unroutable")
    void shouldReportACreatorOnlyPropertyAsUnroutable() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        assertEquals(
                Set.of("street_name"),
                resolver.unroutableWireNames(CreatorDto.class),
                "Jackson binds the key into the parameter and never learns which field it reaches");
        assertTrue(
                resolver.unroutableWireNames(LinkedCreatorDto.class).isEmpty(),
                "the same wire name on the field links parameter and field");
        assertEquals(
                Set.of("streetName"),
                resolver.boundJavaNames(LinkedCreatorDto.class),
                "and the linked property is bound under the field's own name");
        assertTrue(resolver.unroutableWireNames(RecordDto.class).isEmpty(), "record components are linked by name");
        assertEquals(Set.of("streetName"), resolver.boundJavaNames(RecordDto.class));
        // Jackson prunes the invisible field, so nothing is LINKED — but the parameter's own name is
        // the field's, which is the name the engine keys the policy under. Routable.
        assertTrue(
                resolver.unroutableWireNames(FluentCreatorDto.class).isEmpty(),
                "a parameter named after the field it writes is routable by that name");
        assertEquals(Set.of("streetName"), resolver.boundJavaNames(FluentCreatorDto.class));
    }

    /** Owner that drops a key its unwrapped member would otherwise claim. */
    @JsonIgnoreProperties({"street"})
    public static class IgnoringUnwrappedDto {
        @JsonUnwrapped
        public Address address;
    }

    @Test
    @DisplayName("a key the owner ignores is not promoted, because Jackson drops it before the member sees it")
    void shouldNotPromoteAKeyTheOwnerIgnores() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);
        resolver.precompute(IgnoringUnwrappedDto.class);

        IgnoringUnwrappedDto bound = mapper.readValue("{\"street\":\"S\",\"city\":\"C\"}", IgnoringUnwrappedDto.class);
        assertNull(bound.address.street, "Jackson discards the ignored key");
        assertEquals("C", bound.address.city);

        assertNull(resolver.promotedField(IgnoringUnwrappedDto.class, "street"), "a discarded key carries no policy");
        assertEquals(
                new PromotedField(Address.class, "city", List.of("address")),
                resolver.promotedField(IgnoringUnwrappedDto.class, "city"));
    }

    /** Deserializer that binds whatever it reads, outside the declaration view. */
    public static class CustomDtoDeserializer extends JsonDeserializer<CustomDto> {
        @Override
        public CustomDto deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode node = parser.readValueAsTree();
            CustomDto dto = new CustomDto();
            dto.secret = node.path("secret").asText(null);
            return dto;
        }
    }

    /** Type bound by a custom deserializer, with no accessor Jackson could introspect. */
    @JsonDeserialize(using = CustomDtoDeserializer.class)
    public static class CustomDto {
        private String secret;
    }

    /** Type bound through a builder. */
    @JsonDeserialize(builder = BuiltDto.Builder.class)
    public static class BuiltDto {
        private String value;

        /** The builder. */
        @JsonPOJOBuilder(withPrefix = "")
        public static class Builder {
            private String value;

            public Builder value(String value) {
                this.value = value;
                return this;
            }

            public BuiltDto build() {
                BuiltDto dto = new BuiltDto();
                dto.value = value;
                return dto;
            }
        }
    }

    /** Type bound through a delegating creator. */
    public static class DelegatingDto {
        private final String raw;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public DelegatingDto(Map<String, Object> source) {
            this.raw = String.valueOf(source.get("raw"));
        }
    }

    @Test
    @DisplayName("a type the mapper binds outside its declaration view cannot enumerate what it binds")
    void shouldReportNothingEnumerableForTypesBoundOutsideTheDeclarationView() throws Exception {
        ObjectMapper mapper = vanillaMapper();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(mapper);

        assertEquals("Z", mapper.readValue("{\"secret\":\"Z\"}", CustomDto.class).secret, "Jackson binds it");
        assertNull(resolver.boundJavaNames(CustomDto.class), "a custom deserializer binds what it reads");
        assertNull(resolver.boundJavaNames(BuiltDto.class), "a builder binds through its own methods");
        assertNull(resolver.boundJavaNames(DelegatingDto.class), "a delegating creator takes the whole value");
        assertTrue(resolver.unroutableWireNames(DelegatingDto.class).isEmpty(), "and reports no unroutable key");
    }
}
