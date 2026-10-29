// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.workflow.definition.callbacks.StartStateMapperContributor;
import dev.vertique.workflow.definition.di.WorkflowDefinitionModule;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.dsl.WorkflowDefinition;
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
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.types.WorkflowTypeCatalogEntry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;

/**
 * Builds the shared definition graph with the schema module added, so that the state classes of one definition id in
 * different scopes are compared instead of refused. The shared graph leaves the comparison unbound.
 */
final class SchemaDefinitionGraph {

    private SchemaDefinitionGraph() {}

    /**
     * Builds the graph over the pool: the code definitions are contributed to the registry and the type catalog lists
     * the fixture types, the types nested in the test class and the extra entries.
     */
    static AppComponent build(
            Vertx vertx,
            Pool pool,
            Class<?> testClass,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            List<WorkflowTypeCatalogEntry> catalogEntries) {
        return build(vertx, pool, testClass, codeDefinitions, catalogEntries, builder -> {});
    }

    /**
     * Builds the graph like {@link #build(Vertx, Pool, Class, List, List)} with one more start state mapper
     * contributor, so that documents of a test can name a state class by its logical name.
     */
    static AppComponent build(
            Vertx vertx,
            Pool pool,
            Class<?> testClass,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            List<WorkflowTypeCatalogEntry> catalogEntries,
            StartStateMapperContributor extraMapper) {
        return build(vertx, pool, new JsonObject(), testClass, codeDefinitions, catalogEntries, extraMapper);
    }

    /** Builds the graph like {@link #build(Vertx, Pool, Class, List, List, StartStateMapperContributor)} with the given application config. */
    static AppComponent build(
            Vertx vertx,
            Pool pool,
            JsonObject config,
            Class<?> testClass,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            List<WorkflowTypeCatalogEntry> catalogEntries,
            StartStateMapperContributor extraMapper) {
        WorkflowDefinitionSource none = List::of;
        return DaggerSchemaDefinitionGraph_SchemaApp.builder()
                .vertxModule(new VertxModule(vertx, config))
                .postgresInfrastructureModule(new PostgresInfrastructureModule(pool))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("schema-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(none))
                .codeCatalogModule(new CodeCatalogModule(false, codeDefinitions))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(new WorkflowDefinitionParserProbe().module())
                .typeCatalogModule(new TypeCatalogModule(testClass, catalogEntries))
                .extraMapperModule(new ExtraMapperModule(extraMapper))
                .build();
    }

    /** Adds one start state mapper contributor to the callback catalog. */
    @Module
    static final class ExtraMapperModule {

        private final StartStateMapperContributor contributor;

        ExtraMapperModule(StartStateMapperContributor contributor) {
            this.contributor = contributor;
        }

        @Provides
        @IntoSet
        StartStateMapperContributor contributor() {
            return contributor;
        }
    }

    /** The JSON bindings the schema module reads. */
    @Module
    abstract static class JsonBindings {

        @Provides
        @Singleton
        static JsonMapperProfileRegistry profileRegistry() {
            return new DefaultJsonMapperProfileRegistry(Set.of());
        }

        @Provides
        static JsonConfig jsonConfig() {
            return JsonConfig.defaults();
        }
    }

    @Singleton
    @Component(
            modules = {
                VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
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
    interface SchemaApp extends AppComponent {

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

            SchemaApp build();
        }
    }
}
