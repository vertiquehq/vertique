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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonFieldNameResolver}, the Jackson-backed wire &rarr; Java property-name
 * projection every Jackson-bound transport hands to the input-processing engine.
 *
 * <p>The four behaviours pinned here are the ones a wrong implementation silently converts into a
 * dropped {@code @Canonicalize}/{@code @Sanitize}:
 *
 * <ul>
 *   <li>a projection is computed from {@code @JsonProperty}, from a property naming strategy, and
 *       from {@code @JsonAlias};</li>
 *   <li>a primary name always claims its key and an alias fills only keys no primary claims —
 *       matching Jackson's own binding, which accepts such a collision rather than rejecting it;</li>
 *   <li>a route on the reserved {@code vertx} profile projects against {@link DatabindCodec#mapper()},
 *       the mapper that actually materializes its body, while a profiled route uses its own;</li>
 *   <li>the identity short circuit is keyed on the <em>computed</em> projection, never on an
 *       inference about how the mapper is configured;</li>
 *   <li>the projection is <em>precomputed</em> at registration, so the request path neither
 *       introspects nor throws;</li>
 *   <li>two properties claiming the same {@code @JsonAlias} fail composition instead of binding in
 *       an order the projection and Jackson resolve differently.</li>
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

    /** DTO reaching {@link DuplicateAliasDto} through a plain declared field. */
    public static class NestedHolderDto {
        public String label;
        public DuplicateAliasDto nested;
    }

    /** DTO reaching {@link DuplicateAliasDto} through a collection and a map value. */
    public static class ContainerHolderDto {
        public java.util.List<DuplicateAliasDto> many;
        public java.util.Map<String, RenamedDto> byKey;
    }

    /** Cyclic DTO graph — the walk must terminate rather than recurse forever. */
    public static class CyclicNodeDto {
        public String name;
        public CyclicNodeDto child;
        public java.util.List<CyclicNodeDto> children;
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
    @DisplayName("a vertx-profile route projects against DatabindCodec.mapper(); a profiled route against its own")
    void shouldResolveVertxProfileRoutesAgainstDatabindCodecMapper() {
        JacksonFieldNameResolver vertxRoute = JacksonFieldNameResolver.forRoute(null);

        assertSame(
                DatabindCodec.mapper(),
                vertxRoute.mapper(),
                "a route on the reserved vertx profile must introspect the mapper that materializes its body");
        assertEquals(
                "userName",
                vertxRoute.logicalName(RenamedDto.class, "user_name"),
                "the vertx-profile projection honours @JsonProperty");
        assertEquals(
                "home_page",
                vertxRoute.logicalName(StrategyDto.class, "home_page"),
                "the vertx mapper declares no naming strategy, so home_page is not a known wire name");

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
    @DisplayName("precomputeGraph warms the declared field graph so a nested type never introspects lazily")
    void shouldWarmTheDeclaredFieldGraphTransitively() {
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(vanillaMapper());

        // A nested type reached only through a declared field is resolved on the request path exactly
        // like the body type itself, so its projection must be composed at registration too.
        ConfigurationException nested = assertThrows(
                ConfigurationException.class,
                () -> resolver.precomputeGraph(NestedHolderDto.class),
                "a declared field's own type must be warmed with its owner");
        assertTrue(
                nested.getMessage().contains(DuplicateAliasDto.class.getName()),
                "the failure must name the nested type: " + nested.getMessage());

        // Container element and value types are the same case: the engine descends into them.
        ConfigurationException contained = assertThrows(
                ConfigurationException.class,
                () -> resolver.precomputeGraph(ContainerHolderDto.class),
                "a collection element type must be warmed like a plain field type");
        assertTrue(
                contained.getMessage().contains(DuplicateAliasDto.class.getName()),
                "the failure must name the element type: " + contained.getMessage());

        // An array target is unwrapped to its component type, which is where the property set lives.
        ConfigurationException arrayed = assertThrows(
                ConfigurationException.class,
                () -> resolver.precomputeGraph(DuplicateAliasDto[].class),
                "an array target must be unwrapped to its component type");
        assertTrue(
                arrayed.getMessage().contains(DuplicateAliasDto.class.getName()),
                "the failure must name the component type, not the array class: " + arrayed.getMessage());
    }

    @Test
    @DisplayName("precomputeGraph terminates on a cyclic graph and skips scalar property types")
    void shouldTerminateOnCyclesAndSkipScalars() {
        AtomicInteger introspections = new AtomicInteger();
        JacksonFieldNameResolver resolver = JacksonFieldNameResolver.forMapper(countingMapper(introspections));

        assertDoesNotThrow(
                () -> resolver.precomputeGraph(CyclicNodeDto.class),
                "a self-referential type graph must be visited once per type, not followed forever");
        assertEquals("child", resolver.logicalName(CyclicNodeDto.class, "child"), "the warmed projection still serves");

        // A scalar carries no property set the engine keys against, so warming one must introspect
        // nothing — the asymmetry that let a String message type be introspected pointlessly.
        introspections.set(0);
        assertDoesNotThrow(() -> resolver.precomputeGraph(String.class), "a scalar target is not a projection source");
        assertEquals(0, introspections.get(), "warming a scalar must not run a bean introspection");
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
}
