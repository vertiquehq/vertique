// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Low-level verticle deploy/undeploy/track engine.
 *
 * <p>Deploys individual {@link VerticleDeployment} descriptors, tracks their Vert.x deployment IDs,
 * and provides ordered shutdown via {@link #undeployAll()}. For multi-instance deployment, the
 * {@link VerticleDeployment#supplier() supplier} is called once per instance by Vert.x. Use Dagger's
 * {@code Provider<T>} as the supplier to get fresh instances with all dependencies injected.
 *
 * <p>This class does <em>not</em> perform phase orchestration for multibound verticle sets — that
 * responsibility belongs to {@link VerticleDeploymentManager}. Both managers (and any other caller)
 * use this deployer for the actual deploy/undeploy/track primitives.
 *
 * <p>All deployment IDs are tracked for clean shutdown via {@link #undeployAll()}, which reverses
 * the startup order (EDGE → SERVICES → INFRA → BOOTSTRAP, high priority before low within each
 * phase).
 */
@Slf4j
@Singleton
public class VerticleDeployer {

    // --- Internal tracking ---

    /**
     * Internal record tracking a single active or in-flight deployment.
     *
     * @param vertxId the Vert.x deployment ID, or {@link #DEPLOYING_ID} while the deployment is
     *     still in flight
     * @param phase the deployment phase; retained for ordered undeploy
     * @param priority the priority within the phase; retained for ordered undeploy
     */
    private record TrackedDeployment(String vertxId, LifecyclePhase phase, int priority) {}

    /** Sentinel vertxId used while a deployment is in flight. */
    private static final String DEPLOYING_ID = "";

    private final Vertx vertx;
    private final Map<String, TrackedDeployment> tracked = new ConcurrentHashMap<>();

    /**
     * Creates a new deployer.
     *
     * @param vertx the Vert.x instance used to deploy and undeploy verticles
     */
    @Inject
    public VerticleDeployer(Vertx vertx) {
        this.vertx = vertx;
    }

    // --- Individual deployment ---

    /**
     * Deploys a single verticle.
     *
     * <p>Fails immediately if a deployment with the same name is already tracked.
     *
     * @param deployment the deployment descriptor
     * @return a future of the Vert.x deployment ID
     */
    public Future<String> deploy(VerticleDeployment deployment) {
        TrackedDeployment sentinel = new TrackedDeployment(DEPLOYING_ID, deployment.phase(), deployment.priority());
        if (tracked.putIfAbsent(deployment.name(), sentinel) != null) {
            return Future.failedFuture("Deployment name already in use: " + deployment.name());
        }
        return vertx.deployVerticle(deployment.supplier(), deployment.options())
                .onSuccess(id -> {
                    tracked.put(
                            deployment.name(), new TrackedDeployment(id, deployment.phase(), deployment.priority()));
                    log.info(
                            "Deployed verticle: {} instances={}",
                            deployment.name(),
                            deployment.options().getInstances());
                })
                .onFailure(cause -> {
                    tracked.remove(deployment.name(), sentinel);
                    log.error("Failed to deploy verticle: {}", deployment.name(), cause);
                });
    }

    // --- Undeploy ---

    /**
     * Undeploys a previously deployed verticle by name.
     *
     * <p>The deployment is removed from tracking only after Vert.x confirms successful undeployment.
     * If undeployment fails the tracking entry is retained so the caller can retry.
     *
     * @param name the deployment name used in {@link VerticleDeployment}
     * @return a future that succeeds when the verticle is undeployed, or fails if not found
     */
    public Future<Void> undeploy(String name) {
        TrackedDeployment td = tracked.get(name);
        if (td == null || DEPLOYING_ID.equals(td.vertxId())) {
            return Future.failedFuture("No deployment found with name: " + name);
        }
        return vertx.undeploy(td.vertxId()).onSuccess(v -> {
            tracked.remove(name);
            log.info("Undeployed verticle: {}", name);
        });
    }

    /**
     * Undeploys all tracked verticles in reverse startup order.
     *
     * <p>Undeploys in reverse phase order (EDGE → SERVICES → INFRA → BOOTSTRAP), and within each
     * phase in descending priority order. Each group undeploys in parallel ({@link Future#join}),
     * groups are chained sequentially. Verticles currently in-flight (still deploying) are skipped.
     * Verticles that fail to undeploy remain tracked so that callers can inspect or retry them.
     *
     * @return a future that succeeds when all undeploy attempts have settled
     */
    public Future<Void> undeployAll() {
        // Build reverse-ordered groups: phases in reverse enum order, within each phase
        // priorities descending. This ensures EDGE undeploys before INFRA, and high-priority
        // verticles within a phase undeploy before low-priority ones. Non-verticle phases never
        // match a tracked deployment (construction enforces verticle-only phases), so they yield
        // empty groups and are harmless.
        LifecyclePhase[] phases = LifecyclePhase.values();
        List<List<String>> orderedGroups = new ArrayList<>();
        for (int i = phases.length - 1; i >= 0; i--) {
            LifecyclePhase phase = phases[i];
            TreeMap<Integer, List<String>> phaseGroups = new TreeMap<>(Comparator.reverseOrder());
            for (Map.Entry<String, TrackedDeployment> entry : tracked.entrySet()) {
                TrackedDeployment td = entry.getValue();
                if (td.phase() == phase && !DEPLOYING_ID.equals(td.vertxId())) {
                    phaseGroups
                            .computeIfAbsent(td.priority(), k -> new ArrayList<>())
                            .add(entry.getKey());
                }
            }
            orderedGroups.addAll(phaseGroups.values());
        }

        // Chain groups sequentially. Each undeployGroup always succeeds (failures are logged
        // and collected internally), so the chain runs through all groups regardless of errors.
        Future<Void> chain = Future.succeededFuture();
        for (List<String> group : orderedGroups) {
            chain = chain.compose(v -> undeployGroup(group));
        }
        return chain;
    }

    // --- Diagnostics / supervision support ---

    /**
     * Returns the Vert.x deployment ID for a named verticle, or {@code null} if not deployed.
     *
     * <p>Returns {@code null} both when the name is not tracked and when a deployment is still in
     * flight (the internal deploying sentinel has not yet been replaced with a real ID).
     *
     * <p>Used for diagnostics, testing, and runtime coherence validation by the supervision layer.
     *
     * @param name the deployment name
     * @return the deployment ID, or {@code null} if not deployed or deployment is in flight
     */
    public String deploymentId(String name) {
        TrackedDeployment td = tracked.get(name);
        return (td == null || DEPLOYING_ID.equals(td.vertxId())) ? null : td.vertxId();
    }

    /**
     * Atomically removes a deployment from tracking if the current tracked ID matches the expected
     * value.
     *
     * <p>This is a low-level operation for cases where the underlying verticle is known to be dead
     * (stale tracking) and the caller needs to free the name for redeployment. Uses compare-and-swap
     * semantics via {@link java.util.concurrent.ConcurrentHashMap#remove(Object, Object)} to avoid
     * evicting a newer deployment or an in-flight deploying sentinel.
     *
     * @param name the deployment name to evict
     * @param expectedId the Vert.x deployment ID that must match the currently tracked value
     * @return {@code true} if the entry was removed, {@code false} if the name was not tracked or
     *     the tracked ID did not match
     */
    public boolean evict(String name, String expectedId) {
        if (expectedId == null || DEPLOYING_ID.equals(expectedId)) {
            log.debug("Evict rejected for {}: expectedId is {}", name, expectedId == null ? "null" : "DEPLOYING");
            return false;
        }
        TrackedDeployment existing = tracked.get(name);
        if (existing == null || !expectedId.equals(existing.vertxId())) {
            return false;
        }
        boolean removed = tracked.remove(name, existing);
        if (removed) {
            log.info("Evicted deployment tracking: {}", name);
        }
        return removed;
    }

    // --- Private helpers ---

    /**
     * Undeploys a group of verticles in parallel (best-effort). Individual failures are logged but
     * do not prevent other undeploys in the group from completing. Successfully undeployed verticles
     * are removed from tracking.
     *
     * @param names the deployment names to undeploy in this group
     * @return a future that always succeeds after all undeploy attempts in the group have settled
     */
    private Future<Void> undeployGroup(List<String> names) {
        List<Future<Void>> undeploys = names.stream()
                .map(name -> {
                    TrackedDeployment td = tracked.get(name);
                    if (td == null || DEPLOYING_ID.equals(td.vertxId())) {
                        return Future.<Void>succeededFuture();
                    }
                    return vertx.undeploy(td.vertxId())
                            .onSuccess(v -> {
                                tracked.remove(name);
                                log.info("Undeployed verticle: {}", name);
                            })
                            .recover(cause -> {
                                log.warn("Failed to undeploy verticle: {}", name, cause);
                                return Future.succeededFuture();
                            });
                })
                .collect(Collectors.toList());
        return undeploys.isEmpty()
                ? Future.succeededFuture()
                : Future.join(undeploys).mapEmpty();
    }
}
