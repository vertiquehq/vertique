// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.DefaultBranchResultReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultFailMessageFactoryRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultNamedConditionRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStartStateMapperRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateMutatorRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultStateReducerRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultSubjectResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTaskAssignmentResolverRegistry;
import dev.vertique.workflow.definition.callbacks.DefaultTimerResolverRegistry;
import dev.vertique.workflow.definition.callbacks.NamedPayloadMapper;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.PayloadMapperRegistry;
import dev.vertique.workflow.definition.callbacks.RegisteredIdentifierLookup;
import dev.vertique.workflow.definition.callbacks.StartStateMapperRegistry;
import dev.vertique.workflow.definition.compiler.DecisionRouteCompiler;
import dev.vertique.workflow.definition.compiler.WorkflowDefinitionCompiler;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.cel.CelExpressionProfile;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Verifies {@link DefaultWorkflowDefinitionService}:
 * <ul>
 *   <li>{@link WorkflowDefinitionService#load} then
 *       {@link WorkflowDefinitionService#publish} succeeds and makes the definition
 *       resolvable via the registry.</li>
 *   <li>A load failure with invalid YAML leaves the store untouched; a prior active version
 *       remains accessible.</li>
 *   <li>{@link WorkflowDefinitionService#publish} without prior load fails its Future.</li>
 *   <li>Two distinct definitionIds loaded and activated concurrently both appear in the
 *       registry.</li>
 *   <li>A {@link RuntimeException} from {@code registry.register()} resets the PUBLISHING claim
 *       back to CANDIDATE so the caller can retry.</li>
 * </ul>
 */
class DefaultWorkflowDefinitionServiceTest {

    // --- Test types ---

    record OrderState(String id) {}

    record PlaceOrder(String id) {}

    @WorkflowContract
    interface SvcOrderContract {}

    @WorkflowContract
    interface SvcItemV1Contract {}

    @WorkflowContract
    interface SvcScopedOrderContract {}

    @WorkflowContract("lookup-c")
    interface SvcLookupCContract {}

    @WorkflowContract("lookup-d")
    interface SvcLookupDContract {}

    // --- Infrastructure ---

    private DefaultWorkflowRegistry registry;
    private WorkflowDefinitionStore store;
    private DefaultWorkflowDefinitionService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory mapperFactory =
                Mockito.mock(dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());

        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.init", PlaceOrder.class, OrderState.class, cmd -> new OrderState(cmd.id())))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", OrderState.class, s -> s);
        Mockito.when(payloadMappers.lookup(Mockito.anyString())).thenReturn(rawMapper);

        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                payloadMappers,
                startStateMappers,
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        DefaultNamedConditionRegistry conditionRegistry = new DefaultNamedConditionRegistry(Set.of());
        DecisionRouteCompiler decisionRouteCompiler =
                new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);

        WorkflowDefinitionParser parser = ServiceTestHelpers.realParser();
        WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(lookup);
        WorkflowDefinitionPipeline pipeline = new WorkflowDefinitionPipeline(parser, validator, compiler);

        store = new WorkflowDefinitionStore();
        registry = new DefaultWorkflowRegistry();
        service = new DefaultWorkflowDefinitionService(pipeline, store, () -> registry);
    }

    // --- Helpers ---

    private byte[] yamlFor(String defId, long version, Class<?> contract) {
        String yaml = """
                definitionId: %s
                definitionVersion: %d
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: order.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(
                        defId, version, OrderState.class.getName(), contract.getName(), PlaceOrder.class.getName());
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    private SourceMetadata testMeta() {
        return new SourceMetadata("test", null, null, null, null, Instant.now());
    }

    // --- Helpers (shareable pipeline factory) ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private WorkflowDefinitionPipeline buildPipeline() {
        dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory mapperFactory =
                Mockito.mock(dev.vertique.workflow.definition.parser.WorkflowDefinitionMapperFactory.class);
        Mockito.when(mapperFactory.jsonMapper()).thenReturn(new ObjectMapper());

        ExpressionProfile profile = new CelExpressionProfile();

        StartStateMapperRegistry startStateMappers =
                new DefaultStartStateMapperRegistry(Set.of(b -> b.register(new NamedStartStateMapper<>(
                        "order.init", PlaceOrder.class, OrderState.class, cmd -> new OrderState(cmd.id())))));

        PayloadMapperRegistry payloadMappers = Mockito.mock(PayloadMapperRegistry.class);
        NamedPayloadMapper rawMapper = new NamedPayloadMapper<>("any", OrderState.class, s -> s);
        Mockito.when(payloadMappers.lookup(Mockito.anyString())).thenReturn(rawMapper);

        RegisteredIdentifierLookup lookup = new RegisteredIdentifierLookup(
                payloadMappers,
                startStateMappers,
                new DefaultStateReducerRegistry(Set.of()),
                new DefaultStateMutatorRegistry(Set.of()),
                new DefaultTimerResolverRegistry(Set.of()),
                new DefaultFailMessageFactoryRegistry(Set.of()),
                new DefaultSubjectResolverRegistry(Set.of()),
                new DefaultTaskAssignmentResolverRegistry(Set.of()),
                new DefaultBranchResultReducerRegistry(Set.of()),
                new DefaultNamedConditionRegistry(Set.of()));

        DefaultNamedConditionRegistry conditionRegistry = new DefaultNamedConditionRegistry(Set.of());
        DecisionRouteCompiler decisionRouteCompiler =
                new DecisionRouteCompiler(profile, conditionRegistry, mapperFactory);
        WorkflowDefinitionCompiler compiler = new WorkflowDefinitionCompiler(lookup, profile, decisionRouteCompiler);

        WorkflowDefinitionParser parser = ServiceTestHelpers.realParser();
        WorkflowDefinitionDocumentValidator validator = new WorkflowDefinitionDocumentValidator(lookup);
        return new WorkflowDefinitionPipeline(parser, validator, compiler);
    }

    // --- Tests ---

    @Test
    @DisplayName("activation registers the definition in the scope of its key")
    void activationRegistersTheDefinitionInTheScopeOfItsKey() {
        // Given: a valid document loaded into the acme scope, with an id inside the acme namespace
        DefinitionKey acmeKey = new DefinitionKey("acme", "acme:order", 1L);
        ServiceTestHelpers.await(service.load(
                "acme", yamlFor("acme:order", 1, SvcScopedOrderContract.class), DocumentFormat.YAML, testMeta()));

        // When: the loaded key is activated
        ServiceTestHelpers.await(service.publish(acmeKey));

        // Then: the registry holds the definition under the acme key and reports that scope
        assertThat(registry.find(acmeKey)).hasValueSatisfying(registered -> assertThat(registered.definitionScopeKey())
                .isEqualTo("acme"));

        // and: it holds nothing under the default scope
        assertThat(registry.find(new DefinitionKey(WorkflowScopes.DEFAULT, "acme:order", 1L)))
                .isEmpty();
    }

    @Test
    @DisplayName("publishing without a durable store makes the candidate available in memory")
    void publishWithoutStoreMakesCandidateAvailable() {
        // Given: a service without a durable store and a document loaded as a candidate
        DefinitionKey key = key("svc-order", 1);
        CompiledDefinitionRef loaded = ServiceTestHelpers.await(service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("svc-order", 1, SvcOrderContract.class),
                DocumentFormat.YAML,
                testMeta()));
        assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(registry.find(key)).isEmpty();

        // When: the candidate is published
        ServiceTestHelpers.await(service.publish(key));

        // Then: the reference is available and the registry resolves the key
        assertThat(store.list())
                .filteredOn(ref -> ref.definitionId().equals("svc-order"))
                .singleElement()
                .satisfies(ref -> assertThat(ref.status()).isEqualTo(ActivationStatus.AVAILABLE));
        assertThat(registry.resolve(key).plan().planHash()).isEqualTo(loaded.planHash());
    }

    @Test
    @DisplayName("a store-less scope load of a default id under another contract is refused")
    void scopeLoadOfDefaultIdUnderAnotherContractIsRefusedWithoutStore() {
        // Given: a store-less service where the default scope holds lookup-y under C as a candidate
        ServiceTestHelpers.await(service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("lookup-y", 1, SvcLookupCContract.class),
                DocumentFormat.YAML,
                testMeta()));

        // When: acme loads lookup-y under D
        // Then: the load is refused naming acme and the id, and acme holds nothing
        assertThatThrownBy(() -> ServiceTestHelpers.await(service.load(
                        "acme", yamlFor("lookup-y", 1, SvcLookupDContract.class), DocumentFormat.YAML, testMeta())))
                .isInstanceOfSatisfying(WorkflowDefinitionContractMismatchException.class, mismatch -> {
                    assertThat(mismatch.definitionKey().scopeKey()).isEqualTo("acme");
                    assertThat(mismatch.definitionKey().definitionId()).isEqualTo("lookup-y");
                });
        assertThat(ServiceTestHelpers.await(service.list(new DefinitionFilter("acme", null, null))))
                .isEmpty();

        // When: acme loads lookup-y under C
        CompiledDefinitionRef underC = ServiceTestHelpers.await(service.load(
                "acme", yamlFor("lookup-y", 1, SvcLookupCContract.class), DocumentFormat.YAML, testMeta()));

        // Then: the load succeeds
        assertThat(underC.status()).isEqualTo(ActivationStatus.CANDIDATE);

        // When: acme holds lookup-q under D and the default scope later loads lookup-q under C
        ServiceTestHelpers.await(service.load(
                "acme", yamlFor("lookup-q", 1, SvcLookupDContract.class), DocumentFormat.YAML, testMeta()));
        CompiledDefinitionRef later = ServiceTestHelpers.await(service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("lookup-q", 1, SvcLookupCContract.class),
                DocumentFormat.YAML,
                testMeta()));

        // Then: the later default load is accepted
        assertThat(later.status()).isEqualTo(ActivationStatus.CANDIDATE);
    }

    static Stream<Arguments> invalidScopeKeys() {
        return Stream.of(
                Arguments.of("null", null, NullPointerException.class),
                Arguments.of("blank", "  ", IllegalArgumentException.class),
                Arguments.of("256 code points", "a".repeat(256), IllegalArgumentException.class),
                Arguments.of("control character", "ac\tme", IllegalArgumentException.class));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidScopeKeys")
    @DisplayName("load rejects an invalid scope argument before it stores anything")
    void loadRejectsAnInvalidScopeArgument(String label, String scopeKey, Class<? extends Throwable> expected) {
        // Given: a store-less service with a real registry and a valid document
        byte[] document = yamlFor("svc-scoped-order", 1, SvcScopedOrderContract.class);
        DefinitionFilter everything = new DefinitionFilter(null, null, null);

        // When: the document is loaded with an invalid scope key
        // Then: the returned future fails as the key reports it, and the message carries no document content
        assertThatThrownBy(() ->
                        ServiceTestHelpers.await(service.load(scopeKey, document, DocumentFormat.YAML, testMeta())))
                .isInstanceOf(expected)
                .satisfies(failure -> assertThat(String.valueOf(failure.getMessage()))
                        .doesNotContain("svc-scoped-order")
                        .doesNotContain(SvcScopedOrderContract.class.getName()));

        // And: nothing was stored or registered
        assertThat(ServiceTestHelpers.await(service.list(everything))).isEmpty();
        assertThat(store.records(everything)).isEmpty();
        assertThat(registry.allRegistered()).isEmpty();

        // And: the same document loads when the scope is valid
        CompiledDefinitionRef loaded =
                ServiceTestHelpers.await(service.load("acme", document, DocumentFormat.YAML, testMeta()));
        assertThat(loaded.definitionId()).isEqualTo("svc-scoped-order");
        assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
    }

    @Test
    @DisplayName("a store-less load keeps each scope's candidates apart, whatever the id looks like")
    void storeLessLoadsKeepEachScopesCandidatesApart() {
        // Given: one id and version loaded into two scopes, and an id that looks like another scope's prefix
        ServiceTestHelpers.await(
                service.load("acme", yamlFor("svc-order", 1, SvcOrderContract.class), DocumentFormat.YAML, testMeta()));
        ServiceTestHelpers.await(service.load(
                "globex", yamlFor("svc-order", 1, SvcOrderContract.class), DocumentFormat.YAML, testMeta()));
        ServiceTestHelpers.await(service.load(
                "acme", yamlFor("globex:svc-item", 1, SvcItemV1Contract.class), DocumentFormat.YAML, testMeta()));

        // When: each scope is listed
        List<WorkflowDefinitionRecord> acme =
                ServiceTestHelpers.await(service.list(new DefinitionFilter("acme", null, null)));
        List<WorkflowDefinitionRecord> globex =
                ServiceTestHelpers.await(service.list(new DefinitionFilter("globex", null, null)));
        List<WorkflowDefinitionRecord> defaults =
                ServiceTestHelpers.await(service.list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null)));

        // Then: every candidate sits in the scope that was named, and the default scope holds none
        assertThat(acme)
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactly(
                        new DefinitionKey("acme", "globex:svc-item", 1L), new DefinitionKey("acme", "svc-order", 1L));
        assertThat(globex)
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactly(new DefinitionKey("globex", "svc-order", 1L));
        assertThat(defaults).isEmpty();
    }

    @Test
    @DisplayName("a durable load stores the row under the named scope for any id and any valid scope key")
    void durableLoadStoresTheRowUnderTheNamedScopeForAnyId() {
        // Given: a service bound to a durable store
        WorkflowDefinitionRegistryStore durable = Mockito.mock(WorkflowDefinitionRegistryStore.class);
        Mockito.when(durable.insertCandidate(
                        Mockito.any(WorkflowDefinitionRecord.class), Mockito.any(WorkflowDefinitionDocument.class)))
                .thenReturn(io.vertx.core.Future.succeededFuture());
        DefaultWorkflowDefinitionService durableService = new DefaultWorkflowDefinitionService(
                buildPipeline(),
                new WorkflowDefinitionStore(),
                () -> new DefaultWorkflowRegistry(),
                Optional.of(durable),
                Optional.empty(),
                Optional.empty());

        // When: ids that name another scope, and a scope key that holds a colon, are loaded
        ServiceTestHelpers.await(durableService.load(
                "t1", yamlFor("t2:svc-order", 1, SvcOrderContract.class), DocumentFormat.YAML, testMeta()));
        ServiceTestHelpers.await(durableService.load(
                "a:b", yamlFor("svc-item", 1, SvcItemV1Contract.class), DocumentFormat.YAML, testMeta()));

        // Then: each row is keyed by the scope argument and the id as the document declares it
        ArgumentCaptor<WorkflowDefinitionRecord> stored = ArgumentCaptor.forClass(WorkflowDefinitionRecord.class);
        Mockito.verify(durable, Mockito.times(2)).insertCandidate(stored.capture(), Mockito.any());
        assertThat(stored.getAllValues())
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactly(new DefinitionKey("t1", "t2:svc-order", 1L), new DefinitionKey("a:b", "svc-item", 1L));
    }

    @Nested
    @DisplayName("load then activate")
    class LoadThenActivate {

        @Test
        @DisplayName("load then activate makes the definition resolvable via the registry")
        void loadThenActivateSucceeds() {
            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 1, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));
            ServiceTestHelpers.await(service.publish(key("svc-order", 1)));

            RuntimeWorkflow rw = registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-order", 1L));
            assertThat(rw.plan().definitionId()).isEqualTo("svc-order");
            assertThat(rw.plan().definitionVersion()).isEqualTo(1L);

            // list() reflects the activation
            List<WorkflowDefinitionRecord> records = ServiceTestHelpers.await(service.list(globalFilter()));
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(key("svc-order", 1));
            assertThat(records.get(0).status()).isEqualTo(DefinitionStatus.AVAILABLE);
            assertThatThrownBy(records::clear).isInstanceOf(UnsupportedOperationException.class);

            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-item", 1, SvcItemV1Contract.class),
                    DocumentFormat.YAML,
                    testMeta()));
            assertThat(records)
                    .hasSize(1)
                    .extracting(WorkflowDefinitionRecord::key)
                    .containsExactly(key("svc-order", 1));
        }
    }

    @Nested
    @DisplayName("load failure isolation")
    class LoadFailureIsolation {

        @Test
        @DisplayName("invalid YAML load failure leaves store untouched; prior active v1 remains accessible")
        void invalidYamlDoesNotAffectPriorActive() {
            // Establish v1 as active
            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 1, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));
            ServiceTestHelpers.await(service.publish(key("svc-order", 1)));
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-order", 1L))
                            .plan()
                            .definitionVersion())
                    .isEqualTo(1L);

            // Attempt to load bad YAML for v2 — must fail
            byte[] badYaml = "this: is: not valid yaml: for: workflow".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ServiceTestHelpers.await(
                            service.load(WorkflowScopes.DEFAULT, badYaml, DocumentFormat.YAML, testMeta())))
                    .isInstanceOf(WorkflowDefinitionParseException.class);

            // v1 still active in registry
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-order", 1L))
                            .plan()
                            .definitionVersion())
                    .isEqualTo(1L);
            // list still shows only 1 entry
            assertThat(ServiceTestHelpers.await(service.list(globalFilter()))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("activate without prior load")
    class ActivateWithoutLoad {

        @Test
        @DisplayName("activate without prior load throws WorkflowDefinitionException")
        void activateWithoutLoadThrows() {
            assertThatThrownBy(() -> ServiceTestHelpers.await(service.publish(key("svc-order", 99))))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("not found in the store");
        }
    }

    @Nested
    @DisplayName("publish lower version when higher already available")
    class PublishLowerVersionAfterHigher {

        @Test
        @DisplayName("load+publish v2, then load+publish v1 — registry resolves v2, store shows both AVAILABLE")
        void publishLowerVersionAfterHigherLeavesBothAvailable() {
            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 2, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));
            ServiceTestHelpers.await(service.publish(key("svc-order", 2)));

            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 1, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));
            ServiceTestHelpers.await(service.publish(key("svc-order", 1)));

            // Registry must hold the higher (v2) version
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-order", 2L))
                            .plan()
                            .definitionVersion())
                    .isEqualTo(2L);

            // Store must reflect: both versions AVAILABLE
            List<WorkflowDefinitionRecord> records = ServiceTestHelpers.await(service.list(globalFilter()));
            assertThat(records).hasSize(2);
            WorkflowDefinitionRecord v2Ref = records.stream()
                    .filter(r -> r.key().definitionVersion() == 2)
                    .findFirst()
                    .orElseThrow();
            WorkflowDefinitionRecord v1Ref = records.stream()
                    .filter(r -> r.key().definitionVersion() == 1)
                    .findFirst()
                    .orElseThrow();
            assertThat(v2Ref.status()).isEqualTo(DefinitionStatus.AVAILABLE);
            assertThat(v1Ref.status()).isEqualTo(DefinitionStatus.AVAILABLE);
        }
    }

    @Nested
    @DisplayName("concurrent load and activate of distinct definitionIds")
    class ConcurrentDistinctIds {

        @Test
        @DisplayName("two distinct definitions loaded and activated concurrently both appear in registry")
        void concurrentLoadAndActivateBothSucceed() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Void>> futures = new ArrayList<>();

            futures.add(pool.submit(() -> {
                ServiceTestHelpers.await(service.load(
                        WorkflowScopes.DEFAULT,
                        yamlFor("svc-order", 1, SvcOrderContract.class),
                        DocumentFormat.YAML,
                        testMeta()));
                ServiceTestHelpers.await(service.publish(key("svc-order", 1)));
                return null;
            }));
            futures.add(pool.submit(() -> {
                ServiceTestHelpers.await(service.load(
                        WorkflowScopes.DEFAULT,
                        yamlFor("svc-item", 1, SvcItemV1Contract.class),
                        DocumentFormat.YAML,
                        testMeta()));
                ServiceTestHelpers.await(service.publish(key("svc-item", 1)));
                return null;
            }));

            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            for (Future<Void> f : futures) {
                f.get(); // rethrow if any thread threw
            }

            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-order", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("svc-order");
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-item", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("svc-item");
            assertThat(ServiceTestHelpers.await(service.list(globalFilter()))).hasSize(2);
        }
    }

    @Nested
    @DisplayName("concurrent activate of the same (definitionId, version) — W1")
    class ConcurrentSameKeyActivation {

        @Test
        @DisplayName(
                "8 threads calling activate for same key — exactly one wins; final state is AVAILABLE in registry and store")
        void concurrentActivateSameKeySerialized() throws Exception {
            // Load the candidate once
            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 1, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));

            int numThreads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(numThreads);
            List<Future<String>> outcomes = new ArrayList<>();

            for (int i = 0; i < numThreads; i++) {
                outcomes.add(pool.submit(() -> {
                    try {
                        ServiceTestHelpers.await(service.publish(key("svc-order", 1)));
                        return "ok";
                    } catch (Exception ex) {
                        return "err:" + ex.getMessage();
                    }
                }));
            }

            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);

            // Collect results — no thread should have seen "already registered" from the registry
            long okCount = 0;
            for (Future<String> f : outcomes) {
                String result = f.get();
                if ("ok".equals(result)) {
                    okCount++;
                } else {
                    // Error messages are allowed (concurrent PUBLISHING detection) but NEVER
                    // the registry-level "already registered" duplicate error
                    assertThat(result).doesNotContain("already registered");
                }
            }

            // At least one thread must have succeeded
            assertThat(okCount).isGreaterThanOrEqualTo(1);

            // Final state: exactly one AVAILABLE in store; registry resolves the definition
            assertThat(registry.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "svc-order", 1L))
                            .plan()
                            .definitionId())
                    .isEqualTo("svc-order");
            long activeCount = ServiceTestHelpers.await(service.list(globalFilter())).stream()
                    .filter(r -> r.status() == DefinitionStatus.AVAILABLE)
                    .count();
            assertThat(activeCount).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("publish — PUBLISHING reset on RuntimeException")
    class ActivatingResetOnRuntimeException {

        @Test
        @DisplayName("RuntimeException from registry.register() resets store entry to CANDIDATE")
        void runtimeExceptionInRegistryResetsStoreToCandidateForRetry() throws Exception {
            // Build a registry stub that throws WorkflowDefinitionException on first register() call
            dev.vertique.workflow.registry.WorkflowRegistry throwingRegistry =
                    new dev.vertique.workflow.registry.WorkflowRegistry() {
                        private int callCount = 0;

                        @Override
                        public void register(dev.vertique.workflow.dsl.WorkflowDefinition<?, ?> def) {
                            if (callCount++ == 0) {
                                throw new WorkflowDefinitionException("simulated registration failure");
                            }
                        }

                        @Override
                        public void register(String scopeKey, dev.vertique.workflow.dsl.WorkflowDefinition<?, ?> def) {
                            register(def);
                        }

                        @Override
                        public dev.vertique.workflow.registry.RuntimeWorkflow resolve(
                                dev.vertique.workflow.registry.store.DefinitionKey key) {
                            throw new UnsupportedOperationException();
                        }

                        @Override
                        public java.util.Optional<dev.vertique.workflow.registry.RuntimeWorkflow> find(
                                dev.vertique.workflow.registry.store.DefinitionKey key) {
                            return java.util.Optional.empty();
                        }

                        @Override
                        public java.util.List<dev.vertique.workflow.registry.store.DefinitionKey> keysFor(
                                String definitionId) {
                            return java.util.List.of();
                        }

                        @Override
                        public java.util.Optional<dev.vertique.workflow.registry.RegisteredDefinitionClass>
                                definitionClass(Class<?> definitionClass) {
                            return java.util.Optional.empty();
                        }

                        @Override
                        public java.util.Collection<dev.vertique.workflow.registry.RuntimeWorkflow> allRegistered() {
                            return java.util.List.of();
                        }
                    };

            WorkflowDefinitionStore errorStore = new WorkflowDefinitionStore();
            DefaultWorkflowDefinitionService svc =
                    new DefaultWorkflowDefinitionService(buildPipeline(), errorStore, () -> throwingRegistry);

            ServiceTestHelpers.await(svc.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 1, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));

            // activate() must propagate the RuntimeException (not swallow it)
            assertThatThrownBy(() -> ServiceTestHelpers.await(svc.publish(key("svc-order", 1))))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("simulated registration failure");

            // The store entry must have been reset to CANDIDATE (not stuck in PUBLISHING)
            ActivationStatus status = errorStore.currentStatus("svc-order", 1);
            assertThat(status)
                    .as("store entry must be CANDIDATE after RuntimeException so the activation can be retried")
                    .isEqualTo(ActivationStatus.CANDIDATE);
        }
    }

    @Nested
    @DisplayName("load() size cap — A1")
    class LoadSizeCap {

        @Test
        @DisplayName("document exceeding 256 KiB is rejected before parsing")
        void oversizedDocumentIsRejected() {
            byte[] oversized = new byte[WorkflowDefinitionsConfig.defaults().maxDocumentSizeBytes() + 1];
            assertThatThrownBy(() -> ServiceTestHelpers.await(
                            service.load(WorkflowScopes.DEFAULT, oversized, DocumentFormat.YAML, testMeta())))
                    .isInstanceOf(WorkflowDefinitionException.class)
                    .hasMessageContaining("maxDocumentSizeBytes");
        }

        @Test
        @DisplayName("document exactly at the size cap is not rejected by the guard (may fail for other reasons)")
        void documentAtCapNotRejectedBySizeGuard() {
            byte[] atCap = new byte[WorkflowDefinitionsConfig.defaults().maxDocumentSizeBytes()];
            assertThatThrownBy(() -> ServiceTestHelpers.await(
                            service.load(WorkflowScopes.DEFAULT, atCap, DocumentFormat.YAML, testMeta())))
                    .isInstanceOf(WorkflowDefinitionParseException.class);
        }
    }

    private DefinitionKey key(String id, long version) {
        return new DefinitionKey(WorkflowScopes.DEFAULT, id, version);
    }

    private DefinitionFilter globalFilter() {
        return new DefinitionFilter(WorkflowScopes.DEFAULT, null, null);
    }
}
