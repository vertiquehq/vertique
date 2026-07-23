// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The {@link LifecyclePhase#SERVICES SERVICES}-phase shutdown step paired with
 * {@link ServiceDeploymentStartupStep}; it undeploys all service verticles via
 * {@link ServiceDeploymentManager#undeployAll()}.
 *
 * <p>{@link ServiceDeploymentManager#undeployAll()} <em>deregisters supervision before undeploy</em>,
 * suppressing spurious restarts — the deregister-first teardown the generic
 * {@link dev.vertique.deploy.VerticleDeployer} cannot provide. Per FR-APP-028 / AC-14 the lifecycle
 * runner runs this owner-managed shutdown step (in reverse {@link LifecycleOrdered#comparator()}
 * order) <em>before</em> any generic verticle undeploy, and it never undeploys the service verticles
 * through the generic deployer (which would bypass the deregister-first ordering).
 *
 * <p>It is contributed {@code @IntoSet ApplicationShutdownStep} by {@link DispatchModule}. It shares
 * {@link ServiceDeploymentStartupStep#DEPLOY_PRIORITY} with its paired startup step, so that
 * reverse-order teardown runs it symmetrically with the startup ordering.
 */
@Singleton
public final class ServiceDeploymentShutdownStep implements ApplicationShutdownStep {

    private final ServiceDeploymentManager serviceDeploymentManager;

    /**
     * Constructs the step.
     *
     * @param serviceDeploymentManager the manager whose {@link ServiceDeploymentManager#undeployAll()}
     *     this step runs (deregister-first) in the {@link LifecyclePhase#SERVICES} phase during
     *     reverse-order teardown
     */
    @Inject
    public ServiceDeploymentShutdownStep(ServiceDeploymentManager serviceDeploymentManager) {
        this.serviceDeploymentManager = serviceDeploymentManager;
    }

    /**
     * Returns the phase this step runs in.
     *
     * @return {@link LifecyclePhase#SERVICES}
     */
    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.SERVICES;
    }

    /**
     * Returns the fine ordering priority, matching the paired
     * {@link ServiceDeploymentStartupStep#DEPLOY_PRIORITY}.
     *
     * @return {@link ServiceDeploymentStartupStep#DEPLOY_PRIORITY}
     */
    @Override
    public int priority() {
        return ServiceDeploymentStartupStep.DEPLOY_PRIORITY;
    }

    /**
     * Undeploys all service verticles, deregistering supervision first.
     *
     * @return the future returned by {@link ServiceDeploymentManager#undeployAll()}
     */
    @Override
    public Future<Void> stop() {
        return serviceDeploymentManager.undeployAll();
    }
}
