// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * The {@link LifecyclePhase#SERVICES SERVICES}-phase startup step that deploys all service verticles
 * via {@link ServiceDeploymentManager#deployAll()}.
 *
 * <p>This step is the standalone-lifecycle equivalent of the manual
 * {@code serviceDeploymentManager().deployAll()} call applications previously made by hand in
 * {@code MainVerticle.start()} (between {@code deployPhase(INFRA)} and {@code deployPhase(SERVICES)}).
 * It is contributed {@code @IntoSet ApplicationStartupStep} by {@link DispatchModule}, so the
 * lifecycle runner invokes it automatically in {@code SERVICES} phase order.
 *
 * <p><strong>Ordering.</strong> The runner runs a phase's {@link ApplicationStartupStep}s (ordered
 * by {@link LifecycleOrdered#comparator()} — ascending {@link #priority()}) <em>before</em> deploying
 * that phase's verticles. This step returns a deliberately very low priority
 * ({@link #DEPLOY_PRIORITY}) so that, among all {@code SERVICES}-phase startup steps, it runs first —
 * and therefore service dispatch is brought up before any {@code SERVICES}-phase verticle. That
 * preserves the legacy INFRA &rarr; service-dispatch-deploy &rarr; {@code SERVICES}-verticle order
 * (FR-APP-022 / AC-11).
 *
 * <p>This step is paired with {@link ServiceDeploymentShutdownStep}: the runner runs the shutdown
 * step (which calls {@link ServiceDeploymentManager#undeployAll()}, deregistering supervision first)
 * during reverse-order teardown, before any generic verticle undeploy — the owner-managed teardown
 * contract of FR-APP-028 / AC-14.
 */
@Singleton
public final class ServiceDeploymentStartupStep implements ApplicationStartupStep {

    /**
     * The priority for the paired service deploy/undeploy steps. Deliberately very low (much less
     * than {@code 0}, the default) so that this startup step runs <em>first</em> among
     * {@code SERVICES}-phase startup steps — bringing up service dispatch before any
     * {@code SERVICES}-phase verticle. Its paired {@link ServiceDeploymentShutdownStep} shares this
     * priority, so reverse-order teardown runs the service undeploy <em>last</em> among
     * {@code SERVICES}-phase shutdown steps (symmetric to startup). {@code Integer.MIN_VALUE / 2}
     * leaves headroom on both sides for an even-earlier or even-later participant if one is ever
     * needed.
     */
    static final int DEPLOY_PRIORITY = Integer.MIN_VALUE / 2;

    private final ServiceDeploymentManager serviceDeploymentManager;

    /**
     * Constructs the step.
     *
     * @param serviceDeploymentManager the manager whose {@link ServiceDeploymentManager#deployAll()}
     *     this step runs in the {@link LifecyclePhase#SERVICES} phase
     */
    @Inject
    public ServiceDeploymentStartupStep(ServiceDeploymentManager serviceDeploymentManager) {
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
     * Returns the fine ordering priority. See {@link #DEPLOY_PRIORITY}.
     *
     * @return {@link #DEPLOY_PRIORITY} — very low, so this step runs first among {@code SERVICES}
     *     startup steps
     */
    @Override
    public int priority() {
        return DEPLOY_PRIORITY;
    }

    /**
     * Deploys all service verticles.
     *
     * @return the future returned by {@link ServiceDeploymentManager#deployAll()}
     */
    @Override
    public Future<Void> start() {
        return serviceDeploymentManager.deployAll();
    }
}
