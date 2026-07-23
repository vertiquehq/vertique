// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    /** Stable identity for this coordinator instance (hostname + random suffix). */
    private final String serverId;

    private long heartbeatTimerId = -1;
    private long scanTimerId = -1;

    // --- Constructor ---

    /**
     * Creates a new {@code JobCoordinator}.
     *
     * @param vertx      the Vert.x instance used for timer scheduling and event-bus access
     * @param repository the job repository for heartbeat writes and execution recovery
     * @param config     coordinator configuration (intervals, timeouts, enabled flag)
     */
    public JobCoordinator(Vertx vertx, JobRepository repository, JobCoordinatorConfig config) {
        this.vertx = vertx;
        this.repository = repository;
        this.config = config;
        this.serverId = resolveServerId();
    }

    // --- Lifecycle ---

    /**
     * Starts the coordinator: issues an immediate heartbeat and schedules the periodic heartbeat
     * and dead-node scan timers.
     *
     * <p>This method must be called from a Vert.x context (e.g., from a verticle's
     * {@code start()} method). If {@link JobCoordinatorConfig#enabled()} is {@code false}, this
     * method is a no-op and returns immediately.
     *
     * @return a succeeded future when the coordinator has started; failures from the initial
     *         heartbeat write do not propagate — they are logged as warnings
     */
    public Future<Void> start() {
        if (!config.enabled()) {
            log.debug("JobCoordinator is disabled — no timers started");
            return Future.succeededFuture();
        }

        log.info("Starting JobCoordinator for server '{}'", serverId);

        // Issue initial heartbeat immediately (do not wait for the first timer tick)
        repository
                .serverHeartbeat(serverId)
                .onFailure(err -> log.warn("Initial node heartbeat failed: {}", err.getMessage()));

        // Schedule periodic heartbeat
        heartbeatTimerId = vertx.setPeriodic(config.nodeHeartbeatIntervalMs(), id -> repository
                .serverHeartbeat(serverId)
                .onFailure(err -> log.warn("Node heartbeat failed: {}", err.getMessage())));

        // Schedule periodic dead-node scan
        scanTimerId = vertx.setPeriodic(config.scanIntervalMs(), id -> scanForDeadNodes());

        return Future.succeededFuture();
    }

    /**
     * Stops the coordinator: cancels all timers and removes this server's heartbeat row so other
     * nodes know the shutdown was clean.
     *
     * @return a future completed when this server's heartbeat row has been removed; the future
     *         succeeds even if the delete fails (the row will naturally expire)
     */
    public Future<Void> stop() {
        log.info("Stopping JobCoordinator for server '{}'", serverId);

        if (heartbeatTimerId != -1) {
            vertx.cancelTimer(heartbeatTimerId);
            heartbeatTimerId = -1;
        }
        if (scanTimerId != -1) {
            vertx.cancelTimer(scanTimerId);
            scanTimerId = -1;
        }

        // Remove this server's heartbeat row on clean shutdown so other nodes don't treat it
        // as dead on their next scan
        return repository
                .removeServer(serverId)
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
     * {@link JobRepository#abandonAndScheduleRetry} — one transaction, so a crash cannot strand the row
     * in {@code ABANDONED}. A delayed job that has exhausted its attempts is dead-lettered. Other job
     * types (e.g. {@link JobType#CRON}) are marked {@code ABANDONED}; the scheduler re-fires them on the
     * next tick.
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
                    .abandonAndScheduleRetry(
                            job.id(), errorMessage, errorType, job.progress(), Instant.now(), job.attemptNumber() + 1)
                    .onFailure(err ->
                            log.warn("Failed to abandon and re-enqueue execution '{}': {}", job.id(), err.getMessage()))
                    .mapEmpty();
        } else if (job.jobType() == JobType.DELAYED) {
            return repository
                    .completeExecution(job.id(), JobState.DEAD_LETTER, errorMessage, errorType, job.progress())
                    .onFailure(err ->
                            log.warn("Failed to dead-letter exhausted execution '{}': {}", job.id(), err.getMessage()))
                    .mapEmpty();
        } else {
            return repository
                    .completeExecution(job.id(), JobState.ABANDONED, errorMessage, errorType, job.progress())
                    .onFailure(
                            err -> log.warn("Failed to mark execution '{}' ABANDONED: {}", job.id(), err.getMessage()))
                    .mapEmpty();
        }
    }

    // --- Internal helpers ---

    /**
     * Generates a stable server identifier from the hostname and a short random suffix.
     * Falls back to {@code "unknown"} if hostname resolution fails.
     *
     * @return a unique server identifier for this JVM instance
     */
    private static String resolveServerId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return hostname + "-" + suffix;
    }

    /**
     * Returns this coordinator's server identifier.
     *
     * @return the server ID used for heartbeat writes
     */
    public String serverId() {
        return serverId;
    }
}
