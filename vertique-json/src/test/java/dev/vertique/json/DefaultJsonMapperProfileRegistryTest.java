// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>Constructs the registry directly with a {@code Set<JsonMapperProfile>} (no Dagger) to verify
 * the constructor-time validation contract: the built-in {@code vertx} profile is always seeded;
 * application profiles must not override {@code vertx}; duplicate application ids are rejected; each
 * non-{@code vertx} mapper passes a structural round-trip probe; and resolution of an unknown id
 * fails with a message listing the discovered ids. Also includes a characterization test pinning the
 * probe's current behavior for an application profile built on {@link JacksonDefaults#apply(ObjectMapper)}.
 */
class DefaultJsonMapperProfileRegistryTest {

    /**
     * Builds an {@link ObjectMapper} that supports the Vert.x JSON types, suitable for an
     * application profile whose mapper must pass the round-trip probe.
     *
     * @return a mapper with {@link VertxJsonSupport#module()} registered
     */
    private static ObjectMapper vertxAwareMapper() {
        return new ObjectMapper().registerModule(VertxJsonSupport.module());
    }

    @Test
    @DisplayName("vertx profile resolves by default with an empty application set")
    void vertxProfile_resolvableByDefault() {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        assertSame(DatabindCodec.mapper(), registry.mapper(JsonProfileId.VERTX));
    }

    @Test
    @DisplayName("unknown id throws JsonProfileConfigurationException whose message lists discovered ids")
    void unknownId_throws_andMessageListsDiscoveredIds() {
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("payments-v1"), vertxAwareMapper());
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(app));

        JsonProfileConfigurationException ex =
                assertThrows(JsonProfileConfigurationException.class, () -> registry.mapper(JsonProfileId.of("nope")));

        assertTrue(ex.getMessage().contains("vertx"), "message must list the vertx id");
        assertTrue(ex.getMessage().contains("payments-v1"), "message must list the app id");
        assertTrue(ex.getMessage().contains("nope"), "message must name the unknown id");
    }

    @Test
    @DisplayName("application profile resolves and round-trips a JsonObject through its mapper")
    void appProfile_resolvable_andRoundTrips() throws Exception {
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("payments-v1"), vertxAwareMapper());
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(app));

        ObjectMapper resolved = registry.mapper(JsonProfileId.of("payments-v1"));
        JsonObject value = new JsonObject().put("k", "v").put("n", 7);
        JsonObject roundTripped = resolved.readValue(resolved.writeValueAsString(value), JsonObject.class);

        assertEquals(value, roundTripped);
    }

    @Test
    @DisplayName("duplicate application profile ids fail construction")
    void duplicateAppIds_failConstruction() {
        JsonMapperProfile a = JsonMapperProfiles.of(JsonProfileId.of("dup"), vertxAwareMapper());
        JsonMapperProfile b = JsonMapperProfiles.of(JsonProfileId.of("dup"), vertxAwareMapper());

        assertThrows(
                JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(orderedSet(a, b)));
    }

    @Test
    @DisplayName("application profile with reserved vertx id fails construction")
    void appOverridingVertx_failsConstruction() {
        JsonMapperProfile bad = JsonMapperProfiles.of(JsonProfileId.VERTX, vertxAwareMapper());

        assertThrows(JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(bad)));
    }

    @Test
    @DisplayName("non-vertx mapper lacking JsonObject support fails the round-trip probe")
    void nonVertxMapperLackingJsonObjectSupport_failsProbe() {
        // A plain ObjectMapper has no VertxModule, so a JsonObject does not round-trip structurally.
        JsonMapperProfile bad = JsonMapperProfiles.of(JsonProfileId.of("plain"), new ObjectMapper());

        assertThrows(JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(bad)));
    }

    @Test
    @DisplayName("profileIds includes all three built-ins plus the application id")
    void profileIds_includesVertxPlusApp() {
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("legacy-crm"), vertxAwareMapper());
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(app));

        Set<JsonProfileId> ids = registry.profileIds();

        assertTrue(ids.contains(JsonProfileId.VERTX), "profileIds must contain vertx");
        assertTrue(ids.contains(JsonProfileId.of("vertique")), "profileIds must contain vertique");
        assertTrue(ids.contains(JsonProfileId.of("vertique-strict")), "profileIds must contain vertique-strict");
        assertTrue(ids.contains(JsonProfileId.of("legacy-crm")), "profileIds must contain the app id");
    }

    @Test
    @DisplayName("application profile with reserved vertique-strict id fails construction")
    void reservedIdVertiqueStrict_rejectedForAppProfiles() {
        // Given: an application profile claiming the reserved vertique-strict id, backed by a
        // vertx-aware mapper so any failure is unambiguously the reservation guard, not a probe
        // failure.
        JsonMapperProfile bad = JsonMapperProfiles.of(JsonProfileId.of("vertique-strict"), vertxAwareMapper());

        // When/Then: constructing the registry rejects the reserved-id override.
        assertThrows(JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(bad)));
    }

    @Test
    @DisplayName("probe failure caused by a Jackson exception chains the original cause on the thrown exception")
    void probeFailing_jacksonException_causeIsChained() {
        // Given: a profile whose mapper throws a JsonProcessingException during writeValueAsString,
        // simulating a misconfigured serializer that cannot serialize JsonObject.
        JsonMapperProfile bad = JsonMapperProfiles.of(JsonProfileId.of("broken"), new ThrowingObjectMapper());

        // When: the registry is constructed (probe runs eagerly).
        JsonProfileConfigurationException ex = assertThrows(
                JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(bad)));

        // Then: the thrown exception must chain the original Jackson cause so the operator can
        // diagnose the misconfiguration from the stack trace.
        assertNotNull(ex.getCause(), "getCause() must not be null — the Jackson cause must be chained");
        assertInstanceOf(
                JsonProcessingException.class,
                ex.getCause(),
                "getCause() must be the original JsonProcessingException thrown by the mapper");
    }

    /**
     * Characterization: pins current probe behavior for NON_NULL app profiles — see ADR-0135
     * contradiction, routed as follow-up issue (json-004 plan §11.1).
     *
     * <p>ADR-0135 suggests application profiles may build on {@link JacksonDefaults#apply(ObjectMapper)},
     * but that helper sets {@code NON_NULL} serialization inclusion, and the round-trip probe below
     * exercises a {@link JsonObject} sample carrying a null-valued field
     * ({@code new JsonObject().putNull("nullField")}). {@code NON_NULL} drops that field on
     * serialization, so the decoded structure no longer equals the original sample and the probe
     * rejects construction — nobody had exercised this combination before this test.
     */
    @Test
    @DisplayName(
            "application profile built on JacksonDefaults.apply() is rejected by the round-trip probe (NON_NULL drops the null field)")
    void appProfileOnJacksonDefaults_rejectedByRoundTripProbe() {
        // Given: an application profile whose mapper is exactly JacksonDefaults.apply(new ObjectMapper()),
        // as ADR-0135 suggests application profiles may build on the vertique defaults.
        JsonMapperProfile app =
                JsonMapperProfiles.of(JsonProfileId.of("app-on-defaults"), JacksonDefaults.apply(new ObjectMapper()));

        // When: the registry is constructed (the probe runs eagerly).
        JsonProfileConfigurationException ex = assertThrows(
                JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(app)));

        // Then: NON_NULL inclusion drops the probe's null-valued field, so the round-trip probe
        // observes a structural mismatch and rejects construction.
        assertTrue(
                ex.getMessage().contains("failed the round-trip probe"),
                "a NON_NULL app profile must be rejected by the structural round-trip probe: " + ex.getMessage());
    }

    // --- Test helpers ---

    /**
     * Builds an insertion-ordered set of profiles. {@code Set.of(...)} rejects duplicate
     * {@code .equals} elements, but distinct {@link JsonMapperProfile} instances sharing an id are
     * not equal, so a plain {@link java.util.LinkedHashSet} preserves both for the duplicate-id test.
     *
     * @param profiles the profiles to include, in order
     * @return a mutable, insertion-ordered set containing every supplied profile
     */
    private static Set<JsonMapperProfile> orderedSet(JsonMapperProfile... profiles) {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(profiles));
    }

    /**
     * An {@link ObjectMapper} stub that throws a {@link JsonProcessingException} from
     * {@link #writeValueAsString(Object)} unconditionally, simulating a misconfigured serializer
     * that cannot serialize the probe's {@link JsonObject} sample.
     */
    private static final class ThrowingObjectMapper extends ObjectMapper {

        @Override
        public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonGenerationException(
                    "simulated serialization failure in probe test", (com.fasterxml.jackson.core.JsonGenerator) null);
        }
    }
}
