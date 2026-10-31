// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.scope.ScopeKey;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.delayed.config.DelayedJobsConfig;
import dev.vertique.job.postgresql.PgJobRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link DelayedJobClientFactory}: proxy creation, interface validation, and
 * annotation requirement enforcement.
 */
@DisplayName("DelayedJobClientFactory")
@ExtendWith(MockitoExtension.class)
class DelayedJobClientFactoryTest {

    // --- Test Fixtures ---

    /** Valid contract interface carrying @DelayedJobContract. */
    @DelayedJobContract(name = "factory-test-job")
    interface FactoryTestJob extends DelayedJobClient<String> {}

    /** Abstract class — not an interface. */
    abstract static class NotAnInterface implements DelayedJobClient<String> {}

    /** Interface without @DelayedJobContract. */
    interface NoAnnotationContract extends DelayedJobClient<String> {}

    /** Contract with no generated proxy, so clients of it are JDK dynamic proxies. */
    @DelayedJobContract(name = "dynamic-scope-job", maxAttempts = 4, queue = "mail", priority = 7)
    interface DynamicScopeJob extends DelayedJobClient<String> {}

    // --- Setup ---

    @Mock
    DelayedJobService jobService;

    DelayedJobClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DelayedJobClientFactory(jobService, Map.of());
    }

    // --- Tests ---

    // --- Scoped clients ---

    private static final Instant RUN_AT = Instant.parse("2031-01-02T03:04:05Z");
    private static final DurableMetadata PREMERGED = DurableMetadata.of("custom", new JsonObject().put("flag", "kept"));

    /** Real service over a recording repository, so the scope a client selects reaches the saved row. */
    private static final class RecordingGraph {

        final List<JobExecution> saved = new CopyOnWriteArrayList<>();
        final DelayedJobClientFactory factory;

        RecordingGraph() {
            JobRepository repository = mock(JobRepository.class);
            PgJobRepository pgRepository = mock(PgJobRepository.class);
            DelayedJobHandlerRegistrar registrar = mock(DelayedJobHandlerRegistrar.class);
            lenient()
                    .when(registrar.handlerAddresses())
                    .thenReturn(Map.of(
                            "dynamic-scope-job", "test/svc/dynamic",
                            "precompiled-shape", "test/svc/precompiled",
                            "selection-test", "test/svc/selection"));
            lenient().when(repository.save(any())).thenAnswer(invocation -> record(invocation.getArgument(0)));
            lenient().when(pgRepository.save(any(), any())).thenAnswer(invocation -> record(invocation.getArgument(0)));
            DefaultContextHolder holder = new DefaultContextHolder();
            DurableContextPropagator propagator = new DurableContextPropagator(
                    new DurableContextMetadataRegistry(Set.of(), Set.of()), holder, new ContextScopeBinder(holder));
            DelayedJobService service = new DelayedJobService(
                    repository, pgRepository, registrar, propagator, new DelayedJobExceptionMapper());
            factory = new DelayedJobClientFactory(service, Map.of());
        }

        private Future<UUID> record(JobExecution execution) {
            saved.add(execution);
            return Future.succeededFuture(execution.id());
        }
    }

    /** One call of a {@link DelayedJobClient} overload. */
    @FunctionalInterface
    interface Enqueue {
        Future<UUID> call(DelayedJobClient<String> client, SqlClient tx);
    }

    private static Named<Enqueue> overload(String name, Enqueue call) {
        return Named.of(name, call);
    }

    /** Every client overload, with and without pre-merged metadata, on both kinds of client. */
    private static Stream<Arguments> overloadsOnEveryClientKind() {
        DelayedJobOptions options = DelayedJobOptions.builder()
                .runAt(RUN_AT)
                .queue("urgent")
                .priority(9)
                .maxAttempts(2)
                .jobId("job-1")
                .build();
        DelayedJobOptions premerged = DelayedJobOptions.builder()
                .runAt(RUN_AT)
                .queue("urgent")
                .priority(9)
                .maxAttempts(2)
                .jobId("job-1")
                .premergedMetadata(PREMERGED)
                .build();
        return Stream.of(
                        Arguments.of(overload("enqueue(payload)", (c, tx) -> c.enqueue("p")), null),
                        Arguments.of(overload("enqueue(payload, runAt)", (c, tx) -> c.enqueue("p", RUN_AT)), null),
                        Arguments.of(
                                overload("enqueue(payload, delay)", (c, tx) -> c.enqueue("p", Duration.ofHours(1))),
                                null),
                        Arguments.of(overload("enqueue(payload, tx)", (c, tx) -> c.enqueue("p", tx)), null),
                        Arguments.of(overload("enqueue(payload, options)", (c, tx) -> c.enqueue("p", options)), null),
                        Arguments.of(
                                overload("enqueue(payload, options, tx)", (c, tx) -> c.enqueue("p", options, tx)),
                                null),
                        Arguments.of(
                                overload("enqueue(payload, premerged options)", (c, tx) -> c.enqueue("p", premerged)),
                                PREMERGED),
                        Arguments.of(
                                overload(
                                        "enqueue(payload, premerged options, tx)",
                                        (c, tx) -> c.enqueue("p", premerged, tx)),
                                PREMERGED))
                .flatMap(row -> Stream.of(
                        Arguments.of(
                                Named.of("JDK dynamic proxy", DynamicScopeJob.class),
                                true,
                                "test/svc/dynamic",
                                row.get()[0],
                                row.get()[1]),
                        Arguments.of(
                                Named.of("proxy compiled before scopes", PrecompiledShapeJob.class),
                                false,
                                "test/svc/precompiled",
                                row.get()[0],
                                row.get()[1])));
    }

    /** The two kinds of client, a JDK dynamic proxy and a companion proxy with the pre-scope constructor shape. */
    private static Stream<Arguments> clientKinds() {
        return Stream.of(
                Arguments.of(Named.of("JDK dynamic proxy", DynamicScopeJob.class)),
                Arguments.of(Named.of("proxy compiled before scopes", PrecompiledShapeJob.class)));
    }

    @ParameterizedTest
    @MethodSource("overloadsOnEveryClientKind")
    @DisplayName("a scoped client saves the selected scope on every overload and keeps every other field")
    void scopedClientStoresScopeOnEveryOverload(
            Class<? extends DelayedJobClient<String>> contract,
            boolean jdkProxy,
            String address,
            Enqueue enqueue,
            DurableMetadata premergedMetadata) {
        // Given: a real service, a scoped client and an unscoped client of one contract, and a transaction client
        RecordingGraph graph = new RecordingGraph();
        DelayedJobClient<String> scoped =
                graph.factory.client(contract).scope("acme").create();
        DelayedJobClient<String> unscoped = graph.factory.create(contract);
        SqlClient tx = mock(SqlClient.class);

        // When: the scoped client enqueues through the overload, then the unscoped client does the same
        Future<UUID> scopedResult = enqueue.call(scoped, tx);
        Future<UUID> unscopedResult = enqueue.call(unscoped, tx);

        // Then: the scoped row is in acme and equals the unscoped row in every other field
        assertEquals(jdkProxy, Proxy.isProxyClass(scoped.getClass()), "the client must be the expected proxy kind");
        assertTrue(scopedResult.succeeded(), "scoped enqueue must succeed: " + scopedResult.cause());
        assertTrue(unscopedResult.succeeded(), "unscoped enqueue must succeed: " + unscopedResult.cause());
        assertEquals(2, graph.saved.size(), "each enqueue must save exactly one row");
        JobExecution acme = graph.saved.get(0);
        JobExecution other = graph.saved.get(1);
        assertEquals("acme", acme.scopeKey());
        assertEquals(ScopeKey.DEFAULT.value(), other.scopeKey(), "scoping one client must not scope another");
        assertEquals(address, acme.handler());
        assertEquals("p", acme.payload());
        assertTrue(
                Duration.between(other.scheduledAt(), acme.scheduledAt()).abs().compareTo(Duration.ofSeconds(5)) < 0,
                "the scheduled time must be unchanged by the scope");
        assertEquals(
                withoutVolatileFields(other),
                withoutVolatileFields(acme),
                "only the scope may differ between a scoped and an unscoped enqueue");
        if (premergedMetadata != null) {
            assertEquals(premergedMetadata, acme.metadata(), "pre-merged metadata must be saved as given");
        }
    }

    /** The row without its scope or the fields that differ between two calls: the id, a generated job id, and time. */
    private static JobExecution withoutVolatileFields(JobExecution row) {
        return new JobExecution(
                new UUID(0, 0),
                row.jobId().startsWith("delayed-") ? "delayed-generated" : row.jobId(),
                row.jobType(),
                row.handler(),
                row.queue(),
                row.state(),
                row.attemptNumber(),
                row.maxAttempts(),
                row.payload(),
                row.priority(),
                row.lockedBy(),
                Instant.EPOCH,
                row.enqueuedAt(),
                row.startedAt(),
                row.completedAt(),
                row.errorMessage(),
                row.errorType(),
                row.progress(),
                row.parameters(),
                row.attributes(),
                row.metadata(),
                ScopeKey.DEFAULT.value());
    }

    @Test
    @DisplayName("a scoped client of a contract with a generated proxy keeps using that proxy")
    void scopedClientKeepsSelectingTheGeneratedProxy() {
        // Given: a contract whose companion proxy is on the classpath with the unchanged constructor shape
        // When: a scoped client is created
        Object client = new RecordingGraph()
                .factory
                .client(SelectionTestJob.class)
                .scope("acme")
                .create();

        // Then: it is that companion proxy, not a JDK dynamic proxy
        assertInstanceOf(SelectionTestJob_DelayedJobProxy.class, client);
        assertFalse(Proxy.isProxyClass(client.getClass()), "should not be a JDK dynamic proxy");
    }

    static Stream<Named<String>> invalidScopeKeys() {
        return Stream.of(
                Named.of("null", null),
                Named.of("empty", ""),
                Named.of("blank", "   "),
                Named.of("over-long", "x".repeat(256)),
                Named.of("control character", "bad\u0001key"),
                Named.of("format character", "bad\u200Bkey"));
    }

    @ParameterizedTest
    @MethodSource("invalidScopeKeys")
    @DisplayName("scope rejects a null or invalid key when called, without repeating the key")
    void scopeRejectsNullAndInvalidKeys(String key) {
        // Given: a builder
        DelayedJobClientBuilder<DynamicScopeJob> builder = factory.client(DynamicScopeJob.class);

        // When: scope is called with the key
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> builder.scope(key));

        // Then: it throws, and the text does not contain the key
        if (key != null) {
            assertInstanceOf(IllegalArgumentException.class, thrown);
            assertTrue(
                    key.isEmpty() || !String.valueOf(thrown.getMessage()).contains(key),
                    "the failure text must not repeat the key");
        }
    }

    @ParameterizedTest
    @MethodSource("clientKinds")
    @DisplayName("a client from client() without scope saves the default scope, like create()")
    void unscopedClientStaysInDefault(Class<? extends DelayedJobClient<String>> contract) {
        // Given: a client from create() and a client from client() with no scope
        RecordingGraph graph = new RecordingGraph();
        DelayedJobClient<String> created = graph.factory.create(contract);
        DelayedJobClient<String> built = graph.factory.client(contract).create();

        // When: each enqueues
        created.enqueue("p");
        built.enqueue("p");

        // Then: both rows are in the default scope
        assertEquals(
                List.of(ScopeKey.DEFAULT.value(), ScopeKey.DEFAULT.value()),
                graph.saved.stream().map(JobExecution::scopeKey).toList());
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("returns non-null proxy for valid @DelayedJobContract interface")
        void returnsProxyForValidInterface() {
            FactoryTestJob proxy = factory.create(FactoryTestJob.class);
            assertNotNull(proxy);
        }

        @Test
        @DisplayName("returned proxy implements the contract interface")
        void proxyImplementsContractInterface() {
            Object proxy = factory.create(FactoryTestJob.class);
            assertInstanceOf(FactoryTestJob.class, proxy);
        }

        @Test
        @DisplayName("returned proxy implements DelayedJobClient")
        void proxyImplementsDelayedJobClient() {
            Object proxy = factory.create(FactoryTestJob.class);
            assertInstanceOf(DelayedJobClient.class, proxy);
        }

        @Test
        @DisplayName("throws IllegalArgumentException for a non-interface type")
        void throwsForNonInterface() {
            // NotAnInterface is an abstract class, not an interface — use mock class trick:
            // We can't directly pass abstract class, so we test via a concrete non-interface class.
            // Use String.class as a proxy stand-in to exercise the non-interface guard.
            @SuppressWarnings("unchecked")
            Class<DelayedJobClient<String>> badClass = (Class<DelayedJobClient<String>>) (Class<?>) String.class;

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> factory.create(badClass));
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("throws IllegalArgumentException for interface missing @DelayedJobContract")
        void throwsForMissingAnnotation() {
            @SuppressWarnings("unchecked")
            Class<DelayedJobClient<String>> badInterface =
                    (Class<DelayedJobClient<String>>) (Class<?>) NoAnnotationContract.class;

            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> factory.create(badInterface));
            assertTrue(ex.getMessage().contains("@DelayedJobContract"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Provider-based access (DI cycle workaround)")
    class ProviderAccess {

        @Test
        @DisplayName("factory works when accessed via Provider (DI cycle workaround)")
        void factoryWorksViaProvider() {
            // Simulate Provider<DelayedJobClientFactory> lazy access pattern used to
            // break DI cycles when a service depends on the factory that depends on it.
            jakarta.inject.Provider<DelayedJobClientFactory> provider = () -> factory;

            DelayedJobClientFactory lazyFactory = provider.get();
            FactoryTestJob proxy = lazyFactory.create(FactoryTestJob.class);

            assertNotNull(proxy);
            assertTrue(proxy instanceof DelayedJobClient, "Proxy must implement DelayedJobClient");
        }
    }

    @Nested
    @DisplayName("generated-proxy selection")
    class GeneratedProxySelection {

        @Test
        @DisplayName("uses the generated proxy when present on the classpath")
        void usesGeneratedProxyWhenPresent() {
            Object proxy = factory.create(SelectionTestJob.class);

            assertInstanceOf(SelectionTestJob_DelayedJobProxy.class, proxy);
            assertFalse(Proxy.isProxyClass(proxy.getClass()), "should not be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("uses the generated proxy for a nested contract via the flattened companion name")
        void usesGeneratedProxyForNestedContract() {
            Object proxy = factory.create(NestedSelectionHost.NestedJob.class);

            // Selected only if the factory derives the lookup via GeneratedNames.companionFqn (Outer$Inner ->
            // Outer_Inner); a regression to contract.getName() + suffix would miss it and fall back to a JDK proxy.
            assertInstanceOf(NestedSelectionHost_NestedJob_DelayedJobProxy.class, proxy);
            assertFalse(Proxy.isProxyClass(proxy.getClass()), "should not be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("falls back to a JDK dynamic proxy when no generated proxy exists")
        void fallsBackToJdkProxyWhenAbsent() {
            Object proxy = factory.create(FactoryTestJob.class);

            assertTrue(Proxy.isProxyClass(proxy.getClass()), "should be a JDK dynamic proxy");
        }

        @Test
        @DisplayName("fails loudly when a present generated proxy cannot be instantiated")
        void failsLoudlyWhenGeneratedProxyBroken() {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> factory.create(BrokenSelectionJob.class));
            assertTrue(ex.getMessage().contains("BrokenSelectionJob_DelayedJobProxy"), ex.getMessage());
        }
    }

    @Nested
    @DisplayName("config override")
    class ConfigOverride {

        @Test
        @DisplayName("factory with per-contract config creates a proxy that uses config values")
        void factoryWithConfigCreatesProxy() {
            JsonObject config = new JsonObject()
                    .put(
                            "delayedJob",
                            new JsonObject()
                                    .put(
                                            "contracts",
                                            new JsonObject()
                                                    .put(
                                                            "factory-test-job",
                                                            new JsonObject()
                                                                    .put("maxAttempts", 7)
                                                                    .put("queue", "priority"))));
            DelayedJobClientFactory configFactory = new DelayedJobClientFactory(
                    jobService,
                    DelayedJobsConfig.fromConfig(config, new DefaultConfigParser(DefaultConfigMapper.lenient()))
                            .contractIndex());

            FactoryTestJob proxy = configFactory.create(FactoryTestJob.class);

            // Proxy must be non-null and implement the contract — deeper behavior tested in proxy tests
            assertNotNull(proxy);
            assertInstanceOf(FactoryTestJob.class, proxy);
        }
    }

    // Helper to keep assertion in scope
    private static void assertTrue(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, message);
    }
}
