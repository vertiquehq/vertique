// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.di;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.kafka.KafkaConsumerDeploymentManager;
import io.vertx.core.Future;

/**
 * The lifecycle steps that start and stop the Kafka consumers of the application, among them {@code payment-events}.
 *
 * <p>Nothing deploys Kafka consumers on its own. Both steps run in {@link LifecyclePhase#AFTER_START}: the consumers
 * start once the workflow executor is running, and the runner stops them first, in reverse order, before it undeploys
 * the executor, so that no record arrives while the executor is gone.
 */
final class KafkaConsumerSteps {

    private KafkaConsumerSteps() {}

    /**
     * Deploys every enabled Kafka consumer.
     *
     * @param consumers the manager that deploys the consumers
     * @return the startup step
     */
    static ApplicationStartupStep start(KafkaConsumerDeploymentManager consumers) {
        return new ApplicationStartupStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.AFTER_START;
            }

            @Override
            public Future<Void> start() {
                return consumers.deployAll();
            }
        };
    }

    /**
     * Undeploys every Kafka consumer.
     *
     * @param consumers the manager that deploys the consumers
     * @return the shutdown step
     */
    static ApplicationShutdownStep stop(KafkaConsumerDeploymentManager consumers) {
        return new ApplicationShutdownStep() {
            @Override
            public LifecyclePhase phase() {
                return LifecyclePhase.AFTER_START;
            }

            @Override
            public Future<Void> stop() {
                return consumers.undeployAll();
            }
        };
    }
}
