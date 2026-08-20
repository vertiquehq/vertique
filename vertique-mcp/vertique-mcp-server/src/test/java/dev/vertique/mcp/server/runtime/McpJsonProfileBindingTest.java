// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.JsonConfig;
import dev.vertique.mcp.server.McpServerConfig;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-003 — the frozen T002 contract matrix for effective JSON profile binding.
 *
 * <p>Profiles {@code type}, {@code method}, {@code mcp-default}, {@code global-default} and the
 * reserved {@code vertx} are registered (plus {@code strict}, the registered control the sensitivity
 * proof swaps in for the unknown {@code missing} id). Each row composes the resolver once and
 * captures the effective profile or the startup failure.
 *
 * <p>Three rows isolate three boundaries, one each:
 *
 * <ol>
 *   <li>{@link #shouldResolveMethodTypeBoundaryGlobalAndVertxPrecedenceOnceAtComposition()} — the
 *       five-tier precedence method → type → MCP boundary → global → {@code vertx}. The annotation
 *       processor collapses the method-over-type selection into the one nullable declared literal
 *       this resolver receives (TP-001 row {@code shouldResolveMethodJsonProfileOverTypeAndRejectBlankValues}
 *       proves that half at compile time), so the row feeds the exact literal each tier emits.</li>
 *   <li>{@link #shouldFailUnknownAnnotationSelectedProfileBeforeMount()} — an unknown annotation-selected
 *       id fails composition before any mount, and a blank id cannot even be constructed.</li>
 *   <li>{@link #shouldGenerateSchemaWithAndRetainTheSameEffectiveProfileMapper()} — resolution happens
 *       once and the runtime binding retains that exact stable mapper, which is the same instance the
 *       profile-aware schema generator is constructed from.</li>
 * </ol>
 */
@DisplayName("MCP JSON profile binding — T002 contract matrix")
class McpJsonProfileBindingTest {

    // --- Frozen fixture identities ---

    /** The annotation-selected id that names no registered profile. */
    private static final String UNKNOWN_ANNOTATION_PROFILE = "missing";

    // --- Matrix ---

    /**
     * The three named rows of the T002 profile-binding matrix.
     *
     * @return one row per boundary, named exactly as the proof contract lists it
     */
    static Stream<MatrixRow> t002ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldResolveMethodTypeBoundaryGlobalAndVertxPrecedenceOnceAtComposition",
                        McpJsonProfileBindingTest
                                ::shouldResolveMethodTypeBoundaryGlobalAndVertxPrecedenceOnceAtComposition),
                new MatrixRow(
                        "shouldFailUnknownAnnotationSelectedProfileBeforeMount",
                        McpJsonProfileBindingTest::shouldFailUnknownAnnotationSelectedProfileBeforeMount),
                new MatrixRow(
                        "shouldGenerateSchemaWithAndRetainTheSameEffectiveProfileMapper",
                        McpJsonProfileBindingTest::shouldGenerateSchemaWithAndRetainTheSameEffectiveProfileMapper));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t002ContractMatrix")
    @DisplayName("enforces the T002 contract matrix")
    void shouldEnforceT002ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: five-tier precedence ---

    /**
     * Each tier resolves to its exact profile id, and a lower tier never shadows a higher one: a
     * declared id wins over both configured defaults, the MCP boundary default wins over the global
     * one, and the reserved {@code vertx} profile is the tail.
     */
    private static void shouldResolveMethodTypeBoundaryGlobalAndVertxPrecedenceOnceAtComposition() {
        McpJsonProfileResolver bothDefaultsConfigured =
                McpJsonProfileBindingTestFixture.resolver("mcp-default", "global-default");

        assertThat(bothDefaultsConfigured.resolve(JsonProfileId.of("method")).id())
                .as("a method-declared profile wins every configured default")
                .isEqualTo(JsonProfileId.of("method"));
        assertThat(bothDefaultsConfigured.resolve(JsonProfileId.of("type")).id())
                .as("a type-declared profile wins every configured default")
                .isEqualTo(JsonProfileId.of("type"));
        assertThat(bothDefaultsConfigured.resolve(null).id())
                .as("the MCP boundary default wins the global default")
                .isEqualTo(JsonProfileId.of("mcp-default"));

        McpJsonProfileResolver globalDefaultOnly = McpJsonProfileBindingTestFixture.resolver(null, "global-default");
        assertThat(globalDefaultOnly.resolve(null).id())
                .as("the global default applies when the MCP boundary declares none")
                .isEqualTo(JsonProfileId.of("global-default"));

        McpJsonProfileResolver blankBoundaryDefault = McpJsonProfileBindingTestFixture.resolver("  ", "global-default");
        assertThat(blankBoundaryDefault.resolve(null).id())
                .as("a blank MCP boundary default inherits rather than failing")
                .isEqualTo(JsonProfileId.of("global-default"));

        McpJsonProfileResolver noDefaults = McpJsonProfileBindingTestFixture.resolver(null, null);
        assertThat(noDefaults.resolve(null).id())
                .as("the reserved vertx profile is the tail")
                .isEqualTo(JsonProfileId.VERTX);
    }

    // --- Row 2: unknown annotation-selected profile ---

    /**
     * An annotation-selected id that names no registered profile fails composition before mount, and
     * a blank id is rejected by {@link JsonProfileId} before it can reach composition at all.
     *
     * <p>The row counts both the startup errors and the resolved-profile equalities, which is what
     * lets the sensitivity proof swap {@code missing} for the registered {@code strict} and observe
     * the counts move 1 → 0 and 0 → 1.
     */
    private static void shouldFailUnknownAnnotationSelectedProfileBeforeMount() {
        McpJsonProfileResolver resolver = McpJsonProfileBindingTestFixture.resolver("mcp-default", "global-default");

        List<String> startupErrors = new ArrayList<>();
        List<JsonProfileId> resolvedProfiles = new ArrayList<>();
        try {
            resolvedProfiles.add(resolver.resolve(JsonProfileId.of(UNKNOWN_ANNOTATION_PROFILE))
                    .id());
        } catch (JsonProfileConfigurationException failure) {
            startupErrors.add(String.valueOf(failure.getMessage()));
        }

        assertThat(startupErrors)
                .as("the unknown id fails composition exactly once")
                .hasSize(1);
        assertThat(startupErrors.getFirst()).contains(UNKNOWN_ANNOTATION_PROFILE);
        assertThat(resolvedProfiles)
                .as("an unknown id yields no effective profile and therefore no mount")
                .isEmpty();

        assertThatThrownBy(() -> JsonProfileId.of("   "))
                .as("a blank id never reaches composition")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Row 3: one resolution, one retained mapper ---

    /**
     * Resolution happens once per tool and yields the profile's exact stable mapper; the runtime
     * binding built from that resolution — the same binding the profile-aware schema generator is
     * constructed from — normalizes structured content through that identical mapper, and a tool
     * declaring a different profile is bound to a different one.
     */
    private static void shouldGenerateSchemaWithAndRetainTheSameEffectiveProfileMapper() {
        McpJsonProfileResolver resolver = McpJsonProfileBindingTestFixture.resolver("mcp-default", "global-default");

        JsonMapperProfile methodProfile = resolver.resolve(JsonProfileId.of("method"));
        JsonMapperProfile methodProfileAgain = resolver.resolve(JsonProfileId.of("method"));

        assertThat(methodProfile.mapper())
                .as("repeated resolution yields the one stable mapper, never a rebuilt one")
                .isSameAs(methodProfileAgain.mapper());

        McpToolRuntime<Map<String, Object>> methodBinding = McpJsonProfileBindingTestFixture.binding(methodProfile);
        assertThat(methodBinding.normalizeStructuredContent(new ProfileMarker()))
                .as("the retained mapper is the effective profile's mapper")
                .isEqualTo("method");

        JsonMapperProfile typeProfile = resolver.resolve(JsonProfileId.of("type"));
        McpToolRuntime<Map<String, Object>> typeBinding = McpJsonProfileBindingTestFixture.binding(typeProfile);
        assertThat(typeBinding.normalizeStructuredContent(new ProfileMarker()))
                .as("a tool declaring another profile is bound to that profile's mapper")
                .isEqualTo("type");
    }

    // --- Fixtures ---

    /** A value every fixture mapper serializes to its own profile id, revealing which mapper ran. */
    private record ProfileMarker() {}

    /** Framework wiring for the binding proof: registry, configuration, and runtime construction. */
    private static final class McpJsonProfileBindingTestFixture {

        private McpJsonProfileBindingTestFixture() {}

        /** Composes the resolver once against the registered profiles and the two configured tiers. */
        static McpJsonProfileResolver resolver(@Nullable String mcpProfile, @Nullable String globalProfile) {
            McpServerConfig mcpConfig = McpServerConfig.builder()
                    .enabled(true)
                    .jsonProfile(mcpProfile)
                    .build();
            return new McpJsonProfileResolver(new StubProfileRegistry(), new JsonConfig(globalProfile), mcpConfig);
        }

        /** Builds the runtime binding a generated invoker would obtain for the resolved profile. */
        @SuppressWarnings("unchecked")
        static McpToolRuntime<Map<String, Object>> binding(JsonMapperProfile profile) {
            McpToolDescriptor descriptor = new McpToolDescriptor(
                    "weather.lookup",
                    null,
                    "Look up weather",
                    new McpToolAnnotations(true, false, true, false),
                    "{\"type\":\"object\"}",
                    null,
                    new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
            return new McpToolRuntime<>(
                    descriptor, profile.mapper(), (Class<Map<String, Object>>) (Class<?>) Map.class);
        }
    }

    /** The registered profile set of the Given, each with its own marker-serializing mapper. */
    private static final class StubProfileRegistry implements JsonMapperProfileRegistry {

        private final Map<JsonProfileId, JsonMapperProfile> profilesById = new LinkedHashMap<>();

        private StubProfileRegistry() {
            Stream.of("type", "method", "mcp-default", "global-default", "strict", JsonProfileId.VERTX.value())
                    .map(StubProfile::new)
                    .forEach(profile -> profilesById.put(profile.id(), profile));
        }

        @Override
        public ObjectMapper mapper(JsonProfileId id) {
            return profile(id).mapper();
        }

        @Override
        public JsonMapperProfile profile(JsonProfileId id) {
            JsonMapperProfile profile = profilesById.get(id);
            if (profile == null) {
                throw new JsonProfileConfigurationException(
                        "Unknown JSON profile id '" + (id == null ? null : id.value()) + "'");
            }
            return profile;
        }

        @Override
        public Set<JsonProfileId> profileIds() {
            return Set.copyOf(profilesById.keySet());
        }
    }

    /** One registered profile whose mapper serializes {@link ProfileMarker} to the profile id. */
    private static final class StubProfile implements JsonMapperProfile {

        private final JsonProfileId id;
        private final ObjectMapper mapper;

        private StubProfile(String id) {
            this.id = JsonProfileId.of(id);
            SimpleModule marker = new SimpleModule();
            marker.addSerializer(ProfileMarker.class, new ProfileMarkerSerializer(id));
            this.mapper = new ObjectMapper().registerModule(marker);
        }

        @Override
        public JsonProfileId id() {
            return id;
        }

        @Override
        public ObjectMapper mapper() {
            return mapper;
        }
    }

    /** Writes the owning profile's id, so the value reveals which mapper produced it. */
    private static final class ProfileMarkerSerializer extends StdSerializer<ProfileMarker> {

        private final String profileId;

        private ProfileMarkerSerializer(String profileId) {
            super(ProfileMarker.class);
            this.profileId = profileId;
        }

        @Override
        public void serialize(ProfileMarker value, JsonGenerator generator, SerializerProvider provider)
                throws IOException {
            generator.writeString(profileId);
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
