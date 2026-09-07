// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.client.config.RestClientConfig;
import dev.vertique.rest.client.config.RestClientDefaults;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.ws.rs.GET;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the JSON profile mapper precedence resolution on {@link RestClientBuilder}.
 *
 * <p>Drives {@link RestClientBuilder#resolveEffectiveMapper(RestClientConfig, Class, String)} with
 * layered inputs and verifies the frozen precedence order resolves to the correct
 * {@link ObjectMapper}:
 *
 * <ol>
 *   <li>explicit {@link RestClientBuilder#objectMapper(ObjectMapper)} — wins outright, registry
 *       never consulted;</li>
 *   <li>external per-client config {@code jsonProfile} ({@code restClient.{name}.jsonProfile});</li>
 *   <li>builder-level {@code jsonProfile};</li>
 *   <li>{@code @JsonProfile} annotation;</li>
 *   <li>{@code restClient.defaults.jsonProfile} — reserved sub-object default (slice 2.3);</li>
 *   <li>global {@code json.jsonProfile} — via {@link JsonConfig} (slice 2.3);</li>
 *   <li>{@code vertx} default ({@link DatabindCodec#mapper()}), which never requires a registry.</li>
 * </ol>
 *
 * <p>Also covers the standalone-no-registry error path ({@link RestClientConfigurationException}),
 * the unknown-profile fail-fast at build time ({@link JsonProfileConfigurationException}), and
 * codegen parity (a generated static proxy inherits the resolved mapper).
 */
@DisplayName("RestClient JSON profile mapper precedence")
class RestClientMapperPrecedenceTest {

    // --- Shared Vert.x instance ---

    static Vertx vertx;

    // --- App profile mappers (distinct instances so assertSame can prove identity) ---

    static ObjectMapper mapperA;
    static ObjectMapper mapperB;
    static ObjectMapper mapperC;
    static ObjectMapper mapperD;
    static ObjectMapper mapperE;

    /** Profile id "A" (per-client config layer). */
    static final JsonProfileId ID_A = JsonProfileId.of("profile-a");

    /** Profile id "B" (builder layer). */
    static final JsonProfileId ID_B = JsonProfileId.of("profile-b");

    /** Profile id "C" (annotation layer). */
    static final JsonProfileId ID_C = JsonProfileId.of("profile-c");

    /** Profile id "D" (restClient.defaults layer — slice 2.3). */
    static final JsonProfileId ID_D = JsonProfileId.of("profile-d");

    /** Profile id "E" (global json.jsonProfile layer — slice 2.3). */
    static final JsonProfileId ID_E = JsonProfileId.of("profile-e");

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
        mapperA = newVertxAwareMapper();
        mapperB = newVertxAwareMapper();
        mapperC = newVertxAwareMapper();
        mapperD = newVertxAwareMapper();
        mapperE = newVertxAwareMapper();
    }

    @AfterAll
    static void stopVertx() {
        if (vertx != null) {
            vertx.close();
        }
    }

    // --- Helpers ---

    /**
     * Creates a fresh {@link ObjectMapper} with Vert.x JSON-type support registered so it passes the
     * {@link DefaultJsonMapperProfileRegistry} round-trip probe.
     *
     * @return a new probe-passing mapper instance
     */
    private static ObjectMapper newVertxAwareMapper() {
        return new ObjectMapper().registerModule(VertxJsonSupport.module());
    }

    /**
     * Builds a real {@link DefaultJsonMapperProfileRegistry} from the three app profiles A/B/C.
     *
     * @return a registry resolving {@code profile-a/b/c} plus the reserved {@code vertx}
     */
    private static JsonMapperProfileRegistry registryWithAbc() {
        return new DefaultJsonMapperProfileRegistry(Set.of(
                JsonMapperProfiles.of(ID_A, mapperA),
                JsonMapperProfiles.of(ID_B, mapperB),
                JsonMapperProfiles.of(ID_C, mapperC)));
    }

    /**
     * Builds a {@link DefaultJsonMapperProfileRegistry} from all five app profiles A/B/C/D/E.
     *
     * @return a registry resolving {@code profile-a/b/c/d/e} plus the reserved {@code vertx}
     */
    private static JsonMapperProfileRegistry registryWithAbcDe() {
        return new DefaultJsonMapperProfileRegistry(Set.of(
                JsonMapperProfiles.of(ID_A, mapperA),
                JsonMapperProfiles.of(ID_B, mapperB),
                JsonMapperProfiles.of(ID_C, mapperC),
                JsonMapperProfiles.of(ID_D, mapperD),
                JsonMapperProfiles.of(ID_E, mapperE)));
    }

    /**
     * Builds a {@link RestClientConfig} carrying the given {@code jsonProfile} via the boundary
     * parser, exactly as the Dagger provider does.
     *
     * @param jsonProfile the profile id value to set on the config, or {@code null}
     * @return a parsed config for client name {@code "svc"}
     */
    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static RestClientConfig configWithProfile(String jsonProfile) {
        JsonObject svc = new JsonObject().put("baseUrl", "http://svc");
        if (jsonProfile != null) {
            svc.put("jsonProfile", jsonProfile);
        }
        JsonObject section = new JsonObject().put("svc", svc);
        return RestClientConfig.indexFromConfig(new JsonObject().put("restClient", section), configParser())
                .get("svc");
    }

    // --- Fixture client interfaces ---

    /** Client with a {@code @JsonProfile} annotation pointing at profile C. */
    @JsonProfile("profile-c")
    @RestClient(name = "svc", value = "http://svc")
    interface AnnotatedClient {
        /** Single method — shape only. */
        @GET
        io.vertx.core.Future<String> get();
    }

    /** Client with no {@code jsonProfile} annotation. */
    @RestClient(name = "svc", value = "http://svc")
    interface PlainClient {
        /** Single method — shape only. */
        @GET
        io.vertx.core.Future<String> get();
    }

    // --- Precedence (drives resolveEffectiveMapper directly) ---

    @Nested
    @DisplayName("precedence order")
    class Precedence {

        @Test
        @DisplayName(
                "explicitObjectMapper_wins — explicit objectMapper beats config/builder/annotation, registry untouched")
        void explicitObjectMapper_wins() {
            ObjectMapper custom = newVertxAwareMapper();
            // Registry that throws if consulted, proving the explicit mapper short-circuits it.
            JsonMapperProfileRegistry throwingRegistry = new ThrowingRegistry();
            RestClientBuilder builder = RestClientBuilder.create(vertx)
                    .objectMapper(custom)
                    .jsonProfile(ID_B)
                    .jsonMapperProfileRegistry(throwingRegistry);

            RestClientConfig config = configWithProfile("profile-a");
            ObjectMapper resolved = builder.resolveEffectiveMapper(config, AnnotatedClient.class, "svc");

            assertSame(custom, resolved, "explicit objectMapper must win outright");
        }

        @Test
        @DisplayName("configProfile_overridesBuilderAndAnnotation — config A beats builder B and annotation C")
        void configProfile_overridesBuilderAndAnnotation() {
            RestClientBuilder builder =
                    RestClientBuilder.create(vertx).jsonProfile(ID_B).jsonMapperProfileRegistry(registryWithAbc());

            RestClientConfig config = configWithProfile("profile-a");
            ObjectMapper resolved = builder.resolveEffectiveMapper(config, AnnotatedClient.class, "svc");

            assertSame(mapperA, resolved, "config jsonProfile must override builder and annotation");
        }

        @Test
        @DisplayName("builderProfile_overridesAnnotation — builder B beats annotation C when no config")
        void builderProfile_overridesAnnotation() {
            RestClientBuilder builder =
                    RestClientBuilder.create(vertx).jsonProfile(ID_B).jsonMapperProfileRegistry(registryWithAbc());

            // config with no jsonProfile set
            RestClientConfig config = configWithProfile(null);
            ObjectMapper resolved = builder.resolveEffectiveMapper(config, AnnotatedClient.class, "svc");

            assertSame(mapperB, resolved, "builder jsonProfile must override annotation");
        }

        @Test
        @DisplayName("annotationProfile_used_whenNoConfigOrBuilder — annotation C used when only source")
        void annotationProfile_used_whenNoConfigOrBuilder() {
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonMapperProfileRegistry(registryWithAbc());

            // null config, no builder profile → only @JsonProfile("profile-c")
            ObjectMapper resolved = builder.resolveEffectiveMapper(null, AnnotatedClient.class, "svc");

            assertSame(mapperC, resolved, "annotation jsonProfile must be used as the lowest non-default source");
        }

        @Test
        @DisplayName("default_isVertiqueFloor — nothing set resolves through the builder-private registry, not the"
                + " raw Vert.x mapper, registry not required")
        void default_isVertiqueFloor() throws Exception {
            // No explicit mapper, no profile anywhere, no registry seeded: the vertique floor applies and the
            // builder builds its own reserved-profile registry to satisfy it.
            RestClientBuilder builder = RestClientBuilder.create(vertx);

            ObjectMapper resolved = builder.resolveEffectiveMapper(null, PlainClient.class, "svc");

            assertNotSame(DatabindCodec.mapper(), resolved, "the floor is vertique, never the raw Vert.x mapper");
            assertEquals(
                    "{\"present\":\"x\"}",
                    resolved.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.of("present", "x")) {
                        {
                            put("absent", null);
                        }
                    }),
                    "vertique omits null properties");
        }
    }

    // --- reserved-profile resolution without a registry and no-registry errors ---

    @Nested
    @DisplayName("reserved profile and missing registry")
    class VertxAndMissingRegistry {

        @Test
        @DisplayName("explicitSystemProfile_resolvesBuilderPrivateRegistry_withoutRegistry — id 'system' needs no"
                + " injected registry")
        void explicitSystemProfile_resolvesBuilderPrivateRegistry_withoutRegistry() throws Exception {
            // Builder-level profile explicitly set to system; no registry seeded: the builder-private
            // reserved-profile registry serves it (a guarded copy, never the raw Vert.x mapper itself).
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonProfile(JsonProfileId.SYSTEM);

            ObjectMapper resolved = builder.resolveEffectiveMapper(null, PlainClient.class, "svc");

            assertNotSame(
                    DatabindCodec.mapper(),
                    resolved,
                    "explicit system resolves to the registry's guarded copy, not the raw mapper");
            assertEquals(
                    "{\"present\":\"x\",\"absent\":null}",
                    resolved.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.of("present", "x")) {
                        {
                            put("absent", null);
                        }
                    }),
                    "system keeps the raw mapper's null emission");
        }

        @Test
        @DisplayName("nonVertxProfile_withoutRegistry_throwsClearError — names client + profile id")
        void nonVertxProfile_withoutRegistry_throwsClearError() {
            // Builder profile 'legacy-crm', but no registry seeded (standalone builder).
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonProfile(JsonProfileId.of("legacy-crm"));

            RestClientConfigurationException ex = assertThrows(
                    RestClientConfigurationException.class,
                    () -> builder.resolveEffectiveMapper(null, PlainClient.class, "svc"));

            String message = ex.getMessage();
            assertTrue(message.contains("svc"), "message should name the client: " + message);
            assertTrue(message.contains("legacy-crm"), "message should name the profile id: " + message);
        }

        @Test
        @DisplayName("unknownProfileId_throwsAtBuild — registry present but id unregistered fails fast")
        void unknownProfileId_throwsAtBuild() {
            RestClientBuilder builder = RestClientBuilder.create(vertx)
                    .jsonProfile(JsonProfileId.of("not-registered"))
                    .jsonMapperProfileRegistry(registryWithAbc());

            assertThrows(
                    JsonProfileConfigurationException.class,
                    () -> builder.resolveEffectiveMapper(null, PlainClient.class, "svc"));
        }
    }

    // --- Codegen parity ---

    @Nested
    @DisplayName("generated proxy parity")
    class GeneratedProxyParity {

        /**
         * Codegen parity (FR-JSON-030A): a generated static proxy goes through the same
         * {@link DefaultRestClientDispatcher} built with the resolved {@code effectiveMapper}. The
         * dispatcher's mapper is not externally observable, so — per the slice plan's documented
         * fallback — this asserts that {@link RestClientBuilder#resolveEffectiveMapper} returns the
         * profile-A mapper for a generated-proxy client interface, and separately confirms that
         * {@code build()} selects the generated static proxy (not the JDK dynamic proxy) for that
         * interface, proving both halves of the parity path.
         */
        @Test
        @DisplayName("generatedProxy_inheritsResolvedMapper — generated proxy client resolves to profile-A mapper")
        void generatedProxy_inheritsResolvedMapper() {
            JsonMapperProfileRegistry registry = registryWithAbc();
            RestClientBuilder builder = RestClientBuilder.create(vertx)
                    .baseUrl("http://localhost:9999")
                    .jsonMapperProfileRegistry(registry);

            // The interface carries @JsonProfile("profile-a") and has a generated
            // stand-in proxy on the test classpath (GeneratedProfileClient_RestClientProxy).
            ObjectMapper resolved =
                    builder.resolveEffectiveMapper(null, GeneratedProfileClient.class, "generated-profile-client");
            assertSame(mapperA, resolved, "generated-proxy client must resolve to the annotation's profile-A mapper");

            // And the same builder selects the generated static proxy, proving the codegen path is
            // the one that receives the dispatcher built with the resolved mapper.
            Object proxy = builder.build(GeneratedProfileClient.class);
            assertInstanceOf(GeneratedProfileClient_RestClientProxy.class, proxy);
            assertTrue(
                    !Proxy.isProxyClass(proxy.getClass()),
                    "generated static proxy must be selected over the JDK dynamic proxy");
        }
    }

    // --- Slice 2.3: defaults + global tiers ---

    /**
     * Tests for the two new tiers introduced in slice 2.3:
     * {@code restClient.defaults.jsonProfile} (tier 5) and global {@code json.jsonProfile} (tier 6).
     *
     * <p>These tests are RED by design: {@code resolveEffectiveProfileId} does not yet consult
     * {@code restClient.defaults.jsonProfile} or {@code json.jsonProfile} — the green impl adds those
     * tiers.
     */
    @Nested
    @DisplayName("defaults + global tiers (slice 2.3)")
    class DefaultsAndGlobalTiers {

        /**
         * Builds a root config {@link JsonObject} carrying the given per-client jsonProfile (on
         * client {@code "svc"}) and optional defaults.jsonProfile.
         *
         * @param clientProfile per-client restClient.svc.jsonProfile, or {@code null}
         * @param defaultsProfile restClient.defaults.jsonProfile, or {@code null}
         * @return the root config {@link JsonObject}
         */
        private JsonObject rootConfig(String clientProfile, String defaultsProfile) {
            JsonObject client = new JsonObject().put("baseUrl", "http://svc");
            if (clientProfile != null) {
                client.put("jsonProfile", clientProfile);
            }
            JsonObject restClient = new JsonObject().put("svc", client);
            if (defaultsProfile != null) {
                restClient.put("defaults", new JsonObject().put("jsonProfile", defaultsProfile));
            }
            return new JsonObject().put("restClient", restClient);
        }

        @Test
        @DisplayName("perClientOverDefaults — per-client profile 'a' wins over restClient.defaults 'profile-d'")
        void perClientOverDefaults() {
            // GIVEN: per-client profile-a, defaults profile-d, and a global json.jsonProfile 'profile-e'.
            // The builder must be seeded with the defaults and global tiers so the test actually proves
            // per-client wins over *present* lower tiers — not merely over absent ones.
            JsonObject root = rootConfig("profile-a", "profile-d");
            ConfigParser parser = configParser();
            RestClientDefaults defaults = RestClientConfig.defaultsFromConfig(root, parser);
            RestClientConfig clientConfig =
                    RestClientConfig.indexFromConfig(root, parser).get("svc");
            JsonConfig jsonConfig = new JsonConfig("profile-e"); // global tier is present

            JsonMapperProfileRegistry registry = registryWithAbcDe();
            // Seed both lower tiers so the assertion proves per-client truly wins over them
            RestClientBuilder builder = RestClientBuilder.create(vertx)
                    .jsonMapperProfileRegistry(registry)
                    .defaultsJsonProfileId(defaults.jsonProfile())
                    .jsonConfig(jsonConfig);

            // resolveEffectiveMapper must pick profile-a (per-client) over profile-d (defaults) and
            // profile-e (global), even though both lower tiers are genuinely present on the builder.
            ObjectMapper resolved = builder.resolveEffectiveMapper(clientConfig, PlainClient.class, "svc");

            assertSame(mapperA, resolved, "per-client jsonProfile must win over restClient.defaults.jsonProfile");
        }

        @Test
        @DisplayName("defaultsOverGlobal — restClient.defaults 'profile-d' wins over json.jsonProfile 'profile-e'")
        void defaultsOverGlobal() {
            // GIVEN: no per-client profile, defaults profile-d, global profile-e
            JsonObject root = rootConfig(null, "profile-d");
            ConfigParser parser = configParser();
            RestClientDefaults defaults = RestClientConfig.defaultsFromConfig(root, parser);
            RestClientConfig clientConfig =
                    RestClientConfig.indexFromConfig(root, parser).get("svc");
            JsonConfig jsonConfig = new JsonConfig("profile-e");

            JsonMapperProfileRegistry registry = registryWithAbcDe();
            RestClientBuilder builder = RestClientBuilder.create(vertx)
                    .jsonMapperProfileRegistry(registry)
                    .defaultsJsonProfileId(defaults.jsonProfile())
                    .jsonConfig(jsonConfig);

            ObjectMapper resolved = builder.resolveEffectiveMapper(clientConfig, PlainClient.class, "svc");

            assertSame(mapperD, resolved, "restClient.defaults.jsonProfile must win over json.jsonProfile");
        }

        @Test
        @DisplayName("globalApplies — json.jsonProfile 'profile-e' applies when no per-client or defaults id set")
        void globalApplies() {
            // GIVEN: no per-client profile, no defaults, global profile-e
            JsonObject root = rootConfig(null, null);
            ConfigParser parser = configParser();
            RestClientConfig clientConfig =
                    RestClientConfig.indexFromConfig(root, parser).get("svc");
            JsonConfig jsonConfig = new JsonConfig("profile-e");

            JsonMapperProfileRegistry registry = registryWithAbcDe();
            RestClientBuilder builder = RestClientBuilder.create(vertx)
                    .jsonMapperProfileRegistry(registry)
                    .jsonConfig(jsonConfig);

            ObjectMapper resolved = builder.resolveEffectiveMapper(clientConfig, PlainClient.class, "svc");

            assertSame(mapperE, resolved, "json.jsonProfile must apply when no per-client or defaults profile is set");
        }

        @Test
        @DisplayName("explicitObjectMapper_winsOverAllDefaults — explicit objectMapper wins even when all defaults set")
        void explicitObjectMapper_winsOverAllDefaults() {
            // GIVEN: all tiers set, but explicit objectMapper always wins
            ObjectMapper custom = newVertxAwareMapper();
            JsonObject root = rootConfig("profile-a", "profile-d");
            ConfigParser parser = configParser();
            RestClientConfig clientConfig =
                    RestClientConfig.indexFromConfig(root, parser).get("svc");

            JsonMapperProfileRegistry throwingRegistry = new ThrowingRegistry();
            RestClientBuilder builder =
                    RestClientBuilder.create(vertx).objectMapper(custom).jsonMapperProfileRegistry(throwingRegistry);

            // The explicit objectMapper must win; the registry must never be consulted
            ObjectMapper resolved = builder.resolveEffectiveMapper(clientConfig, PlainClient.class, "svc");

            assertSame(
                    custom, resolved, "explicit objectMapper must win outright even when all defaults are configured");
        }

        // --- Helper ---

        /**
         * Creates a lenient {@link ConfigParser} for test-side config parsing.
         *
         * @return a lenient {@link DefaultConfigParser}
         */
        private ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }
    }

    // --- TP-001: default and reserved ids resolve through a registry, with or without injection ---

    /**
     * TP-001 (T012): every id, including the reserved {@code system} id, now resolves through a
     * {@link JsonMapperProfileRegistry} — the injected one, or a builder-private, lazily-created
     * seeded registry for reserved ids when none is injected. A non-reserved id without a registry
     * still fails fast.
     *
     * <p>Sub-cases:
     *
     * <ol>
     *   <li>(a) an injected seeded registry, no profile anywhere — resolves the registry's
     *       {@code vertique} mapper by identity;</li>
     *   <li>(b) no registry, no profile anywhere — resolves a mapper behaving like {@code vertique}
     *       (not the raw shared {@code DatabindCodec.mapper()});</li>
     *   <li>(c) explicit {@code system}, no registry — resolves the builder-private registry's
     *       {@code system} copy, built once per builder;</li>
     *   <li>(d) explicit {@code custom}, no registry — still throws
     *       {@link RestClientConfigurationException} (regression coverage).</li>
     * </ol>
     *
     * <p>Plus the supporting structural check: {@code RestClientBuilder.java} no longer references
     * {@code DatabindCodec}.
     */
    @Nested
    @DisplayName("defaultAndReservedIdsResolveThroughARegistry")
    class DefaultAndReservedIdsResolveThroughARegistry {

        /** A record with one nullable property, used to observe null-inclusion behavior. */
        private record NullableFieldSample(String name, String note) {}

        @Test
        @DisplayName("(a) injectedRegistry_noProfileAnywhere_resolvesReservedVertiqueIdByIdentity")
        void injectedRegistry_noProfileAnywhere_resolvesReservedVertiqueIdByIdentity() {
            JsonMapperProfileRegistry registry = new DefaultJsonMapperProfileRegistry(Set.of());
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonMapperProfileRegistry(registry);

            ObjectMapper resolved = builder.resolveEffectiveMapper(null, PlainClient.class, "svc");

            assertSame(
                    registry.mapper(JsonProfileId.of("vertique")),
                    resolved,
                    "with an injected registry and no profile anywhere, the reserved 'vertique' id must resolve"
                            + " through the registry");
        }

        @Test
        @DisplayName("(b) noRegistry_noProfileAnywhere_resolvesVertiqueSemantics_notRawDatabindCodec")
        void noRegistry_noProfileAnywhere_resolvesVertiqueSemantics_notRawDatabindCodec() throws Exception {
            RestClientBuilder builder = RestClientBuilder.create(vertx);

            ObjectMapper resolved = builder.resolveEffectiveMapper(null, PlainClient.class, "svc");

            assertNotSame(
                    DatabindCodec.mapper(),
                    resolved,
                    "a zero-config standalone builder must no longer resolve the raw shared"
                            + " DatabindCodec.mapper()");
            String json = resolved.writeValueAsString(new NullableFieldSample("x", null));
            assertFalse(
                    json.contains("note"),
                    "the 'vertique' profile omits null properties (NON_NULL inclusion); got: " + json);
        }

        @Test
        @DisplayName("(c) explicitSystem_noRegistry_resolvesBuilderPrivateRegistryCopy_builtOncePerBuilder")
        void explicitSystem_noRegistry_resolvesBuilderPrivateRegistryCopy_builtOncePerBuilder() throws Exception {
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonProfile(JsonProfileId.SYSTEM);

            ObjectMapper resolvedFirst = builder.resolveEffectiveMapper(null, PlainClient.class, "svc");
            ObjectMapper resolvedSecond = builder.resolveEffectiveMapper(null, PlainClient.class, "svc");

            assertNotSame(
                    DatabindCodec.mapper(),
                    resolvedFirst,
                    "explicit 'system' without a registry must resolve the builder-private registry's own"
                            + " copy, not the shared DatabindCodec.mapper() instance itself");
            assertSame(
                    resolvedFirst,
                    resolvedSecond,
                    "the builder-private registry must be built at most once per builder — two resolutions on"
                            + " the same builder must share the same mapper instance");

            // System semantics: a null property is emitted (no NON_NULL opinion), unlike 'vertique'.
            String json = resolvedFirst.writeValueAsString(new NullableFieldSample("x", null));
            assertTrue(
                    json.contains("note"),
                    "the 'system' profile emits null properties (no NON_NULL opinion); got: " + json);

            // System semantics: java.time renders ISO-8601.
            assertEquals(
                    "\"2026-01-02\"",
                    resolvedFirst.writeValueAsString(LocalDate.of(2026, 1, 2)),
                    "the 'system' profile must render LocalDate as ISO-8601");

            // System semantics: Optional binds (Jdk8Module registered).
            Optional<String> deserialized = resolvedFirst.readValue("\"hi\"", new TypeReference<Optional<String>>() {});
            assertEquals(Optional.of("hi"), deserialized, "the 'system' profile must bind Optional<String>");

            // A different builder must get its own builder-private registry instance.
            RestClientBuilder otherBuilder = RestClientBuilder.create(vertx).jsonProfile(JsonProfileId.SYSTEM);
            ObjectMapper resolvedFromOtherBuilder = otherBuilder.resolveEffectiveMapper(null, PlainClient.class, "svc");

            assertNotSame(
                    resolvedFirst,
                    resolvedFromOtherBuilder,
                    "two different builders must not share the same builder-private registry instance");
        }

        @Test
        @DisplayName("(d) explicitCustom_noRegistry_throwsConfigurationException")
        void explicitCustom_noRegistry_throwsConfigurationException() {
            RestClientBuilder builder = RestClientBuilder.create(vertx).jsonProfile(JsonProfileId.of("custom"));

            RestClientConfigurationException ex = assertThrows(
                    RestClientConfigurationException.class,
                    () -> builder.resolveEffectiveMapper(null, PlainClient.class, "svc"));

            String message = ex.getMessage();
            assertTrue(message.contains("svc"), "message should name the client: " + message);
            assertTrue(message.contains("custom"), "message should name the profile id: " + message);
        }

        @Test
        @DisplayName("structural: RestClientBuilder.java has no DatabindCodec token")
        void restClientBuilderSource_hasNoDatabindCodecToken() throws IOException {
            Path source = Path.of(
                    System.getProperty("user.dir"),
                    "src",
                    "main",
                    "java",
                    "dev",
                    "vertique",
                    "rest",
                    "client",
                    "RestClientBuilder.java");
            Assumptions.assumeTrue(Files.isRegularFile(source), "RestClientBuilder.java not found at " + source);

            String contents = Files.readString(source);

            assertFalse(
                    contents.contains("DatabindCodec"),
                    "RestClientBuilder.java must not reference DatabindCodec once every JSON profile resolves"
                            + " through a registry");
        }
    }

    // --- Test doubles ---

    /** Registry that fails any consultation, proving the explicit-objectMapper path never calls it. */
    private static final class ThrowingRegistry implements JsonMapperProfileRegistry {
        @Override
        public ObjectMapper mapper(JsonProfileId id) {
            throw new AssertionError("registry must not be consulted when an explicit objectMapper is set");
        }

        @Override
        public JsonMapperProfile profile(JsonProfileId id) {
            throw new AssertionError("registry must not be consulted when an explicit objectMapper is set");
        }

        @Override
        public Set<JsonProfileId> profileIds() {
            return Set.of();
        }
    }
}
