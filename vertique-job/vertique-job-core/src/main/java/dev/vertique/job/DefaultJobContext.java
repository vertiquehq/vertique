// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import io.vertx.core.Future;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory implementation of {@link JobContext}.
 *
 * <p>All state is held in-memory for the duration of the execution. When a {@link JobRepository}
 * is present (Phase 2+), the cron trigger or coordinator may flush logs to persistent storage
 * after the execution completes.
 *
 * <p>Thread-safe: uses {@link ConcurrentHashMap} for metadata and steps, and a {@code volatile}
 * flag for cancellation.
 */
public class DefaultJobContext implements JobContext {

    /** Sentinel value stored in {@link #completedSteps} while a step task is in progress. */
    private static final Object IN_PROGRESS = new Object();

    /** Sentinel value stored in {@link #completedSteps} when a step completes with a null result. */
    private static final Object COMPLETED_NULL = new Object();

    private final String jobId;
    private final UUID executionId;
    private final int attemptNumber;
    private final JobType jobType;
    private final DefaultProgressReporter progressReporter;
    private final DefaultJobLogger jobLogger;
    private final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> completedSteps = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    /**
     * Creates a new in-memory job context.
     *
     * @param jobId         the logical job identifier
     * @param executionId   the unique execution identifier
     * @param attemptNumber the zero-based attempt counter
     * @param jobType       the scheduling mechanism that triggered this execution
     */
    public DefaultJobContext(String jobId, UUID executionId, int attemptNumber, JobType jobType) {
        this.jobId = jobId;
        this.executionId = executionId;
        this.attemptNumber = attemptNumber;
        this.jobType = jobType;
        this.progressReporter = new DefaultProgressReporter();
        this.jobLogger = new DefaultJobLogger();
    }

    @Override
    public String jobId() {
        return jobId;
    }

    @Override
    public UUID executionId() {
        return executionId;
    }

    @Override
    public int attemptNumber() {
        return attemptNumber;
    }

    @Override
    public JobType jobType() {
        return jobType;
    }

    @Override
    public ProgressReporter progress() {
        return progressReporter;
    }

    @Override
    public JobLogger logger() {
        return jobLogger;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public void saveMetadata(String key, Object value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        Object value = metadata.get(key);
        return value != null ? (T) value : null;
    }

    @Override
    public boolean hasCompletedStep(String stepName) {
        Object value = completedSteps.get(stepName);
        // IN_PROGRESS means the step is running but not yet completed
        return value != null && value != IN_PROGRESS;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Future<T> runStepOnce(String stepName, Supplier<Future<T>> task) {
        // Use putIfAbsent as an atomic gate to prevent concurrent execution of the same step
        Object existing = completedSteps.putIfAbsent(stepName, IN_PROGRESS);
        if (existing != null) {
            if (existing == IN_PROGRESS || existing == COMPLETED_NULL) {
                return Future.succeededFuture(null);
            }
            return Future.succeededFuture((T) existing);
        }
        try {
            return task.get()
                    .onSuccess(result -> completedSteps.put(stepName, result != null ? result : COMPLETED_NULL))
                    .onFailure(err -> completedSteps.remove(stepName));
        } catch (Exception e) {
            // Supplier threw synchronously — remove marker so step can be retried
            completedSteps.remove(stepName);
            return Future.failedFuture(e);
        }
    }
}
