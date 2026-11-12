// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase orchestrator for multibound {@link VerticleDeployment} entries.
 *
 * <p>Orders deployments by {@link LifecycleOrdered#comparator()}: phase, priority, then
 * deployment name. Each deployment completes before the next starts, including equal-priority
 * deployments. Only the {@link LifecyclePhase#isVerticlePhase() verticle-subset} phases
 * (BOOTSTRAP → INFRA → SERVICES → EDGE) can carry deployments.
 *
 * <p>On failure, rollback is <em>invocation-scoped</em>: only verticles successfully deployed by
 * the current {@link #deployAll()} or {@link #deployPhase(LifecyclePhase)} call are undeployed.
 * Previously healthy deployments from earlier calls are never touched.
 *
 * <p>Delegates all deploy/undeploy/track operations to {@link VerticleDeployer}.
 */
@Slf4j
@Singleton
public class VerticleDeploymentManager {

    private final VerticleDeployer deployer;
    private final Set<VerticleDeployment> deployments;

    /**
     * Creates a new deployment manager.
     *
     * @param deployer the low-level deployer used for deploy/undeploy/track operations
     * @param deployments the set of deployment descriptors from Dagger multibinding
     */
    @Inject
    public VerticleDeploymentManager(VerticleDeployer deployer, Set<VerticleDeployment> deployments) {
        this.deployer = deployer;
        this.deployments = deployments;
    }

    /**
     * Deploys all registered verticles sequentially by phase, priority, then name.
     *
     * <p>If a deployment fails, later deployments are not attempted. Only verticles successfully
     * started by this invocation are rolled back, sequentially in reverse successful-start order.
     * Previously healthy deployments from earlier calls are never touched.
     *
     * @return a future that succeeds when all deployments complete
     */
    public Future<Void> deployAll() {
        return deploySequentially(
                deployments.stream().sorted(LifecycleOrdered.comparator()).toList());
    }

    /**
     * Deploys only the verticles registered for the given phase.
     *
     * <p>Within the phase, deployments start sequentially by ascending priority, then name.
     * On failure, later deployments are not attempted and only verticles successfully started
     * by this invocation are rolled back, sequentially in reverse successful-start order.
     * Other phases and previously deployed verticles in the same phase are NOT affected.
     *
     * @param phase the verticle-subset phase to deploy; must be one of BOOTSTRAP, INFRA, SERVICES,
     *     or EDGE
     * @return a future that succeeds when all verticles in the phase are deployed
     * @throws IllegalArgumentException if {@code phase} is not a
     *     {@link LifecyclePhase#isVerticlePhase() verticle phase}
     */
    public Future<Void> deployPhase(LifecyclePhase phase) {
        if (!phase.isVerticlePhase()) {
            throw new IllegalArgumentException(
                    "deployPhase requires a verticle-subset phase (BOOTSTRAP/INFRA/SERVICES/EDGE); got "
                            + phase
                            + ". CONFIGURE/VALIDATE/MIGRATE/AFTER_START work is contributed as"
                            + " ApplicationStartupStep, not VerticleDeployment.");
        }
        return deploySequentially(deployments.stream()
                .filter(d -> d.phase() == phase)
                .sorted(LifecycleOrdered.comparator())
                .toList());
    }

    /**
     * Undeploys all tracked verticles in reverse startup order.
     *
     * <p>Delegates to {@link VerticleDeployer#undeployAll()}: phases undeploy in reverse enum order
     * (EDGE &rarr; SERVICES &rarr; INFRA &rarr; BOOTSTRAP), and within each phase in descending
     * priority and name order. Each undeployment completes before the next is attempted;
     * individual failures are logged and swallowed so all cleanup is attempted.
     *
     * <p>This is the manager-level entry point the host-neutral lifecycle runner uses for cross-phase
     * teardown of the generic verticles it deployed; it never touches the {@code Vertx} instance.
     *
     * @return a future that succeeds when all undeploy attempts have settled
     */
    public Future<Void> undeployAll() {
        return deployer.undeployAll();
    }

    // --- Private helpers ---

    private Future<Void> deploySequentially(List<VerticleDeployment> ordered) {
        // Each mutation happens in the composed chain before the next deployment can begin.
        List<String> started = new ArrayList<>();
        Future<Void> chain = Future.succeededFuture();
        for (VerticleDeployment deployment : ordered) {
            chain = chain.compose(v -> deployer.deploy(deployment).map(id -> {
                started.add(deployment.name());
                return (Void) null;
            }));
        }
        return chain.recover(cause -> rollbackStarted(started).transform(v -> Future.failedFuture(cause)));
    }

    /** Rolls back this invocation in reverse order, preserving best-effort cleanup after failures. */
    private Future<Void> rollbackStarted(List<String> started) {
        Future<Void> chain = Future.succeededFuture();
        for (int i = started.size() - 1; i >= 0; i--) {
            String name = started.get(i);
            chain = chain.compose(v -> deployer.undeploy(name)).recover(cause -> {
                log.warn("Rollback: failed to undeploy {}", name, cause);
                return Future.succeededFuture();
            });
        }
        return chain;
    }
}
