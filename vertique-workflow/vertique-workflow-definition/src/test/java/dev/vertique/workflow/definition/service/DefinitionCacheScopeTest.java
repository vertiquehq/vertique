// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that the in-memory definition cache keeps the retained unscoped members on the global
 * entry while key-scoped members, the service load path and the loader hooks never cross tenants.
 */
class DefinitionCacheScopeTest {

    record OrderState(String id) {}

    record PlaceOrder(String id) {}

    @WorkflowContract
    interface ScopeOrderV1 {}

    @WorkflowContract
    interface ScopeOrderV2 {}

    private static final String ID = "scope-order";
    private static final Instant LOADED = Instant.parse("2026-01-01T00:00:00Z");

    private static final SourceMetadata GLOBAL_SOURCE =
            new SourceMetadata("global-source", "uri:global", "rev-global", "author-global", "upload-global", LOADED);
    private static final SourceMetadata TENANT_A_SOURCE =
            new SourceMetadata("tenant-a-source", "uri:tenant-a", "rev-a", "author-a", "upload-a", LOADED);
    private static final SourceMetadata TENANT_B_SOURCE =
            new SourceMetadata("tenant-b-source", "uri:tenant-b", "rev-b", "author-b", "upload-b", LOADED);

    private static final String GLOBAL_HASH = "a".repeat(64);
    private static final String TENANT_A_HASH = "b".repeat(64);
    private static final String TENANT_B_HASH = "c".repeat(64);
    private static final String GLOBAL_V2_HASH = "d".repeat(64);
    private static final String TENANT_A_V2_HASH = "e".repeat(64);

    private static final DefinitionKey GLOBAL_V1 = new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1);
    private static final DefinitionKey GLOBAL_V2 = new DefinitionKey(WorkflowScopes.DEFAULT, ID, 2);
    private static final DefinitionKey TENANT_A_V1 = new DefinitionKey("tenant-a", ID, 1);
    private static final DefinitionKey TENANT_A_V2 = new DefinitionKey("tenant-a", ID, 2);
    private static final DefinitionKey TENANT_B_V1 = new DefinitionKey("tenant-b", ID, 1);

    @Test
    @DisplayName("unscoped cache members address only the global entry and scoped members never cross tenants")
    void isolatesLegacyGlobalAndScopedTenantCache() {
        WorkflowDefinitionStore cache = new WorkflowDefinitionStore();
        CompiledDefinition globalV1 = compiled(1, ScopeOrderV1.class, GLOBAL_HASH, GLOBAL_SOURCE);
        CompiledDefinition tenantAV1 = compiled(1, ScopeOrderV1.class, TENANT_A_HASH, TENANT_A_SOURCE);
        CompiledDefinition tenantBV1 = compiled(1, ScopeOrderV1.class, TENANT_B_HASH, TENANT_B_SOURCE);
        CompiledDefinition globalV2 = compiled(2, ScopeOrderV2.class, GLOBAL_V2_HASH, GLOBAL_SOURCE);
        CompiledDefinition tenantAV2 = compiled(2, ScopeOrderV2.class, TENANT_A_V2_HASH, TENANT_A_SOURCE);

        // Given tenant candidates for the same id and version arrive before any global entry exists
        cache.candidate(record(TENANT_A_V1, tenantAV1, DefinitionStatus.CANDIDATE), tenantAV1);
        cache.candidate(record(TENANT_B_V1, tenantBV1, DefinitionStatus.CANDIDATE), tenantBV1);
        assertThat(cache.tryClaimForActivation(TENANT_A_V1)).isTrue();

        // When every retained unscoped member runs against the global key
        // Then none of them sees or touches the tenant entries
        assertThat(cache.get(ID, 1)).isEmpty();
        assertThat(cache.currentStatus(ID, 1)).isNull();
        assertThat(cache.sourceMetadata(ID, 1)).isEmpty();
        assertThat(cache.list()).isEmpty();
        assertThatThrownBy(() -> cache.tryClaimForActivation(ID, 1))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> cache.markActive(ID, 1))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("no entry");
        cache.resetToCandidate(ID, 1);
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.PUBLISHING);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);

        // When the legacy store call adds the global candidate for the same id and version
        // Then it does not collide with the tenant entries and addresses only the global entry
        assertThatCode(() -> cache.storeCandidate(globalV1)).doesNotThrowAnyException();
        cache.metadata(record(GLOBAL_V1, globalV1, DefinitionStatus.CANDIDATE));
        assertThat(cache.get(ID, 1).orElseThrow().compiled()).isSameAs(globalV1);
        assertThat(cache.get(TENANT_A_V1).orElseThrow().compiled()).isSameAs(tenantAV1);
        assertThat(cache.get(TENANT_B_V1).orElseThrow().compiled()).isSameAs(tenantBV1);
        assertThat(cache.sourceMetadata(ID, 1)).contains(GLOBAL_SOURCE);
        assertThat(cache.sourceMetadata(TENANT_A_V1)).contains(TENANT_A_SOURCE);
        assertThat(cache.sourceMetadata(TENANT_B_V1)).contains(TENANT_B_SOURCE);
        assertThat(cache.list()).singleElement().satisfies(ref -> {
            assertThat(ref.definitionId()).isEqualTo(ID);
            assertThat(ref.definitionVersion()).isEqualTo(1);
            assertThat(ref.planHash()).isEqualTo(GLOBAL_HASH);
            assertThat(ref.source()).isEqualTo(GLOBAL_SOURCE);
            assertThat(ref.status()).isEqualTo(ActivationStatus.CANDIDATE);
        });

        // When the unscoped claim, reset and activation run while tenant a is still activating
        // Then only the global entry moves
        assertThat(cache.tryClaimForActivation(ID, 1)).isTrue();
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.PUBLISHING);
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.PUBLISHING);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);
        cache.resetToCandidate(ID, 1);
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.PUBLISHING);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(cache.tryClaimForActivation(ID, 1)).isTrue();
        assertThat(cache.markActive(ID, 1)).isTrue();
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.PUBLISHING);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(cache.list()).singleElement().satisfies(ref -> {
            assertThat(ref.status()).isEqualTo(ActivationStatus.AVAILABLE);
            assertThat(ref.planHash()).isEqualTo(GLOBAL_HASH);
        });

        // When the scoped claim, reset and activation run for the tenants
        // Then the global entry and the other tenant never change
        assertThat(cache.markActive(TENANT_A_V1)).isTrue();
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(cache.tryClaimForActivation(TENANT_B_V1)).isTrue();
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.PUBLISHING);
        cache.resetToCandidate(TENANT_B_V1);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.tryClaimForActivation(TENANT_B_V1)).isTrue();
        assertThat(cache.markActive(TENANT_B_V1)).isTrue();

        // When tenant a activates a higher version
        // Then every earlier version stays available beside it
        cache.candidate(record(TENANT_A_V2, tenantAV2, DefinitionStatus.CANDIDATE), tenantAV2);
        assertThat(cache.tryClaimForActivation(TENANT_A_V2)).isTrue();
        assertThat(cache.markActive(TENANT_A_V2)).isTrue();
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_A_V2)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.AVAILABLE);

        // When the legacy members publish a higher global version
        // Then the global lower version stays available beside it
        cache.storeCandidate(globalV2);
        cache.metadata(record(GLOBAL_V2, globalV2, DefinitionStatus.CANDIDATE));
        assertThat(cache.tryClaimForActivation(ID, 2)).isTrue();
        assertThat(cache.markActive(ID, 2)).isTrue();
        assertThat(cache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(ID, 2)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_A_V2)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.list())
                .extracting(CompiledDefinitionRef::definitionVersion, CompiledDefinitionRef::status)
                .containsExactlyInAnyOrder(
                        tuple(1L, ActivationStatus.AVAILABLE), tuple(2L, ActivationStatus.AVAILABLE));
        assertThat(cache.list()).allSatisfy(ref -> assertThat(ref.source()).isEqualTo(GLOBAL_SOURCE));

        // Then the scoped metadata views return exactly each scope's rows with its own provenance
        assertThat(records(cache, null, null))
                .containsExactlyInAnyOrder(GLOBAL_V1, GLOBAL_V2, TENANT_A_V1, TENANT_A_V2, TENANT_B_V1);
        assertThat(records(cache, WorkflowScopes.DEFAULT, null)).containsExactlyInAnyOrder(GLOBAL_V1, GLOBAL_V2);
        assertThat(records(cache, "tenant-a", null)).containsExactlyInAnyOrder(TENANT_A_V1, TENANT_A_V2);
        assertThat(records(cache, "tenant-b", null)).containsExactly(TENANT_B_V1);
        assertThat(records(cache, "tenant-c", null)).isEmpty();
        assertThat(records(cache, null, Set.of(DefinitionStatus.AVAILABLE)))
                .containsExactlyInAnyOrder(GLOBAL_V1, GLOBAL_V2, TENANT_A_V1, TENANT_A_V2, TENANT_B_V1);
        assertThat(records(cache, WorkflowScopes.DEFAULT, Set.of(DefinitionStatus.AVAILABLE)))
                .containsExactlyInAnyOrder(GLOBAL_V1, GLOBAL_V2);
        assertThat(records(cache, "tenant-a", Set.of())).isEmpty();
        assertThat(cache.sourceMetadata(ID, 1)).contains(GLOBAL_SOURCE);
        assertThat(cache.sourceMetadata(TENANT_A_V1)).contains(TENANT_A_SOURCE);
        assertThat(cache.sourceMetadata(TENANT_B_V1)).contains(TENANT_B_SOURCE);
        assertThat(cache.sourceMetadata(new DefinitionKey("tenant-c", ID, 1))).isEmpty();
        assertThat(cache.get(new DefinitionKey("tenant-c", ID, 1))).isEmpty();
        assertThat(cache.currentStatus(new DefinitionKey("tenant-c", ID, 1))).isNull();

        // When committed metadata for one tenant arrives with new provenance and a new status
        // Then only that tenant's view changes
        DefinitionProvenance changed =
                new DefinitionProvenance("tenant-b-reloaded", "uri:tenant-b-2", "rev-b-2", null, null, LOADED);
        cache.metadata(new WorkflowDefinitionRecord(
                TENANT_B_V1,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                TENANT_B_HASH,
                DefinitionStatus.DEPRECATED,
                changed,
                "YAML",
                LOADED,
                LOADED,
                Map.of()));
        assertThat(cache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.DEPRECATED);
        assertThat(cache.sourceMetadata(TENANT_B_V1))
                .contains(new SourceMetadata("tenant-b-reloaded", "uri:tenant-b-2", "rev-b-2", null, null, LOADED));
        assertThat(cache.sourceMetadata(TENANT_A_V1)).contains(TENANT_A_SOURCE);
        assertThat(cache.sourceMetadata(ID, 1)).contains(GLOBAL_SOURCE);
        assertThat(cache.currentStatus(ID, 2)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(cache.currentStatus(TENANT_A_V2)).isEqualTo(ActivationStatus.AVAILABLE);

        // When the loader restores an active tenant row and receives metadata for another tenant
        WorkflowDefinitionStore restored = new WorkflowDefinitionStore();
        DocumentDefinitionLoader loader = new DocumentDefinitionLoader(null, null, restored, Optional.empty());
        restored.storeCandidate(globalV1);
        restored.metadata(record(GLOBAL_V1, globalV1, DefinitionStatus.CANDIDATE));
        loader.onRestored(record(TENANT_A_V1, tenantAV1, DefinitionStatus.AVAILABLE), tenantAV1.definition());
        loader.onMetadata(record(TENANT_B_V1, tenantBV1, DefinitionStatus.CANDIDATE));

        // Then the restored tenant row is cached under its own key with its own provenance
        assertThat(restored.get(TENANT_A_V1).orElseThrow().compiled().planHash())
                .isEqualTo(TENANT_A_HASH);
        assertThat(restored.get(TENANT_A_V1).orElseThrow().compiled().source()).isEqualTo(TENANT_A_SOURCE);
        assertThat(restored.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(restored.sourceMetadata(TENANT_A_V1)).contains(TENANT_A_SOURCE);

        // And the metadata-only tenant has a metadata row but no executable cache entry
        assertThat(restored.get(TENANT_B_V1)).isEmpty();
        assertThat(restored.sourceMetadata(TENANT_B_V1)).contains(TENANT_B_SOURCE);
        assertThat(records(restored, "tenant-b", null)).containsExactly(TENANT_B_V1);

        // And the global entry is unchanged and still the only unscoped result
        assertThat(restored.get(ID, 1).orElseThrow().compiled()).isSameAs(globalV1);
        assertThat(restored.currentStatus(ID, 1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(restored.sourceMetadata(ID, 1)).contains(GLOBAL_SOURCE);
        assertThat(restored.list()).singleElement().satisfies(ref -> {
            assertThat(ref.planHash()).isEqualTo(GLOBAL_HASH);
            assertThat(ref.status()).isEqualTo(ActivationStatus.CANDIDATE);
            assertThat(ref.source()).isEqualTo(GLOBAL_SOURCE);
        });
        assertThat(records(restored, WorkflowScopes.DEFAULT, null)).containsExactly(GLOBAL_V1);

        // When the service loads the same document globally and for two tenants with distinct provenance
        WorkflowDefinitionStore serviceCache = new WorkflowDefinitionStore();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        DefaultWorkflowDefinitionService service =
                new DefaultWorkflowDefinitionService(buildPipeline(), serviceCache, () -> registry);
        byte[] document = yamlFor(ID, 1, ScopeOrderV1.class);
        ServiceTestHelpers.await(service.load(WorkflowScopes.DEFAULT, document, DocumentFormat.YAML, GLOBAL_SOURCE));
        ServiceTestHelpers.await(service.load("tenant-a", document, DocumentFormat.YAML, TENANT_A_SOURCE));
        ServiceTestHelpers.await(service.load("tenant-b", document, DocumentFormat.YAML, TENANT_B_SOURCE));

        // Then each scope lists and reports only its own row
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(GLOBAL_V1))).contains(GLOBAL_SOURCE);
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(TENANT_A_V1)))
                .contains(TENANT_A_SOURCE);
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(TENANT_B_V1)))
                .contains(TENANT_B_SOURCE);
        assertThat(serviceCache.list()).singleElement().satisfies(ref -> assertThat(ref.source())
                .isEqualTo(GLOBAL_SOURCE));
        assertThat(ServiceTestHelpers.await(service.list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))))
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactly(GLOBAL_V1);
        assertThat(ServiceTestHelpers.await(service.list(new DefinitionFilter("tenant-a", null, null))))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.key()).isEqualTo(TENANT_A_V1);
                    assertThat(row.provenance().sourceType()).isEqualTo("tenant-a-source");
                });
        assertThat(ServiceTestHelpers.await(service.list(new DefinitionFilter("tenant-b", null, null))))
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactly(TENANT_B_V1);
        assertThat(ServiceTestHelpers.await(service.list(new DefinitionFilter(null, null, null))))
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactlyInAnyOrder(GLOBAL_V1, TENANT_A_V1, TENANT_B_V1);

        // When one tenant activates through the service
        ServiceTestHelpers.await(service.publish(TENANT_A_V1));

        // Then only that tenant's cache entry becomes active
        assertThat(serviceCache.currentStatus(TENANT_A_V1)).isEqualTo(ActivationStatus.AVAILABLE);
        assertThat(serviceCache.currentStatus(ID, 1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(serviceCache.currentStatus(TENANT_B_V1)).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(serviceCache.list()).singleElement().satisfies(ref -> assertThat(ref.status())
                .isEqualTo(ActivationStatus.CANDIDATE));
        assertThat(registry.allRegistered()).hasSize(1);
    }

    private static List<DefinitionKey> records(
            WorkflowDefinitionStore cache, String scopeKey, Set<DefinitionStatus> statuses) {
        return cache.records(new DefinitionFilter(scopeKey, null, statuses)).stream()
                .map(WorkflowDefinitionRecord::key)
                .toList();
    }

    private static WorkflowDefinitionRecord record(
            DefinitionKey key, CompiledDefinition compiled, DefinitionStatus status) {
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                compiled.planHash(),
                status,
                DefaultWorkflowDefinitionService.provenance(compiled.source()),
                "YAML",
                LOADED,
                LOADED,
                Map.of());
    }

    private static CompiledDefinition compiled(
            long version, Class<?> contract, String planHash, SourceMetadata source) {
        return new CompiledDefinition(
                definition(version, contract), planHash, source, OrderState.class.getName(), contract.getName());
    }

    private static WorkflowDefinition<OrderState, ?> definition(long version, Class<?> contract) {
        return new WorkflowDefinition<>() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public Class contract() {
                return contract;
            }

            @Override
            public Class<OrderState> stateType() {
                return OrderState.class;
            }

            @Override
            public String definitionId() {
                return ID;
            }

            @Override
            public long definitionVersion() {
                return version;
            }

            @Override
            public void define(WorkflowBuilder<OrderState> wf) {
                wf.init(PlaceOrder.class, cmd -> new OrderState(cmd.id()))
                        .initialStep("done")
                        .complete("done");
            }
        };
    }

    private static byte[] yamlFor(String definitionId, long version, Class<?> contract) {
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
                definitionId, version, OrderState.class.getName(), contract.getName(), PlaceOrder.class.getName());
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WorkflowDefinitionPipeline buildPipeline() {
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
}
