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
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParser;
import dev.vertique.workflow.definition.pipeline.WorkflowDefinitionPipeline;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.validator.WorkflowDefinitionDocumentValidator;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import io.vertx.core.Future;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Verifies that a service bound to a durable store keeps tenant definition ids inside the tenant's
 * namespace while global loads and the no-store service keep their previous behavior.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DefaultWorkflowDefinitionServiceNamespaceTest {

    record OrderState(String id) {}

    record PlaceOrder(String id) {}

    @WorkflowContract(definitionId = "X", definitionVersion = 1)
    interface PlainContract {}

    @WorkflowContract(definitionId = "t2:X", definitionVersion = 1)
    interface ForeignPrefixContract {}

    @WorkflowContract(definitionId = "t1:", definitionVersion = 1)
    interface BlankNameContract {}

    @WorkflowContract(definitionId = "t1:X", definitionVersion = 1)
    interface NamespacedContract {}

    @WorkflowContract(definitionId = "a:b:X", definitionVersion = 1)
    interface ColonTenantContract {}

    @WorkflowContract(definitionId = "global:X", definitionVersion = 1)
    interface GlobalColonContract {}

    @Test
    @DisplayName("a tenant load outside the tenant namespace fails without trace; global and no-store loads are kept")
    void rejectsTenantLoadsOutsideTheNamespaceAndAcceptsGlobalAndNoStore() {
        // Given a service bound to a durable store and a second service with no store bound
        WorkflowDefinitionRegistryStore durable = Mockito.mock(WorkflowDefinitionRegistryStore.class);
        Mockito.when(durable.insertCandidate(
                        Mockito.any(WorkflowDefinitionRecord.class), Mockito.any(WorkflowDefinitionDocument.class)))
                .thenReturn(Future.succeededFuture());
        WorkflowDefinitionStore durableCache = new WorkflowDefinitionStore();
        DefaultWorkflowRegistry durableRegistry = new DefaultWorkflowRegistry();
        DefaultWorkflowDefinitionService service = new DefaultWorkflowDefinitionService(
                buildPipeline(),
                durableCache,
                () -> durableRegistry,
                Optional.of(durable),
                Optional.empty(),
                Optional.empty());
        DefaultWorkflowDefinitionService noStoreService = new DefaultWorkflowDefinitionService(
                buildPipeline(), new WorkflowDefinitionStore(), () -> new DefaultWorkflowRegistry());
        DefinitionFilter all = new DefinitionFilter(DefinitionFilter.TenantScope.ALL, null, null);

        // When documents outside the namespace are loaded for tenants t1 and a:b
        // Then each load fails naming the tenant key and the id
        assertThatThrownBy(() -> ServiceTestHelpers.await(
                        service.load(yamlFor("X", PlainContract.class), DocumentFormat.YAML, meta(), "t1")))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("t1")
                .hasMessageContaining("X");
        assertThatThrownBy(() -> ServiceTestHelpers.await(
                        service.load(yamlFor("t2:X", ForeignPrefixContract.class), DocumentFormat.YAML, meta(), "t1")))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("t1")
                .hasMessageContaining("t2:X");
        assertThatThrownBy(() -> ServiceTestHelpers.await(
                        service.load(yamlFor("t1:", BlankNameContract.class), DocumentFormat.YAML, meta(), "t1")))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("t1")
                .hasMessageContaining("t1:");
        assertThatThrownBy(() -> ServiceTestHelpers.await(
                        service.load(yamlFor("a:b:X", ColonTenantContract.class), DocumentFormat.YAML, meta(), "a:b")))
                .isInstanceOf(WorkflowDefinitionException.class)
                .hasMessageContaining("a:b")
                .hasMessageContaining("a:b:X");

        // And nothing reached the store, the cache, the metadata or the registry
        Mockito.verify(durable, Mockito.never())
                .insertCandidate(Mockito.any(WorkflowDefinitionRecord.class), Mockito.any());
        assertThat(durableCache.records(all)).isEmpty();
        assertThat(durableCache.get(new DefinitionKey("t1", "X", 1))).isEmpty();
        assertThat(durableCache.get(new DefinitionKey("t1", "t2:X", 1))).isEmpty();
        assertThat(durableCache.get(new DefinitionKey("t1", "t1:", 1))).isEmpty();
        assertThat(durableCache.get(new DefinitionKey("a:b", "a:b:X", 1))).isEmpty();
        assertThat(durableRegistry.allRegistered()).isEmpty();

        // When the namespaced id t1:X is loaded for t1 and a colon id is loaded for the null tenant
        CompiledDefinitionRef namespaced = ServiceTestHelpers.await(
                service.load(yamlFor("t1:X", NamespacedContract.class), DocumentFormat.YAML, meta(), "t1"));
        CompiledDefinitionRef global = ServiceTestHelpers.await(
                service.load(yamlFor("global:X", GlobalColonContract.class), DocumentFormat.YAML, meta(), null));

        // Then both are stored as candidates under their own owner
        assertThat(namespaced.definitionId()).isEqualTo("t1:X");
        assertThat(namespaced.status()).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(global.definitionId()).isEqualTo("global:X");
        assertThat(global.status()).isEqualTo(ActivationStatus.CANDIDATE);
        ArgumentCaptor<WorkflowDefinitionRecord> stored = ArgumentCaptor.forClass(WorkflowDefinitionRecord.class);
        Mockito.verify(durable, Mockito.times(2)).insertCandidate(stored.capture(), Mockito.any());
        assertThat(stored.getAllValues())
                .extracting(WorkflowDefinitionRecord::key)
                .containsExactly(new DefinitionKey("t1", "t1:X", 1), new DefinitionKey(null, "global:X", 1));
        assertThat(durableCache.get(new DefinitionKey("t1", "t1:X", 1))).isPresent();
        assertThat(durableCache.get(new DefinitionKey(null, "global:X", 1))).isPresent();

        // And the service with no store still loads an unprefixed id for a tenant as before
        CompiledDefinitionRef unprefixed = ServiceTestHelpers.await(
                noStoreService.load(yamlFor("X", PlainContract.class), DocumentFormat.YAML, meta(), "t1"));
        assertThat(unprefixed.definitionId()).isEqualTo("X");
        assertThat(unprefixed.status()).isEqualTo(ActivationStatus.CANDIDATE);
    }

    @Test
    @DisplayName(
            "a malformed tenant key fails key validation without echoing it; a tenant key that prefixes a colon id loads")
    void rejectsMalformedTenantKeysWithoutEchoingThemAndKeepsNestedColonNames() {
        // Given a service bound to a durable store
        WorkflowDefinitionRegistryStore durable = Mockito.mock(WorkflowDefinitionRegistryStore.class);
        Mockito.when(durable.insertCandidate(
                        Mockito.any(WorkflowDefinitionRecord.class), Mockito.any(WorkflowDefinitionDocument.class)))
                .thenReturn(Future.succeededFuture());
        DefaultWorkflowDefinitionService service = new DefaultWorkflowDefinitionService(
                buildPipeline(),
                new WorkflowDefinitionStore(),
                () -> new DefaultWorkflowRegistry(),
                Optional.of(durable),
                Optional.empty(),
                Optional.empty());

        // When a tenant key with a line break loads an otherwise unprefixed document
        // Then the load fails key validation, not the namespace rule, and the message carries no raw key
        assertThatThrownBy(() -> ServiceTestHelpers.await(
                        service.load(yamlFor("X", PlainContract.class), DocumentFormat.YAML, meta(), "t\n1")))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(failure ->
                        assertThat(failure.getMessage()).contains("tenantKey").doesNotContain("\n"));
        Mockito.verify(durable, Mockito.never())
                .insertCandidate(Mockito.any(WorkflowDefinitionRecord.class), Mockito.any());

        // When tenant a loads a document whose name part itself contains a colon
        CompiledDefinitionRef nested = ServiceTestHelpers.await(
                service.load(yamlFor("a:b:X", ColonTenantContract.class), DocumentFormat.YAML, meta(), "a"));

        // Then it is accepted, because tenant keys cannot contain a colon and the first colon ends the key
        assertThat(nested.definitionId()).isEqualTo("a:b:X");
    }

    private static SourceMetadata meta() {
        return new SourceMetadata("test", null, null, null, null, Instant.now());
    }

    private static byte[] yamlFor(String definitionId, Class<?> contract) {
        String yaml =
                """
                definitionId: "%s"
                definitionVersion: 1
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: order.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(definitionId, OrderState.class.getName(), contract.getName(), PlaceOrder.class.getName());
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
