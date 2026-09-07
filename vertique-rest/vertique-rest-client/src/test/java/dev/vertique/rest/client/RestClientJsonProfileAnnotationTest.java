// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import io.vertx.core.Vertx;
import jakarta.ws.rs.GET;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RestClientBuilder} resolves the interface-level {@link JsonProfile} annotation as
 * the sole per-binding selection surface for a REST client (FR-JSON-060/061/066, Phase-3 contract).
 *
 * <p>After S8 removed the legacy {@code @RestClient.jsonProfile} attribute, {@code @JsonProfile} on
 * the interface TYPE is the only annotation surface. These tests verify:
 *
 * <ul>
 *   <li>Level 4 of {@code resolveEffectiveProfileId} reads interface-level {@link JsonProfile};</li>
 *   <li>the FR-JSON-066 method-level reject — a {@code @JsonProfile} on a method of a
 *       {@code @RestClient} interface must fail the client build, including when the builder was
 *       supplied an explicit {@link RestClientBuilder#objectMapper(ObjectMapper)} (the guard must run
 *       before the explicit-mapper early-return at {@code resolveEffectiveMapper});</li>
 *   <li>the legacy {@code @RestClient.jsonProfile()} attribute method no longer exists on the
 *       annotation (proves the breaking removal, not merely an unreachable code path).</li>
 * </ul>
 *
 * <p>Resolution of a profile id is observed exactly as {@code RestClientMapperPrecedenceTest} does
 * it: a registry maps each {@link JsonProfileId} to a distinct {@link ObjectMapper} instance, and the
 * test asserts the resolved mapper is reference-identical to the expected profile's mapper.
 * Build-fails cases drive the public {@link RestClientBuilder#build(Class)} path and assert the
 * thrown {@link IllegalStateException}.
 */
@DisplayName("RestClient @JsonProfile annotation reading")
class RestClientJsonProfileAnnotationTest {

    // --- Shared Vert.x instance ---

    static Vertx vertx;

    // --- Distinct profile mappers (so assertSame can prove identity) ---

    static ObjectMapper mapperPaymentsV2;

    /** Profile id named by the interface-level {@code @JsonProfile} fixtures. */
    static final JsonProfileId ID_PAYMENTS_V2 = JsonProfileId.of("payments-v2");

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
        mapperPaymentsV2 = newVertxAwareMapper();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // --- Helpers ---

    /**
     * Creates a fresh {@link ObjectMapper} with Vert.x JSON-type support so it passes the
     * {@link DefaultJsonMapperProfileRegistry} round-trip probe.
     *
     * @return a new probe-passing mapper instance
     */
    private static ObjectMapper newVertxAwareMapper() {
        return new ObjectMapper().registerModule(VertxJsonSupport.module());
    }

    /**
     * Builds a registry resolving every profile id this test references to its distinct mapper.
     *
     * @return a registry resolving {@code payments-v2} plus the reserved {@code system},
     *     {@code vertique}, and {@code vertique-strict} profiles
     */
    private static JsonMapperProfileRegistry registry() {
        return new DefaultJsonMapperProfileRegistry(Set.of(JsonMapperProfiles.of(ID_PAYMENTS_V2, mapperPaymentsV2)));
    }

    // --- Fixture client interfaces ---

    /** Interface-level {@code @JsonProfile} only — the sole selection surface. */
    @RestClient(name = "payments", value = "http://payments")
    @JsonProfile("payments-v2")
    interface JsonProfileOnlyClient {
        /** Shape only. */
        @GET
        io.vertx.core.Future<String> get();
    }

    /** Method-level {@code @JsonProfile} on a {@code @RestClient} interface (FR-066 reject). */
    @RestClient(name = "method-level", value = "http://method-level")
    interface MethodLevelJsonProfileClient {
        /** Method carries a {@code @JsonProfile} — TYPE-level placement is required, so this fails. */
        @GET
        @JsonProfile("v2")
        io.vertx.core.Future<String> get();
    }

    // --- 1. interface @JsonProfile read at Level 4 ---

    @Test
    @DisplayName("resolveEffectiveProfileId_readsInterfaceJsonProfile — @JsonProfile(\"payments-v2\") resolves")
    void resolveEffectiveProfileId_readsInterfaceJsonProfile() {
        // GIVEN an interface with only @JsonProfile("payments-v2") and a seeded registry.
        RestClientBuilder builder = RestClientBuilder.create(vertx).jsonMapperProfileRegistry(registry());

        // WHEN the builder resolves the effective mapper (the observable proxy for the resolved id).
        ObjectMapper resolved = builder.resolveEffectiveMapper(null, JsonProfileOnlyClient.class, "payments");

        // THEN the payments-v2 profile mapper is selected.
        assertSame(
                mapperPaymentsV2,
                resolved,
                "interface-level @JsonProfile(\"payments-v2\") must resolve to the payments-v2 mapper");
    }

    // --- 2. method-level @JsonProfile fails the build (FR-066) ---

    @Test
    @DisplayName("methodLevelJsonProfileOnRestClientInterface_failsClientBuild — names method + TYPE-level rule")
    void methodLevelJsonProfileOnRestClientInterface_failsClientBuild() {
        // GIVEN a @RestClient interface with @JsonProfile on a METHOD (not the TYPE).
        RestClientBuilder builder = RestClientBuilder.create(vertx).jsonMapperProfileRegistry(registry());

        // WHEN building, THEN an IllegalStateException naming the method and requiring TYPE placement.
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> builder.build(MethodLevelJsonProfileClient.class));

        String message = ex.getMessage();
        assertTrue(message.contains("get"), "message should name the offending method 'get': " + message);
        assertTrue(
                message.toUpperCase().contains("TYPE"),
                "message should state TYPE-level placement is required: " + message);
    }

    // --- 3. method-level @JsonProfile fails even with an explicit objectMapper (FR-066 not bypassed) ---

    @Test
    @DisplayName("methodLevelJsonProfileOnRestClientInterface_failsEvenWithExplicitMapper — guard not bypassed")
    void methodLevelJsonProfileOnRestClientInterface_failsEvenWithExplicitMapper() {
        // GIVEN a method-level @JsonProfile AND an explicit objectMapper on the builder. The explicit
        // mapper short-circuits resolveEffectiveMapper at its early-return, so siting the FR-066 guard
        // only inside resolveEffectiveProfileId would let this build slip through. The guard must run
        // BEFORE the early-return.
        ObjectMapper explicit = newVertxAwareMapper();
        RestClientBuilder builder = RestClientBuilder.create(vertx).objectMapper(explicit);

        // WHEN building, THEN an IllegalStateException is STILL thrown despite the explicit mapper.
        assertThrows(
                IllegalStateException.class,
                () -> builder.build(MethodLevelJsonProfileClient.class),
                "the FR-066 method-level guard must run before the explicit-mapper early-return");
    }

    // --- 4. removal-negative: the legacy attribute method is gone (FR-JSON-061 contract) ---

    @Test
    @DisplayName("restClientJsonProfileAttributeMethod_noLongerExists — @RestClient.jsonProfile() removed")
    void restClientJsonProfileAttributeMethod_noLongerExists() {
        // GIVEN the @RestClient annotation type after S8 removed its jsonProfile() attribute.
        // WHEN reflecting for the attribute method, THEN NoSuchMethodException proves it is gone.
        assertThrows(
                NoSuchMethodException.class,
                () -> RestClient.class.getDeclaredMethod("jsonProfile"),
                "the @RestClient.jsonProfile() attribute method must be removed, not merely unreachable");
    }
}
