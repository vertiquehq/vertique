// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.vertx.core.json.jackson.DatabindCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JacksonFieldNameResolver}, the Jackson-backed wire &rarr; Java property-name
 * projection REST hands to the input-processing engine.
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
 *       inference about how the mapper is configured.</li>
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
}
