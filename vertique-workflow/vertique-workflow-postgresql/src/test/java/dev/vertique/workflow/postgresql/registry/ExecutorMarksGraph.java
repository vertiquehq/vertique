// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dagger.Component;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.migration.WorkflowMigrationModule;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservationModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CallbackCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CodeCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.DefinitionSourceModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.LargeUnicodeMapperModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.PostgresInfrastructureModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.TypeCatalogModule;
import dev.vertique.workflow.postgresql.registry.SchemaDefinitionGraph.ExtraMapperModule;
import dev.vertique.workflow.postgresql.registry.SchemaDefinitionGraph.JsonBindings;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * The shared definition graph with the schema module, so that state types of one definition id in different scopes are
 * compared, and with the entry points the executor proofs use besides the shared ones: the deployer that can stop the
 * executor of a running graph, and the plan cache the executor builds into.
 */
final class ExecutorMarksGraph {

    private ExecutorMarksGraph() {}

    /** The shared graph and the entry points the executor proofs add. */
    interface MarksGraph extends AppComponent {

        /** Deploys and undeploys the verticles of the graph by name, among them the {@code workflow-executor}. */
        VerticleDeployer verticleDeployer();

        /** The cache the executor builds the plans of the rows it does not hold into. */
        PlanCache planCache();
    }

    /**
     * Builds the graph over the pool: the code definitions are contributed to the registry and the type catalog lists
     * the fixture types, the types nested in the test class and the extra entries.
     */
    static MarksGraph build(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            Class<?> testClass,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            List<WorkflowTypeCatalogEntry> catalogEntries) {
        WorkflowDefinitionSource none = List::of;
        return DaggerExecutorMarksGraph_MarksApp.builder()
                .vertxModule(new VertxModule(vertx, config))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("marks-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(none))
                .codeCatalogModule(new CodeCatalogModule(false, codeDefinitions))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(new WorkflowDefinitionParserProbe().module())
                .typeCatalogModule(new TypeCatalogModule(testClass, catalogEntries))
                .extraMapperModule(new ExtraMapperModule(builder -> {}))
                .build();
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                WorkflowExecutorConfigModule.class,
                DeployerModule.class,
                CoreLifecycleStepsModule.class,
                WorkflowPostgresqlModule.class,
                WorkflowDefinitionModule.class,
                DefinitionSourceModule.class,
                CodeCatalogModule.class,
                LargeUnicodeMapperModule.class,
                CallbackCatalogModule.class,
                PostgresInfrastructureModule.class,
                BootObservationModule.class,
                WorkflowDefinitionParserProbe.ParserModule.class,
                TypeCatalogModule.class,
                WorkflowSchemaModule.class,
                WorkflowMigrationModule.class,
                ExtraMapperModule.class,
                JsonBindings.class
            })
    interface MarksApp extends MarksGraph {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder postgresInfrastructureModule(PostgresInfrastructureModule module);

            Builder bootObservationModule(BootObservationModule module);

            Builder definitionSourceModule(DefinitionSourceModule module);

            Builder codeCatalogModule(CodeCatalogModule module);

            Builder largeUnicodeMapperModule(LargeUnicodeMapperModule module);

            Builder parserModule(WorkflowDefinitionParserProbe.ParserModule module);

            Builder typeCatalogModule(TypeCatalogModule module);

            Builder extraMapperModule(ExtraMapperModule module);

            MarksApp build();
        }
    }
}
