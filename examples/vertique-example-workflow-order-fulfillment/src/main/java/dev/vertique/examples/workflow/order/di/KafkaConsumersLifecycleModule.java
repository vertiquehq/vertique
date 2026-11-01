// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.kafka.KafkaConsumerDeploymentManager;

/**
 * Contributes the lifecycle steps that start and stop the Kafka consumers of the application, among them
 * {@code payment-events}.
 *
 * <p>Only a component that includes {@code KafkaModule} includes this module, because the steps need its
 * {@link KafkaConsumerDeploymentManager}.
 */
@Module
public abstract class KafkaConsumersLifecycleModule {

    /**
     * Contributes the step that starts the Kafka consumers once the application is up.
     *
     * @param consumers the manager that deploys the consumers
     * @return the startup step
     */
    @Provides
    @IntoSet
    static ApplicationStartupStep startKafkaConsumers(KafkaConsumerDeploymentManager consumers) {
        return KafkaConsumerSteps.start(consumers);
    }

    /**
     * Contributes the step that stops the Kafka consumers before the application goes down.
     *
     * @param consumers the manager that deploys the consumers
     * @return the shutdown step
     */
    @Provides
    @IntoSet
    static ApplicationShutdownStep stopKafkaConsumers(KafkaConsumerDeploymentManager consumers) {
        return KafkaConsumerSteps.stop(consumers);
    }
}
