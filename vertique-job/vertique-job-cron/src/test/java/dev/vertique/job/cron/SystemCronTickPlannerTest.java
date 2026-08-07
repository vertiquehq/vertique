// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link SystemCronTickPlanner} — the production tick math extracted
 * verbatim from {@code CronScheduler.scheduleNext}.
 *
 * <p>These are pure unit tests: no Vert.x, no timers, no sleeping. Every {@code now} is a fixed
 * instant, so the assertions pin exact arithmetic rather than a tolerance window.
 */
@DisplayName("SystemCronTickPlanner")
class SystemCronTickPlannerTest {

    /** A whole-second instant used as the reference boundary for the delay vectors. */
    private static final Instant BOUNDARY = Instant.parse("2026-08-07T10:15:30Z");

    private static final SystemCronTickPlanner planner = new SystemCronTickPlanner();

    @Test
    @DisplayName("scheduledAt is the cron expression's next fire time after now — whole-second, strictly after")
    void scheduledAtMatchesCronExpressionFromNow() {
        CronJobDefinition job = CompressedCronTickPlannerTest.jobFiring("* * * * * *");
        Instant now = Instant.parse("2026-08-07T10:15:30.123456789Z");

        CronTickPlanner.Tick tick = planner.plan(job, null, now);

        assertEquals(
                job.cronExpression().computeNextFireTime(now, job.timezone()),
                tick.scheduledAt(),
                "planner must delegate the nominal instant to the job's own CronExpression");
        assertEquals(Instant.parse("2026-08-07T10:15:31Z"), tick.scheduledAt());
        assertEquals(0, tick.scheduledAt().getNano(), "nominal fire instants are whole-second");
        assertTrue(tick.scheduledAt().isAfter(now), "next fire time is strictly after now");
    }

    @Test
    @DisplayName("delayMs is the clamped positive millisecond distance between now and scheduledAt")
    void delayIsClampedPositiveMillisBetweenNowAndScheduledAt() {
        CronJobDefinition job = CompressedCronTickPlannerTest.jobFiring("* * * * * *");

        // 250ms past a whole second: the next occurrence is the following whole second, 750ms out.
        Instant justAfterBoundary = BOUNDARY.plusMillis(250);
        CronTickPlanner.Tick afterBoundary = planner.plan(job, null, justAfterBoundary);
        assertEquals(BOUNDARY.plusSeconds(1), afterBoundary.scheduledAt());
        assertEquals(750L, afterBoundary.delayMs(), "250ms after a boundary must arm 750ms out");

        // 250ms before a whole second: the boundary itself is the next occurrence, 250ms out.
        Instant justBeforeBoundary = BOUNDARY.minusMillis(250);
        CronTickPlanner.Tick beforeBoundary = planner.plan(job, null, justBeforeBoundary);
        assertEquals(BOUNDARY, beforeBoundary.scheduledAt());
        assertEquals(250L, beforeBoundary.delayMs(), "250ms before a boundary must arm 250ms out");

        // 1ns before a whole second: the sub-millisecond distance truncates to 0 and is clamped to 1,
        // so a timer is always armed rather than being scheduled with a zero/negative delay.
        Instant oneNanoBeforeBoundary = BOUNDARY.minusNanos(1);
        CronTickPlanner.Tick clamped = planner.plan(job, null, oneNanoBeforeBoundary);
        assertEquals(BOUNDARY, clamped.scheduledAt());
        assertTrue(clamped.delayMs() >= 1, "delay must be clamped to at least 1ms, was " + clamped.delayMs());
    }

    @Test
    @DisplayName("previousScheduledAt is ignored — production progression derives from wall clock")
    void previousScheduledAtIsIgnored() {
        CronJobDefinition job = CompressedCronTickPlannerTest.jobFiring("* * * * * *");
        Instant now = Instant.parse("2026-08-07T10:15:30.400Z");

        CronTickPlanner.Tick withoutPrevious = planner.plan(job, null, now);
        CronTickPlanner.Tick withPrevious = planner.plan(job, Instant.parse("2020-01-01T00:00:00Z"), now);

        assertEquals(
                withoutPrevious,
                withPrevious,
                "the production planner must derive the tick from now alone, never from the previous nominal");
    }
}
