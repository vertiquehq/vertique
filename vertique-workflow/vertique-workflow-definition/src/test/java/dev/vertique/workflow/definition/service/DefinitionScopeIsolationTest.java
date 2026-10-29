// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.engine.testsupport.NoOpWorkflowDefinitionLifecycle;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies that the service load path keeps the rows of one id and version apart per scope: each scope lists and
 * reports only its own row and provenance, and a publication reaches the lifecycle with exactly the key that was named.
 */
class DefinitionScopeIsolationTest {

    record OrderState(String id) {}

    record PlaceOrder(String id) {}

    @WorkflowContract
    interface ScopeOrderV1 {}

    private static final String ID = "scope-order";
    private static final Instant LOADED = Instant.parse("2026-01-01T00:00:00Z");

    private static final SourceMetadata GLOBAL_SOURCE =
            new SourceMetadata("global-source", "uri:global", "rev-global", "author-global", "upload-global", LOADED);
    private static final SourceMetadata TENANT_A_SOURCE =
            new SourceMetadata("tenant-a-source", "uri:tenant-a", "rev-a", "author-a", "upload-a", LOADED);
    private static final SourceMetadata TENANT_B_SOURCE =
            new SourceMetadata("tenant-b-source", "uri:tenant-b", "rev-b", "author-b", "upload-b", LOADED);

    private static final DefinitionKey GLOBAL_V1 = new DefinitionKey(WorkflowScopes.DEFAULT, ID, 1);
    private static final DefinitionKey TENANT_A_V1 = new DefinitionKey("tenant-a", ID, 1);
    private static final DefinitionKey TENANT_B_V1 = new DefinitionKey("tenant-b", ID, 1);

    @Test
    @DisplayName("a load of the same document into three scopes keeps each scope's row and provenance apart")
    void keepsEachScopesRowsAndProvenanceApart() {
        // Given a service over a durable store
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        NoOpWorkflowDefinitionLifecycle lifecycle = new NoOpWorkflowDefinitionLifecycle();
        DefaultWorkflowDefinitionService service = new DefaultWorkflowDefinitionService(
                buildPipeline(),
                () -> registry,
                new InMemoryWorkflowDefinitionRegistryStore(),
                lifecycle,
                Optional.empty());
        byte[] document = yamlFor(ID, 1, ScopeOrderV1.class);

        // When the service loads the same document globally and for two tenants with distinct provenance
        ServiceTestHelpers.await(service.load(WorkflowScopes.DEFAULT, document, DocumentFormat.YAML, GLOBAL_SOURCE));
        ServiceTestHelpers.await(service.load("tenant-a", document, DocumentFormat.YAML, TENANT_A_SOURCE));
        ServiceTestHelpers.await(service.load("tenant-b", document, DocumentFormat.YAML, TENANT_B_SOURCE));

        // Then each scope lists and reports only its own row
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(GLOBAL_V1))).contains(GLOBAL_SOURCE);
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(TENANT_A_V1)))
                .contains(TENANT_A_SOURCE);
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(TENANT_B_V1)))
                .contains(TENANT_B_SOURCE);
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

        // When one tenant publishes through the service
        ServiceTestHelpers.await(service.publish(TENANT_A_V1));

        // Then the lifecycle received exactly that key and the service registered nothing itself
        assertThat(lifecycle.calls()).containsExactly("publish(" + TENANT_A_V1 + ")");
        assertThat(registry.codeDefinitions()).isEmpty();
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
