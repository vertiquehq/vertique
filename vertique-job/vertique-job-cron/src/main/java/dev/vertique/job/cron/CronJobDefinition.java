// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import java.time.ZoneId;
import java.util.Map;

/**
 * Immutable definition of a registered cron job, built from a {@link CronJob} annotation by
 * {@link CronJobRegistrar} and registered with the {@link CronScheduler}.
 *
 * @param id             unique job identifier (stable across retries)
 * @param cronExpression parsed cron expression used to compute fire times
 * @param target         the target reference for this job; determines how the runtime address
 *                       is resolved per fire by the scheduler
 * @param handlerAddress event bus address for {@link CronTargetReference.EventBusTarget} jobs;
 *                       {@code null} for {@link CronTargetReference.ServiceTarget} jobs (resolved
 *                       once per fire by the scheduler, via
 *                       {@link dev.vertique.services.ServiceTargetResolver}, before the execution
 *                       record is written)
 * @param mode           execution mode controlling every-instance vs single-instance firing
 * @param timezone       timezone for evaluating the cron expression
 * @param maxAttempts    maximum number of attempts per execution
 * @param payload        optional static payload to include in the dispatch body, or {@code null}
 * @param overlapPolicy  policy for handling fires that overlap with a still-running execution
 * @param tracked        whether to persist execution records for dashboard visibility;
 *                       always {@code true} for {@link ExecutionMode#SINGLE_INSTANCE} jobs
 * @param parameters     static key-value metadata provided at definition time (e.g. from config),
 *                       available to the handler via {@link dev.vertique.job.JobDispatchContext#parameters()};
 *                       never {@code null} (use {@link Map#of()} when empty)
 * @param misfirePolicy  policy for handling fires missed while all nodes were down; defaults to
 *                       {@link MisfirePolicy#FIRE_NOW} for {@link ExecutionMode#SINGLE_INSTANCE}
 *                       jobs and {@link MisfirePolicy#SKIP} for {@link ExecutionMode#EVERY_INSTANCE}
 */
public record CronJobDefinition(
        String id,
        CronExpression cronExpression,
        CronTargetReference target,
        String handlerAddress,
        ExecutionMode mode,
        ZoneId timezone,
        int maxAttempts,
        Object payload,
        OverlapPolicy overlapPolicy,
        boolean tracked,
        Map<String, Object> parameters,
        MisfirePolicy misfirePolicy) {

    /**
     * Compact constructor that defensively copies the parameters map to ensure immutability.
     */
    public CronJobDefinition {
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }
}
