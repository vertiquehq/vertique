// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import dev.vertique.core.context.ContextValue;
import dev.vertique.core.eventbus.DispatchContextValue;
import dev.vertique.core.scope.ScopeKey;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable dispatch context record injected into job handler methods alongside {@link JobContext}.
 *
 * <p>Carries scheduling metadata that was known at dispatch time: the job identity, attempt
 * counters, queue assignment, timing, and static parameters. Unlike {@link JobContext}, this
 * record is value-based and does not hold mutable state.
 *
 * <p>Annotated with {@link DispatchContextValue} so it is automatically injected into handler
 * methods that declare it as a parameter:
 * <pre>{@code
 * public Future<Void> process(MyPayload payload, JobDispatchContext dispatchCtx) {
 *     if (dispatchCtx.attemptNumber() > 0) {
 *         log.warn("Retrying attempt {}", dispatchCtx.attemptNumber());
 *     }
 *     ...
 * }
 * }</pre>
 *
 * @param jobId         the logical job identifier (stable across retries)
 * @param executionId   the unique execution identifier; stable across retries for a delayed job
 *                      ({@code attemptNumber} distinguishes attempts), distinct per cron fire
 * @param jobType       the scheduling mechanism that triggered this execution
 * @param attemptNumber zero-based attempt counter (0 = first attempt)
 * @param maxAttempts   maximum attempts allowed (inclusive)
 * @param queue         the logical queue this execution belongs to
 * @param scheduledAt   when this execution was scheduled
 * @param startedAt     when this execution was handed to the dispatcher
 * @param parameters    static parameters provided at scheduling time (immutable)
 * @param attributes    mutable runtime attributes set via {@link #withAttribute(String, Object)}
 * @param scopeKey      the scope of the execution, a partition label recorded on the job row;
 *                      {@code null} is read as {@link ScopeKey#DEFAULT}, so the accessor never
 *                      returns {@code null}. It is independent of the ambient
 *                      {@link dev.vertique.core.scope.ScopeContext} and is not part of
 *                      {@link #toMdcContext()}.
 */
@DispatchContextValue
public record JobDispatchContext(
        String jobId,
        java.util.UUID executionId,
        JobType jobType,
        int attemptNumber,
        int maxAttempts,
        String queue,
        Instant scheduledAt,
        Instant startedAt,
        Map<String, Object> parameters,
        Map<String, Object> attributes,
        @Nullable String scopeKey)
        implements ContextValue {

    /**
     * Creates a dispatch context in the default scope.
     *
     * <p>This is the constructor shape that predates {@code scopeKey}; it passes {@code null}, which
     * the canonical constructor reads as {@link ScopeKey#DEFAULT}.
     *
     * @param jobId         the logical job identifier (stable across retries)
     * @param executionId   the unique execution identifier
     * @param jobType       the scheduling mechanism that triggered this execution
     * @param attemptNumber zero-based attempt counter (0 = first attempt)
     * @param maxAttempts   maximum attempts allowed (inclusive)
     * @param queue         the logical queue this execution belongs to
     * @param scheduledAt   when this execution was scheduled
     * @param startedAt     when this execution was handed to the dispatcher
     * @param parameters    static parameters provided at scheduling time
     * @param attributes    mutable runtime attributes
     */
    public JobDispatchContext(
            String jobId,
            java.util.UUID executionId,
            JobType jobType,
            int attemptNumber,
            int maxAttempts,
            String queue,
            Instant scheduledAt,
            Instant startedAt,
            Map<String, Object> parameters,
            Map<String, Object> attributes) {
        this(
                jobId,
                executionId,
                jobType,
                attemptNumber,
                maxAttempts,
                queue,
                scheduledAt,
                startedAt,
                parameters,
                attributes,
                null);
    }

    /**
     * Compact constructor that defensively copies the maps to ensure immutability and reads a
     * {@code null} scope as the default.
     */
    public JobDispatchContext {
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        scopeKey = scopeKey != null ? scopeKey : ScopeKey.DEFAULT.value();
    }

    // --- Factory methods ---

    /**
     * Creates a dispatch context from a {@link JobExecution} record.
     *
     * @param execution   the execution to extract metadata from
     * @param executionId the execution UUID (stable across retries for a delayed job; distinct per cron fire)
     * @param startedAt   when this dispatch started
     * @return a new dispatch context
     */
    public static JobDispatchContext fromExecution(JobExecution execution, UUID executionId, Instant startedAt) {
        return new JobDispatchContext(
                execution.jobId(),
                executionId,
                execution.jobType(),
                execution.attemptNumber(),
                execution.maxAttempts(),
                execution.queue(),
                execution.scheduledAt(),
                startedAt,
                execution.parameters(),
                execution.attributes(),
                execution.scopeKey());
    }

    // --- Derived views ---

    /**
     * Builds the standard MDC context map for this dispatch.
     *
     * @return an immutable map of MDC key-value pairs
     */
    public Map<String, String> toMdcContext() {
        return Map.of(
                "job.id", jobId(),
                "job.type", jobType().name(),
                "job.executionId", executionId().toString(),
                "job.queue", queue(),
                "job.attempt", String.valueOf(attemptNumber()));
    }

    // --- Mutation ---

    /**
     * Returns a copy of this context with the given attribute added or replaced.
     *
     * @param key   the attribute key
     * @param value the attribute value
     * @return updated context record
     */
    public JobDispatchContext withAttribute(String key, Object value) {
        Map<String, Object> newAttrs = new HashMap<>(attributes);
        newAttrs.put(key, value);
        return new JobDispatchContext(
                jobId,
                executionId,
                jobType,
                attemptNumber,
                maxAttempts,
                queue,
                scheduledAt,
                startedAt,
                parameters,
                Map.copyOf(newAttrs),
                scopeKey);
    }
}
