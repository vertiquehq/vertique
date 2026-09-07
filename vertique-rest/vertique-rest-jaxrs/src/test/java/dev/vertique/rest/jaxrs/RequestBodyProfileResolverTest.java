// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.security.SecurityPolicy;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestBodyProfileResolver}: the per-method resolution of the effective
 * request-body {@link ObjectMapper} from {@code @JsonProfile} on the method, {@code @JsonProfile} on
 * the class, {@link JaxRsConfig#jsonProfile()} (config key {@code jaxrs.jsonProfile}), and
 * {@link JsonConfig#effectiveProfile()} (config key {@code json.jsonProfile}, floored at
 * {@code vertique}).
 *
 * <p>The precedence under test (highest first) is: method annotation &rarr; class annotation &rarr;
 * {@code jaxrs.jsonProfile} &rarr; {@code json.jsonProfile} &rarr; the {@code vertique} floor. Every
 * effective id resolves to its mapper from the registry, and an unknown id fails fast with
 * {@link JsonProfileConfigurationException}; the resolver returns {@code null} ("no override") only
 * when that mapper is the same instance as {@link VertiqueJson#mapper()}, which in this unbooted
 * harness is nothing but an explicitly installed mapper.
 */
class RequestBodyProfileResolverTest {

    // --- Fixtures: resources carrying @JsonProfile in various positions ---

    /** Class-annotated with the {@code class-profile}; one method overrides it with {@code method-profile}. */
    @JsonProfile("class-profile")
    static class ClassAnnotatedResource {
        @JsonProfile("method-profile")
        public String methodOverride() {
            return "x";
        }

        public String inheritsClass() {
            return "y";
        }
    }

    /** No class- or method-level annotation: resolution falls through to config / the vertique floor. */
    static class UnannotatedResource {
        public String plain() {
            return "z";
        }
    }

    /** Method annotated with an id that is not registered, to drive the fail-fast path. */
    static class UnknownProfileResource {
        @JsonProfile("does-not-exist")
        public String unknown() {
            return "w";
        }
    }

    /**
     * Class annotated with a valid {@code class-profile}; one method carries a blank
     * {@code @JsonProfile("")}. The blank method annotation must be treated as ABSENT so resolution
     * falls through to the (valid) class annotation rather than masking it.
     */
    @JsonProfile("class-profile")
    static class BlankMethodOverClassResource {
        @JsonProfile("")
        public String blankMethod() {
            return "b";
        }
    }

    /**
     * Resource with only a blank method-level {@code @JsonProfile("")} and no class annotation. The
     * blank annotation must be treated as absent so resolution falls through the config tail to the
     * vertique floor (or to a configured default), never crashing on {@code JsonProfileId.of("")}.
     */
    static class BlankMethodOnlyResource {
        @JsonProfile("")
        public String blankMethod() {
            return "b";
        }
    }

    /**
     * Restores the raw Vert.x delegate as the process JSON codec's mapper after every test.
     *
     * <p>Only the sentinel test installs a mapper, but the reset is unconditional: a leaked
     * installation would otherwise make the next {@code install} of a different profile id throw, and
     * this module runs one fork for the whole class. The seam is opened by the module's surefire and
     * failsafe {@code -Dvertique.json.codec.allowReset=true} argLine.
     */
    @AfterEach
    void resetProcessCodec() {
        VertiqueJson.resetForTests();
    }

    // --- Helpers ---

    /** Builds a probe-passing application profile whose mapper registers Vert.x JSON support. */
    private static JsonMapperProfile appProfile(String id) {
        ObjectMapper mapper = new ObjectMapper().registerModule(VertxJsonSupport.module());
        return JsonMapperProfiles.of(JsonProfileId.of(id), mapper);
    }

    /**
     * Builds a registry with the {@code method-profile}, {@code class-profile}, {@code config-profile}
     * (the {@code jaxrs.jsonProfile} tier), and {@code global-profile} (the {@code json.jsonProfile}
     * tier) apps.
     */
    private static DefaultJsonMapperProfileRegistry registryWithAppProfiles() {
        return new DefaultJsonMapperProfileRegistry(Set.of(
                appProfile("method-profile"),
                appProfile("class-profile"),
                appProfile("config-profile"),
                appProfile("global-profile")));
    }

    /** Returns the method annotations declared on the given fixture method. */
    private static List<Annotation> methodAnnotations(Class<?> resourceType, String methodName)
            throws NoSuchMethodException {
        Method method = resourceType.getMethod(methodName);
        return Arrays.asList(method.getAnnotations());
    }

    /** Returns the type annotations declared on the given fixture class. */
    private static List<Annotation> classAnnotations(Class<?> resourceType) {
        return Arrays.asList(resourceType.getAnnotations());
    }

    /** Builds a {@link ResourceMethodMeta} for a fixture method with the supplied method/class annotations. */
    private static ResourceMethodMeta metaFor(
            Class<?> resourceType,
            String methodName,
            List<Annotation> methodAnnotations,
            List<Annotation> classAnnotations)
            throws Exception {
        Object resource = resourceType.getDeclaredConstructor().newInstance();
        Method method = resourceType.getMethod(methodName);
        return new ResourceMethodMeta(
                resource,
                method,
                methodName,
                "POST",
                "/things",
                List.of(),
                method.getReturnType(),
                false,
                false,
                new SecurityPolicy.None(),
                ResourceMethodMeta.MediaTypes.EMPTY,
                null,
                methodAnnotations,
                classAnnotations,
                List.of(),
                List.of());
    }

    /** Builds a {@link JaxRsConfig} whose {@code jsonProfile} ({@code jaxrs.jsonProfile}) is the supplied value (may be null). */
    private static JaxRsConfig configWith(String jaxrsJsonProfile) {
        return JaxRsConfig.builder().jsonProfile(jaxrsJsonProfile).build();
    }

    /** Builds a {@link JsonConfig} whose {@code jsonProfile} ({@code json.jsonProfile}) is the supplied value (may be null). */
    private static JsonConfig globalWith(String jsonProfile) {
        return new JsonConfig(jsonProfile);
    }

    // --- Tests: existing precedence (method/class/jaxrs/floor) under the new 4-arg signature ---

    @Test
    @DisplayName("method @JsonProfile overrides class @JsonProfile, which overrides config, which overrides the floor")
    void methodProfile_overridesClassProfile_overridesConfig_overridesFloor() throws Exception {
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        JsonConfig noGlobal = JsonConfig.defaults();

        // 1. Method annotation wins over class annotation and config.
        ResourceMethodMeta methodWins = metaFor(
                ClassAnnotatedResource.class,
                "methodOverride",
                methodAnnotations(ClassAnnotatedResource.class, "methodOverride"),
                classAnnotations(ClassAnnotatedResource.class));
        assertSame(
                registry.mapper(JsonProfileId.of("method-profile")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(
                        methodWins, configWith("config-profile"), noGlobal, registry),
                "method @JsonProfile must win");

        // 2. With no method annotation, the class annotation wins over config.
        ResourceMethodMeta classWins = metaFor(
                ClassAnnotatedResource.class,
                "inheritsClass",
                methodAnnotations(ClassAnnotatedResource.class, "inheritsClass"),
                classAnnotations(ClassAnnotatedResource.class));
        assertSame(
                registry.mapper(JsonProfileId.of("class-profile")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(
                        classWins, configWith("config-profile"), noGlobal, registry),
                "class @JsonProfile must win when method has none");

        // 3. With no method or class annotation, config wins over the floor.
        ResourceMethodMeta configWins = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));
        assertSame(
                registry.mapper(JsonProfileId.of("config-profile")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(
                        configWins, configWith("config-profile"), noGlobal, registry),
                "jaxrs.jsonProfile must win when no annotation present");

        // 4. With nothing set anywhere, the effective id is the vertique floor -> that profile's mapper.
        assertSame(
                registry.mapper(JsonProfileId.of("vertique")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(configWins, configWith(null), noGlobal, registry),
                "the vertique floor must resolve to the vertique profile's mapper");
    }

    @Test
    @DisplayName("an unknown configured profile id throws JsonProfileConfigurationException at resolution")
    void unknownConfiguredProfile_throwsAtResolution() throws Exception {
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));

        assertThrows(
                JsonProfileConfigurationException.class,
                () -> RequestBodyProfileResolver.resolveRequestBodyMapper(
                        meta, configWith("not-registered"), JsonConfig.defaults(), registry),
                "an unknown configured profile must fail fast at resolution");
    }

    @Test
    @DisplayName("an unknown method @JsonProfile id throws JsonProfileConfigurationException at resolution")
    void unknownAnnotatedProfile_throwsAtResolution() throws Exception {
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                UnknownProfileResource.class,
                "unknown",
                methodAnnotations(UnknownProfileResource.class, "unknown"),
                classAnnotations(UnknownProfileResource.class));

        assertThrows(
                JsonProfileConfigurationException.class,
                () -> RequestBodyProfileResolver.resolveRequestBodyMapper(
                        meta, configWith(null), JsonConfig.defaults(), registry),
                "an unknown annotated profile must fail fast at resolution");
    }

    @Test
    @DisplayName("no selection resolves the vertique floor's mapper; an explicit system resolves the system mapper")
    void noSelection_resolvesTheFloorMapper() throws Exception {
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        JsonConfig noGlobal = JsonConfig.defaults();
        ResourceMethodMeta meta = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));

        // Nothing set and a blank jaxrs.jsonProfile both fall through to the vertique floor; an explicit
        // "system" resolves the registry's system mapper, which in this unbooted harness (nothing
        // installed as the process codec) is NOT the process mapper and so is returned, not nulled.
        assertSame(
                registry.mapper(JsonProfileId.of("vertique")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(meta, configWith(null), noGlobal, registry));
        assertSame(
                registry.mapper(JsonProfileId.of("vertique")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(meta, configWith("  "), noGlobal, registry));
        assertSame(
                registry.mapper(JsonProfileId.SYSTEM),
                RequestBodyProfileResolver.resolveRequestBodyMapper(meta, configWith("system"), noGlobal, registry));
    }

    // --- Tests: new tiers (jaxrs.jsonProfile + json.jsonProfile) — slice 2.2 ---

    @Test
    @DisplayName("method @JsonProfile wins over both jaxrs.jsonProfile and json.jsonProfile defaults")
    void methodAnnotationWins() throws Exception {
        // given a method @JsonProfile("method-profile"), jaxrs.jsonProfile="config-profile",
        // json.jsonProfile="global-profile"
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                ClassAnnotatedResource.class,
                "methodOverride",
                methodAnnotations(ClassAnnotatedResource.class, "methodOverride"),
                classAnnotations(ClassAnnotatedResource.class));

        // when resolved
        ObjectMapper resolved = RequestBodyProfileResolver.resolveRequestBodyMapper(
                meta, configWith("config-profile"), globalWith("global-profile"), registry);

        // then the method annotation id "method-profile" wins
        assertSame(registry.mapper(JsonProfileId.of("method-profile")), resolved, "method annotation must win");
    }

    @Test
    @DisplayName("class @JsonProfile wins over both jaxrs.jsonProfile and json.jsonProfile defaults")
    void classAnnotationOverDefaults() throws Exception {
        // given only a class @JsonProfile("class-profile") plus both defaults set
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                ClassAnnotatedResource.class,
                "inheritsClass",
                methodAnnotations(ClassAnnotatedResource.class, "inheritsClass"),
                classAnnotations(ClassAnnotatedResource.class));

        // when resolved
        ObjectMapper resolved = RequestBodyProfileResolver.resolveRequestBodyMapper(
                meta, configWith("config-profile"), globalWith("global-profile"), registry);

        // then the class annotation id "class-profile" wins
        assertSame(registry.mapper(JsonProfileId.of("class-profile")), resolved, "class annotation must win");
    }

    @Test
    @DisplayName("jaxrs.jsonProfile wins over json.jsonProfile when no annotation present")
    void jaxrsDefaultOverGlobal() throws Exception {
        // given no annotation, jaxrs.jsonProfile="config-profile", json.jsonProfile="global-profile"
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));

        // when resolved
        ObjectMapper resolved = RequestBodyProfileResolver.resolveRequestBodyMapper(
                meta, configWith("config-profile"), globalWith("global-profile"), registry);

        // then the jaxrs.jsonProfile id "config-profile" wins
        assertSame(registry.mapper(JsonProfileId.of("config-profile")), resolved, "jaxrs.jsonProfile must win");
    }

    @Test
    @DisplayName("json.jsonProfile applies when no annotation and no jaxrs.jsonProfile")
    void globalDefaultApplies() throws Exception {
        // given no annotation and no jaxrs.jsonProfile, json.jsonProfile="global-profile"
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));

        // when resolved
        ObjectMapper resolved = RequestBodyProfileResolver.resolveRequestBodyMapper(
                meta, configWith(null), globalWith("global-profile"), registry);

        // then the json.jsonProfile id "global-profile" applies (RED: resolver ignores the global tier)
        assertSame(registry.mapper(JsonProfileId.of("global-profile")), resolved, "json.jsonProfile must apply");
    }

    @Test
    @DisplayName("vertique floor: nothing set anywhere resolves the vertique profile's mapper")
    void vertiqueFloor() throws Exception {
        // given nothing set: no annotation, no jaxrs.jsonProfile, no json.jsonProfile
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));

        // when resolved, then the vertique floor resolves that profile's mapper
        assertSame(
                registry.mapper(JsonProfileId.of("vertique")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(
                        meta, configWith(null), JsonConfig.defaults(), registry),
                "the vertique floor must resolve the vertique profile's mapper");
    }

    // --- Tests: the vertique floor and the process-mapper identity sentinel (T011/TP-001) ---

    /**
     * The tail of the precedence is {@code jsonConfig.effectiveProfile()} — the {@code vertique} floor —
     * and the {@code null} "no override" sentinel fires <em>iff</em> the resolved mapper is the same
     * instance as {@link VertiqueJson#mapper()}, never because the resolved id happens to be
     * {@code system}.
     *
     * <p>Row (c) is what distinguishes the identity rule from an id comparison: the effective id is
     * {@code system} while the process codec runs the {@code vertique} profile, so the resolved
     * {@code system} mapper is <em>not</em> the process mapper and must be returned rather than
     * collapsed to {@code null}. An implementation that compared profile ids would return {@code null}
     * there and fail this row.
     *
     * <p>The rows are wrapped in {@code assertAll} so a failing row does not hide the ones after it:
     * the baseline needs every row's outcome from one run, not just the first failure.
     *
     * @throws Exception if the fixture method or resource cannot be reflected
     */
    @Test
    @DisplayName("the floor is vertique and the null sentinel fires only for the process mapper instance")
    void floorIsVertiqueAndSentinelFiresOnlyForTheProcessMapper() throws Exception {
        // given meta without annotations, no jaxrs.jsonProfile, and a seeded registry
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                UnannotatedResource.class,
                "plain",
                methodAnnotations(UnannotatedResource.class, "plain"),
                classAnnotations(UnannotatedResource.class));
        JsonProfileId vertique = JsonProfileId.of("vertique");

        // The three rows run under assertAll so one failing row never hides the next: each row's
        // state (which mapper is installed as the process codec) is set up inside its own executable,
        // in order, and every outcome is reported from a single run.
        assertAll(
                // (a) nothing configured anywhere, the process codec still on its raw delegate: the
                // tail is the vertique floor's mapper, not a null "no override".
                () -> assertSame(
                        registry.mapper(vertique),
                        RequestBodyProfileResolver.resolveRequestBodyMapper(
                                meta, configWith(null), JsonConfig.defaults(), registry),
                        "(a) with nothing configured the tail must be the vertique floor's mapper"),
                // (b) explicit json.jsonProfile=system while the registry's system mapper IS the
                // process codec's mapper: the resolved instance is the process mapper, so the sentinel
                // returns null.
                () -> {
                    VertiqueJson.install(JsonProfileId.SYSTEM, registry.mapper(JsonProfileId.SYSTEM));
                    assertNull(
                            RequestBodyProfileResolver.resolveRequestBodyMapper(
                                    meta, configWith(null), new JsonConfig("system", null), registry),
                            "(b) an explicit system selection whose mapper is the installed process mapper"
                                    + " must be null");
                },
                // (c) explicit json.jsonProfile=system while json.systemProfile=vertique put a
                // DIFFERENT instance in the process codec: the resolved system mapper is not the
                // process mapper, so it is returned.
                () -> {
                    VertiqueJson.resetForTests();
                    VertiqueJson.install(vertique, registry.mapper(vertique));
                    assertSame(
                            registry.mapper(JsonProfileId.SYSTEM),
                            RequestBodyProfileResolver.resolveRequestBodyMapper(
                                    meta, configWith(null), new JsonConfig("system", "vertique"), registry),
                            "(c) an explicit system selection must resolve the registry's system mapper when"
                                    + " the process codec runs another profile");
                });
    }

    // --- Tests: blank @JsonProfile fall-through (harmonized blank-as-absent semantics) ---

    @Test
    @DisplayName("blank method @JsonProfile falls through to a valid class @JsonProfile")
    void blankMethodJsonProfile_fallsThroughToClassProfile() throws Exception {
        // given a method @JsonProfile("") and a class @JsonProfile("class-profile")
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                BlankMethodOverClassResource.class,
                "blankMethod",
                methodAnnotations(BlankMethodOverClassResource.class, "blankMethod"),
                classAnnotations(BlankMethodOverClassResource.class));

        // when resolved
        ObjectMapper resolved = RequestBodyProfileResolver.resolveRequestBodyMapper(
                meta, configWith(null), JsonConfig.defaults(), registry);

        // then the blank method annotation is treated as absent and the class profile applies
        assertSame(
                registry.mapper(JsonProfileId.of("class-profile")),
                resolved,
                "blank method @JsonProfile must fall through to the class profile, not mask it");
    }

    @Test
    @DisplayName("blank @JsonProfile alone falls through to the vertique floor (no IllegalArgumentException)")
    void blankJsonProfileAlone_fallsThroughToVertiqueFloor() throws Exception {
        // given only a blank method @JsonProfile(""), no class annotation, no jaxrs/global default
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                BlankMethodOnlyResource.class,
                "blankMethod",
                methodAnnotations(BlankMethodOnlyResource.class, "blankMethod"),
                classAnnotations(BlankMethodOnlyResource.class));

        // when resolved, then the vertique floor's mapper comes back with no exception thrown
        assertSame(
                registry.mapper(JsonProfileId.of("vertique")),
                RequestBodyProfileResolver.resolveRequestBodyMapper(
                        meta, configWith(null), JsonConfig.defaults(), registry),
                "blank @JsonProfile must fall through to the vertique floor, not crash on JsonProfileId.of(\"\")");
    }

    @Test
    @DisplayName("blank method @JsonProfile falls through to the jaxrs.jsonProfile default")
    void blankMethodJsonProfile_fallsThroughToJaxrsDefault() throws Exception {
        // given a blank method @JsonProfile(""), no class annotation, jaxrs.jsonProfile="config-profile"
        DefaultJsonMapperProfileRegistry registry = registryWithAppProfiles();
        ResourceMethodMeta meta = metaFor(
                BlankMethodOnlyResource.class,
                "blankMethod",
                methodAnnotations(BlankMethodOnlyResource.class, "blankMethod"),
                classAnnotations(BlankMethodOnlyResource.class));

        // when resolved
        ObjectMapper resolved = RequestBodyProfileResolver.resolveRequestBodyMapper(
                meta, configWith("config-profile"), JsonConfig.defaults(), registry);

        // then the blank method annotation is absent and the jaxrs default applies
        assertSame(
                registry.mapper(JsonProfileId.of("config-profile")),
                resolved,
                "blank method @JsonProfile must fall through to the jaxrs.jsonProfile default");
    }
}
