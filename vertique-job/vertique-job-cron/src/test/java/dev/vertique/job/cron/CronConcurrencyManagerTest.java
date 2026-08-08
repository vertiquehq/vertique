// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit proofs for {@link CronConcurrencyManager} — the seam that decides, exactly, what an
 * overlapping cron tick does.
 *
 * <p>These proofs live here rather than in {@link CronSchedulerTest} because <b>handler invocations
 * are not cron ticks</b>: under {@link OverlapPolicy#SKIP} a suppressed tick never reaches the
 * handler at all, so counting handler hits cannot prove how many scheduling decisions were made;
 * under {@link OverlapPolicy#QUEUE_ONE} an invocation count cannot distinguish the queued fire from
 * the next natural fire. The manager's own return values <em>can</em> distinguish both, so overlap
 * exactness is proven here and {@code CronSchedulerTest} keeps only thin wiring proofs.
 *
 * <p>Every test is sleep-free and needs no {@code Vertx}: the manager's admission/overlap/completion
 * API is synchronous and state-based, driven entirely by direct calls.
 */
@DisplayName("CronConcurrencyManager")
class CronConcurrencyManagerTest {

    private static final String JOB_ID = "overlap-job";

    /** Second tick — arrives while the first execution is still running. */
    private static final Instant TICK_2 = Instant.parse("2026-08-07T10:00:02Z");
    /** Third tick — also arrives while the first execution is still running. */
    private static final Instant TICK_3 = Instant.parse("2026-08-07T10:00:03Z");

    /** Builds a minimal EVERY_INSTANCE job definition carrying the given overlap policy. */
    private static CronJobDefinition job(OverlapPolicy overlapPolicy) {
        return new CronJobDefinition(
                JOB_ID,
                new CronExpression("* * * * * *"),
                new CronTargetReference.EventBusTarget("test.overlap.address"),
                "test.overlap.address",
                ExecutionMode.EVERY_INSTANCE,
                ZoneId.of("UTC"),
                3,
                null,
                overlapPolicy,
                false,
                Map.of(),
                MisfirePolicy.SKIP);
    }

    @Test
    @DisplayName("SKIP suppresses an overlapping fire and queues nothing — a later tick fires again")
    void skipPolicySuppressesFireWhileExecutionInFlight() {
        CronConcurrencyManager manager = new CronConcurrencyManager(4);

        // Given: the first tick is admitted and its execution is in flight.
        assertTrue(manager.tryAcquireInFlight(JOB_ID), "first tick must be admitted");

        // When: a second tick arrives while that execution is still running.
        assertFalse(
                manager.tryAcquireInFlight(JOB_ID),
                "overlapping tick must be refused admission while the job is in flight");
        manager.handleOverlap(job(OverlapPolicy.SKIP), TICK_2);

        // Then: nothing was queued — completion finds no pending fire and releases the guard.
        // This is the observable difference from QUEUE_ONE: the fire was discarded, not deferred.
        assertNull(manager.markCompleted(JOB_ID), "SKIP must not queue the overlapping fire");

        // And: a tick arriving after the release fires again.
        assertTrue(manager.tryAcquireInFlight(JOB_ID), "a tick after completion must be admitted again");
    }

    @Test
    @DisplayName("QUEUE_ONE queues exactly one overlapping fire and releases it on completion")
    void queueOnePolicyQueuesExactlyOneAndReleasesItOnCompletion() {
        CronConcurrencyManager manager = new CronConcurrencyManager(4);
        CronJobDefinition queueOneJob = job(OverlapPolicy.QUEUE_ONE);

        // Given: the first tick is admitted and its execution is in flight.
        assertTrue(manager.tryAcquireInFlight(JOB_ID), "first tick must be admitted");

        // When: two further ticks arrive while that execution is still running.
        assertFalse(manager.tryAcquireInFlight(JOB_ID), "second tick must be refused admission");
        manager.handleOverlap(queueOneJob, TICK_2);
        assertFalse(manager.tryAcquireInFlight(JOB_ID), "third tick must be refused admission");
        manager.handleOverlap(queueOneJob, TICK_3);

        // Then: completion releases exactly one queued fire, and it is the most recent overlapping
        // tick — the earlier queued tick was replaced, not accumulated.
        Instant queued = manager.markCompleted(JOB_ID);
        assertEquals(TICK_3, queued, "the most recent overlapping tick must be the one queued");

        // And: the queued fire is distinguishable from a natural next fire — the in-flight guard is
        // deliberately retained so the queued dispatch reuses the slot, whereas a natural fire would
        // have to acquire the guard itself.
        assertFalse(
                manager.tryAcquireInFlight(JOB_ID), "in-flight guard must be retained for the queued fire to reuse");

        // And: only ONE fire was queued — the queued fire's own completion finds nothing pending.
        assertNull(manager.markCompleted(JOB_ID), "at most one fire may be queued under QUEUE_ONE");
        assertTrue(manager.tryAcquireInFlight(JOB_ID), "guard must be released once the queued fire completes");
    }

    @Test
    @DisplayName("a synchronously failing dispatch releases both guards so a later tick still fires")
    void overlapAccountingReleasesGuardOnFailure() {
        // maxConcurrentJobs = 1 makes the concurrency slot observable: if the slot were not
        // released, the follow-up dispatch below would be queued instead of run.
        CronConcurrencyManager manager = new CronConcurrencyManager(1);
        AtomicBoolean firstRan = new AtomicBoolean();

        // Given: an admitted fire whose dispatch throws synchronously.
        assertTrue(manager.tryAcquireInFlight(JOB_ID), "first tick must be admitted");
        assertDoesNotThrow(
                () -> manager.acquireSlotAndRun(JOB_ID, () -> {
                    firstRan.set(true);
                    throw new IllegalStateException("dispatch boom");
                }),
                "a synchronous dispatch failure must be contained, not propagated");
        assertTrue(firstRan.get(), "the dispatch action must actually have run");

        // Then: the in-flight guard was released — a subsequent tick is admitted.
        assertTrue(manager.tryAcquireInFlight(JOB_ID), "failed execution must release the in-flight guard");

        // And: the concurrency slot was released too — the next dispatch runs immediately rather
        // than waiting behind the failed one.
        AtomicBoolean secondRan = new AtomicBoolean();
        manager.acquireSlotAndRun(JOB_ID, () -> secondRan.set(true));
        assertTrue(secondRan.get(), "failed execution must release its concurrency slot");
    }
}
