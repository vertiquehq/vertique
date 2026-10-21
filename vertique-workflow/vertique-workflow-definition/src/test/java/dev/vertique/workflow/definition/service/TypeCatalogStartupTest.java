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
import dev.vertique.workflow.definition.source.DefinitionResource;
import dev.vertique.workflow.definition.source.SourceMetadata;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowCoreModule;
import dev.vertique.workflow.exception.WorkflowTypeNameConflictException;
import dev.vertique.workflow.exception.WorkflowUnknownTypeNameException;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.types.WorkflowType;
import dev.vertique.workflow.types.WorkflowTypeCatalog;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry.Kind;
import dev.vertique.workflow.types.WorkflowTypeNames;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies how the contributed type catalogs and the packaged documents feed the one type-name
 * index at startup, using real generated Dagger components. A hand-written catalog contribution
 * stands in for the catalog module the annotation processor generates.
 */
class TypeCatalogStartupTest {

    // --- Fixture types ---

    /** An unannotated start payload that no catalog lists. */
    record LegacyPlain(String id) {}

    /** An unannotated state that no catalog lists. */
    record LegacyState(String id) {}

    @WorkflowContract
    interface LegacyContract {}

    /** An annotated state that only the catalog and the document mention. */
    @WorkflowType("acme.state")
    record AcmeState(String id) {}

    record AcmeStart(String id) {}

    @WorkflowContract
    interface AcmeContract {}

    @WorkflowType("order")
    record OrderFromFirstModule(String id) {}

    @WorkflowType("order")
    record OrderFromSecondModule(String id) {}

    @WorkflowContract("shared")
    interface SharedNameContract {}

    @WorkflowType("shared")
    record SharedNameType(String id) {}

    // --- Fixture documents ---

    /** A document that names every class by binary name, so it needs no catalog entry to resolve. */
    private static final String LEGACY_DOCUMENT =
            """
            definitionId: legacy
            definitionVersion: 1
            stateType: %s
            contract: %s
            # the start payload is an unannotated class named by its binary name
            startPayloadType: %s
            initialStateMapper: legacy.init
            initialStep: done
            steps:
              - id: done
                type: complete
            """.formatted(LegacyState.class.getName(), LegacyContract.class.getName(), LegacyPlain.class.getName());

    /** A document whose state type is the logical name of an annotated class. */
    private static final String ACME_DOCUMENT = """
            definitionId: acme
            definitionVersion: 1
            stateType: acme.state
            contract: %s
            startPayloadType: %s
            initialStateMapper: acme.init
            initialStep: done
            steps:
              - id: done
                type: complete
            """.formatted(AcmeContract.class.getName(), AcmeStart.class.getName());

    private static SourceMetadata metadata(String uri) {
        return new SourceMetadata("test", uri, null, null, null, Instant.now());
    }

    private static DefinitionResource resource(String text, String uri) {
        return new DefinitionResource(text.getBytes(StandardCharsets.UTF_8), DocumentFormat.YAML, metadata(uri));
    }

    // --- Fixture modules ---

    /** Maps the legacy start payload to the legacy state. */
    @Module
    static class LegacyMapperModule {

        @Provides
        @IntoSet
        static StartStateMapperContributor legacyMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "legacy.init", LegacyPlain.class, LegacyState.class, plain -> new LegacyState(plain.id())));
        }
    }

    /** Offers the legacy document as a packaged resource. */
    @Module
    static class LegacyPackagedSourceModule {

        @Provides
        @IntoSet
        static WorkflowDefinitionSource legacySource() {
            return () -> List.of(resource(LEGACY_DOCUMENT, "test://legacy/1"));
        }
    }

    /** Lists the legacy contract and state, but not the legacy start payload. */
    @Module
    static class LegacyContractAndStateCatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog legacyCatalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, LegacyContract.class.getName(), LegacyContract.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, LegacyState.class.getName(), LegacyState.class));
        }
    }

    /** Maps the acme start payload to the annotated acme state. */
    @Module
    static class AcmeMapperModule {

        @Provides
        @IntoSet
        static StartStateMapperContributor acmeMapper() {
            return builder -> builder.register(new NamedStartStateMapper<>(
                    "acme.init", AcmeStart.class, AcmeState.class, start -> new AcmeState(start.id())));
        }
    }

    /** Lists the annotated acme state; no code definition references it. */
    @Module
    static class AcmeStateCatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog acmeCatalog() {
            return () -> List.of(new WorkflowTypeCatalogEntry(Kind.TYPE, "acme.state", AcmeState.class));
        }
    }

    /** Offers the acme document as a packaged resource. */
    @Module
    static class AcmePackagedSourceModule {

        @Provides
        @IntoSet
        static WorkflowDefinitionSource acmeSource() {
            return () -> List.of(resource(ACME_DOCUMENT, "test://acme/1"));
        }
    }

    /** Two modules that each list a different class under the type name {@code order}. */
    @Module
    static class FirstOrderCatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog firstOrderCatalog() {
            return () -> List.of(new WorkflowTypeCatalogEntry(Kind.TYPE, "order", OrderFromFirstModule.class));
        }
    }

    @Module
    static class SecondOrderCatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog secondOrderCatalog() {
            return () -> List.of(new WorkflowTypeCatalogEntry(Kind.TYPE, "order", OrderFromSecondModule.class));
        }
    }

    /** One catalog that lists a contract and a type under the same name. */
    @Module
    static class SharedNameCatalogModule {

        @Provides
        @IntoSet
        static WorkflowTypeCatalog sharedNameCatalog() {
            return () -> List.of(
                    new WorkflowTypeCatalogEntry(Kind.CONTRACT, "shared", SharedNameContract.class),
                    new WorkflowTypeCatalogEntry(Kind.TYPE, "shared", SharedNameType.class));
        }
    }

    // --- Components ---

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                AcmeMapperModule.class,
                AcmeStateCatalogModule.class,
                AcmePackagedSourceModule.class
            })
    interface CataloguedPackagedStateComponent {

        WorkflowRegistry workflowRegistry();
    }

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                FirstOrderCatalogModule.class,
                SecondOrderCatalogModule.class
            })
    interface ConflictingCatalogsComponent {

        WorkflowRegistry workflowRegistry();
    }

    @Singleton
    @Component(modules = {WorkflowDefinitionModule.class, WorkflowCoreModule.class, SharedNameCatalogModule.class})
    interface SharedNameComponent {

        WorkflowRegistry workflowRegistry();

        WorkflowTypeNames workflowTypeNames();
    }

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                LegacyMapperModule.class,
                LegacyPackagedSourceModule.class
            })
    interface PackagedBinaryNameComponent {

        WorkflowRegistry workflowRegistry();

        WorkflowDefinitionService workflowDefinitionService();
    }

    @Singleton
    @Component(
            modules = {
                WorkflowDefinitionModule.class,
                WorkflowCoreModule.class,
                LegacyMapperModule.class,
                LegacyContractAndStateCatalogModule.class
            })
    interface ServiceOnlyComponent {

        WorkflowDefinitionService workflowDefinitionService();
    }

    // --- Scenarios ---

    @Nested
    @DisplayName("a packaged document whose state is an annotated, catalogued class no code definition references")
    class CataloguedPackagedState {

        @Test
        @DisplayName("registers at startup under its state's logical name")
        void packagedDocumentWithCataloguedStateRegistersAtStartup() {
            CataloguedPackagedStateComponent component =
                    DaggerTypeCatalogStartupTest_CataloguedPackagedStateComponent.create();

            RuntimeWorkflow registered = component.workflowRegistry().resolveCurrent("acme");

            assertThat(registered.plan().definitionId()).isEqualTo("acme");
            assertThat(registered.plan().definitionVersion()).isEqualTo(1);
            assertThat(registered.stateType()).isEqualTo(AcmeState.class);
        }
    }

    @Nested
    @DisplayName("contributed catalogs")
    class CatalogMerge {

        @Test
        @DisplayName(
                "two catalogs mapping one type name to different classes fail startup naming the name and both classes")
        void catalogsMappingOneTypeNameToTwoClassesFailStartup() {
            ConflictingCatalogsComponent component = DaggerTypeCatalogStartupTest_ConflictingCatalogsComponent.create();

            assertThatThrownBy(component::workflowRegistry)
                    .isInstanceOf(WorkflowTypeNameConflictException.class)
                    .hasMessageContaining("order")
                    .hasMessageContaining(OrderFromFirstModule.class.getName())
                    .hasMessageContaining(OrderFromSecondModule.class.getName());
        }

        @Test
        @DisplayName("a contract and a type may share one name because the namespaces are separate")
        void contractAndTypeSharingANameStartAndResolveSeparately() {
            SharedNameComponent component = DaggerTypeCatalogStartupTest_SharedNameComponent.create();

            component.workflowRegistry();

            assertThat(component.workflowTypeNames().findContract("shared")).contains(SharedNameContract.class);
            assertThat(component.workflowTypeNames().find("shared")).contains(SharedNameType.class);
        }
    }

    @Nested
    @DisplayName("an unannotated, uncatalogued class named by its binary name")
    class UnannotatedBinaryName {

        @Test
        @DisplayName("registers from a packaged document, and a later service load of the registered classes succeeds")
        void packagedBinaryNameRegistersAndItsClassesJoinTheIndex() {
            PackagedBinaryNameComponent component = DaggerTypeCatalogStartupTest_PackagedBinaryNameComponent.create();

            RuntimeWorkflow registered = component.workflowRegistry().resolveCurrent("legacy");
            assertThat(registered.startPayloadType()).isEqualTo(LegacyPlain.class);
            assertThat(registered.stateType()).isEqualTo(LegacyState.class);

            // The same text, loaded for a tenant so it does not collide with the packaged global definition.
            CompiledDefinitionRef loaded = ServiceTestHelpers.await(component
                    .workflowDefinitionService()
                    .load(
                            "acme",
                            LEGACY_DOCUMENT.getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            metadata("test://legacy/tenant")));
            assertThat(loaded.definitionId()).isEqualTo("legacy");
            assertThat(loaded.definitionVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("is refused by a service load when no catalog and no registered definition references it")
        void serviceLoadOfTheSameTextIsRefusedWithoutLoadingTheClass() {
            ServiceOnlyComponent component = DaggerTypeCatalogStartupTest_ServiceOnlyComponent.create();
            RecordingClassLoader recorder = new RecordingClassLoader();

            var future = recorder.callWithContextLoader(() -> component
                    .workflowDefinitionService()
                    .load(
                            WorkflowScopes.DEFAULT,
                            LEGACY_DOCUMENT.getBytes(StandardCharsets.UTF_8),
                            DocumentFormat.YAML,
                            metadata("test://legacy/service")));

            assertThatThrownBy(() -> ServiceTestHelpers.await(future))
                    .isInstanceOf(WorkflowUnknownTypeNameException.class)
                    .hasMessageContaining("startPayloadType")
                    .hasMessageContaining(LegacyPlain.class.getName());
            assertThat(recorder.requested()).doesNotContain(LegacyPlain.class.getName());
            assertThat(ServiceTestHelpers.await(component
                            .workflowDefinitionService()
                            .list(new DefinitionFilter(WorkflowScopes.DEFAULT, null, null))))
                    .isEmpty();
        }
    }
}
