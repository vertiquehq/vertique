// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.deploy.VerticleDeploymentManager;
import java.util.Set;

/**
 * Minimal passive {@link VertiqueApplicationComponent} for {@link VertiqueBootstrapVerticleIT}, built
 * by {@link TestApplicationFactory}. It exposes a fixed startup-step set, shutdown-step set, and a
 * {@link VerticleDeploymentManager}, with no choreography of its own (the lifecycle runner drives it).
 *
 * @param startupSteps the multibound-equivalent startup steps
 * @param shutdownSteps the multibound-equivalent shutdown steps
 * @param verticleDeploymentManager the (real, empty) deployment manager
 */
record TestApplicationComponent(
        Set<ApplicationStartupStep> startupSteps,
        Set<ApplicationShutdownStep> shutdownSteps,
        VerticleDeploymentManager verticleDeploymentManager)
        implements VertiqueApplicationComponent {}
