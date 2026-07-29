// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import io.vertx.core.Future;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI for persisting job execution state.
 *
 * <p>This interface is intentionally left without an implementation in this module — it serves
 * as a stable contract for Phase 2 ({@code vertique-job-postgresql}) and any custom
 * persistence adapters.
 *
 * <p>The cron module works without a {@code JobRepository}: executions are tracked in-memory
 * only. Bind an implementation in your Dagger component to enable persistent tracking,
 * heartbeat monitoring, and retry coordination.
 *
 * <p>Future-scheduled jobs use a single {@link JobState#ENQUEUED} state with a future
 * {@code scheduled_at} value. The {@link #claimNextJob(String, int)} implementation must
 * filter by {@code scheduled_at <= NOW()} so that jobs are only claimed when their scheduled
 * time has passed — no separate SCHEDULED state or transition method is needed.
 */
public interface JobRepository {

    /**
     * Persists a new job execution record.
     *
     * @param execution the execution to save
     * @return a future of the saved execution ID
     */
    Future<UUID> save(JobExecution execution);

    /**
     * Atomically transitions the execution state if the current state matches {@code fromState}.
     *
     * @param executionId the execution to update
     * @param fromState   the expected current state (optimistic concurrency guard)
     * @param toState     the target state
     * @return a succeeded future if the transition was applied, a failed future otherwise
     */
    Future<Void> updateState(UUID executionId, JobState fromState, JobState toState);

    /**
     * Claims the next available jobs from the given queue for processing.
     *
     * <p>Claiming atomically transitions matching executions from {@code ENQUEUED} to
     * {@code PROCESSING} so no two workers process the same execution.
     *
     * @param queue     the queue name to poll
     * @param batchSize maximum number of executions to claim
     * @return a future of the claimed executions (may be empty)
     */
    Future<List<JobExecution>> claimNextJob(String queue, int batchSize);

    /**
     * Updates the heartbeat timestamp and progress snapshot for a running execution.
     *
     * @param executionId the execution to heartbeat
     * @param progress    the latest progress snapshot
     * @return a succeeded future when the heartbeat is recorded
     */
    Future<Void> heartbeat(UUID executionId, ProgressSnapshot progress);

    /**
     * Finds executions in {@code PROCESSING} state whose heartbeat has not been updated
     * within the given timeout (candidate for re-enqueue or dead-lettering).
     *
     * @param heartbeatTimeout the maximum age of the last heartbeat before an execution is stale
     * @return a future of stale executions
     */
    Future<List<JobExecution>> findStale(Duration heartbeatTimeout);

    /**
     * Finds an execution by its unique ID.
     *
     * @param executionId the execution ID to look up
     * @return a future of the execution wrapped in {@link Optional}, or {@code Optional.empty()}
     *         if not found
     */
    Future<Optional<JobExecution>> findById(UUID executionId);

    /**
     * Appends log entries to the persistent log for the given execution.
     *
     * @param executionId the execution whose log to append to
     * @param entries     the entries to persist
     * @return a succeeded future when the entries have been saved
     */
    Future<Void> saveLogs(UUID executionId, List<LogEntry> entries);

    /**
     * Atomically completes an execution by transitioning to a terminal or non-terminal state and
     * recording the final error information and progress snapshot.
     *
     * <p>The underlying SQL accepts both {@code PROCESSING} and {@code ABANDONED} as the current
     * state, so a late handler reply can still overwrite a coordinator-injected {@code ABANDONED}
     * mark — see the class-level javadoc on {@link JobCoordinator} for the race-condition
     * explanation.
     *
     * @param executionId  the execution to complete
     * @param newState     the target state ({@code SUCCEEDED}, {@code FAILED}, {@code DEAD_LETTER},
     *                     {@code CANCELLED}, or {@code ABANDONED})
     * @param errorMessage the error message, or {@code null} on success
     * @param errorType    the exception class name, or {@code null} on success
     * @param progress     the final progress snapshot
     * @return the persisted {@link JobExecution}, or {@link Optional#empty()} when no row transitioned
     *         — i.e. the requested {@code (current state → newState)} pair is not a valid transition
     *         (the execution is already terminal, or the transition is disallowed by the state model)
     */
    Future<Optional<JobExecution>> completeExecution(
            UUID executionId, JobState newState, String errorMessage, String errorType, ProgressSnapshot progress);

    /**
     * Schedules a retry by transitioning the execution back to {@code ENQUEUED} with a new
     * scheduled time and incremented attempt counter. Clears {@code lockedBy} and
     * {@code startedAt}.
     *
     * @param executionId     the execution to retry
     * @param nextScheduledAt when the retry should become eligible for claiming
     * @param newAttempt      the new attempt number (incremented from previous)
     * @return a succeeded future when the retry is scheduled
     */
    Future<Void> scheduleRetry(UUID executionId, Instant nextScheduledAt, int newAttempt);

    /**
     * Atomically records a retryable handler failure and re-enqueues the execution in a single
     * durable operation. Within one transaction it transitions the row to {@code FAILED} (recording
     * the error and progress) and then back to {@code ENQUEUED} with the new scheduled time and
     * incremented attempt — so a retryable execution is never left stranded in an intermediate
     * {@code FAILED} state if the process crashes between the two writes (either both apply or
     * neither does).
     *
     * <p>The returned {@link Optional} carries the persisted <strong>{@code FAILED}</strong> snapshot
     * (for audit/notification of the failed attempt), even though the committed row is
     * {@code ENQUEUED}. It is {@link Optional#empty()} when no row transitioned (the execution was
     * already terminal — an idempotent no-op).
     *
     * @param executionId     the execution that failed and should be retried
     * @param errorMessage    the failure message, or {@code null}
     * @param errorType       the exception class name, or {@code null}
     * @param progress        the final progress snapshot for the failed attempt
     * @param nextScheduledAt when the retry should become eligible for claiming
     * @param nextAttempt     the new attempt number (incremented from previous)
     * @return the persisted {@code FAILED} snapshot, or {@link Optional#empty()} on a no-op
     */
    Future<Optional<JobExecution>> failAndScheduleRetry(
            UUID executionId,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt);

    /**
     * Atomically records an interrupted execution (timeout or dead-node recovery) and re-enqueues it
     * for retry in a single durable operation — the {@code ABANDONED} counterpart of
     * {@link #failAndScheduleRetry}. Within one transaction it transitions the row to
     * {@code ABANDONED} (recording the error and progress) and then back to {@code ENQUEUED} with the
     * new scheduled time and incremented attempt, so an interrupted execution is never left stranded in
     * {@code ABANDONED} if the process crashes between the two writes (either both apply or neither).
     *
     * <p>The returned {@link Optional} carries the persisted <strong>{@code ABANDONED}</strong> snapshot
     * (for audit/notification of the interrupted attempt), even though the committed row is
     * {@code ENQUEUED}. It is {@link Optional#empty()} when no row transitioned (already terminal).
     *
     * @param executionId     the interrupted execution to retry
     * @param errorMessage    the interruption message, or {@code null}
     * @param errorType       the interruption type, or {@code null}
     * @param progress        the final progress snapshot for the interrupted attempt
     * @param nextScheduledAt when the retry should become eligible for claiming
     * @param nextAttempt     the new attempt number (incremented from previous)
     * @return the persisted {@code ABANDONED} snapshot, or {@link Optional#empty()} on a no-op
     */
    Future<Optional<JobExecution>> abandonAndScheduleRetry(
            UUID executionId,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt);

    /**
     * Attempts to insert a {@link JobExecution} row, returning the generated ID on success or
     * {@link java.util.Optional#empty()} when a conflict prevents the insert (e.g., the
     * {@code ON CONFLICT DO NOTHING} clause matched an existing row).
     *
     * <p>This is the entry point for {@link dev.vertique.job.cron.ExecutionMode#SINGLE_INSTANCE}
     * leader election: all nodes race to insert a row for the same {@code (job_id, scheduled_at)};
     * only one wins and proceeds with dispatch.
     *
     * @param execution the execution to insert
     * @return a future of {@link java.util.Optional} containing the ID of the inserted row, or
     *         {@link java.util.Optional#empty()} if the insert was suppressed by a conflict
     */
    Future<Optional<UUID>> tryInsert(JobExecution execution);

    /**
     * Inserts or updates a cron job schedule definition in the {@code job_schedules} table for
     * dashboard visibility.
     *
     * <p>This is an UPSERT: if a row for {@code schedule.jobId()} already exists, it is updated
     * with the latest annotation-derived values. Code is always the source of truth — the DB
     * entry is overwritten on every deploy.
     *
     * @param schedule the schedule definition to persist
     * @return a succeeded future when the upsert is applied
     */
    Future<Void> saveSchedule(CronJobSchedule schedule);

    /**
     * Updates the {@code last_fired_at} and {@code next_fire_at} timestamps for the given
     * schedule entry. Called after each cron fire to keep the dashboard view current.
     *
     * @param jobId       the job identifier whose schedule to update
     * @param lastFiredAt the instant this fire just occurred
     * @param nextFireAt  the computed next fire time
     * @return a succeeded future when the update is applied
     */
    Future<Void> updateScheduleFireTimes(String jobId, Instant lastFiredAt, Instant nextFireAt);

    /**
     * Loads a cron job schedule by job ID. Used for misfire detection to read
     * {@code last_fired_at} at startup.
     *
     * @param jobId the job identifier to look up
     * @return a future of the schedule wrapped in {@link Optional}, or {@code Optional.empty()}
     *         if no schedule row has been persisted for this job yet
     */
    Future<Optional<CronJobSchedule>> findSchedule(String jobId);

    // --- Node heartbeat ---

    /**
     * Registers or updates this server's heartbeat timestamp in {@code job_server_heartbeats}.
     *
     * <p>Uses an UPSERT so that the first call inserts a new row and subsequent periodic calls
     * refresh the {@code last_heartbeat} column. The {@link dev.vertique.job.JobCoordinator} calls
     * this on a fixed interval (default every 10 s) so that other nodes can detect a crash when
     * the heartbeat goes stale.
     *
     * @param serverId the unique identifier for this application instance
     * @return a succeeded future when the heartbeat has been recorded
     */
    Future<Void> serverHeartbeat(String serverId);

    /**
     * Finds server IDs whose {@code last_heartbeat} is older than the given timeout.
     *
     * <p>Used by the {@link dev.vertique.job.JobCoordinator} during periodic dead-node scans.
     * A server is considered dead when no heartbeat update has arrived within the timeout window.
     *
     * @param heartbeatTimeout the maximum age before a heartbeat is considered stale
     * @return a future of server IDs for nodes that are presumed dead
     */
    Future<List<String>> findDeadServers(Duration heartbeatTimeout);

    /**
     * Removes a dead server's heartbeat row after all of its orphaned executions have been
     * recovered.
     *
     * <p>Deleting the row prevents the server from appearing in repeated dead-node scans. The
     * {@link dev.vertique.job.JobCoordinator} calls this after recovering every execution that
     * was locked by {@code serverId}.
     *
     * @param serverId the identifier of the server whose heartbeat row should be removed
     * @return a succeeded future when the row has been deleted (or did not exist)
     */
    Future<Void> removeServer(String serverId);

    /**
     * Finds all {@link JobExecution} records that are in {@link JobState#PROCESSING} state and
     * were claimed by the given server.
     *
     * <p>Used during dead-node recovery: after a crash is detected, the coordinator fetches every
     * orphaned execution owned by the dead server and transitions it to
     * {@link JobState#ABANDONED} so it can be retried or dead-lettered.
     *
     * @param serverId the node identity recorded in the {@code locked_by} column
     * @return a future of executions currently held by the given server
     */
    Future<List<JobExecution>> findByLockedBy(String serverId);
}
