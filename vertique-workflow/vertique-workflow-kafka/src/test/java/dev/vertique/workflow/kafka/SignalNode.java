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
import dev.vertique.core.json.JsonMapperProfile;
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
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.services.di.WorkflowSourceModule;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The Dagger graph of one node of the two-node proofs: the same production modules as {@link SourceNode}, with a rig
 * that supplies the definitions of the node, if it deploys any, the JSON profile of the process and the sources it
 * hosts. The role of the node is not in the graph but in its configuration: {@code workflow.executor.enabled=false}
 * makes it record-only, and the registry then ignores the definitions the rig contributes.
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
            SignalNode.Rig.class
        })
interface SignalNode {

    /** The startup steps of the graph, to run in lifecycle order before the verticles and the consumers deploy. */
    Set<ApplicationStartupStep> startupSteps();

    /** The verticle deployments of the graph; a recording-only node has no {@code workflow-executor} among them. */
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

        SignalNode build();
    }

    /** What one node supplies: its pool, its definitions, the process JSON profile and its sources. */
    @Module
    final class Rig {

        private final Pool pool;
        private final JsonMapperProfile profile;
        private final RecordingInterceptor interceptor;
        private final List<WorkflowDefinition<?, ?>> definitions;
        private final List<Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>>> sources;

        Rig(
                Pool pool,
                JsonMapperProfile profile,
                RecordingInterceptor interceptor,
                List<WorkflowDefinition<?, ?>> definitions,
                List<Function<WorkflowKafkaSignals, KafkaConsumerBinding<?>>> sources) {
            this.pool = pool;
            this.profile = profile;
            this.interceptor = interceptor;
            this.definitions = List.copyOf(definitions);
            this.sources = List.copyOf(sources);
        }

        @Provides
        @Singleton
        Pool pool() {
            return pool;
        }

        @Provides
        @IntoSet
        JsonMapperProfile profile() {
            return profile;
        }

        @Provides
        @IntoSet
        KafkaConsumerInterceptor interceptor() {
            return interceptor;
        }

        @Provides
        @IntoSet
        WorkflowContributor definitions() {
            return registry -> definitions.forEach(registry::register);
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
