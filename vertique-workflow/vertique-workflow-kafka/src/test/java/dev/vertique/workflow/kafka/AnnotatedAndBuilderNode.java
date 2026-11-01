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
import dev.vertique.kafka.KafkaConsumerBinding;
import dev.vertique.kafka.KafkaConsumerDeploymentManager;
import dev.vertique.kafka.KafkaModule;
import dev.vertique.kafka.interceptor.KafkaConsumerInterceptor;
import dev.vertique.kafka.json.KafkaJsonModule;
import dev.vertique.workflow.di.WorkflowExecutorConfigModule;
import dev.vertique.workflow.di.WorkflowRecordingConfigModule;
import dev.vertique.workflow.kafka.CarrierSignalTypes.OrdersContract;
import dev.vertique.workflow.kafka.CarrierSignalTypes.ShipmentDelivered;
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
 * The Dagger graph of one node that consumes the same carriers' message from two sources: the annotated listener
 * {@link CarrierSignals}, which the generated registrations module registers, and a source written with the builder
 * for a topic of its own, which the node's rig contributes. Both are described to the builder in the same words, so
 * a proof can compare how the two handle a record.
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
            AnnotatedAndBuilderNode.Rig.class
        })
interface AnnotatedAndBuilderNode {

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

        AnnotatedAndBuilderNode build();
    }

    /**
     * What the node leaves to its application: the pool, an observer and interceptor, the definition, the lookup of the
     * source class that shares the generated module, and the builder source over the topic of the proof.
     */
    @Module
    final class Rig {

        private final Pool pool;
        private final RecordingObserver observer;
        private final RecordingInterceptor interceptor;
        private final SignalTopic builderTopic;

        Rig(Pool pool, RecordingObserver observer, RecordingInterceptor interceptor, SignalTopic builderTopic) {
            this.pool = pool;
            this.observer = observer;
            this.interceptor = interceptor;
            this.builderTopic = builderTopic;
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
            return registry -> registry.register(new CarrierSignalTypes.OrderShipping());
        }

        @Provides
        ShipmentLookup lookup() {
            return new ShipmentLookup.Recording();
        }

        /** The builder source: the settings the annotated listener writes, in the builder's words. */
        @Provides
        @IntoSet
        KafkaConsumerBinding<?> builderSource(WorkflowKafkaSignals signals) {
            return signals.on(builderTopic.topic(), ShipmentDelivered.class)
                    .groupId(builderTopic.group())
                    .name(builderTopic.name())
                    .dedupBy(ShipmentDelivered::eventId)
                    .signal(OrdersContract.class, CarrierSignalTypes.SHIPMENT_DELIVERED)
                    .key(ShipmentDelivered::carrierRef)
                    .build();
        }
    }
}
