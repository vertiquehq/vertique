// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Test-only {@link CronTickPlanner} that removes the whole-second alignment floor from scheduler
 * tests: nominal fire instants are derived through the job's real {@link CronExpression} from a
 * fixed <em>historical</em> base, and the armed timer delay is a constant {@value #DELAY_MS}ms.
 *
 * <p>Progression is nominal, not wall-clock: the first plan of a run starts from the planner's base
 * and every subsequent plan advances one expression occurrence past {@code previousScheduledAt}
 * (threaded in by {@link CronScheduler}'s scheduling recursion). The planner is stateless and
 * ignores {@code now} entirely, so a scheduler armed with it walks its expression at timer speed
 * instead of wall-clock speed.
 *
 * <p><strong>NEVER use this planner with a real persistent repository.</strong> Its nominals diverge
 * from wall clock by construction. The base is deliberately historical so that every synthetic
 * {@code firedAt} is still before real {@code now} and persistence coherence
 * ({@code next_fire_at > last_fired_at}, recomputed from real time by
 * {@code CronJobDispatcher.updateFireTimes}) continues to hold; a near-now base would let synthetic
 * nominals outrun the wall clock and invert that ordering.
 *
 * <p><strong>NEVER use this planner for a proof that compares {@code scheduledAt} against wall-clock
 * instants.</strong> Every synthetic nominal precedes wall clock, so such a comparison is vacuous
 * and the proof it is supposed to carry silently disappears. Tests with that shape stay on
 * {@link SystemCronTickPlanner}.
 *
 * <p><strong>Restart caveat:</strong> because the planner holds no state, a {@code stop()} followed
 * by {@code start()} restarts nominal progression from the planner's base and replays the same
 * nominals. A test asserting monotonically increasing nominals across a restart must construct a
 * second planner via {@link #CompressedCronTickPlanner(Instant)} with a later base.
 */
final class CompressedCronTickPlanner implements CronTickPlanner {

    /** Fixed historical origin of nominal progression when no explicit base is supplied. */
    static final Instant DEFAULT_BASE = Instant.parse("2020-01-01T00:00:00Z");

    /** Constant real timer delay armed for every planned tick, in milliseconds. */
    static final long DELAY_MS = 100L;

    /** Origin the first plan of a run (null {@code previousScheduledAt}) derives its nominal from. */
    private final Instant base;

    /** Creates a planner whose nominal progression starts at {@link #DEFAULT_BASE}. */
    CompressedCronTickPlanner() {
        this(DEFAULT_BASE);
    }

    /**
     * Creates a planner with an explicit historical base — the knob for restart scenarios, where a
     * second planner with a later base keeps nominals increasing across {@code stop()}/{@code
     * start()}.
     *
     * @param base origin the first plan of a run derives its nominal from; must stay comfortably in
     *     the past so synthetic nominals never outrun wall clock
     */
    CompressedCronTickPlanner(Instant base) {
        this.base = base;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code now} is ignored: the nominal is the job expression's next occurrence after
     * {@code previousScheduledAt}, or after this planner's base on the first plan of a run.
     */
    @Override
    public Tick plan(CronJobDefinition job, @Nullable Instant previousScheduledAt, Instant now) {
        Instant origin = previousScheduledAt != null ? previousScheduledAt : base;
        Instant scheduledAt = job.cronExpression().computeNextFireTime(origin, job.timezone());
        return new Tick(scheduledAt, DELAY_MS);
    }
}
