// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import dev.vertique.job.JobRepository;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Recovers missed cron job fires at startup based on each job's {@link MisfirePolicy}.
 *
 * <p>Only {@link ExecutionMode#SINGLE_INSTANCE} jobs with a non-{@link MisfirePolicy#SKIP} policy
 * are eligible. For each eligible job the persisted {@code last_fired_at} is loaded from the
 * repository and any fires that should have occurred since then are dispatched via the provided
 * callback.
 *
 * <p>Recovery is fire-and-forget: failures are logged as warnings but do not prevent the
 * scheduler from operating normally.
 */
@Slf4j
final class CronMisfireRecovery {

    /**
     * Maximum number of missed fires executed during misfire recovery with
     * {@link MisfirePolicy#FIRE_ALL}. Caps recovery to avoid overwhelming the system after a
     * prolonged outage.
     */
    static final int MAX_MISFIRE_FIRES = CronScheduler.MAX_MISFIRE_FIRES;

    private final JobRepository repository;

    /**
     * Creates a new misfire recovery helper.
     *
     * @param repository the job repository used to read persisted schedule state
     */
    CronMisfireRecovery(JobRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs misfire recovery for all eligible jobs in the given list. For each job that has missed
     * fires since its last recorded execution, the {@code fireCallback} is invoked for each
     * missed fire time.
     *
     * <p>A job is eligible if:
     * <ul>
     *   <li>Its {@link MisfirePolicy} is not {@link MisfirePolicy#SKIP}</li>
     *   <li>It is a {@link ExecutionMode#SINGLE_INSTANCE} job (EVERY_INSTANCE fires on every
     *       node independently and does not have shared tracking state)</li>
     * </ul>
     *
     * @param jobs         the full list of registered cron job definitions
     * @param fireCallback callback invoked for each missed fire; receives the job definition and
     *                     the scheduled-at time that was missed
     */
    void recover(List<CronJobDefinition> jobs, BiConsumer<CronJobDefinition, Instant> fireCallback) {
        for (CronJobDefinition job : jobs) {
            if (job.misfirePolicy() == MisfirePolicy.SKIP) {
                continue;
            }
            if (job.mode() != ExecutionMode.SINGLE_INSTANCE) {
                // EVERY_INSTANCE fires on every node independently — no shared tracking state
                continue;
            }
            repository
                    .findSchedule(job.id())
                    .onSuccess(optSchedule -> {
                        if (optSchedule.isEmpty()) {
                            // New job — no persisted history, nothing to recover
                            return;
                        }
                        dev.vertique.job.CronJobSchedule schedule = optSchedule.get();
                        if (schedule.lastFiredAt() == null) {
                            // Never fired — nothing to recover
                            return;
                        }
                        Instant now = Instant.now();
                        if (job.misfirePolicy() == MisfirePolicy.FIRE_NOW) {
                            recoverFireNow(job, schedule.lastFiredAt(), now, fireCallback);
                        } else if (job.misfirePolicy() == MisfirePolicy.FIRE_ALL) {
                            recoverFireAll(job, schedule.lastFiredAt(), now, fireCallback);
                        }
                    })
                    .onFailure(
                            err -> log.warn("Misfire check failed for cron job '{}': {}", job.id(), err.getMessage()));
        }
    }

    /**
     * Finds the single latest missed fire and invokes the callback for it.
     *
     * @param job          the cron job to recover
     * @param lastFiredAt  the last time this job successfully fired
     * @param now          the current instant used as the recovery window end
     * @param fireCallback the callback to invoke with the missed fire time
     */
    private void recoverFireNow(
            CronJobDefinition job,
            Instant lastFiredAt,
            Instant now,
            BiConsumer<CronJobDefinition, Instant> fireCallback) {
        List<Instant> recentFires =
                job.cronExpression().computeFireTimesBetween(lastFiredAt, now, job.timezone(), MAX_MISFIRE_FIRES);
        if (recentFires.isEmpty()) {
            return;
        }
        // The last element is the most recent missed fire (list is chronological).
        // If the list was capped, continue scanning forward to find the true latest.
        Instant latest = recentFires.get(recentFires.size() - 1);
        if (recentFires.size() >= MAX_MISFIRE_FIRES) {
            List<Instant> tailFires =
                    job.cronExpression().computeFireTimesBetween(latest, now, job.timezone(), MAX_MISFIRE_FIRES);
            while (!tailFires.isEmpty()) {
                latest = tailFires.get(tailFires.size() - 1);
                if (tailFires.size() < MAX_MISFIRE_FIRES) {
                    break;
                }
                tailFires =
                        job.cronExpression().computeFireTimesBetween(latest, now, job.timezone(), MAX_MISFIRE_FIRES);
            }
        }
        log.warn("Cron job '{}' missed fire at {} — executing now (FIRE_NOW policy)", job.id(), latest);
        fireCallback.accept(job, latest);
    }

    /**
     * Finds all missed fires since {@code lastFiredAt} and invokes the callback for each.
     * Capped at {@link #MAX_MISFIRE_FIRES} to avoid overwhelming the system.
     *
     * @param job          the cron job to recover
     * @param lastFiredAt  the last time this job successfully fired
     * @param now          the current instant used as the recovery window end
     * @param fireCallback the callback to invoke for each missed fire time
     */
    private void recoverFireAll(
            CronJobDefinition job,
            Instant lastFiredAt,
            Instant now,
            BiConsumer<CronJobDefinition, Instant> fireCallback) {
        List<Instant> missedFires =
                job.cronExpression().computeFireTimesBetween(lastFiredAt, now, job.timezone(), MAX_MISFIRE_FIRES);
        if (missedFires.isEmpty()) {
            return;
        }
        log.warn(
                "Cron job '{}' has {} missed fire(s) — executing all (FIRE_ALL policy){}",
                job.id(),
                missedFires.size(),
                missedFires.size() >= MAX_MISFIRE_FIRES ? " [capped at " + MAX_MISFIRE_FIRES + "]" : "");
        for (Instant missed : missedFires) {
            fireCallback.accept(job, missed);
        }
    }
}
