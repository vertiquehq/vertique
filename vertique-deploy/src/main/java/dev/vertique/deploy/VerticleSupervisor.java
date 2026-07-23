// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Supervises deployed verticles by name with one-for-one restart and bounded exponential backoff.
 *
 * <p>Tracks restart attempts within a sliding time window. When the restart budget is exhausted,
 * the verticle is marked unavailable and callers can query this via {@link #isAvailable(String)}.
 *
 * <p>Intentional undeploys are distinguished from crashes by calling {@link #unsupervise(String)}
 * before undeploying, which sets an internal flag to suppress restart attempts.
 *
 * <p>Restart de-duplication is enforced via the {@code restartInProgress} flag inside {@link
 * SupervisedState}. Multiple concurrent {@link #reportFatalError} calls for the same name will
 * result in at most one scheduled restart attempt.
 *
 * <p>Each restart calls {@link VerticleDeployer#undeploy(String)} before the redeploy action,
 * ensuring no orphaned instances remain. If the undeploy fails because Vert.x no longer has the
 * deployment (stale tracking), the supervisor automatically evicts the stale tracking entry via
 * {@link VerticleDeployer#evict(String, String)} before proceeding to redeploy.
 */
@Slf4j
@Singleton
public class VerticleSupervisor {

    // --- Internal State ---

    /**
     * Mutable state tracked per supervised verticle name.
     *
     * <p>Not a record because fields must be mutated over the lifetime of the supervisor. Fields
     * are protected by {@code synchronized(state)} blocks where noted.
     */
    private static final class SupervisedState {
        final SupervisionConfig config;
        volatile boolean available = true;
        volatile boolean intentionalUndeploy = false;
        volatile boolean restartInProgress = false;
        final List<Instant> restartTimestamps = new ArrayList<>();
        int consecutiveRestarts = 0;
        String deploymentId;
        Runnable redeployAction;

        /**
         * Creates a new state with the given supervision configuration.
         *
         * @param config the supervision configuration controlling restart budget and backoff
         */
        SupervisedState(SupervisionConfig config) {
            this.config = config;
        }
    }

    private final Vertx vertx;
    private final VerticleDeployer deployer;
    private final Map<String, SupervisedState> states = new ConcurrentHashMap<>();

    /**
     * Creates a new supervisor.
     *
     * @param vertx the Vert.x instance used to schedule restart timers and check deployment IDs
     * @param deployer the deployer used to undeploy and evict stale tracking entries on restart
     */
    @Inject
    public VerticleSupervisor(Vertx vertx, VerticleDeployer deployer) {
        this.vertx = vertx;
        this.deployer = deployer;
    }

    // --- Lifecycle ---

    /**
     * Starts supervising a verticle after successful deployment.
     *
     * <p>Validates that the deployer's tracked ID for {@code name} matches the provided {@code
     * deploymentId}. If the deployer has no record of this name ({@code deploymentId(name)} returns
     * {@code null}) or the tracked ID differs, an {@link IllegalStateException} is thrown.
     *
     * <p>If the name was already supervised (re-registration after a restart cycle), the mutable
     * state ({@code available}, {@code restartInProgress}, {@code consecutiveRestarts}) is reset to
     * reflect the fresh deployment. The restart timestamp history is preserved so the sliding window
     * budget remains accurate across redeploys.
     *
     * @param name the deployment name as registered with {@link VerticleDeployer}
     * @param deploymentId the Vert.x deployment ID returned by {@link VerticleDeployer#deploy}
     * @param config the supervision configuration to use for this verticle
     * @param redeployAction the action to run to redeploy the verticle on restart
     * @throws IllegalStateException if the deployer's tracked ID for {@code name} is null or does
     *     not match {@code deploymentId}
     */
    public void supervise(String name, String deploymentId, SupervisionConfig config, Runnable redeployAction) {
        String trackedId = deployer.deploymentId(name);
        if (trackedId == null) {
            throw new IllegalStateException(
                    "Cannot supervise '" + name + "': deployer has no tracked deployment for this name");
        }
        if (!trackedId.equals(deploymentId)) {
            throw new IllegalStateException("Cannot supervise '"
                    + name
                    + "': provided deploymentId '"
                    + deploymentId
                    + "' does not match deployer-tracked ID '"
                    + trackedId
                    + "'");
        }
        states.compute(name, (key, existing) -> {
            if (existing != null) {
                // Re-registration after restart — reset mutable state, preserve timestamp history
                synchronized (existing) {
                    existing.deploymentId = deploymentId;
                    existing.redeployAction = redeployAction;
                    existing.available = true;
                    existing.restartInProgress = false;
                    existing.consecutiveRestarts = 0;
                }
                return existing;
            }
            SupervisedState state = new SupervisedState(config);
            state.deploymentId = deploymentId;
            state.redeployAction = redeployAction;
            return state;
        });
    }

    /**
     * Stops supervising a verticle before intentional undeploy, suppressing any restart attempt.
     *
     * <p>Must be called before triggering the actual undeploy to distinguish intentional shutdown
     * from an unexpected crash. Any pending restart timer that fires after this call will see
     * {@code intentionalUndeploy == true} and skip the redeploy action.
     *
     * @param name the deployment name to stop supervising
     */
    public void unsupervise(String name) {
        SupervisedState state = states.get(name);
        if (state != null) {
            synchronized (state) {
                state.intentionalUndeploy = true;
                state.restartInProgress = false;
            }
        }
        states.remove(name);
    }

    // --- Error Reporting ---

    /**
     * Reports a fatal error and schedules a restart if the budget allows.
     *
     * <p>Concurrent calls for the same name are de-duplicated: once a restart is in progress,
     * subsequent calls are silently ignored until the restart completes.
     *
     * <p>If the name is not supervised, this call is silently ignored.
     *
     * @param name the deployment name of the verticle that encountered the error
     * @param error the fatal error that caused the verticle to fail
     */
    public void reportFatalError(String name, Throwable error) {
        SupervisedState state = states.get(name);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.intentionalUndeploy || state.restartInProgress) {
                return;
            }
            log.warn("Fatal error in verticle {}: {}", name, error.getMessage(), error);
            scheduleRestart(name, state);
        }
    }

    /**
     * Reports a failed redeploy, clears the restart-in-progress flag, and schedules another
     * restart attempt if the budget allows.
     *
     * <p>The failed attempt has already been counted in the restart budget (timestamp was added by
     * the previous {@link #scheduleRestart} call). This method simply clears the de-duplication
     * guard and retries.
     *
     * <p>If the name is not supervised or an intentional undeploy has been requested, this call is
     * silently ignored (or clears the in-progress flag and returns, respectively).
     *
     * @param name the deployment name of the verticle whose redeploy failed
     * @param cause the cause of the redeploy failure
     */
    public void reportRedeployFailure(String name, Throwable cause) {
        SupervisedState state = states.get(name);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.intentionalUndeploy) {
                state.restartInProgress = false;
                return;
            }
            state.restartInProgress = false;
            log.warn("Redeploy failed for verticle {}, scheduling retry", name, cause);
            scheduleRestart(name, state);
        }
    }

    // --- Availability ---

    /**
     * Returns {@code true} if the named verticle is available.
     *
     * <p>Returns {@code false} while a restart is in progress or after the restart budget has been
     * exhausted. Returns {@code true} for names that are not supervised (fail-open).
     *
     * @param name the deployment name to check
     * @return {@code true} if the verticle is available or not supervised
     */
    public boolean isAvailable(String name) {
        SupervisedState state = states.get(name);
        return state == null || state.available;
    }

    /**
     * Returns an unmodifiable snapshot of the availability status of all supervised verticles.
     *
     * <p>The map keys are deployment names and the values indicate whether each verticle is
     * currently available. Verticles that are restarting or have exhausted their restart budget
     * will appear as unavailable.
     *
     * @return an unmodifiable map of deployment name to availability status; empty if no verticles
     *     are supervised
     */
    public Map<String, Boolean> availability() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        states.forEach((name, state) -> result.put(name, state.available));
        return Collections.unmodifiableMap(result);
    }

    // --- Restart Logic ---

    /**
     * Schedules a restart with exponential backoff.
     *
     * <p>Must be called while holding the lock on {@code state}. Prunes stale timestamps outside
     * the sliding window, checks the budget, and if restarts remain, schedules an
     * undeploy-then-redeploy after a computed backoff delay. If the budget is exhausted the
     * verticle is marked unavailable immediately.
     *
     * <p>The restart cycle includes built-in stale tracking recovery: if
     * {@link VerticleDeployer#undeploy(String)} fails and the verticle is no longer in
     * {@link Vertx#deploymentIDs()}, the supervisor automatically calls
     * {@link VerticleDeployer#evict(String, String)} to clear the stale tracking entry so the name
     * can be reused on redeploy.
     *
     * @param name the deployment name of the verticle to restart
     * @param state the mutable supervision state (caller must hold the lock)
     */
    private void scheduleRestart(String name, SupervisedState state) {
        // Prune timestamps outside the sliding window
        Instant windowStart = Instant.now().minusMillis(state.config.withinMs());
        state.restartTimestamps.removeIf(ts -> ts.isBefore(windowStart));

        if (state.restartTimestamps.size() >= state.config.maxRestarts()) {
            state.available = false;
            log.warn(
                    "Verticle {} restart budget exhausted ({} restarts within {}ms). Marking UNAVAILABLE.",
                    name,
                    state.config.maxRestarts(),
                    state.config.withinMs());
            return;
        }

        state.restartInProgress = true;
        state.available = false;
        state.restartTimestamps.add(Instant.now());
        state.consecutiveRestarts++;
        long backoff = computeBackoff(state);

        log.info(
                "Restarting verticle {} in {}ms (attempt {} within window)",
                name,
                backoff,
                state.restartTimestamps.size());

        vertx.setTimer(backoff, id -> {
            String currentDeploymentId;
            Runnable currentRedeployAction;
            synchronized (state) {
                if (state.intentionalUndeploy) {
                    state.restartInProgress = false;
                    return;
                }
                currentDeploymentId = state.deploymentId;
                currentRedeployAction = state.redeployAction;
            }
            if (currentRedeployAction == null) {
                log.error("No redeploy action for {}; cannot restart", name);
                synchronized (state) {
                    state.restartInProgress = false;
                }
                return;
            }
            // Undeploy existing instance; includes built-in stale tracking recovery.
            // Redeploy only runs after successful undeploy (or stale recovery). If undeploy
            // fails for a live instance, restartInProgress is cleared so the next
            // reportFatalError can trigger a fresh attempt — no duplicate instances.
            deployer.undeploy(name)
                    .recover(cause -> {
                        // Check if the verticle is actually dead (stale deployer tracking)
                        if (vertx.deploymentIDs().contains(currentDeploymentId)) {
                            // Still alive — propagate the failure (no redeploy)
                            return Future.failedFuture(cause);
                        }
                        // Dead — CAS evict stale tracking so the name can be reused for redeploy
                        if (!deployer.evict(name, currentDeploymentId)) {
                            log.warn(
                                    "CAS evict missed for {} (expectedId={}); tracking may have been replaced",
                                    name,
                                    currentDeploymentId);
                        }
                        return Future.succeededFuture();
                    })
                    .onSuccess(v -> {
                        synchronized (state) {
                            if (state.intentionalUndeploy) {
                                state.restartInProgress = false;
                                return;
                            }
                        }
                        try {
                            currentRedeployAction.run();
                        } catch (Exception e) {
                            log.error("Redeploy action threw for {}, treating as redeploy failure", name, e);
                            reportRedeployFailure(name, e);
                        }
                    })
                    .onFailure(cause -> {
                        log.warn("Cannot restart {}: undeploy failed for live instance, scheduling retry", name, cause);
                        synchronized (state) {
                            state.restartInProgress = false;
                            if (!state.intentionalUndeploy) {
                                scheduleRestart(name, state);
                            }
                        }
                    });
        });
    }

    /**
     * Computes the exponential backoff delay for the current restart attempt.
     *
     * @param state the supervision state containing the consecutive restart count and config
     * @return the capped backoff delay in milliseconds
     */
    private long computeBackoff(SupervisedState state) {
        double delay = state.config.initialBackoffMs() * Math.pow(2, state.consecutiveRestarts - 1);
        return Math.min((long) delay, state.config.maxBackoffMs());
    }
}
