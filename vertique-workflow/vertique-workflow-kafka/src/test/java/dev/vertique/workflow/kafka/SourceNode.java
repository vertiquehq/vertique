// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.inboxoutbox.InboxService;
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaConsumerDeploymentManager;
import dev.vertique.kafka.KafkaModule;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.json.KafkaJsonModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanSchemaProducer;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.services.di.WorkflowSourceModule;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The Dagger graph of one node that turns Kafka records into workflow signals: the production modules of a node that
 * runs the workflow executor on PostgreSQL, the Kafka consumer runtime with its JSON serde, and
 * {@link WorkflowSourceModule}, which holds the delivery bean and the observer set.
 *
 * <p>The graph installs neither {@code WorkflowServicesModule} nor the outbox: a signal source needs neither, and the
 * graph compiling and delivering is the evidence. It needs an inbox, which makes the recording of each target
 * idempotent; the test binds one over the {@code inbox} table of its database. The test supplies only what a node
 * leaves to its application: the pool, the two workflow definitions, the sources, an observer of the delivery bean and
 * an interceptor of the consumer.
 */
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            KafkaModule.class,
            KafkaJsonModule.class,
            WorkflowPostgresqlModule.class,
            WorkflowExecutorConfigModule.class,
            WorkflowRecordingConfigModule.class,
            WorkflowSourceModule.class,
            SourceNode.Engine.class,
            SourceNode.Rig.class
        })
interface SourceNode {

    /** The startup steps of the graph, to run in lifecycle order before the executor and the consumers deploy. */
    Set<ApplicationStartupStep> startupSteps();

    /** The verticle deployments of the graph, among them the {@code workflow-executor}. */
    Set<VerticleDeployment> verticleDeployments();

    /** Deploys and undeploys verticles by name. */
    VerticleDeployer verticleDeployer();

    /** Deploys and undeploys the consumers of every source. */
    KafkaConsumerDeploymentManager consumerDeployments();

    /** The workflow operations facade, to start instances. */
    WorkflowOperations workflowOperations();

    /** The transactional workflow operations. */
    TransactionalWorkflowOperations<SqlClient> transactionalOperations();

    /** The probe that waits until the engine has run the work recorded for an instance. */
    WorkflowExecutionProbe executionProbe();

    /** The holder of the ambient context, for a stage that reads it. */
    ContextHolder contextHolder();

    @Component.Builder
    interface Builder {

        Builder vertxModule(VertxModule module);

        Builder rig(Rig module);

        SourceNode build();
    }

    /** What the production modules leave to the node: the clock, the plan validator and two recorders that keep nothing. */
    @Module
    abstract class Engine {

        /** The inbox a node that runs a source installs; here over the table of the proofs' database. */
        @Provides
        @Singleton
        static InboxService inboxService() {
            return new TableInbox();
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

        /**
         * A producer of the schemas a definition row stores, which a node that runs the executor must bind. The
         * signal sources read no schema, so the producer answers empty ones; the schema module is not on the test class
         * path of this module.
         */
        @Provides
        @Singleton
        static WorkflowPlanSchemaProducer schemaProducer() {
            return new WorkflowPlanSchemaProducer() {
                @Override
                public JsonObject startSchema(RuntimeWorkflow plan) {
                    return new JsonObject();
                }

                @Override
                public Map<String, Map<String, JsonObject>> decisionSchemas(RuntimeWorkflow plan) {
                    return Map.of();
                }
            };
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
        static WorkflowSideEffectRecorder<SqlClient> eventRecorder() {
            return recorderOf(IntentKind.WORKFLOW_EVENT);
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        @Singleton
        static WorkflowSideEffectRecorder<SqlClient> serviceRecorder() {
            return recorderOf(IntentKind.SERVICE);
        }

        private static WorkflowSideEffectRecorder<SqlClient> recorderOf(IntentKind kind) {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return kind;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient transaction) {
                    return Future.succeededFuture(RecorderResult.empty());
                }
            };
        }
    }

    /** What one scenario supplies: its pool, its observers and the definitions of its sources. */
    @Module
    final class Rig {

        private final Pool pool;
        private final RecordingObserver observer;
        private final RecordingInterceptor interceptor;
        private final List<Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>>> sources;

        Rig(
                Pool pool,
                RecordingObserver observer,
                RecordingInterceptor interceptor,
                List<Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>>> sources) {
            this.pool = pool;
            this.observer = observer;
            this.interceptor = interceptor;
            this.sources = List.copyOf(sources);
        }

        @Provides
        @Singleton
        Pool pool() {
            return pool;
        }

        @Provides
        @IntoSet
        SignalSourceObserver observer() {
            return observer;
        }

        @Provides
        @IntoSet
        KafkaConsumerInterceptor interceptor() {
            return interceptor;
        }

        @Provides
        @IntoSet
        WorkflowContributor definitions() {
            return registry -> {
                registry.register(new SourceScenario.OrderShipping());
                registry.register(new SourceScenario.ReturnShipping());
            };
        }

        @Provides
        @ElementsIntoSet
        Set<KafkaConsumerBinding<?>> bindings(WorkflowKafkaSignals signals) {
            Set<KafkaConsumerBinding<?>> built = new HashSet<>();
            for (Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>> source : sources) {
                built.add(source.apply(signals));
            }
            return built;
        }
    }
}
