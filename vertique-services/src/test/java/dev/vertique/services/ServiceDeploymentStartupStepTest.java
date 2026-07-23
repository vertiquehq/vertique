// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServiceDeploymentStartupStep} — the {@link LifecyclePhase#SERVICES}-phase
 * startup step that deploys service verticles via {@link ServiceDeploymentManager#deployAll()}.
 *
 * <p>Verifies that {@link ServiceDeploymentStartupStep#start()} delegates to {@code deployAll()} and
 * returns its future verbatim, that {@link ServiceDeploymentStartupStep#phase()} is {@code SERVICES},
 * and that {@link ServiceDeploymentStartupStep#priority()} is low (negative) so the step runs first
 * among {@code SERVICES}-phase startup steps — bringing service dispatch up before any
 * {@code SERVICES} verticle.
 */
class ServiceDeploymentStartupStepTest {

    @Test
    @DisplayName("start() delegates to ServiceDeploymentManager.deployAll() and returns its future")
    void start_callsDeployAll_returnsItsFuture() {
        ServiceDeploymentManager manager = mock(ServiceDeploymentManager.class);
        Future<Void> deployFuture = Future.succeededFuture();
        when(manager.deployAll()).thenReturn(deployFuture);

        ServiceDeploymentStartupStep step = new ServiceDeploymentStartupStep(manager);
        Future<Void> result = step.start();

        verify(manager).deployAll();
        assertSame(deployFuture, result, "start() returns the deployAll() future verbatim");
    }

    @Test
    @DisplayName("phase() is SERVICES")
    void phase_isServices() {
        ServiceDeploymentStartupStep step = new ServiceDeploymentStartupStep(mock(ServiceDeploymentManager.class));
        assertEquals(LifecyclePhase.SERVICES, step.phase());
    }

    @Test
    @DisplayName("priority() is very low so the step runs before other SERVICES startup steps")
    void priority_isVeryLow() {
        ServiceDeploymentStartupStep step = new ServiceDeploymentStartupStep(mock(ServiceDeploymentManager.class));
        assertEquals(ServiceDeploymentStartupStep.DEPLOY_PRIORITY, step.priority());
        assertTrue(step.priority() < 0, "priority must be negative (lower than the default 0) to run first");
    }
}
