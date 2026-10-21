// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.pipeline.CompiledDefinition;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.WorkflowType;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a durable document whose state class carries a logical name is restored with the plan hash it was
 * published with: the loader's hash of the restored definition equals the hash stored at publication and the hash the
 * registry computes for the same definition, so a restart does not refuse the document.
 */
class DocumentRestorePlanHashTest {

    // --- Fixture types ---

    /** A state class with a logical name different from its binary name. */
    @WorkflowType("restore.state")
    record RestoreState(String id) {}

    record RestoreStart(String id) {}

    @WorkflowContract("restore.contract")
    interface RestoreContract {}

    // --- Fixture modules and component ---

    @Module
    static class CatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog catalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, "restore.contract", RestoreContract.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "restore.start", RestoreStart.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "restore.state", RestoreState.class));
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor mapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "restore.init", RestoreStart.class, RestoreState.class, start -> new RestoreState(start.id())));
        }
    }

    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, CatalogModule.class})
    interface RestoreComponent {

        WorkflowDefinitionService workflowDefinitionService();

        WorkflowDefinitionStore workflowDefinitionStore();

        DocumentDefinitionLoader documentDefinitionLoader();

        WorkflowRegistry workflowRegistry();
    }

    // --- Fixture documents ---

    private static final DefinitionKey KEY = new DefinitionKey(WorkflowScopes.DEFAULT, "restore-saga", 1);

    private static String document() {
        return """
                definitionId: restore-saga
                definitionVersion: 1
                contract: restore.contract
                stateType: restore.state
                startPayloadType: restore.start
                initialStateMapper: restore.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """;
    }

    private static CompiledDefinition publish(RestoreComponent component) {
        ServiceTestHelpers.await(component
                .workflowDefinitionService()
                .load(
                        document().getBytes(StandardCharsets.UTF_8),
                        DocumentFormat.YAML,
                        new SourceMetadata("test", "test://restore", null, null, null, Instant.now()),
                        null));
        return component.workflowDefinitionStore().get(KEY).orElseThrow().compiled();
    }

    private static WorkflowDefinitionRecord storedRecord(String planHash) {
        Instant now = Instant.now();
        return new WorkflowDefinitionRecord(
                KEY,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                planHash,
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance("test", "test://restore", null, null, null, now),
                "YAML",
                now,
                now);
    }

    // --- Tests ---

    @Test
    @DisplayName("a restored document's hash equals the hash it was published with")
    void restoredDocumentHashEqualsTheHashItWasPublishedWith() {
        // Given: a published document over an annotated state class, and the hash stored with it
        RestoreComponent component = DaggerDocumentRestorePlanHashTest_RestoreComponent.create();
        CompiledDefinition published = publish(component);
        WorkflowDefinitionRecord stored = storedRecord(published.planHash());
        WorkflowDefinitionDocument document = new WorkflowDefinitionDocument(KEY, document(), "YAML");

        // When: a fresh process restores the stored document into a definition
        WorkflowDefinition<?, ?> restored = component.documentDefinitionLoader().load(stored, document);

        // Then: the state class is the annotated one, and the loader's hash equals the stored hash
        assertThat(restored.stateType()).isEqualTo(RestoreState.class);
        assertThat(component.documentDefinitionLoader().planHash(restored))
                .as("the hash a restore compares with the stored hash")
                .isEqualTo(stored.planHash());
    }

    @Test
    @DisplayName("the loader's hash of a restored document equals the hash the registry computes for it")
    void loaderHashEqualsTheHashTheRegistryComputes() {
        // Given: a published document over an annotated state class
        RestoreComponent component = DaggerDocumentRestorePlanHashTest_RestoreComponent.create();
        CompiledDefinition published = publish(component);

        // When: the registry registers the definition
        component.workflowRegistry().register(published.definition());
        String registered = component
                .workflowRegistry()
                .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "restore-saga", 1L))
                .plan()
                .planHash();

        // Then: the plan carries the logical state name, and the registry and loader hashes agree
        assertThat(component
                        .workflowRegistry()
                        .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, "restore-saga", 1L))
                        .plan()
                        .stateTypeName())
                .isEqualTo("restore.state");
        assertThat(component.documentDefinitionLoader().planHash(published.definition()))
                .isEqualTo(registered)
                .isEqualTo(published.planHash());
    }

    @Test
    @DisplayName("a restored document is cached with the same state type name as a freshly loaded one")
    void restoredDocumentIsCachedWithTheSameStateTypeNameAsAFreshLoad() {
        // Given: the state type name a fresh load of the document caches
        String freshlyLoaded = publish(DaggerDocumentRestorePlanHashTest_RestoreComponent.create())
                .stateTypeName();
        RestoreComponent restarted = DaggerDocumentRestorePlanHashTest_RestoreComponent.create();
        WorkflowDefinitionRecord stored = storedRecord("a".repeat(64));
        WorkflowDefinitionDocument document = new WorkflowDefinitionDocument(KEY, document(), "YAML");

        // When: a restarted process restores the document into an empty cache
        WorkflowDefinition<?, ?> restored = restarted.documentDefinitionLoader().load(stored, document);
        restarted.documentDefinitionLoader().onRestored(stored, restored);

        // Then: the cache entry names the state class as the fresh load does
        assertThat(restarted
                        .workflowDefinitionStore()
                        .get(KEY)
                        .orElseThrow()
                        .compiled()
                        .stateTypeName())
                .isEqualTo(freshlyLoaded)
                .isEqualTo("restore.state");
    }
}
