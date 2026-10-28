// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.exception.WorkflowNodeRoleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Verifies the description of the executor's deployment and the control of an engine that has no Vert.x instance. */
class WorkflowExecutorTest {

    @SuppressWarnings("unchecked")
    private static WorkflowExecutor executor(WorkflowExecutorConfig config) {
        return new WorkflowExecutor(
                null,
                config,
                Mockito.mock(WorkflowCommandRepository.class),
                Mockito.mock(WorkflowTransactionRunner.class),
                Mockito.mock(WorkflowSegments.class),
                "node-1");
    }

    @Test
    @DisplayName(
            "the deployment is workflow-executor in the SERVICES phase at priority 100 with the configured instances")
    void deploymentIsTheServicesPhaseExecutorWithTheConfiguredInstances() {
        // Given an executor configured for three instances
        WorkflowExecutor executor = executor(new WorkflowExecutorConfig(3, 8, 100, 60));

        // When its deployment is described
        VerticleDeployment deployment = executor.deployment();

        // Then it is the SERVICES deployment of the relay's kind, on the event loop
        assertThat(deployment.name()).isEqualTo("workflow-executor");
        assertThat(deployment.phase()).isEqualTo(LifecyclePhase.SERVICES);
        assertThat(deployment.priority()).isEqualTo(100);
        assertThat(deployment.options().getInstances()).isEqualTo(3);
        assertThat(deployment.supplier().get()).isInstanceOf(WorkflowExecutorVerticle.class);
        assertThat(deployment.supplier().get())
                .isNotSameAs(deployment.supplier().get());
    }

    @Test
    @DisplayName("starting an executor that has no Vert.x instance fails, and stopping one that never started succeeds")
    void startWithoutVertxFailsAndStopOfAnUnstartedExecutorSucceeds() {
        // Given an executor without a Vert.x instance
        WorkflowExecutor executor = executor(WorkflowExecutorConfig.defaults());

        // When it is started and stopped
        var start = executor.start();
        var stop = executor.stop();

        // Then the start fails with a clear cause and the stop is a no-op
        assertThat(start.failed()).isTrue();
        assertThat(start.cause()).isInstanceOf(IllegalStateException.class).hasMessageContaining("Vert.x");
        assertThat(stop.succeeded()).isTrue();
    }

    @Test
    @DisplayName("a node id is the host name plus a random suffix, different on every call")
    void nodeIdIsDifferentOnEveryCall() {
        assertThat(WorkflowExecutor.newNodeId()).isNotEqualTo(WorkflowExecutor.newNodeId());
    }

    @Test
    @DisplayName("the margin is a tenth of the lease and at least one second")
    void marginIsATenthOfTheLeaseAndAtLeastOneSecond() {
        assertThat(new WorkflowExecutorConfig(1, 1, 100, 5).marginMs()).isEqualTo(1000L);
        assertThat(new WorkflowExecutorConfig(1, 1, 100, 60).marginMs()).isEqualTo(6000L);
        assertThat(new WorkflowExecutorConfig(1, 1, 100, 10).marginMs()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("starting the executor of a recording-only node fails with the node-role error")
    void startOnARecordingOnlyNodeFailsWithTheNodeRoleError() {
        // Given an executor whose configuration turns the executor off
        WorkflowExecutor executor = executor(new WorkflowExecutorConfig(1, 8, 100, 60, 3, 100, 1000, 1000, 300, false));

        // When it is started
        var start = executor.start();

        // Then the start fails with the node-role error and deploys nothing
        assertThat(start.failed()).isTrue();
        assertThat(start.cause()).isInstanceOf(WorkflowNodeRoleException.class);
        assertThat(start.cause().getMessage()).contains("workflow.executor.enabled=false");
    }
}
