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
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.engine.testsupport.NoOpWorkflowDefinitionLifecycle;
import dev.vertique.workflow.exception.WorkflowDefinitionContractMismatchException;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        service = new DefaultWorkflowDefinitionService(
                pipeline,
                store,
                () -> registry,
                new InMemoryWorkflowDefinitionRegistryStore(),
                new NoOpWorkflowDefinitionLifecycle(),
                Optional.empty());
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
    @DisplayName("a scope load of a default id under another contract is refused")
    void scopeLoadOfDefaultIdUnderAnotherContractIsRefused() {
        // Given: a service where the default scope holds lookup-y under C as a candidate
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
        // Given: a service with a real registry and a valid document
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
    @DisplayName("a load keeps each scope's candidates apart, whatever the id looks like")
    void loadsKeepEachScopesCandidatesApart() {
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
                durable,
                new NoOpWorkflowDefinitionLifecycle(),
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
    @DisplayName("load failure isolation")
    class LoadFailureIsolation {

        @Test
        @DisplayName("invalid YAML load failure leaves the store untouched; the prior v1 candidate remains listed")
        void invalidYamlDoesNotAffectPriorCandidate() {
            // Establish v1 as a candidate
            ServiceTestHelpers.await(service.load(
                    WorkflowScopes.DEFAULT,
                    yamlFor("svc-order", 1, SvcOrderContract.class),
                    DocumentFormat.YAML,
                    testMeta()));

            // Attempt to load bad YAML for v2 — must fail
            byte[] badYaml = "this: is: not valid yaml: for: workflow".getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> ServiceTestHelpers.await(
                            service.load(WorkflowScopes.DEFAULT, badYaml, DocumentFormat.YAML, testMeta())))
                    .isInstanceOf(WorkflowDefinitionParseException.class);

            // list still shows only 1 entry
            assertThat(ServiceTestHelpers.await(service.list(globalFilter()))).hasSize(1);
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
