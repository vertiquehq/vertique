// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.cache.CacheCoreModule;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.workflow.definition.parser.WorkflowDefinitionParserProbe;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.di.WorkflowDefinitionsConfigModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.migration.WorkflowMigrationModule;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservationModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CallbackCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.CodeCatalogModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.DefinitionSourceModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.LargeUnicodeMapperModule;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.TypeCatalogModule;
import dev.vertique.workflow.schema.WorkflowSchemaModule;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.List;
import java.util.Set;

/**
 * An application graph like the shared one that also exposes its plan cache and reports the cache's events to a
 * recording observer, so that tests of several nodes on one database can look at what each node's cache did.
 */
final class PublicationNodeGraph {

    private PublicationNodeGraph() {}

    /** The shared graph with the entry point to the plan cache. */
    interface Node extends AppComponent {

        PlanCache planCache();

        /** Deploys and undeploys the verticles of the graph by name, among them the executor. */
        dev.vertique.deploy.VerticleDeployer verticleDeployer();
    }

    @Singleton
    @Component(
            modules = {
                dev.vertique.core.VertxModule.class,
                ConfigParsingModule.class,
                WorkflowDefinitionsConfigModule.class,
                WorkflowExecutorConfigModule.class,
                DeployerModule.class,
                CoreLifecycleStepsModule.class,
                WorkflowPostgresqlModule.class,
                dev.vertique.workflow.definition.di.WorkflowDefinitionModule.class,
                DefinitionSourceModule.class,
                CodeCatalogModule.class,
                LargeUnicodeMapperModule.class,
                CallbackCatalogModule.class,
                Infrastructure.class,
                BootObservationModule.class,
                WorkflowDefinitionParserProbe.ParserModule.class,
                TypeCatalogModule.class,
                WorkflowSchemaModule.class,
                WorkflowMigrationModule.class,
                PlanCacheFixture.JsonBindings.class,
                CacheCoreModule.class,
                PlanCacheFixture.ObserverModule.class
            })
    interface PublicationApp extends Node {

        @Component.Builder
        interface Builder {

            Builder vertxModule(VertxModule module);

            Builder infrastructure(Infrastructure module);

            Builder bootObservationModule(BootObservationModule module);

            Builder definitionSourceModule(DefinitionSourceModule module);

            Builder codeCatalogModule(CodeCatalogModule module);

            Builder largeUnicodeMapperModule(LargeUnicodeMapperModule module);

            Builder parserModule(WorkflowDefinitionParserProbe.ParserModule module);

            Builder typeCatalogModule(TypeCatalogModule module);

            Builder observerModule(PlanCacheFixture.ObserverModule module);

            PublicationApp build();
        }
    }

    /**
     * The pool, the clock, the plan validator and a recorder of service intents, and no recorder of workflow events, so
     * that a plan with task reminders does not compose with this wiring.
     */
    @Module
    static final class Infrastructure {

        private final Pool pool;

        Infrastructure(Pool pool) {
            this.pool = pool;
        }

        @Provides
        @Singleton
        Pool pool() {
            return pool;
        }

        @Provides
        @Singleton
        static Clock clock() {
            return Clock.systemUTC();
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        @Singleton
        static WorkflowSideEffectRecorder<SqlClient> serviceRecorder() {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.SERVICE;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient transaction) {
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
        }
    }

    /**
     * Builds one node over the pool: no packaged documents, the given code definitions, and a type catalog of the
     * classes nested in {@code catalogHost}.
     */
    static Node node(
            Vertx vertx,
            Pool pool,
            Class<?> catalogHost,
            List<WorkflowDefinition<?, ?>> codeDefinitions,
            RecordingCacheObserver observer) {
        WorkflowDefinitionSource none = List::of;
        return DaggerPublicationNodeGraph_PublicationApp.builder()
                .vertxModule(new VertxModule(vertx, ManagedDefinitionDatabase.withFastExecutor(new JsonObject())))
                .infrastructure(new Infrastructure(pool))
                .bootObservationModule(
                        new BootObservationModule(new BootObservation("publication-unused", 1, "unused", false)))
                .definitionSourceModule(new DefinitionSourceModule(none))
                .codeCatalogModule(new CodeCatalogModule(false, codeDefinitions))
                .largeUnicodeMapperModule(new LargeUnicodeMapperModule(false))
                .parserModule(new WorkflowDefinitionParserProbe().module())
                .typeCatalogModule(new TypeCatalogModule(catalogHost, List.of()))
                .observerModule(new PlanCacheFixture.ObserverModule(observer))
                .build();
    }
}
