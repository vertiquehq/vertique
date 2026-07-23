// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase orchestrator for multibound {@link VerticleDeployment} entries.
 *
 * <p>Groups deployments by {@link LifecyclePhase} (ascending enum order). Only the
 * {@link LifecyclePhase#isVerticlePhase() verticle-subset} phases (BOOTSTRAP → INFRA → SERVICES →
 * EDGE) can carry deployments; the non-verticle phases simply yield empty groups. Within each phase,
 * deployments are ordered by {@link VerticleDeployment#priority() priority} (ascending). Each
 * priority group deploys in parallel with wait-all semantics ({@link Future#join}). Priority groups
 * within a phase are chained sequentially.
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
     * Deploys all registered verticles, ordered by phase then priority.
     *
     * <p>Phases are deployed in ascending enum order; the verticle-subset phases carry deployments in
     * the order BOOTSTRAP → INFRA → SERVICES → EDGE, while non-verticle phases yield empty groups.
     * Within each phase, lower-priority groups deploy first. Within the same priority, all verticles deploy
     * in parallel with wait-all semantics ({@link Future#join}). If any deployment fails, only
     * verticles successfully started by <em>this</em> invocation are rolled back; previously healthy
     * deployments from earlier calls are not touched.
     *
     * @return a future that succeeds when all deployments complete
     */
    public Future<Void> deployAll() {
        if (deployments.isEmpty()) {
            return Future.succeededFuture();
        }

        Set<String> started = ConcurrentHashMap.newKeySet();
        Future<Void> chain = Future.succeededFuture();
        for (LifecyclePhase phase : LifecyclePhase.values()) {
            TreeMap<Integer, List<VerticleDeployment>> groups = deployments.stream()
                    .filter(d -> d.phase() == phase)
                    .collect(Collectors.groupingBy(VerticleDeployment::priority, TreeMap::new, Collectors.toList()));
            for (Map.Entry<Integer, List<VerticleDeployment>> group : groups.entrySet()) {
                chain = chain.compose(v -> deployGroupTracked(group.getValue(), started));
            }
        }
        return chain.recover(cause -> rollbackStarted(started).transform(v -> Future.failedFuture(cause)));
    }

    /**
     * Deploys only the verticles registered for the given phase.
     *
     * <p>Within the phase, lower-priority groups deploy first. Within the same priority, all
     * verticles deploy in parallel with wait-all semantics ({@link Future#join}). On partial
     * failure, only verticles successfully started by <em>this</em> invocation are rolled back.
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
        List<VerticleDeployment> phaseDeployments =
                deployments.stream().filter(d -> d.phase() == phase).collect(Collectors.toList());
        if (phaseDeployments.isEmpty()) {
            return Future.succeededFuture();
        }

        Set<String> started = ConcurrentHashMap.newKeySet();
        TreeMap<Integer, List<VerticleDeployment>> groups = phaseDeployments.stream()
                .collect(Collectors.groupingBy(VerticleDeployment::priority, TreeMap::new, Collectors.toList()));

        Future<Void> chain = Future.succeededFuture();
        for (Map.Entry<Integer, List<VerticleDeployment>> group : groups.entrySet()) {
            chain = chain.compose(v -> deployGroupTracked(group.getValue(), started));
        }
        return chain.recover(cause -> rollbackStarted(started).transform(v -> Future.failedFuture(cause)));
    }

    /**
     * Undeploys all tracked verticles in reverse startup order.
     *
     * <p>Delegates to {@link VerticleDeployer#undeployAll()}: phases undeploy in reverse enum order
     * (EDGE &rarr; SERVICES &rarr; INFRA &rarr; BOOTSTRAP), and within each phase in descending
     * priority order. Each group undeploys in parallel ({@link Future#join}); individual failures are
     * logged and swallowed so the returned future always succeeds once all undeploy attempts settle.
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

    /**
     * Deploys all verticles in a priority group in parallel with wait-all semantics. Successfully
     * deployed names are added to the {@code started} set for invocation-scoped rollback.
     *
     * @param group the list of deployments to start in parallel
     * @param started the set tracking names deployed by this invocation
     * @return a future that completes when all deployments in the group have settled
     */
    private Future<Void> deployGroupTracked(List<VerticleDeployment> group, Set<String> started) {
        List<Future<String>> futures = group.stream()
                .map(d -> deployer.deploy(d).onSuccess(id -> started.add(d.name())))
                .collect(Collectors.toList());
        return Future.join(futures).mapEmpty();
    }

    /**
     * Rolls back only the verticles that were successfully deployed by the current invocation.
     * All rollback undeploys are attempted regardless of individual failures.
     *
     * @param started the set of deployment names started by this invocation
     * @return a future that completes when all rollback undeploys have been attempted
     */
    private Future<Void> rollbackStarted(Set<String> started) {
        List<Future<Void>> undeploys = started.stream()
                .map(name -> deployer.undeploy(name).recover(cause -> {
                    log.warn("Rollback: failed to undeploy {}", name, cause);
                    return Future.succeededFuture();
                }))
                .collect(Collectors.toList());
        return undeploys.isEmpty()
                ? Future.succeededFuture()
                : Future.join(undeploys).mapEmpty();
    }
}
