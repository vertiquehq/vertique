// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import dev.vertique.core.async.Combinators;
import dev.vertique.job.Checkpoint;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobExecutionStateTransitionEvent;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.LogEntry;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Decorator around a {@link JobRepository} that fans out persisted state transitions to registered
 * {@link JobExecutionStateTransitionListener}s.
 *
 * <p>After the underlying repository successfully persists a state change, each listener in the
 * injected set is notified synchronously with a curated {@link JobExecutionStateTransitionEvent}.
 * Listeners are isolated: a {@link RuntimeException} thrown by one listener is caught, logged as a
 * warning, and does not prevent later listeners from running or cause the future to fail.
 *
 * <p>Three methods add this post-write notification: {@link #completeExecution} (terminal / abandoned
 * outcomes) and the two atomic retry operations {@link #failAndScheduleRetry} and
 * {@link #abandonAndScheduleRetry} (each fires the {@code FAILED} / {@code ABANDONED} mark snapshot,
 * not the final {@code ENQUEUED} state). All other {@link JobRepository} methods are delegated verbatim
 * to the decorated {@code raw} repository without modification.
 *
 * <p>Instances of this class are built exclusively by {@link JobPostgresqlModule} and are
 * never constructed directly by application code.
 */
@Slf4j
final class NotifyingJobRepository implements JobRepository {

    // --- Fields ---

    /** The underlying PostgreSQL repository; all methods are delegated to it. */
    private final JobRepository delegate;

    /** Frozen snapshot of the listeners notified after each successful persisted state transition. */
    private final List<JobExecutionStateTransitionListener> listeners;

    // --- Constructor ---

    /**
     * Creates a new notifying decorator.
     *
     * @param delegate  the raw {@link JobRepository} to delegate all persistence to
     * @param listeners the set of listeners to notify after a successful persisted state transition
     *                  (via {@code completeExecution} / {@code failAndScheduleRetry} /
     *                  {@code abandonAndScheduleRetry}); may be empty
     */
    NotifyingJobRepository(JobRepository delegate, Set<JobExecutionStateTransitionListener> listeners) {
        this.delegate = delegate;
        this.listeners = List.copyOf(listeners);
    }

    // --- JobRepository ---

    /** {@inheritDoc} */
    @Override
    public Future<UUID> save(JobExecution execution) {
        return delegate.save(execution);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> updateState(UUID executionId, JobState fromState, JobState toState) {
        return delegate.updateState(executionId, fromState, toState);
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<JobExecution>> claimNextJob(String queue, int batchSize) {
        return delegate.claimNextJob(queue, batchSize);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> heartbeat(UUID executionId, ProgressSnapshot progress) {
        return delegate.heartbeat(executionId, progress);
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<JobExecution>> findStale(Duration heartbeatTimeout) {
        return delegate.findStale(heartbeatTimeout);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Optional<JobExecution>> findById(UUID executionId) {
        return delegate.findById(executionId);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> saveLogs(UUID executionId, List<LogEntry> entries) {
        return delegate.saveLogs(executionId, entries);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> saveCheckpoint(UUID executionId, String key, Object value) {
        return delegate.saveCheckpoint(executionId, key, value);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Optional<Checkpoint>> loadCheckpoint(UUID executionId, String key) {
        return delegate.loadCheckpoint(executionId, key);
    }

    /**
     * {@inheritDoc}
     *
     * <p>After the underlying write succeeds, if the returned {@link Optional} is present (a
     * row was actually transitioned), each registered {@link JobExecutionStateTransitionListener}
     * is notified synchronously via {@link #notifyListeners(JobExecution)}. Listener exceptions
     * are isolated — one bad listener does not affect others or cause the future to fail.
     *
     * <p>When the delegate returns {@link Optional#empty()} (idempotent no-op), no listeners
     * are called, preserving the contract that listeners fire only on real persisted transitions.
     */
    @Override
    public Future<Optional<JobExecution>> completeExecution(
            UUID executionId, JobState newState, String errorMessage, String errorType, ProgressSnapshot progress) {
        return delegate.completeExecution(executionId, newState, errorMessage, errorType, progress)
                .onSuccess(opt -> opt.ifPresent(this::notifyListeners));
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> scheduleRetry(UUID executionId, Instant nextScheduledAt, int newAttempt) {
        return delegate.scheduleRetry(executionId, nextScheduledAt, newAttempt);
    }

    /**
     * {@inheritDoc}
     *
     * <p>On success with a present result, the returned <strong>{@code FAILED}</strong> snapshot
     * is delivered to each listener via {@link #notifyListeners(JobExecution)} — so the failed
     * attempt is audited even though the committed row is {@code ENQUEUED}. An empty result
     * (idempotent no-op) fires nothing. Listener exceptions are isolated.
     */
    @Override
    public Future<Optional<JobExecution>> failAndScheduleRetry(
            UUID executionId,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt) {
        return delegate.failAndScheduleRetry(
                        executionId, errorMessage, errorType, progress, nextScheduledAt, nextAttempt)
                .onSuccess(opt -> opt.ifPresent(this::notifyListeners));
    }

    /**
     * {@inheritDoc}
     *
     * <p>On success with a present result, the returned <strong>{@code ABANDONED}</strong> snapshot
     * is delivered to each listener via {@link #notifyListeners(JobExecution)} — so the interrupted
     * attempt is audited even though the committed row is {@code ENQUEUED}. An empty result
     * (idempotent no-op) fires nothing. Listener exceptions are isolated.
     */
    @Override
    public Future<Optional<JobExecution>> abandonAndScheduleRetry(
            UUID executionId,
            String errorMessage,
            String errorType,
            ProgressSnapshot progress,
            Instant nextScheduledAt,
            int nextAttempt) {
        return delegate.abandonAndScheduleRetry(
                        executionId, errorMessage, errorType, progress, nextScheduledAt, nextAttempt)
                .onSuccess(opt -> opt.ifPresent(this::notifyListeners));
    }

    /** {@inheritDoc} */
    @Override
    public Future<Optional<UUID>> tryInsert(JobExecution execution) {
        return delegate.tryInsert(execution);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> saveSchedule(CronJobSchedule schedule) {
        return delegate.saveSchedule(schedule);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> updateScheduleFireTimes(String jobId, Instant lastFiredAt, Instant nextFireAt) {
        return delegate.updateScheduleFireTimes(jobId, lastFiredAt, nextFireAt);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Optional<CronJobSchedule>> findSchedule(String jobId) {
        return delegate.findSchedule(jobId);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> serverHeartbeat(String serverId) {
        return delegate.serverHeartbeat(serverId);
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<String>> findDeadServers(Duration heartbeatTimeout) {
        return delegate.findDeadServers(heartbeatTimeout);
    }

    /** {@inheritDoc} */
    @Override
    public Future<Void> removeServer(String serverId) {
        return delegate.removeServer(serverId);
    }

    /** {@inheritDoc} */
    @Override
    public Future<List<JobExecution>> findByLockedBy(String serverId) {
        return delegate.findByLockedBy(serverId);
    }

    // --- Internal helpers ---

    /**
     * Builds a {@link JobExecutionStateTransitionEvent} from the persisted execution and notifies
     * each registered listener. Each listener is invoked in sequence; exceptions are caught
     * individually so one failing listener does not prevent subsequent listeners from running.
     *
     * @param exec the persisted {@link JobExecution} returned by the database
     */
    private void notifyListeners(JobExecution exec) {
        JobExecutionStateTransitionEvent event = new JobExecutionStateTransitionEvent(
                exec.id(),
                exec.jobId(),
                exec.jobType(),
                exec.queue(),
                exec.state(),
                exec.attemptNumber(),
                exec.maxAttempts(),
                exec.errorType(),
                exec.scheduledAt(),
                exec.enqueuedAt(),
                exec.startedAt(),
                exec.completedAt(),
                exec.metadata());
        // Deliberate observer-isolation broadening: forEachSwallowSync catches Exception (broader
        // than this site's original RuntimeException). For a conforming listener (no checked
        // exceptions) the behaviour is identical; a sneak-thrown checked exception now logs-and-
        // continues via the onFailure logger below rather than propagating — an isolation choice.
        Combinators.forEachSwallowSync(
                listeners,
                listener -> listener.onStateTransition(event),
                (listener, e) -> log.warn(
                        "JobExecutionStateTransitionListener {} threw during onStateTransition: {}",
                        listener.getClass().getName(),
                        e.toString(),
                        e));
    }
}
