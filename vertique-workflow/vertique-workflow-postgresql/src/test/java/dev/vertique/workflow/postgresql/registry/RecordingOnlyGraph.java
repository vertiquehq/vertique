// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dagger.Component;
import dev.vertique.cache.CacheCoreModule;
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
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
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
import dev.vertique.workflow.schema.WorkflowPayloadSchemas;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Application graphs for the node-role proofs: the shared definition graph with the recording configuration, the
 * executor and plan-cache entry points, and either with or without the schema module.
 *
 * <p>The graph without the schema module is a graph of its own on purpose. A proof that a node needs no schema module,
 * or that an executor node refuses to start without it, must not depend on whether the shared graph carries the
 * module.
 */
final class RecordingOnlyGraph {

    private RecordingOnlyGraph() {}

    /** The packaged document source of a node and the probe that counts the parses made on it. */
    record Packaged(WorkflowDefinitionSource source, WorkflowDefinitionParserProbe parserProbe) {

        /** A node that ships no packaged document. */
        static Packaged none() {
            return new Packaged(List::of, new WorkflowDefinitionParserProbe());
        }
    }

    /** The shared graph with the entry points the node-role proofs use besides the shared ones. */
    interface Node extends AppComponent {

        /** Deploys and undeploys the verticles of the graph by name, among them the {@code workflow-executor}. */
        VerticleDeployer verticleDeployer();

        /** The cache the plans of document rows are built into. */
        PlanCache planCache();
    }

    /** A node of the graph with the schema module, which binds the payload schema reads. */
    interface SchemaNode extends Node {

        WorkflowPayloadSchemas payloadSchemas();
    }

    /**
     * Builds a node with the schema module over the pool.
     *
     * @param config the application configuration; the executor poll interval is shortened unless it sets it
     * @param testClass the class whose nested types the type catalog lists
     * @param codeDefinitions the code definitions the node's application contributes
     * @param catalogEntries more type catalog entries
     * @param cacheObserver reports the plan cache's events
     * @param packaged the packaged documents the node's application ships and the probe that counts their parses
     */
    static SchemaNode withSchemaModule(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            Class<?> testClass,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            List<WorkflowTypeCatalogEntry> catalogEntries,
            RecordingCacheObserver cacheObserver,
            Packaged packaged) {
        return DaggerRecordingOnlyGraph_SchemaApp.builder()
                .vertxModule(new VertxModule(vertx, ManagedDefinitionDatabase.withFastExecutor(config)))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("node-role-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(packaged.source()))
                .codeCatalogModule(new CodeCatalogModule(false, codeDefinitions))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(packaged.parserProbe().module())
                .typeCatalogModule(new TypeCatalogModule(testClass, catalogEntries))
                .observerModule(new PlanCacheFixture.ObserverModule(cacheObserver))
                .build();
    }

    /** Builds a node without the schema module, and so without a schema producer, over the pool. */
    static Node withoutSchemaModule(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            Class<?> testClass,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            List<WorkflowTypeCatalogEntry> catalogEntries,
            RecordingCacheObserver cacheObserver,
            Packaged packaged) {
        return DaggerRecordingOnlyGraph_SchemalessApp.builder()
                .vertxModule(new VertxModule(vertx, ManagedDefinitionDatabase.withFastExecutor(config)))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("node-role-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(packaged.source()))
                .codeCatalogModule(new CodeCatalogModule(false, codeDefinitions))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(packaged.parserProbe().module())
                .typeCatalogModule(new TypeCatalogModule(testClass, catalogEntries))
                .observerModule(new PlanCacheFixture.ObserverModule(cacheObserver))
                .build();
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                WorkflowExecutorConfigModule.class,
                WorkflowRecordingConfigModule.class,
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
                PlanCacheFixture.JsonBindings.class,
                CacheCoreModule.class,
                PlanCacheFixture.ObserverModule.class
            })
    interface SchemaApp extends SchemaNode {

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

            Builder observerModule(PlanCacheFixture.ObserverModule module);

            SchemaApp build();
        }
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                WorkflowExecutorConfigModule.class,
                WorkflowRecordingConfigModule.class,
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
                CacheCoreModule.class,
                PlanCacheFixture.ObserverModule.class
            })
    interface SchemalessApp extends Node {

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

            Builder observerModule(PlanCacheFixture.ObserverModule module);

            SchemalessApp build();
        }
    }
}
