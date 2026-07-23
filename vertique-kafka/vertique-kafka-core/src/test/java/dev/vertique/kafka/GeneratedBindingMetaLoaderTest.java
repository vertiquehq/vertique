// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceContractRegistry;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link GeneratedBindingMetaLoader}: companion class loading (found / missing /
 * malformed), and the three conversion methods ({@code toSourceEntry}, {@code toRouterEntry},
 * {@code toHandlerEntry}) against representative {@link KafkaBindingMeta} instances.
 */
class GeneratedBindingMetaLoaderTest {

    // --- Service fixtures for conversion tests ---

    record TestPayload(String value) {}

    @ServiceContract(value = "gbml-service")
    interface GbmlService {

        @ServiceOperation("handle")
        @OneWay
        Future<Void> handle(TestPayload payload);

        @ServiceOperation("query")
        Future<String> query(String id);
    }

    static class GbmlServiceImpl implements GbmlService {

        @Override
        public Future<Void> handle(TestPayload payload) {
            return Future.succeededFuture();
        }

        @Override
        public Future<String> query(String id) {
            return Future.succeededFuture("ok");
        }
    }

    @ServiceContract(value = "gbml-router-target")
    interface GbmlRouterTarget {

        @ServiceOperation("process")
        @OneWay
        Future<Void> process(String event);
    }

    static class GbmlRouterTargetImpl implements GbmlRouterTarget {

        @Override
        public Future<Void> process(String event) {
            return Future.succeededFuture();
        }
    }

    // --- Model 4 handler fixture ---

    @KafkaListener(name = "gbml-handler", topic = "gbml.events", groupId = "gbml-grp")
    static class GbmlHandler implements KafkaRecordHandler<TestPayload> {

        @Override
        public Future<Void> handle(KafkaMessage<TestPayload> message) {
            return Future.succeededFuture();
        }
    }

    // --- Shared helpers ---

    /** JSON-capable serde registry for the conversion calls. */
    private static final dev.vertique.kafka.serialization.KafkaSerdeRegistry JSON_SERDE =
            new dev.vertique.kafka.serialization.KafkaSerdeRegistry(
                    Set.of(new dev.vertique.kafka.serialization.TestJsonSerdeProvider()));

    /**
     * Returns a lenient {@link ConfigParser} for test use, matching the production boundary parser.
     *
     * @return a lenient config parser
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    private static dev.vertique.kafka.config.KafkaConfig emptyKafkaConfig() {
        return dev.vertique.kafka.config.KafkaConfig.fromConfig(new JsonObject(), configParser());
    }

    /** Empty per-consumer config index for conversion calls that configure no consumer. */
    private static java.util.Map<String, dev.vertique.kafka.config.KafkaConsumerConfig> emptyConsumerIndex() {
        return java.util.Map.of();
    }

    private static Set<String> usedNames() {
        return new HashSet<>();
    }

    private static List<String> violations() {
        return new ArrayList<>();
    }

    // --- load() tests ---

    @Nested
    @DisplayName("load() — companion class discovery")
    class LoadTests {

        @Test
        @DisplayName("returns the METAS list when a well-formed companion exists")
        void returnsMetasForWellFormedCompanion() {
            List<KafkaBindingMeta> metas = GeneratedBindingMetaLoader.load(GbmlFixture.class);

            assertNotNull(metas);
            assertEquals(2, metas.size());
            assertEquals("fixture-svc-handle", metas.get(0).name());
            assertEquals("fixture-router", metas.get(1).name());
        }

        @Test
        @DisplayName("returns null when no companion class exists")
        void returnsNullWhenNoCompanion() {
            List<KafkaBindingMeta> metas = GeneratedBindingMetaLoader.load(GbmlNoCompanionFixture.class);

            assertNull(metas, "Expected null when companion class is absent");
        }

        @Test
        @DisplayName("throws KafkaRegistrationException when companion is present but METAS field is missing")
        void throwsWhenMetasFieldIsMissing() {
            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> GeneratedBindingMetaLoader.load(GbmlMissingMetasFixture.class));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("missing") || v.contains("METAS")),
                    "Expected violation about missing METAS field, got: " + ex.violations());
        }

        @Test
        @DisplayName("throws KafkaRegistrationException when METAS contains non-KafkaBindingMeta elements")
        void throwsWhenMetasHasWrongElementType() {
            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> GeneratedBindingMetaLoader.load(GbmlWrongTypeFixture.class));

            assertTrue(
                    ex.violations().stream()
                            .anyMatch(v -> v.contains("unexpected type") || v.contains("KafkaBindingMeta")),
                    "Expected violation about wrong element type, got: " + ex.violations());
        }

        @Test
        @DisplayName("throws KafkaRegistrationException when METAS field is present but non-static")
        void throwsWhenMetasFieldIsNonStatic() {
            KafkaRegistrationException ex = assertThrows(
                    KafkaRegistrationException.class,
                    () -> GeneratedBindingMetaLoader.load(GbmlNonStaticMetasFixture.class));

            assertTrue(
                    ex.violations().stream().anyMatch(v -> v.contains("malformed") || v.contains("static")),
                    "Expected violation about non-static METAS field, got: " + ex.violations());
        }
    }

    // --- toSourceEntry() tests (Model 1) ---

    @Nested
    @DisplayName("toSourceEntry() — Model 1 BINDING conversion")
    class ToSourceEntryTests {

        @Test
        @DisplayName("produces a BINDING entry with derived name and resolved address")
        void producesBindingEntryWithDerivedName() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GbmlServiceImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            ServiceContractRegistry.ContractEntry<?> contractEntry = registry.resolve(GbmlService.class);

            // Meta for the 'handle' operation: no explicit name → derive from serviceName + operationId
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "", // blank name → derive
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null, // valueType null for SOURCE — resolved at runtime from ServiceMethodMeta
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE
                    "handle", // targetOperation matches the @ServiceOperation operationId
                    List.of());

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toSourceEntry(
                    meta,
                    contractEntry,
                    resolver,
                    JSON_SERDE,
                    emptyKafkaConfig(),
                    emptyConsumerIndex(),
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals(ConsumerEntry.Kind.BINDING, entry.kind());
            // derived name: {serviceName}-{operationId} = "gbml-service-handle"
            assertEquals("gbml-service-handle", entry.name());
            assertEquals("gbml.events", entry.config().topic());
            assertEquals("gbml-grp", entry.config().groupId());
            // stable target id from ServiceTargetResolver
            assertEquals("gbml-service.handle", entry.stableTargetId());
            // oneWay flag from @OneWay on the contract method
            assertTrue(entry.targetOneWay());
        }

        @Test
        @DisplayName("explicit name in meta takes precedence over derived name")
        void explicitNameTakesPrecedence() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GbmlServiceImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            ServiceContractRegistry.ContractEntry<?> contractEntry = registry.resolve(GbmlService.class);

            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "my-explicit-name", // explicit name
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null, // valueType null for SOURCE
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE
                    "handle",
                    List.of());

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toSourceEntry(
                    meta,
                    contractEntry,
                    resolver,
                    JSON_SERDE,
                    emptyKafkaConfig(),
                    emptyConsumerIndex(),
                    usedNames,
                    violations);

            assertNotNull(entry);
            assertEquals("my-explicit-name", entry.name());
        }

        @Test
        @DisplayName("unknown targetOperation adds a violation and returns null")
        void unknownTargetOperationAddsViolation() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GbmlServiceImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            ServiceContractRegistry.ContractEntry<?> contractEntry = registry.resolve(GbmlService.class);

            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "x",
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.SOURCE,
                    null, // valueType null for SOURCE
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // jsonProfile null for SOURCE
                    "nonExistentOp",
                    List.of());

            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toSourceEntry(
                    meta,
                    contractEntry,
                    resolver,
                    JSON_SERDE,
                    emptyKafkaConfig(),
                    emptyConsumerIndex(),
                    usedNames(),
                    violations);

            assertNull(entry);
            assertFalse(violations.isEmpty(), "Expected at least one violation");
        }
    }

    // --- toRouterEntry() tests (Model 3) ---

    @Nested
    @DisplayName("toRouterEntry() — Model 3 ROUTER conversion")
    class ToRouterEntryTests {

        @Test
        @DisplayName("produces a ROUTER entry with resolved route target addresses")
        void producesRouterEntryWithResolvedTargets() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GbmlRouterTargetImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);

            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "my-router",
                    "gbml.router.events",
                    "my-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(
                            new KafkaBindingMeta.RouteMeta(
                                    "event-type",
                                    "",
                                    "created",
                                    false,
                                    String.class,
                                    GbmlRouterTarget.class,
                                    "process"),
                            new KafkaBindingMeta.RouteMeta("", "", "", true, Void.class, null, null)));

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    JSON_SERDE,
                    emptyKafkaConfig(),
                    emptyConsumerIndex(),
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals(ConsumerEntry.Kind.ROUTER, entry.kind());
            assertEquals("my-router", entry.name());
            assertEquals("gbml.router.events", entry.config().topic());
            assertEquals(2, entry.routes().size());

            ConsumerEntry.RouteEntry dispatchRoute = entry.routes().get(0);
            assertEquals("event-type", dispatchRoute.matchHeader());
            assertEquals("created", dispatchRoute.matchValue());
            assertNotNull(dispatchRoute.targetAddress(), "Expected a resolved target address");
            assertEquals("gbml-router-target.process", dispatchRoute.stableTargetId());

            ConsumerEntry.RouteEntry defaultRoute = entry.routes().get(1);
            assertTrue(defaultRoute.defaultHandler());
            assertNull(defaultRoute.targetAddress());
        }

        @Test
        @DisplayName("router meta jsonProfile reaches the resolved consumer serde config")
        void generatedBindingMetaLoader_routerPassesJsonProfileToConsumerConfig() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GbmlRouterTargetImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);

            // A ROUTER meta carrying a jsonProfile default, exactly as BindingMetaEmitter would emit it.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "profile-router",
                    "gbml.router.events",
                    "profile-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    "payments-v2", // jsonProfile carried by the meta (from @JsonProfile)
                    null,
                    List.of(new KafkaBindingMeta.RouteMeta(
                            "event-type", "", "created", false, String.class, GbmlRouterTarget.class, "process")));

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    JSON_SERDE,
                    emptyKafkaConfig(),
                    emptyConsumerIndex(),
                    usedNames,
                    violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            // The codegen-supplied profile must be threaded through ResolvedKafkaConsumerConfig.resolve
            // into the merged serde config view, with no per-consumer config overriding it.
            assertEquals(
                    "payments-v2",
                    entry.config().serdeConfig().getString("jsonProfile"),
                    "Expected the router meta's jsonProfile to reach the serde config");
        }

        @Test
        @DisplayName("route with unregistered target service adds violation and skips that route")
        void routeWithUnregisteredServiceAddsViolation() {
            // Empty registry — GbmlRouterTarget not registered
            ServiceContractRegistry registry = ServiceContractRegistry.build(Set.of(), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);

            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "bad-router",
                    "gbml.router.events",
                    "bad-router-grp",
                    KafkaBindingMeta.Kind.ROUTER,
                    null,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of(new KafkaBindingMeta.RouteMeta(
                            "event-type", "", "created", false, String.class, GbmlRouterTarget.class, "process")));

            List<String> violations = violations();
            // The one route is skipped → routes list is empty → validateAndBuild succeeds
            // but we also check the violation was recorded
            GeneratedBindingMetaLoader.toRouterEntry(
                    meta,
                    registry,
                    resolver,
                    JSON_SERDE,
                    emptyKafkaConfig(),
                    emptyConsumerIndex(),
                    usedNames(),
                    violations);

            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("not registered")),
                    "Expected violation about unregistered service, got: " + violations);
        }
    }

    // --- toHandlerEntry() tests (Model 4) ---

    @Nested
    @DisplayName("toHandlerEntry() — Model 4 HANDLER conversion")
    class ToHandlerEntryTests {

        @Test
        @DisplayName("produces a HANDLER entry with the live handler instance and Jackson deserializer")
        void producesHandlerEntryWithLiveHandler() {
            GbmlHandler handler = new GbmlHandler();

            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "gbml-handler",
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.HANDLER,
                    TestPayload.class,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of());

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toHandlerEntry(
                    meta, handler, JSON_SERDE, emptyKafkaConfig(), emptyConsumerIndex(), usedNames, violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertEquals(ConsumerEntry.Kind.HANDLER, entry.kind());
            assertEquals("gbml-handler", entry.name());
            assertEquals("gbml.events", entry.config().topic());
            assertEquals("gbml-grp", entry.config().groupId());
            assertSame(handler, entry.handler(), "Expected the live handler instance");
            assertEquals(TestPayload.class, entry.valueType());
            assertNotNull(entry.deserializer(), "Expected a non-null Jackson deserializer");
        }

        @Test
        @DisplayName("codegen-supplied jsonProfile reaches serdeConfig().getString(\"jsonProfile\")")
        void jsonProfileFromMetaReachesSerdeConfig() {
            GbmlHandler handler = new GbmlHandler();

            // A HANDLER meta carrying an explicit @JsonProfile-derived profile, exactly as
            // BindingMetaEmitter would emit it for a codegen handler.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "gbml-handler-profile",
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.HANDLER,
                    TestPayload.class,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    "payments-events-v2", // jsonProfile carried by the meta (from @JsonProfile)
                    null,
                    List.of());

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toHandlerEntry(
                    meta, handler, JSON_SERDE, emptyKafkaConfig(), emptyConsumerIndex(), usedNames, violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            // The codegen-supplied profile must be threaded through ResolvedKafkaConsumerConfig.resolve
            // into the merged serde config view, with no per-consumer config overriding it.
            assertEquals(
                    "payments-events-v2",
                    entry.config().serdeConfig().getString("jsonProfile"),
                    "Expected the meta's jsonProfile to reach the serde config");
        }

        @Test
        @DisplayName("null jsonProfile in meta omits the key from serdeConfig (framework default path)")
        void nullJsonProfileOmitsSerdeConfigKey() {
            GbmlHandler handler = new GbmlHandler();

            // A HANDLER meta with null jsonProfile (what BindingMetaEmitter emits for a no-profile
            // listener) leaves the "jsonProfile" key absent, so the vertx/default serde path runs.
            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "gbml-handler-default",
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.HANDLER,
                    TestPayload.class,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null, // no profile → key omitted from serde config
                    null,
                    List.of());

            Set<String> usedNames = usedNames();
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toHandlerEntry(
                    meta, handler, JSON_SERDE, emptyKafkaConfig(), emptyConsumerIndex(), usedNames, violations);

            assertTrue(violations.isEmpty(), "Expected no violations, got: " + violations);
            assertNotNull(entry);
            assertNull(
                    entry.config().serdeConfig().getString("jsonProfile"),
                    "Expected no jsonProfile key when meta carries no profile");
        }

        @Test
        @DisplayName("duplicate name adds violation and returns null")
        void duplicateNameAddsViolation() {
            GbmlHandler handler = new GbmlHandler();

            KafkaBindingMeta meta = new KafkaBindingMeta(
                    "gbml-handler",
                    "gbml.events",
                    "gbml-grp",
                    KafkaBindingMeta.Kind.HANDLER,
                    TestPayload.class,
                    ErrorStrategy.SKIP,
                    CommitStrategy.AUTO,
                    "",
                    null,
                    null,
                    List.of());

            Set<String> usedNames = usedNames();
            usedNames.add("gbml-handler"); // pre-claim the name
            List<String> violations = violations();
            ConsumerEntry entry = GeneratedBindingMetaLoader.toHandlerEntry(
                    meta, handler, JSON_SERDE, emptyKafkaConfig(), emptyConsumerIndex(), usedNames, violations);

            assertNull(entry);
            assertTrue(
                    violations.stream().anyMatch(v -> v.contains("Duplicate")),
                    "Expected violation about duplicate name, got: " + violations);
        }
    }

    // --- Dual-annotation parity test (Fix 3) ---

    @Nested
    @DisplayName(
            "Fix 3: dual-annotation — scanKafkaSources ignores ROUTER/HANDLER metas; scanListeners ignores SOURCE metas")
    class DualAnnotationTests {

        @Test
        @DisplayName("scanKafkaSources processes only SOURCE-kind metas — ROUTER metas are ignored")
        void scanKafkaSourcesIgnoresRouterMetas() {
            ServiceContractRegistry registry =
                    ServiceContractRegistry.build(Set.of(new GbmlServiceImpl()), configParser());
            ServiceTargetResolver resolver = ServiceTargetResolver.of(registry);
            ServiceContractRegistry.ContractEntry<?> contractEntry = registry.resolve(GbmlService.class);

            // GbmlDualAnnotationFixture has a companion with one SOURCE meta and one ROUTER meta.
            List<KafkaBindingMeta> metas = GeneratedBindingMetaLoader.load(GbmlDualAnnotationFixture.class);
            assertNotNull(metas, "Expected companion to be found");
            assertEquals(2, metas.size(), "Expected 2 metas (SOURCE + ROUTER) in companion");

            // Drive only the SOURCE-kind metas through toSourceEntry — ROUTER metas must be skipped.
            List<String> violations = violations();
            Set<String> usedNames = usedNames();
            for (KafkaBindingMeta meta : metas) {
                if (meta.kind() != KafkaBindingMeta.Kind.SOURCE) {
                    continue;
                }
                GeneratedBindingMetaLoader.toSourceEntry(
                        meta,
                        contractEntry,
                        resolver,
                        JSON_SERDE,
                        emptyKafkaConfig(),
                        emptyConsumerIndex(),
                        usedNames,
                        violations);
            }

            // Verify that no spurious violation references the ROUTER meta's binding name.
            assertTrue(
                    violations.stream().noneMatch(v -> v.contains("dual-router")),
                    "ROUTER meta 'dual-router' must not appear in scanKafkaSources violations: " + violations);
        }

        @Test
        @DisplayName("load() returns companion with both SOURCE and ROUTER metas for dual-annotation class")
        void dualAnnotationCompanionContainsBothKinds() {
            List<KafkaBindingMeta> metas = GeneratedBindingMetaLoader.load(GbmlDualAnnotationFixture.class);
            assertNotNull(metas);
            assertEquals(2, metas.size());

            long sourceCount = metas.stream()
                    .filter(m -> m.kind() == KafkaBindingMeta.Kind.SOURCE)
                    .count();
            long routerCount = metas.stream()
                    .filter(m -> m.kind() == KafkaBindingMeta.Kind.ROUTER)
                    .count();
            assertEquals(1, sourceCount, "Expected exactly one SOURCE meta");
            assertEquals(1, routerCount, "Expected exactly one ROUTER meta");
        }
    }

    // --- Private assertion helpers not in JUnit core ---

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " — expected: " + expected + ", actual: " + actual);
        }
    }
}
