// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates job lifecycle across nodes: node heartbeat emission, dead-node detection, orphaned
 * execution recovery, and cooperative cancellation.
 *
 * <h2>Design</h2>
 * <p>Three detection layers are combined:
 * <ol>
 *   <li><b>Node heartbeat</b> — this node writes to {@code job_server_heartbeats} every
 *       {@link JobCoordinatorConfig#nodeHeartbeatIntervalMs()} milliseconds. The first write
 *       is issued immediately in {@link #start()}.
 *   <li><b>Dead-node scan</b> — every {@link JobCoordinatorConfig#scanIntervalMs()} milliseconds
 *       the coordinator queries for servers whose {@code last_heartbeat} is older than
 *       {@link JobCoordinatorConfig#nodeHeartbeatTimeoutMs()} milliseconds and recovers
 *       their orphaned executions.
 *   <li><b>Cooperative cancellation</b> — publishing on the event-bus address
 *       {@code job.cancel.<executionId>} signals in-process handlers to stop. The coordinator
 *       also marks the execution as {@link JobState#CANCELLED} in the repository.
 * </ol>
 *
 * <h2>Recovery behaviour</h2>
 * <p>For each orphaned execution owned by a dead server the coordinator:
 * <ol>
 *   <li>For a {@link JobType#DELAYED} job with retries remaining, calls
 *       {@link JobRepository#abandonAndScheduleRetry} — one transaction that records the
 *       {@link JobState#ABANDONED} interruption and re-enqueues the execution, so a crash cannot
 *       strand it in {@code ABANDONED}.
 *   <li>A delayed job that has exhausted its attempts is dead-lettered via
 *       {@link JobRepository#completeExecution} with {@link JobState#DEAD_LETTER}.
 *   <li>After processing all executions, removes the dead server's heartbeat row via
 *       {@link JobRepository#removeServer}.
 * </ol>
 * <p>Cron jobs are marked {@link JobState#ABANDONED} via {@link JobRepository#completeExecution} —
 * the next scheduled fire creates a fresh execution at the next cron tick. The coordinator does
 * <em>not</em> attempt to replay cron executions.
 *
 * <h2>Late handler completions (race condition)</h2>
 * <p>A handler may reply <em>after</em> the coordinator has already marked the execution
 * {@code ABANDONED}. The SQL_COMPLETE query in {@link JobRepository#completeExecution} accepts
 * both {@code PROCESSING} and {@code ABANDONED} as the current state, and the
 * {@link JobState#ABANDONED} transition table allows {@code SUCCEEDED} and {@code FAILED} as
 * targets. This means late completions overwrite the coordinator's mark — correct behaviour.
 */
@Slf4j
public class JobCoordinator {

    // --- Fields ---

    private final Vertx vertx;
    private final JobRepository repository;
    private final JobCoordinatorConfig config;

    /**
     * This node's identity: the heartbeat {@code server_id}, which must equal the {@code locked_by}
     * value the node's repository stamps on its claims for dead-node recovery to find them.
     */
    private final String serverId;

    private long heartbeatTimerId = -1;
    private long scanTimerId = -1;

    /** Whether {@link #start()} armed the timers and {@link #stop()} has not yet run. */
    private boolean started;

    // --- Constructor ---

    /**
     * Creates a new {@code JobCoordinator} that heartbeats under the given node identity.
     *
     * <p>Pass the same value the node's repository stamps as {@code locked_by} on the executions it
     * claims (the {@link JobNodeId} binding), so that a surviving node's coordinator finds this node's
     * executions by its heartbeat row when this node dies.
     *
     * @param vertx      the Vert.x instance used for timer scheduling and event-bus access
     * @param repository the job repository for heartbeat writes and execution recovery
     * @param config     coordinator configuration (intervals, timeouts, enabled flag)
     * @param serverId   this node's identity; must not be {@code null} or blank
     * @throws IllegalArgumentException if {@code serverId} is {@code null} or blank
     */
    public JobCoordinator(Vertx vertx, JobRepository repository, JobCoordinatorConfig config, String serverId) {
        if (serverId == null || serverId.isBlank()) {
            throw new IllegalArgumentException("serverId must not be null or blank");
        }
        this.vertx = vertx;
        this.repository = repository;
        this.config = config;
        this.serverId = serverId;
    }

    // --- Lifecycle ---

    /**
     * Starts the coordinator: issues an immediate heartbeat and schedules the periodic heartbeat
     * and dead-node scan timers.
     *
     * <p>{@code JobCoordinatorModule} contributes a verticle deployment that calls this method and
     * {@link #stop()}, so an application that installs a scheduling module does not call them itself.
     * If {@link JobCoordinatorConfig#enabled()} is {@code false}, this method is a no-op and returns
     * immediately. Calling it again while started is also a no-op.
     *
     * @return a future that succeeds once the initial heartbeat write has settled, so a caller that
     *         waits for it claims nothing before this node's heartbeat row exists; a failed initial
     *         write does not fail the future — it is logged as a warning and the periodic heartbeat
     *         retries it
     */
    public Future<Void> start() {
        if (!config.enabled()) {
            log.debug("JobCoordinator is disabled — no timers started");
            return Future.succeededFuture();
        }
        if (started) {
            return Future.succeededFuture();
        }
        started = true;

        log.info("Starting JobCoordinator for server '{}'", serverId);

        // Schedule periodic heartbeat
        heartbeatTimerId = vertx.setPeriodic(config.nodeHeartbeatIntervalMs(), id -> repository
                .serverHeartbeat(serverId)
                .onFailure(err -> log.warn("Node heartbeat failed: {}", err.getMessage())));

        // Schedule periodic dead-node scan
        scanTimerId = vertx.setPeriodic(config.scanIntervalMs(), id -> scanForDeadNodes());

        // Issue the initial heartbeat immediately (do not wait for the first timer tick) and complete
        // once it settles, so the node's row exists before the claimers deploy
        return repository
                .serverHeartbeat(serverId)
                .onFailure(err -> log.warn("Initial node heartbeat failed: {}", err.getMessage()))
                .recover(err -> Future.succeededFuture());
    }

    /**
     * Stops the coordinator: cancels all timers and, when this node holds no {@code PROCESSING}
     * claims, removes its heartbeat row so other nodes know the shutdown was clean.
     *
     * <p>When executions claimed by this node are still {@code PROCESSING} — handlers still running
     * as the node shuts down — the heartbeat row is kept. It then expires, and a surviving node's
     * coordinator recovers whatever is still {@code PROCESSING} under this node's identity, which a
     * deleted row would never let it find. A no-op when the coordinator was never started.
     *
     * @return a future completed when the claim check and any heartbeat removal have settled; the
     *         future succeeds even if either fails (the row then expires and is handled by recovery)
     */
    public Future<Void> stop() {
        if (!started) {
            return Future.succeededFuture();
        }
        started = false;
        log.info("Stopping JobCoordinator for server '{}'", serverId);

        if (heartbeatTimerId != -1) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
        if (scanTimerId != -1) {
            vertx.cancelTimer(scanTimerId);
            scanTimerId = -1;
        }

        // Remove this server's heartbeat row on clean shutdown so other nodes don't treat it as dead
        // on their next scan, unless it still holds claims that only dead-node recovery can reclaim.
        return repository
                .findByLockedBy(serverId)
                .compose(claims -> {
                    if (!claims.isEmpty()) {
                        log.info(
                                "Keeping heartbeat of server '{}' on shutdown: {} execution(s) still PROCESSING;"
                                        + " a surviving node recovers them once it expires",
                                serverId,
                                claims.size());
                        return Future.<Void>succeededFuture();
                    }
                    return repository.removeServer(serverId);
                })
                .onFailure(err -> log.warn("Failed to remove server heartbeat on shutdown: {}", err.getMessage()))
                .recover(err -> Future.succeededFuture());
    }

    // --- Cancellation ---

    /**
     * Cooperatively cancels a running execution.
     *
     * <p>Publishes a cancel signal on the event-bus address {@code job.cancel.<executionId>}
     * so that handlers listening via {@code DefaultJobContext} can observe the cancellation flag.
     * Also marks the execution as {@link JobState#CANCELLED} in the repository so the state is
     * durable even if the handler ignores the signal.
     *
     * @param executionId the ID of the execution to cancel
     * @return a future completed when the repository state has been updated
     */
    public Future<Void> cancelExecution(UUID executionId) {
        log.info("Cancelling execution '{}'", executionId);
        vertx.eventBus().publish("job.cancel." + executionId, "cancel");
        return repository
                .completeExecution(
                        executionId, JobState.CANCELLED, "Cancelled via coordinator", null, ProgressSnapshot.EMPTY)
                .mapEmpty();
    }

    // --- Dead-node scan ---

    /**
     * Scans for dead servers (those with an expired heartbeat) and initiates recovery for each
     * one. Failures during the scan are logged but do not propagate — the next scheduled scan
     * will retry.
     */
    private void scanForDeadNodes() {
        log.debug("Scanning for dead nodes (timeout={}ms)", config.nodeHeartbeatTimeoutMs());
        repository
                .findDeadServers(Duration.ofMillis(config.nodeHeartbeatTimeoutMs()))
                .onSuccess(deadServers -> {
                    if (!deadServers.isEmpty()) {
                        log.info("Dead-node scan found {} dead server(s): {}", deadServers.size(), deadServers);
                    }
                    for (String deadServerId : deadServers) {
                        recoverDeadServer(deadServerId);
                    }
                })
                .onFailure(err -> log.warn("Dead-node scan failed: {}", err.getMessage()));
    }

    /**
     * Recovers all orphaned executions belonging to a dead server, then removes its heartbeat row.
     *
     * <p>Per orphaned execution, {@link #recoverExecution} either atomically abandons-and-re-enqueues a
     * retryable delayed job, dead-letters an exhausted delayed job, or marks a cron orphan
     * {@link JobState#ABANDONED} (the scheduler re-fires it). The dead server's heartbeat row is removed
     * <strong>only after every per-execution recovery write has committed</strong>: if any recovery
     * fails, {@code removeServer} is skipped so the next dead-node scan re-discovers this server and
     * retries its orphans. Removing the heartbeat while an orphan is still {@code PROCESSING} would
     * orphan it permanently — {@link JobRepository#findDeadServers} reads the heartbeat table, so a
     * deleted row is never re-surfaced.
     *
     * @param deadServerId the server ID of the crashed node whose executions need recovery
     * @return a future that completes after recovery + heartbeat removal; failed (and {@code removeServer}
     *         skipped) when any per-execution recovery write failed
     */
    private Future<Void> recoverDeadServer(String deadServerId) {
        log.info("Recovering orphaned executions from dead server '{}'", deadServerId);
        return repository
                .findByLockedBy(deadServerId)
                .compose(orphanedJobs -> {
                    log.info("Found {} orphaned execution(s) for server '{}'", orphanedJobs.size(), deadServerId);
                    List<Future<Void>> recoveries = orphanedJobs.stream()
                            .map(job -> recoverExecution(job, deadServerId))
                            .toList();
                    return Future.join(new ArrayList<>(recoveries))
                            .compose(composite -> repository.removeServer(deadServerId));
                })
                .onFailure(err -> log.warn(
                        "Recovery for dead server '{}' incomplete; leaving heartbeat for the next scan to retry: {}",
                        deadServerId,
                        err.getMessage()));
    }

    /**
     * Recovers a single orphaned execution from a dead server.
     *
     * <p>For a {@link JobType#DELAYED} job with retries remaining, atomically records the
     * {@link JobState#ABANDONED} interruption and re-enqueues it via
     * {@link JobRepository#abandonClaimedAndScheduleRetry} — one transaction, so a crash cannot strand
     * the row in {@code ABANDONED}. A delayed job that has exhausted its attempts is dead-lettered. Other
     * job types (e.g. {@link JobType#CRON}) are marked {@code ABANDONED}; the scheduler re-fires them on
     * the next tick.
     *
     * <p>Every write is fenced by the claim the scan read ({@code locked_by = deadServerId} at the attempt
     * number read): an execution that another node has claimed again, or that the same node re-claimed after
     * a re-enqueue, since the scan read it matches no row. That result is skipped
     * and logged at {@code DEBUG}; it neither fails the recovery nor triggers a later write.
     *
     * @param job          the orphaned execution to recover
     * @param deadServerId the server ID of the crashed node (used in the error message)
     * @return a future completed when the recovery write commits; failed (so the caller skips heartbeat
     *         removal and retries on the next scan) when the write fails
     */
    private Future<Void> recoverExecution(JobExecution job, String deadServerId) {
        log.info("Recovering execution '{}' (job={}, type={})", job.id(), job.jobId(), job.jobType());
        String errorMessage = "Node " + deadServerId + " crashed";
        String errorType = "NodeCrashException";
        if (job.jobType() == JobType.DELAYED && job.attemptNumber() + 1 < job.maxAttempts()) {
            return repository
                    .abandonClaimedAndScheduleRetry(
                            job.id(),
                            deadServerId,
                            job.attemptNumber(),
                            errorMessage,
                            errorType,
                            job.progress(),
                            Instant.now(),
                            job.attemptNumber() + 1)
                    .onSuccess(result -> logIfClaimMoved(result, job))
                    .onFailure(err ->
                            log.warn("Failed to abandon and re-enqueue execution '{}': {}", job.id(), err.getMessage()))
                    .mapEmpty();
        } else if (job.jobType() == JobType.DELAYED) {
            return repository
                    .completeClaimedExecution(
                            job.id(),
                            deadServerId,
                            job.attemptNumber(),
                            JobState.DEAD_LETTER,
                            errorMessage,
                            errorType,
                            job.progress())
                    .onSuccess(result -> logIfClaimMoved(result, job))
                    .onFailure(err ->
                            log.warn("Failed to dead-letter exhausted execution '{}': {}", job.id(), err.getMessage()))
                    .mapEmpty();
        } else {
            return repository
                    .completeClaimedExecution(
                            job.id(),
                            deadServerId,
                            job.attemptNumber(),
                            JobState.ABANDONED,
                            errorMessage,
                            errorType,
                            job.progress())
                    .onSuccess(result -> logIfClaimMoved(result, job))
                    .onFailure(
                            err -> log.warn("Failed to mark execution '{}' ABANDONED: {}", job.id(), err.getMessage()))
                    .mapEmpty();
        }
    }

    /**
     * Logs a skipped recovery. A recovery write is fenced by the dead server's claim, so an empty result
     * means the execution is no longer {@code PROCESSING} under that claim — it was recovered or claimed
     * again since this scan read it. That is a normal outcome, not an error: the execution is left alone.
     */
    private void logIfClaimMoved(Optional<JobExecution> result, JobExecution job) {
        if (result.isEmpty()) {
            log.debug("Skipped recovery of execution '{}': it is no longer under the claim the scan read", job.id());
        }
    }

    // --- Internal helpers ---

    /**
     * Returns this coordinator's server identifier.
     *
     * @return the server ID used for heartbeat writes
     */
    public String serverId() {
        return serverId;
    }
}
