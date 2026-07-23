// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import io.vertx.core.Future;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runtime context for a single job execution.
 *
 * <p>Provides access to job identity, progress reporting, structured logging, cancellation
 * signalling, per-execution metadata storage, step deduplication (idempotency), and checkpoints.
 *
 * <p>Annotated with {@link DispatchContextValue} so it is automatically injected into handler
 * methods that declare it as a parameter:
 * <pre>{@code
 * public Future<Void> processReport(ReportRequest request, JobContext ctx) {
 *     ctx.logger().info("Starting report generation");
 *     ctx.progress().setTotal(100);
 *     return generateReport(request, ctx);
 * }
 * }</pre>
 *
 * <p>The in-memory implementation ({@link DefaultJobContext}) is created by the cron trigger
 * and placed in {@link dev.vertique.core.eventbus.DispatchMetadata#dispatchContext()} before dispatch.
 */
@DispatchContextValue
public interface JobContext extends ContextValue {

    /**
     * Returns the logical job identifier (stable across retries).
     *
     * @return the job ID
     */
    String jobId();

    /**
     * Returns the unique identifier for this specific execution attempt.
     *
     * @return the execution ID
     */
    UUID executionId();

    /**
     * Returns the zero-based attempt counter for this execution (0 = first attempt).
     *
     * @return the attempt number
     */
    int attemptNumber();

    /**
     * Returns the scheduling mechanism that triggered this execution.
     *
     * @return the job type
     */
    JobType jobType();

    /**
     * Returns the progress reporter for this execution.
     *
     * @return the progress reporter (never {@code null})
     */
    ProgressReporter progress();

    /**
     * Returns the buffered logger for this execution.
     *
     * @return the job logger (never {@code null})
     */
    JobLogger logger();

    /**
     * Returns {@code true} if a cancellation request has been signalled for this execution.
     *
     * <p>Handlers should poll this method periodically and terminate gracefully if it returns
     * {@code true}.
     *
     * @return {@code true} if cancellation has been requested
     */
    boolean isCancelled();

    /**
     * Sets the cancellation flag for this execution.
     *
     * @param cancelled {@code true} to signal cancellation, {@code false} to clear it
     */
    void setCancelled(boolean cancelled);

    /**
     * Stores a named metadata value for this execution. Values are available for the duration
     * of the execution and may be inspected by interceptors.
     *
     * @param key   the metadata key (must not be {@code null})
     * @param value the value to store (may be {@code null})
     */
    void saveMetadata(String key, Object value);

    /**
     * Retrieves a previously stored metadata value by key, cast to the given type.
     *
     * @param key  the metadata key
     * @param type the expected value type
     * @param <T>  the value type
     * @return the stored value, or {@code null} if not present
     */
    <T> T getMetadata(String key, Class<T> type);

    /**
     * Returns {@code true} if the named step has already completed successfully in this execution.
     * Used to implement idempotent multi-step workflows.
     *
     * @param stepName the step identifier
     * @return {@code true} if the step is recorded as completed
     */
    boolean hasCompletedStep(String stepName);

    /**
     * Executes the given task exactly once per execution for the named step.
     * If the step has already completed, the task is skipped and the previously recorded result
     * is returned. If the task succeeds, the step is marked as completed.
     *
     * <p>If the task fails, the step is removed from the completed set so it can be retried on
     * the next call.
     *
     * <p>This provides lightweight idempotency for multi-step handlers without requiring a
     * database transaction:
     * <pre>{@code
     * return ctx.runStepOnce("send-email", () -> emailService.send(message));
     * }</pre>
     *
     * <p><b>Concurrency note:</b> If a second call arrives while the first task is still in
     * progress, it returns a succeeded future with {@code null} rather than the eventual result.
     * Callers should avoid concurrent calls to the same step name.
     *
     * @param stepName the unique step identifier within this execution
     * @param task     the task to execute if the step has not yet completed
     * @param <T>      the task result type
     * @return a future of the task result (from cache on second call, from task on first)
     */
    <T> Future<T> runStepOnce(String stepName, Supplier<Future<T>> task);

    /**
     * Saves a named checkpoint value for this execution.
     * Checkpoints survive execution restarts when backed by a {@link JobRepository}.
     *
     * @param key   the checkpoint key
     * @param value the value to checkpoint
     */
    void checkpoint(String key, Object value);

    /**
     * Retrieves the last saved checkpoint value for the given key, cast to the given type.
     *
     * @param key  the checkpoint key
     * @param type the expected value type
     * @param <T>  the value type
     * @return the checkpointed value, or {@code null} if no checkpoint exists for this key
     */
    <T> T lastCheckpoint(String key, Class<T> type);
}
