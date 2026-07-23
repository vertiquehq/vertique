// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.deploy.VerticleDeploymentManager;
import java.util.Set;

/**
 * The passive contract an application's Dagger {@code @Component} extends so the host-neutral
 * lifecycle runner can drive it.
 *
 * <p>A {@code VertiqueApplicationComponent} is intentionally <em>passive</em>: it exposes the
 * application's lifecycle inputs — the multibound {@link ApplicationStartupStep} and {@link
 * ApplicationShutdownStep} sets, and the {@link VerticleDeploymentManager} — but owns no
 * choreography itself. The choreography (phase ordering, fail-fast, teardown) lives in {@link
 * VertiqueApplicationBootstrap}, which depends only on this interface — never on a concrete
 * application type or any host type.
 *
 * <p>This separation keeps the dependency-injection boundary (which component is assembled, and how)
 * orthogonal to the process-lifecycle boundary (how the application is started and stopped). An
 * application declares its {@code @Component} as {@code extends VertiqueApplicationComponent}; a
 * bridge host does the same with its own component.
 */
public interface VertiqueApplicationComponent {

    /**
     * Returns the multibound set of application startup steps.
     *
     * <p>The runner partitions these by {@link ApplicationStartupStep#phase() phase} and runs each
     * phase's steps sequentially, ordered by {@link
     * dev.vertique.core.lifecycle.LifecycleOrdered#comparator()}.
     *
     * @return the startup steps; never {@code null} (empty when none are contributed)
     */
    Set<ApplicationStartupStep> startupSteps();

    /**
     * Returns the multibound set of application shutdown steps.
     *
     * <p>During teardown the runner runs these in reverse lifecycle order for the phases whose
     * startup work completed.
     *
     * @return the shutdown steps; never {@code null} (empty when none are contributed)
     */
    Set<ApplicationShutdownStep> shutdownSteps();

    /**
     * Returns the verticle deployment manager that deploys this application's verticles per
     * lifecycle phase.
     *
     * @return the deployment manager; never {@code null}
     */
    VerticleDeploymentManager verticleDeploymentManager();
}
