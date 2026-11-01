// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.kafka;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.kafka.KafkaConsumerDeploymentManager;
import dev.vertique.kafka.KafkaModule;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.json.KafkaJsonModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.services.di.WorkflowSourceModule;
import dev.vertique.workflow.source.SignalSourceObserver;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * The Dagger graph of one node whose only source is a class: the production modules of {@link SourceNode}, and the
 * generated registrations module in place of a rig that builds bindings by hand. The graph names neither
 * {@code WorkflowKafkaSignalsModule} nor any {@code @Provides} method for the source class; the source reaches the
 * consumer runtime through the generated module alone.
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
            GeneratedRegistrationsModule.class,
            SourceNode.Engine.class,
            SourceClassNode.Rig.class
        })
interface SourceClassNode {

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

    /** The probe that waits until the engine has run the work recorded for an instance. */
    WorkflowExecutionProbe executionProbe();

    @Component.Builder
    interface Builder {

        Builder vertxModule(VertxModule module);

        Builder rig(Rig module);

        SourceClassNode build();
    }

    /** What the node leaves to its application: the pool, an observer and interceptor, the definition and the lookup. */
    @Module
    final class Rig {

        private final Pool pool;
        private final RecordingObserver observer;
        private final RecordingInterceptor interceptor;
        private final ShipmentLookup lookup;

        Rig(Pool pool, RecordingObserver observer, RecordingInterceptor interceptor, ShipmentLookup lookup) {
            this.pool = pool;
            this.observer = observer;
            this.interceptor = interceptor;
            this.lookup = lookup;
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
            return registry -> registry.register(new SourceScenario.OrderShipping());
        }

        @Provides
        ShipmentLookup lookup() {
            return lookup;
        }
    }
}
