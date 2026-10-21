// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.definition.callbacks.NamedStartStateMapper;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.DocumentFormat;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.registry.store.DefinitionFilter;
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
import io.vertx.core.Future;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a document loaded through the service API, or restored from the store, resolves its
 * type and contract names through the catalog-fed index only: an unknown name is refused with a typed
 * error and no class is loaded by a name the document supplied.
 */
class CatalogOnlyDocumentLoadTest {

    // --- Fixture types ---

    /** A class on the classpath that no catalog entry and no registered definition references. */
    record NeverCatalogued(String id) {}

    @WorkflowType("never.start")
    record NeverStart(String id) {}

    @WorkflowContract("never.contract")
    interface NeverContract {}

    @WorkflowType("acme.state")
    record AcmeState(String id) {}

    record AcmeStart(String id) {}

    @WorkflowContract("acme.contract.a")
    interface AcmeContractA {}

    @WorkflowContract("acme.contract.b")
    interface AcmeContractB {}

    // --- Fixture modules and component ---

    /** Lists the contract and start payload of the refused document, and the annotated acme classes. */
    @Module
    static class CatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog catalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, "never.contract", NeverContract.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, "acme.contract.a", AcmeContractA.class),
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, "acme.contract.b", AcmeContractB.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "never.start", NeverStart.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "acme.start", AcmeStart.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "acme.state", AcmeState.class));
        }
    }

    @Module
    static class MapperModule {

        @Provides
        @IntoSet
        static StartStateMapperContributor neverMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "never.init", NeverStart.class, NeverCatalogued.class, start -> new NeverCatalogued(start.id())));
        }

        @Provides
        @IntoSet
        static StartStateMapperContributor acmeMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "acme.init", AcmeStart.class, AcmeState.class, start -> new AcmeState(start.id())));
        }
    }

    @Singleton
    @Component(
            modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, CatalogModule.class, MapperModule.class
            })
    interface DocumentLoadComponent {

        WorkflowDefinitionService workflowDefinitionService();

        WorkflowDefinitionStore workflowDefinitionStore();

        DocumentDefinitionLoader documentDefinitionLoader();
    }

    // --- Fixture documents ---

    /** Everything resolves through the catalog except the state type, which is a binary class name. */
    private static String documentNamingAnUncataloguedState() {
        return """
                definitionId: never
                definitionVersion: 1
                contract: never.contract
                # offending line: a binary name that no catalog entry and no registered definition lists
                stateType: %s
                startPayloadType: never.start
                initialStateMapper: never.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(NeverCatalogued.class.getName());
    }

    private static String acmeDocument(String definitionId, String contract, String stateType) {
        return """
                definitionId: %s
                definitionVersion: 1
                contract: %s
                stateType: %s
                startPayloadType: acme.start
                initialStateMapper: acme.init
                initialStep: done
                steps:
                  - id: done
                    type: complete
                """.formatted(definitionId, contract, stateType);
    }

    private static Future<CompiledDefinitionRef> load(DocumentLoadComponent component, String document) {
        return component
                .workflowDefinitionService()
                .load(
                        WorkflowScopes.DEFAULT,
                        document.getBytes(StandardCharsets.UTF_8),
                        DocumentFormat.YAML,
                        new SourceMetadata("test", "test://document", null, null, null, Instant.now()));
    }

    // --- Tests ---

    @Test
    @DisplayName(
            "a service load naming an uncatalogued binary class fails with the unknown-name error and loads nothing")
    void serviceLoadRefusesUncataloguedBinaryNameWithoutLoadingIt() {
        DocumentLoadComponent component = DaggerCatalogOnlyDocumentLoadTest_DocumentLoadComponent.create();
        RecordingClassLoader recorder = new RecordingClassLoader();

        Future<CompiledDefinitionRef> loading =
                recorder.callWithContextLoader(() -> load(component, documentNamingAnUncataloguedState()));

        assertThatThrownBy(() -> ServiceTestHelpers.await(loading))
                .isInstanceOf(WorkflowUnknownTypeNameException.class)
                .hasMessageContaining("stateType")
                .hasMessageContaining(NeverCatalogued.class.getName());
        assertThat(recorder.requested()).doesNotContain(NeverCatalogued.class.getName());
        assertThat(ServiceTestHelpers.await(component
                        .workflowDefinitionService()
                        .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))))
                .isEmpty();
    }

    @Test
    @DisplayName("a service load naming a catalogued logical name or a catalogued class's binary name succeeds")
    void serviceLoadAcceptsCataloguedLogicalAndBinaryNames() {
        DocumentLoadComponent component = DaggerCatalogOnlyDocumentLoadTest_DocumentLoadComponent.create();

        ServiceTestHelpers.await(load(component, acmeDocument("acme-a", "acme.contract.a", "acme.state")));
        ServiceTestHelpers.await(load(component, acmeDocument("acme-b", "acme.contract.b", AcmeState.class.getName())));

        for (String definitionId : List.of("acme-a", "acme-b")) {
            DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, definitionId, 1);
            assertThat(component
                            .workflowDefinitionStore()
                            .get(key)
                            .orElseThrow()
                            .compiled()
                            .definition()
                            .stateType())
                    .isEqualTo(AcmeState.class);
        }
    }

    @Test
    @DisplayName(
            "a restored document naming an uncatalogued binary class fails with the unknown-name error and loads nothing")
    void restoredDocumentResolvesCataloguedNamesOnly() {
        DocumentLoadComponent component = DaggerCatalogOnlyDocumentLoadTest_DocumentLoadComponent.create();
        RecordingClassLoader recorder = new RecordingClassLoader();
        DefinitionKey key = new DefinitionKey(WorkflowScopes.DEFAULT, "never", 1);
        Instant now = Instant.now();
        WorkflowDefinitionRecord stored = new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                "a".repeat(64),
                DefinitionStatus.CANDIDATE,
                new DefinitionProvenance("test", "test://never", null, null, null, now),
                "YAML",
                now,
                now,
                Map.of(),
                null);
        WorkflowDefinitionDocument document =
                new WorkflowDefinitionDocument(key, documentNamingAnUncataloguedState(), "YAML");

        assertThatThrownBy(() -> recorder.callWithContextLoader(
                        () -> component.documentDefinitionLoader().load(stored, document)))
                .isInstanceOf(WorkflowUnknownTypeNameException.class)
                .hasMessageContaining("stateType")
                .hasMessageContaining(NeverCatalogued.class.getName());
        assertThat(recorder.requested()).doesNotContain(NeverCatalogued.class.getName());
    }
}
