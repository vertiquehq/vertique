// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultJsonMapperProfileRegistry}.
 *
 * <p>Constructs the registry directly with a {@code Set<JsonMapperProfile>} (no Dagger) to verify
 * the constructor-time validation contract: the built-in {@code system} profile is always seeded;
 * application profiles must not override {@code system}; duplicate application ids are rejected; each
 * application-contributed mapper passes a structural round-trip probe; and resolution of an unknown id
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
    @DisplayName("system profile resolves by default with an empty application set")
    void systemProfile_resolvableByDefault() {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        assertNotSame(DatabindCodec.mapper(), registry.mapper(JsonProfileId.SYSTEM));
    }

    @Test
    @DisplayName("unknown id throws JsonProfileConfigurationException whose message lists discovered ids")
    void unknownId_throws_andMessageListsDiscoveredIds() {
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("payments-v1"), vertxAwareMapper());
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(app));

        JsonProfileConfigurationException ex =
                assertThrows(JsonProfileConfigurationException.class, () -> registry.mapper(JsonProfileId.of("nope")));

        assertTrue(ex.getMessage().contains("system"), "message must list the system id");
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
    @DisplayName("application profile with reserved system id fails construction")
    void appOverridingSystem_failsConstruction() {
        JsonMapperProfile bad = JsonMapperProfiles.of(JsonProfileId.SYSTEM, vertxAwareMapper());

        assertThrows(JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(bad)));
    }

    @Test
    @DisplayName("application mapper lacking JsonObject support fails the round-trip probe")
    void applicationMapperLackingJsonObjectSupport_failsProbe() {
        // A plain ObjectMapper has no VertxModule, so a JsonObject does not round-trip structurally.
        JsonMapperProfile bad = JsonMapperProfiles.of(JsonProfileId.of("plain"), new ObjectMapper());

        assertThrows(JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(bad)));
    }

    @Test
    @DisplayName("profileIds includes all three built-ins plus the application id")
    void profileIds_includesSystemPlusApp() {
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("legacy-crm"), vertxAwareMapper());
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(app));

        Set<JsonProfileId> ids = registry.profileIds();

        assertTrue(ids.contains(JsonProfileId.SYSTEM), "profileIds must contain system");
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
     * GH-240 — an application profile built on {@link JacksonDefaults#apply(ObjectMapper)} is
     * accepted.
     *
     * <p>ADR-0135 and the module document both offer that helper as a sanctioned seed for an
     * application profile, while the probe used to round-trip a {@link JsonObject} carrying an
     * explicit null field. {@code apply} sets {@code NON_NULL} inclusion, so the field was dropped
     * and structural equality failed — the registry rejected exactly the seed both documents
     * promise. The probe now carries no null field, because omitting nulls is a
     * serialization-inclusion policy rather than structural corruption.
     */
    @Test
    @DisplayName("application profile built on JacksonDefaults.apply() is accepted by the round-trip probe")
    void appProfileOnJacksonDefaults_acceptedByRoundTripProbe() {
        // Given: an application profile whose mapper is exactly JacksonDefaults.apply(new ObjectMapper()),
        // the seed ADR-0135 and the module document both sanction.
        JsonMapperProfile app =
                JsonMapperProfiles.of(JsonProfileId.of("app-on-defaults"), JacksonDefaults.apply(new ObjectMapper()));

        // When: the registry is constructed (the probe runs eagerly).
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of(app));

        // Then: construction succeeds and the profile resolves.
        assertSame(
                app.mapper(),
                registry.mapper(JsonProfileId.of("app-on-defaults")),
                "a profile seeded from JacksonDefaults.apply must survive the structural round-trip probe");
    }

    /**
     * GH-240 — a mapper that really does corrupt structure is still rejected, so dropping the null
     * field from the payload did not disarm the probe.
     */
    @Test
    @DisplayName("a mapper that loses structure is still rejected by the round-trip probe")
    void structureLosingMapper_stillRejectedByRoundTripProbe() {
        // Given: a mapper that cannot round-trip the probe's array sample.
        ObjectMapper broken = new ObjectMapper();
        SimpleModule sabotage = new SimpleModule();
        sabotage.addDeserializer(JsonArray.class, new JsonDeserializer<>() {
            @Override
            public JsonArray deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                parser.skipChildren();
                return new JsonArray();
            }
        });
        broken.registerModule(sabotage);
        JsonMapperProfile app = JsonMapperProfiles.of(JsonProfileId.of("lossy"), broken);

        // When / then: the probe observes the structural mismatch and rejects construction.
        JsonProfileConfigurationException ex = assertThrows(
                JsonProfileConfigurationException.class, () -> new DefaultJsonMapperProfileRegistry(Set.of(app)));
        assertTrue(
                ex.getMessage().contains("failed the round-trip probe"),
                "a structure-losing profile must still be rejected: " + ex.getMessage());
    }

    /**
     * TP-003 — the registry seeds exactly the reserved trio {@code system}/{@code vertique}/{@code
     * vertique-strict}; {@code system} differs from {@code vertique} only by the opinions {@code
     * applyOpinionated} sets plus comment leniency; {@code system} caches one stable mapper instance;
     * both reserved factories copy the raw Vert.x factory's {@link StreamReadConstraints}; and the
     * registry rejects the retired {@code vertx} id (naming the rename) and any application profile
     * activating Jackson default typing (contracts/json-default-profile.md, "Reserved profiles" and
     * "`JsonMapperProfileRegistry` contract strengthening").
     */
    @Test
    @DisplayName("registry seeds exactly system/vertique/vertique-strict; system is stable, matches raw "
            + "StreamReadConstraints, and differs from vertique only by the opinions; vertx and default "
            + "typing are rejected naming the rename / \"default typing\"")
    void reservedIdsAreSystemVertiqueAndStrict() throws Exception {
        DefaultJsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());

        // --- Then: the three reserved profiles resolve. ---
        assertDoesNotThrow(() -> registry.profile(JsonProfileId.SYSTEM), "the reserved 'system' id must resolve");
        assertDoesNotThrow(
                () -> registry.profile(JsonProfileId.of("vertique")), "the reserved 'vertique' id must resolve");
        assertDoesNotThrow(
                () -> registry.profile(JsonProfileId.of("vertique-strict")),
                "the reserved 'vertique-strict' id must resolve");

        // --- Then: profile(JsonProfileId.of("vertx")) throws, naming the rename. ---
        JsonProfileConfigurationException vertxEx = assertThrows(
                JsonProfileConfigurationException.class,
                () -> registry.profile(JsonProfileId.of("vertx")),
                "the retired 'vertx' id must no longer resolve");
        assertTrue(
                vertxEx.getMessage().contains("vertx") && vertxEx.getMessage().contains("system"),
                "the vertx rejection must name the rename (mention both 'vertx' and 'system'): "
                        + vertxEx.getMessage());

        // --- Then: registry.profile(SYSTEM).mapper() is the same instance across calls, and is NOT
        // DatabindCodec.mapper() itself (the recipe is a copy, never the shared instance). ---
        ObjectMapper systemMapperFirst = registry.profile(JsonProfileId.SYSTEM).mapper();
        ObjectMapper systemMapperSecond = registry.profile(JsonProfileId.SYSTEM).mapper();
        assertSame(
                systemMapperFirst,
                systemMapperSecond,
                "the 'system' profile must cache and return the same mapper instance on each call");
        assertNotSame(
                DatabindCodec.mapper(),
                systemMapperFirst,
                "the 'system' mapper must be a copy of DatabindCodec.mapper(), never the shared instance");

        // --- Then: both factories' StreamReadConstraints match the raw Vert.x factory's, field by
        // field (StreamReadConstraints has no equals()). ---
        ObjectMapper vertiqueMapper = registry.mapper(JsonProfileId.of("vertique"));
        StreamReadConstraints rawConstraints =
                DatabindCodec.mapper().getFactory().streamReadConstraints();
        assertStreamReadConstraintsMatch(
                rawConstraints, systemMapperFirst.getFactory().streamReadConstraints(), "system");
        assertStreamReadConstraintsMatch(
                rawConstraints, vertiqueMapper.getFactory().streamReadConstraints(), "vertique");

        // --- Then: system has Jdk8Module, JavaTimeModule, and VertxModule registered. Module type ids
        // may be Class or String depending on the module, so compare by toString(). ---
        Set<String> systemModuleIds = systemMapperFirst.getRegisteredModuleIds().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertTrue(
                systemModuleIds.stream().anyMatch(id -> id.contains("Jdk8Module")),
                "the 'system' mapper must have Jdk8Module registered: " + systemModuleIds);
        assertTrue(
                systemModuleIds.stream().anyMatch(id -> id.contains("jsr310") || id.contains("JavaTimeModule")),
                "the 'system' mapper must have JavaTimeModule registered: " + systemModuleIds);
        assertTrue(
                systemModuleIds.stream().anyMatch(id -> id.contains("VertxModule")),
                "the 'system' mapper must have VertxModule registered: " + systemModuleIds);

        // --- Then: the fresh-seeded vertique mapper ALSO records the VertxModule id (Jackson records a
        // module id only when dedup is enabled at registration); the process-codec install guard reads
        // getRegisteredModuleIds(), so the sanctioned recipe must leave the id visible. ---
        Set<String> vertiqueModuleIds = vertiqueMapper.getRegisteredModuleIds().stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
        assertTrue(
                vertiqueModuleIds.stream().anyMatch(id -> id.contains("VertxModule")),
                "the 'vertique' mapper (fresh seed) must record the VertxModule id: " + vertiqueModuleIds);

        // --- Then: comparing system and vertique, exactly the features applyOpinionated sets
        // (inclusion, USE_BIG_DECIMAL_FOR_FLOATS, READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE) plus
        // ALLOW_COMMENTS (on for system, off for vertique) differ. ---
        assertEquals(
                JsonInclude.Include.USE_DEFAULTS,
                systemMapperFirst
                        .getSerializationConfig()
                        .getDefaultPropertyInclusion()
                        .getValueInclusion(),
                "'system' must carry no inclusion opinion (USE_DEFAULTS, as raw does), unlike 'vertique' (NON_NULL)");
        assertEquals(
                JsonInclude.Include.NON_NULL,
                vertiqueMapper
                        .getSerializationConfig()
                        .getDefaultPropertyInclusion()
                        .getValueInclusion(),
                "'vertique' must keep the NON_NULL inclusion opinion");
        assertFalse(
                systemMapperFirst.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                "'system' must not enable USE_BIG_DECIMAL_FOR_FLOATS (that is a vertique-only opinion)");
        assertTrue(
                vertiqueMapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                "'vertique' must enable USE_BIG_DECIMAL_FOR_FLOATS");
        assertFalse(
                systemMapperFirst.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE),
                "'system' must not enable READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE");
        assertTrue(
                vertiqueMapper.isEnabled(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE),
                "'vertique' must enable READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE");
        assertTrue(
                systemMapperFirst.getFactory().isEnabled(JsonParser.Feature.ALLOW_COMMENTS),
                "'system' must inherit ALLOW_COMMENTS from the raw Vert.x factory (copy() preserves it)");
        assertFalse(
                vertiqueMapper.getFactory().isEnabled(JsonParser.Feature.ALLOW_COMMENTS),
                "'vertique' must NOT accept comments (seeded from a fresh ObjectMapper, not from system's copy)");

        // --- Then: registering an application profile under the retired id 'vertx' fails,
        // naming the rename. ---
        JsonMapperProfile vertxApp = JsonMapperProfiles.of(JsonProfileId.of("vertx"), vertxAwareMapper());
        JsonProfileConfigurationException vertxAppEx = assertThrows(
                JsonProfileConfigurationException.class,
                () -> new DefaultJsonMapperProfileRegistry(Set.of(vertxApp)),
                "an application profile must not be able to re-register the retired 'vertx' id");
        assertTrue(
                vertxAppEx.getMessage().contains("vertx")
                        && vertxAppEx.getMessage().contains("system"),
                "re-registering 'vertx' must fail naming the rename: " + vertxAppEx.getMessage());

        // --- Then: registering an application profile whose mapper has Jackson default typing active
        // fails, naming "default typing". ---
        ObjectMapper defaultTypedMapper = vertxAwareMapper()
                .activateDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .build());
        JsonMapperProfile defaultTypedApp = JsonMapperProfiles.of(JsonProfileId.of("typed-app"), defaultTypedMapper);
        JsonProfileConfigurationException typingEx = assertThrows(
                JsonProfileConfigurationException.class,
                () -> new DefaultJsonMapperProfileRegistry(Set.of(defaultTypedApp)),
                "an application profile activating Jackson default typing must be rejected at construction");
        assertTrue(
                typingEx.getMessage().contains("default typing"),
                "the default-typing rejection must name \"default typing\": " + typingEx.getMessage());
    }

    /**
     * Compares two {@link StreamReadConstraints} field-by-field ({@code StreamReadConstraints} does
     * not implement {@code equals()}).
     *
     * @param expected the constraints to compare against (the raw Vert.x factory's)
     * @param actual the constraints under test
     * @param label identifies which mapper is under test, for failure messages
     */
    private static void assertStreamReadConstraintsMatch(
            StreamReadConstraints expected, StreamReadConstraints actual, String label) {
        assertEquals(
                expected.getMaxNestingDepth(),
                actual.getMaxNestingDepth(),
                label + ": maxNestingDepth must match the raw Vert.x factory's");
        assertEquals(
                expected.getMaxNumberLength(),
                actual.getMaxNumberLength(),
                label + ": maxNumberLength must match the raw Vert.x factory's");
        assertEquals(
                expected.getMaxStringLength(),
                actual.getMaxStringLength(),
                label + ": maxStringLength must match the raw Vert.x factory's");
        assertEquals(
                expected.getMaxNameLength(),
                actual.getMaxNameLength(),
                label + ": maxNameLength must match the raw Vert.x factory's");
        assertEquals(
                expected.getMaxDocumentLength(),
                actual.getMaxDocumentLength(),
                label + ": maxDocumentLength must match the raw Vert.x factory's");
        assertEquals(
                expected.getMaxTokenCount(),
                actual.getMaxTokenCount(),
                label + ": maxTokenCount must match the raw Vert.x factory's");
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
