// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CompressedCronTickPlanner} — the test-only planner that scheduler tests run on to
 * escape the whole-second cron alignment floor.
 *
 * <p>These are pure unit tests: no Vert.x, no timers, no sleeping. They pin the three properties the
 * converted scheduler tests stand on: nominals come from the job's <em>real</em>
 * {@link CronExpression} walked forward from a fixed historical base, the armed delay is constant,
 * and the historical base keeps every synthetic nominal behind wall clock so persistence coherence
 * is never inverted.
 */
@DisplayName("CompressedCronTickPlanner")
class CompressedCronTickPlannerTest {

    /** Timezone used by every job in this class. */
    private static final ZoneId UTC = ZoneId.of("UTC");

    /** The planner's default historical origin, pinned as a literal rather than read from the class. */
    private static final Instant BASE = Instant.parse("2020-01-01T00:00:00Z");

    private final CompressedCronTickPlanner planner = new CompressedCronTickPlanner();

    /**
     * Builds an EVERY_INSTANCE job in {@link #UTC} for the given expression; only the expression and
     * timezone matter to the planner.
     *
     * @param expression the cron expression the job fires on
     * @return a job definition using {@code expression}
     */
    private static CronJobDefinition jobFiring(String expression) {
        return new CronJobDefinition(
                "compressed-job",
                new CronExpression(expression),
                new CronTargetReference.EventBusTarget("test.address"),
                "test.address",
                ExecutionMode.EVERY_INSTANCE,
                UTC,
                3,
                null,
                OverlapPolicy.SKIP,
                true,
                Map.of(),
                MisfirePolicy.SKIP);
    }

    @Test
    @DisplayName("nominals start at the historical base and advance one real expression occurrence per plan")
    void nominalsAdvanceThroughTheRealExpressionFromTheHistoricalBase() {
        assertEquals(BASE, CompressedCronTickPlanner.DEFAULT_BASE, "the default base is the fixed historical origin");

        CronJobDefinition everySecond = jobFiring("* * * * * *");
        // now is deliberately unrelated to the nominals: the planner must ignore it entirely.
        Instant now = Instant.parse("2026-08-07T10:15:30.123456789Z");

        // First plan of a run (null previous) originates at the base, through the real expression.
        CronTickPlanner.Tick first = planner.plan(everySecond, null, now);
        assertEquals(Instant.parse("2020-01-01T00:00:01Z"), first.scheduledAt());
        assertEquals(
                everySecond.cronExpression().computeNextFireTime(BASE, UTC),
                first.scheduledAt(),
                "the first nominal is the real expression's next occurrence after the base");

        // Chained plans advance exactly one occurrence per call, whole-second and strictly increasing.
        Instant previous = first.scheduledAt();
        for (int i = 2; i <= 5; i++) {
            CronTickPlanner.Tick next = planner.plan(everySecond, previous, now);
            assertEquals(
                    everySecond.cronExpression().computeNextFireTime(previous, UTC),
                    next.scheduledAt(),
                    "each chained nominal is the real expression's next occurrence after the previous one");
            assertEquals(BASE.plusSeconds(i), next.scheduledAt(), "every-second expression advances one second");
            assertEquals(0, next.scheduledAt().getNano(), "nominal fire instants are whole-second");
            assertTrue(next.scheduledAt().isAfter(previous), "nominals are strictly increasing");
            previous = next.scheduledAt();
        }

        // "One occurrence" is the expression's occurrence, not one second: a minute expression steps
        // a whole minute per plan, proving the nominals really are expression-derived.
        CronJobDefinition everyMinute = jobFiring("0 * * * * *");
        CronTickPlanner.Tick firstMinute = planner.plan(everyMinute, null, now);
        assertEquals(Instant.parse("2020-01-01T00:01:00Z"), firstMinute.scheduledAt());
        CronTickPlanner.Tick secondMinute = planner.plan(everyMinute, firstMinute.scheduledAt(), now);
        assertEquals(Instant.parse("2020-01-01T00:02:00Z"), secondMinute.scheduledAt());

        // The explicit-base constructor moves the origin — the restart knob keeps nominals increasing
        // across a stop()/start() that would otherwise replay from the default base.
        Instant laterBase = Instant.parse("2021-06-15T12:00:00Z");
        CronTickPlanner.Tick restarted = new CompressedCronTickPlanner(laterBase).plan(everySecond, null, now);
        assertEquals(laterBase.plusSeconds(1), restarted.scheduledAt());
        assertTrue(
                restarted.scheduledAt().isAfter(previous),
                "a later-base planner resumes ahead of the default-base nominals it replaces");
    }

    @Test
    @DisplayName("delayMs is a constant 100ms regardless of job, previous nominal, or now")
    void delayIsConstantCompressed() {
        CronJobDefinition everySecond = jobFiring("* * * * * *");
        CronJobDefinition hourly = jobFiring("0 0 * * * *");
        Instant now = Instant.parse("2026-08-07T10:15:30.123456789Z");

        assertEquals(100L, planner.plan(everySecond, null, now).delayMs(), "first plan of a run");
        assertEquals(
                100L,
                planner.plan(everySecond, Instant.parse("2020-03-04T05:06:07Z"), now)
                        .delayMs(),
                "chained plan");
        assertEquals(
                100L,
                planner.plan(hourly, null, Instant.EPOCH).delayMs(),
                "a sparse expression and an unrelated now change nothing");
        assertEquals(
                100L,
                new CompressedCronTickPlanner(Instant.parse("2021-06-15T12:00:00Z"))
                        .plan(hourly, Instant.parse("2021-06-15T12:00:00Z"), now)
                        .delayMs(),
                "an explicit base changes nothing");
    }

    @Test
    @DisplayName("the historical base keeps the first million chained nominals behind wall clock")
    void historicalBaseKeepsNominalsBehindWallClock() {
        CronJobDefinition everySecond = jobFiring("* * * * * *");
        Instant now = Instant.now();

        // The every-second expression advances exactly one second per plan, so the n-th chained
        // nominal is BASE + n seconds. Bounding n at 10^6 (~11.6 days) is therefore an arithmetic
        // assertion, not a million-iteration loop.
        Instant millionthNominal = BASE.plusSeconds(1_000_000L);
        assertEquals(Instant.parse("2020-01-12T13:46:40Z"), millionthNominal, "10^6 seconds past the base");
        assertTrue(
                millionthNominal.isBefore(now),
                "the millionth nominal (" + millionthNominal + ") must still precede wall clock (" + now + ")");

        // Ground the arithmetic in the planner's actual progression over a bounded chain.
        Instant previous = null;
        for (int i = 1; i <= 10; i++) {
            CronTickPlanner.Tick tick = planner.plan(everySecond, previous, now);
            assertEquals(BASE.plusSeconds(i), tick.scheduledAt(), "nominal " + i + " is BASE + " + i + "s");
            assertTrue(tick.scheduledAt().isBefore(now), "every synthetic nominal precedes wall clock");
            previous = tick.scheduledAt();
        }
    }
}
