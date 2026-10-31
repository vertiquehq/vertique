// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.core.context.DurableMetadata;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Hand-copied body of a generated {@code {Contract}_DelayedJobProxy} that enqueues through the service it is
 * given, with the constructor {@code (DelayedJobService, DelayedJobContract, JsonObject)}. It stands for a proxy
 * compiled before clients could select a scope, so it never sets a scope itself.
 */
public final class PrecompiledShapeJob_DelayedJobProxy implements PrecompiledShapeJob {

    private final DelayedJobService jobService;
    private final String handlerName;
    private final int effectiveMaxAttempts;
    private final String effectiveQueue;
    private final int effectivePriority;

    /**
     * Matches the generated proxy constructor signature.
     *
     * @param jobService the delayed-job service to enqueue through
     * @param annotation the contract annotation supplying name and defaults
     * @param contractConfig the per-contract config section overriding annotation defaults
     */
    public PrecompiledShapeJob_DelayedJobProxy(
            DelayedJobService jobService, DelayedJobContract annotation, JsonObject contractConfig) {
        this.jobService = jobService;
        this.handlerName = annotation.name();
        this.effectiveMaxAttempts = contractConfig.getInteger("maxAttempts", annotation.maxAttempts());
        this.effectiveQueue = contractConfig.getString("queue", annotation.queue());
        this.effectivePriority = contractConfig.getInteger("priority", annotation.priority());
    }

    @Override
    public Future<UUID> enqueue(String payload) {
        return doEnqueue(payload, null, null, null);
    }

    @Override
    public Future<UUID> enqueue(String payload, Instant runAt) {
        return doEnqueue(payload, runAt, null, null);
    }

    @Override
    public Future<UUID> enqueue(String payload, Duration delay) {
        return doEnqueue(payload, delay != null ? Instant.now().plus(delay) : null, null, null);
    }

    @Override
    public Future<UUID> enqueue(String payload, SqlClient tx) {
        if (tx == null) {
            return Future.failedFuture(
                    new NullPointerException("SqlClient argument must not be null on transactional enqueue overload"));
        }
        return doEnqueue(payload, null, tx, null);
    }

    @Override
    public Future<UUID> enqueue(String payload, DelayedJobOptions options) {
        return doEnqueue(payload, null, null, options);
    }

    @Override
    public Future<UUID> enqueue(String payload, DelayedJobOptions options, SqlClient tx) {
        if (tx == null) {
            return Future.failedFuture(
                    new NullPointerException("SqlClient argument must not be null on transactional enqueue overload"));
        }
        return doEnqueue(payload, null, tx, options);
    }

    private Future<UUID> doEnqueue(String payload, Instant runAt, SqlClient tx, DelayedJobOptions options) {
        String queue = effectiveQueue;
        int priority = effectivePriority;
        int maxAttempts = effectiveMaxAttempts;
        String jobId = null;
        DurableMetadata premergedMetadata = null;
        if (options != null) {
            if (options.runAt() != null) {
                runAt = options.runAt();
            }
            if (options.queue() != null) {
                queue = options.queue();
            }
            if (options.priority() != null) {
                priority = options.priority();
            }
            if (options.maxAttempts() != null) {
                maxAttempts = options.maxAttempts();
            }
            jobId = options.jobId();
            premergedMetadata = options.premergedMetadata();
        }
        var jobBuilder = DelayedJob.builder()
                .handler(handlerName)
                .payload(payload)
                .runAt(runAt)
                .queue(queue)
                .priority(priority)
                .maxAttempts(maxAttempts)
                .jobId(jobId);
        if (premergedMetadata != null) {
            DelayedJob job = jobBuilder.metadata(premergedMetadata).build();
            return tx != null ? jobService.enqueuePremerged(job, tx) : jobService.enqueuePremerged(job);
        }
        DelayedJob job = jobBuilder.build();
        return tx != null ? jobService.enqueue(job, tx) : jobService.enqueue(job);
    }
}
