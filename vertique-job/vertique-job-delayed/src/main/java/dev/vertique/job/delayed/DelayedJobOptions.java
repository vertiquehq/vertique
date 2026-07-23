// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.context.DurableMetadata;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.experimental.FieldDefaults;

/**
 * Per-enqueue overrides for a typed delayed job.
 *
 * <p>All fields are nullable — {@code null} means "use the contract default" from the
 * {@link DelayedJobContract} annotation (or its config override). This avoids ambiguity
 * with zero-value primitives.
 *
 * <p>This class is used only as a method parameter in the typed client proxy and is never
 * serialized or deserialized via Jackson.
 *
 * <p>Example:
 * <pre>{@code
 * job.enqueue(payload, DelayedJobOptions.builder()
 *     .runAt(Instant.now().plusHours(1))
 *     .queue("priority")
 *     .jobId("webhook-pay_123")
 *     .build());
 * }</pre>
 *
 * @see DelayedJobClient#enqueue(Object, DelayedJobOptions)
 */
@Getter
@Builder
@Accessors(fluent = true)
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DelayedJobOptions {

    /**
     * When the job should become eligible for execution. {@code null} means immediate.
     */
    Instant runAt;

    /**
     * Queue override. {@code null} means use the contract default.
     */
    String queue;

    /**
     * Priority override. {@code null} means use the contract default.
     */
    Integer priority;

    /**
     * Max attempts override. {@code null} means use the contract default.
     */
    Integer maxAttempts;

    /**
     * Stable job ID for idempotency. {@code null} means auto-generated.
     */
    String jobId;

    /**
     * Pre-merged durable context metadata as a {@link DurableMetadata} namespaced document.
     *
     * <p><strong>Advanced / internal framework use only.</strong> Normal callers must leave this
     * field {@code null} and rely on the standard capture path in
     * {@link DelayedJobService#enqueue(DelayedJob)} which calls
     * {@code DurableContextPropagator.mergeCaptured(..., DELAYED_JOB)} automatically.
     *
     * <p>When non-{@code null}, this value bypasses {@code mergeCaptured}: the
     * {@link DelayedJobClientProxy} threads it directly onto {@code DelayedJob.builder().metadata(...)}
     * and routes the downstream call to the package-private
     * {@code DelayedJobService.enqueuePremerged(...)} variant, which persists the document as-is into
     * {@code job_executions.metadata} without re-running durable capture or collision detection.
     *
     * <p>Intended exclusively for callers (e.g. workflow timer create/recovery) that have
     * already captured and merged their own durable context and must write an identical document to
     * a replacement delayed-job execution without triggering a second capture pass.
     *
     * @see DelayedJobService
     * @see DelayedJobClientProxy
     */
    DurableMetadata premergedMetadata;
}
