// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDeploymentShutdownStep} — the {@link LifecyclePhase#SERVICES}-phase
 * shutdown step that undeploys service verticles via {@link ServiceDeploymentManager#undeployAll()}
 * (deregister-first).
 *
 * <p>Verifies that {@link ServiceDeploymentShutdownStep#stop()} delegates to {@code undeployAll()}
 * and returns its future verbatim, that {@link ServiceDeploymentShutdownStep#phase()} is
 * {@code SERVICES}, and that its priority matches the paired
 * {@link ServiceDeploymentStartupStep#DEPLOY_PRIORITY}.
 */
class ServiceDeploymentShutdownStepTest {

    @Test
    @DisplayName("stop() delegates to ServiceDeploymentManager.undeployAll() and returns its future")
    void stop_callsUndeployAll_returnsItsFuture() {
        ServiceDeploymentManager manager = mock(ServiceDeploymentManager.class);
        Future<Void> undeployFuture = Future.succeededFuture();
        when(manager.undeployAll()).thenReturn(undeployFuture);

        ServiceDeploymentShutdownStep step = new ServiceDeploymentShutdownStep(manager);
        Future<Void> result = step.stop();

        verify(manager).undeployAll();
        assertSame(undeployFuture, result, "stop() returns the undeployAll() future verbatim");
    }

    @Test
    @DisplayName("phase() is SERVICES")
    void phase_isServices() {
        ServiceDeploymentShutdownStep step = new ServiceDeploymentShutdownStep(mock(ServiceDeploymentManager.class));
        assertEquals(LifecyclePhase.SERVICES, step.phase());
    }

    @Test
    @DisplayName("priority() matches the paired startup step's DEPLOY_PRIORITY")
    void priority_matchesStartupStep() {
        ServiceDeploymentShutdownStep step = new ServiceDeploymentShutdownStep(mock(ServiceDeploymentManager.class));
        assertEquals(ServiceDeploymentStartupStep.DEPLOY_PRIORITY, step.priority());
    }
}
