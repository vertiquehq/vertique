// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParseException;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.engine.testsupport.InMemoryDefinitionRegistryModule;
import dev.vertique.workflow.engine.testsupport.NoOpWorkflowDefinitionLifecycle;
import dev.vertique.workflow.exception.WorkflowDefinitionException;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies asynchronous service behavior when no durable registry or lifecycle is installed. */
class DefinitionAsyncFallbackTest {

    record OrderState(String orderId) {}

    record PlaceOrder(String orderId) {}

    @WorkflowContract
    interface FallbackOrderV1 {}

    @WorkflowContract
    interface FallbackOrderV2 {}

    @Singleton
    @Component(
            modules = {
                WorkflowCoreModule.class,
                WorkflowDefinitionModule.class,
                InMemoryDefinitionRegistryModule.class,
                TestSupportModule.class
            })
    interface InMemoryStoreComponent {

        WorkflowDefinitionService definitionService();

        WorkflowRegistry workflowRegistry();

        NoOpWorkflowDefinitionLifecycle definitionLifecycle();

        Optional<WorkflowDefinitionsConfig> definitionsConfig();
    }

    @Module
    static class TestSupportModule {

        @Provides
        @dagger.multibindings.IntoSet
        static StartStateMapperContributor startStateMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "fallback.order.init", PlaceOrder.class, OrderState.class, p -> new OrderState(p.orderId())));
        }

        /** Lists the classes a service-loaded document may name; the service resolves names through the catalog only. */
        @Provides
        @dagger.multibindings.IntoSet
        static WorkflowTypeCatalog typeCatalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.TYPE, OrderState.class.getName(), OrderState.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, PlaceOrder.class.getName(), PlaceOrder.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, FallbackOrderV1.class.getName(), FallbackOrderV1.class),
                    new WorkflowTypeCatalogEntry(
                            Kind.CONTRACT, FallbackOrderV2.class.getName(), FallbackOrderV2.class));
        }

        @Provides
        @jakarta.inject.Singleton
        static WorkflowPlanValidator workflowPlanValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }
    }

    @Test
    @DisplayName("load, publish, list and source metadata keep their outcomes over the store and the lifecycle")
    void preservesOutcomesOverTheStore() {
        InMemoryStoreComponent component = DaggerDefinitionAsyncFallbackTest_InMemoryStoreComponent.create();
        WorkflowDefinitionService service = component.definitionService();
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, "fallback-order", 1);
        SourceMetadata provenance =
                new SourceMetadata("test", "opaque:source", "rev-1", "author", "upload", Instant.now());

        assertThat(component.definitionsConfig()).isEmpty();

        CompiledDefinitionRef loaded = ServiceTestHelpers.await(service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("fallback-order", 1, FallbackOrderV1.class),
                DocumentFormat.YAML,
                provenance));
        assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(component.workflowRegistry().codeDefinitions()).isEmpty();

        ServiceTestHelpers.await(service.publish(key));
        assertThat(component.definitionLifecycle().calls()).containsExactly("publish(" + key + ")");

        DefinitionFilter global = new DefinitionFilter(WorkflowScopes.DEFAULT, null, null);
        java.util.List<WorkflowDefinitionRecord> records = ServiceTestHelpers.await(service.list(global));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(key);
        assertThat(records.get(0).status()).isEqualTo(DefinitionStatus.CANDIDATE);
        assertThat(ServiceTestHelpers.await(service.sourceMetadata(key))).contains(provenance);
        assertThatThrownBy(records::clear).isInstanceOf(UnsupportedOperationException.class);
        ServiceTestHelpers.await(service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("second-fallback-order", 1, FallbackOrderV2.class),
                DocumentFormat.YAML,
                provenance));
        assertThat(records).hasSize(1);
        assertThat(ServiceTestHelpers.await(service.list(global))).hasSize(2);

        DefinitionFilter none = new DefinitionFilter(null, null, Set.of());
        assertThat(ServiceTestHelpers.await(service.list(none))).isEmpty();

        Future<CompiledDefinitionRef> malformed = service.load(
                WorkflowScopes.DEFAULT,
                "definitionId: [".getBytes(StandardCharsets.UTF_8),
                DocumentFormat.YAML,
                provenance);
        assertThatThrownBy(() -> ServiceTestHelpers.await(malformed))
                .isInstanceOf(WorkflowDefinitionParseException.class);
        Future<CompiledDefinitionRef> duplicate = service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("fallback-order", 1, FallbackOrderV1.class),
                DocumentFormat.YAML,
                provenance);
        assertThatThrownBy(() -> ServiceTestHelpers.await(duplicate)).isInstanceOf(WorkflowDefinitionException.class);
        Future<CompiledDefinitionRef> invalidArgument =
                service.load(WorkflowScopes.DEFAULT, null, DocumentFormat.YAML, provenance);
        assertThatThrownBy(() -> ServiceTestHelpers.await(invalidArgument)).isInstanceOf(NullPointerException.class);
        Future<Void> invalidActivation = service.publish(null);
        assertThatThrownBy(() -> ServiceTestHelpers.await(invalidActivation)).isInstanceOf(RuntimeException.class);
        Future<List<WorkflowDefinitionRecord>> invalidFilter = service.list(null);
        assertThatThrownBy(() -> ServiceTestHelpers.await(invalidFilter)).isInstanceOf(RuntimeException.class);
        Future<Optional<SourceMetadata>> invalidKey = service.sourceMetadata(null);
        assertThatThrownBy(() -> ServiceTestHelpers.await(invalidKey)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a 300-code-point revision fails the load and registers nothing")
    void rejectsOverLongProvenanceBeforeAnyState() {
        // Given a service over an in-memory store and metadata whose revision exceeds the 255 code point bound
        InMemoryStoreComponent component = DaggerDefinitionAsyncFallbackTest_InMemoryStoreComponent.create();
        WorkflowDefinitionService service = component.definitionService();
        SourceMetadata overLong =
                new SourceMetadata("test", "opaque:source", "r".repeat(300), null, null, Instant.now());

        // When the document is loaded
        Future<CompiledDefinitionRef> loaded = service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("fallback-order", 1, FallbackOrderV1.class),
                DocumentFormat.YAML,
                overLong);

        // Then the Future fails with IllegalArgumentException and nothing was stored or published
        assertThatThrownBy(() -> ServiceTestHelpers.await(loaded)).isInstanceOf(IllegalArgumentException.class);
        DefinitionFilter all = new DefinitionFilter(null, null, null);
        assertThat(ServiceTestHelpers.await(service.list(all))).isEmpty();
        assertThat(component.workflowRegistry().codeDefinitions()).isEmpty();
    }

    @Test
    @DisplayName("a 2049-code-point uri fails the load and registers nothing")
    void rejectsOverLongUriBeforeAnyState() {
        // Given a service over an in-memory store and metadata whose uri exceeds the 2048 code point bound
        InMemoryStoreComponent component = DaggerDefinitionAsyncFallbackTest_InMemoryStoreComponent.create();
        WorkflowDefinitionService service = component.definitionService();
        SourceMetadata overLong = new SourceMetadata("test", "u".repeat(2049), "rev-1", null, null, Instant.now());

        // When the document is loaded
        Future<CompiledDefinitionRef> loaded = service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("fallback-order", 1, FallbackOrderV1.class),
                DocumentFormat.YAML,
                overLong);

        // Then the Future fails with IllegalArgumentException and nothing was stored or published
        assertThatThrownBy(() -> ServiceTestHelpers.await(loaded)).isInstanceOf(IllegalArgumentException.class);
        DefinitionFilter all = new DefinitionFilter(null, null, null);
        assertThat(ServiceTestHelpers.await(service.list(all))).isEmpty();
        assertThat(component.workflowRegistry().codeDefinitions()).isEmpty();
    }

    @Test
    @DisplayName("a 2048-code-point uri loads as a candidate")
    void acceptsUriAtTheBound() {
        // Given a service over an in-memory store and metadata whose uri is exactly 2048 supplementary characters
        InMemoryStoreComponent component = DaggerDefinitionAsyncFallbackTest_InMemoryStoreComponent.create();
        WorkflowDefinitionService service = component.definitionService();
        String atBound = "\uD83D\uDE00".repeat(2048);
        SourceMetadata provenance = new SourceMetadata("test", atBound, "rev-1", null, null, Instant.now());

        // When the document is loaded
        CompiledDefinitionRef loaded = ServiceTestHelpers.await(service.load(
                WorkflowScopes.DEFAULT,
                yamlFor("fallback-order", 1, FallbackOrderV1.class),
                DocumentFormat.YAML,
                provenance));

        // Then it is a candidate that keeps the full uri, and it is listed but not published
        assertThat(loaded.status()).isEqualTo(ActivationStatus.CANDIDATE);
        assertThat(loaded.source().uri()).isEqualTo(atBound);
        DefinitionFilter all = new DefinitionFilter(null, null, null);
        assertThat(ServiceTestHelpers.await(service.list(all))).hasSize(1);
        assertThat(component.workflowRegistry().codeDefinitions()).isEmpty();
    }

    private static byte[] yamlFor(String id, long version, Class<?> contract) {
        return ("""
                definitionId: %s
                definitionVersion: %d
                stateType: %s
                contract: %s
                startPayloadType: %s
                initialStateMapper: fallback.order.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """)
                .formatted(id, version, OrderState.class.getName(), contract.getName(), PlaceOrder.class.getName())
                .getBytes(StandardCharsets.UTF_8);
    }
}
