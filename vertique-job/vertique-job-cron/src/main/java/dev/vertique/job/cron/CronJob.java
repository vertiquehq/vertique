// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method as a recurring cron job.
 *
 * <p>The annotated method must return {@code Future<Void>} and may declare a
 * {@link dev.vertique.job.JobContext} and/or {@link dev.vertique.job.JobDispatchContext} parameter
 * for access to job execution state and scheduling metadata:
 *
 * <pre>{@code
 * @CronJob(id = "daily-report", cron = "0 0 8 * * *")
 * public Future<Void> generateDailyReport(JobContext ctx) {
 *     ctx.logger().info("Generating daily report");
 *     return reportService.generate();
 * }
 * }</pre>
 *
 * <p>Place this annotation on the <em>implementation</em> method, not on the contract interface.
 * The annotation is discovered at startup by {@link CronJobRegistrar}, which scans service
 * implementations via the {@link dev.vertique.services.ServiceContractRegistry}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CronJob {

    /**
     * Unique job identifier, stable across deployments.
     * Used as the key in configuration overrides.
     *
     * @return the job ID (must not be blank)
     */
    String id();

    /**
     * Cron expression in 6-field format: {@code second minute hour day-of-month month day-of-week}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "0 0 * * * *"} - every hour</li>
     *   <li>{@code "0 0 8 * * *"} - daily at 08:00</li>
     *   <li>{@code "0/30 * * * * *"} - every 30 seconds (using step from 0)</li>
     *   <li>{@code "0 0 9 * * 1"} - every Monday at 09:00 (1=Monday)</li>
     * </ul>
     *
     * @return the cron expression (must not be blank)
     */
    String cron();

    /**
     * Execution mode controlling single-instance vs every-instance firing. Defaults to
     * {@link ExecutionMode#EVERY_INSTANCE}.
     *
     * <p>{@link ExecutionMode#SINGLE_INSTANCE} requires a {@link dev.vertique.job.JobRepository}
     * binding for INSERT ON CONFLICT leader election. Only one node per cluster will execute the
     * job per fire time.
     *
     * @return the execution mode
     */
    ExecutionMode mode() default ExecutionMode.EVERY_INSTANCE;

    /**
     * Timezone ID for evaluating the cron expression. Defaults to {@code "UTC"}.
     *
     * @return the timezone ID (must be a valid {@link java.time.ZoneId})
     */
    String timezone() default "UTC";

    /**
     * Maximum number of execution attempts before the job is moved to the dead-letter state.
     * Defaults to {@code 3}.
     *
     * @return the maximum attempt count (must be positive)
     */
    int maxAttempts() default 3;

    /**
     * Overlap policy controlling what happens when this job fires while a previous execution is
     * still in progress. Defaults to {@link OverlapPolicy#SKIP}.
     *
     * @return the overlap policy
     */
    OverlapPolicy overlapPolicy() default OverlapPolicy.SKIP;

    /**
     * Whether to persist execution records for dashboard visibility. When unset (empty string),
     * the global {@code cron.tracked} config setting is used (default {@code true}).
     *
     * <p>{@link ExecutionMode#SINGLE_INSTANCE} jobs are always tracked regardless of this setting,
     * because persistence is required for leader election.
     *
     * @return {@code "true"}, {@code "false"}, or {@code ""} (empty = use global config default)
     */
    String tracked() default "";

    /**
     * Misfire policy for handling fires missed while the application was down.
     *
     * <p>Defaults to {@link MisfirePolicy#FIRE_NOW} for {@link ExecutionMode#SINGLE_INSTANCE} jobs
     * and {@link MisfirePolicy#SKIP} for {@link ExecutionMode#EVERY_INSTANCE} jobs.
     *
     * <p>Misfire detection requires a {@link dev.vertique.job.JobRepository} binding and a
     * persisted schedule with {@code last_fired_at} data. Jobs without a repository or with
     * {@code tracked=false} always behave as {@link MisfirePolicy#SKIP} at runtime regardless of
     * this setting.
     *
     * @return the misfire policy
     */
    MisfirePolicy misfirePolicy() default MisfirePolicy.FIRE_NOW;
}
