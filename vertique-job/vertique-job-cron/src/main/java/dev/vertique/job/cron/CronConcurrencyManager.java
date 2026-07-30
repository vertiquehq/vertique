// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages global concurrency slots and per-job overlap policies for cron job execution.
 *
 * <p>Two levels of control are provided:
 *
 * <ul>
 *   <li><b>Per-job overlap:</b> {@link #tryAcquireInFlight} prevents a job from running twice
 *       concurrently on the same node. When a job is already in-flight, callers invoke
 *       {@link #handleOverlap} to apply the job's {@link OverlapPolicy}: {@link OverlapPolicy#SKIP}
 *       discards the fire; {@link OverlapPolicy#QUEUE_ONE} stores one pending fire that runs as
 *       soon as the current execution ends.
 *   <li><b>Global concurrency:</b> {@link #acquireSlotAndRun} limits total concurrent jobs across
 *       all definitions to the configured {@code maxConcurrentJobs}. Excess dispatches are queued
 *       and run in FIFO order as slots are released via {@link #releaseSlot()}.
 * </ul>
 *
 * <p><b>Guard release is single-owner.</b> Each admitted fire holds two guards — its in-flight entry
 * and one concurrency slot — and exactly one site releases them: normally the dispatcher's completion
 * callback (through {@link CronScheduler}), and for a <em>synchronous</em> dispatch failure
 * {@link #acquireSlotAndRun} itself, which contains the throw (see that method). A caller that
 * releases a second time for the same fire drives {@link #activeConcurrentCount} negative, which
 * permanently over-admits jobs and is a worse failure than a stranded guard.
 */
@Slf4j
final class CronConcurrencyManager {

    private final int maxConcurrentJobs;

    /** Tracks jobs currently being executed on this instance. */
    private final Set<String> inFlightJobs = ConcurrentHashMap.newKeySet();

    /**
     * Stores the most recently skipped scheduled-at time per job when using
     * {@link OverlapPolicy#QUEUE_ONE}. Only one pending fire per job is kept.
     */
    private final Map<String, Instant> pendingFires = new ConcurrentHashMap<>();

    /** Tracks the total number of concurrently executing jobs across all definitions. */
    private final AtomicInteger activeConcurrentCount = new AtomicInteger();

    /** Queue of dispatch tasks waiting for a concurrency slot to become available. */
    private final Queue<Runnable> waitingForSlot = new ConcurrentLinkedQueue<>();

    /**
     * Creates a new concurrency manager with the given limit.
     *
     * @param maxConcurrentJobs the maximum number of jobs that can execute concurrently; must be
     *                          positive
     */
    CronConcurrencyManager(int maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
    }

    /**
     * Attempts to mark a job as in-flight. Returns {@code true} if the job was successfully added
     * (i.e., it was not already in-flight); returns {@code false} if the job is already running.
     *
     * @param jobId the cron job ID to acquire
     * @return {@code true} if acquired, {@code false} if already in-flight
     */
    boolean tryAcquireInFlight(String jobId) {
        return inFlightJobs.add(jobId);
    }

    /**
     * Removes a job from the in-flight set without releasing a concurrency slot. Used when a job
     * loses leader election (SINGLE_INSTANCE) or a slot was never acquired.
     *
     * @param jobId the cron job ID to remove
     */
    void removeInFlight(String jobId) {
        inFlightJobs.remove(jobId);
    }

    /**
     * Handles a fire that overlaps with a still-running execution, according to the job's
     * {@link OverlapPolicy}.
     *
     * <p>With {@link OverlapPolicy#SKIP} a warning is logged and the fire is discarded. With
     * {@link OverlapPolicy#QUEUE_ONE} the scheduled time is stored; if a fire was already queued,
     * it is replaced by the newer one and an additional warning is logged.
     *
     * @param job         the cron job whose fire overlapped
     * @param scheduledAt the time this fire was scheduled for
     */
    void handleOverlap(CronJobDefinition job, Instant scheduledAt) {
        if (job.overlapPolicy() == OverlapPolicy.QUEUE_ONE) {
            Instant previous = pendingFires.put(job.id(), scheduledAt);
            if (previous != null) {
                log.warn(
                        "Cron job '{}' replacing queued fire (scheduled={}) with newer fire (scheduled={}) "
                                + "— job execution is significantly behind schedule",
                        job.id(),
                        previous,
                        scheduledAt);
            } else {
                log.info("Cron job '{}' queued (QUEUE_ONE) — previous execution still in progress", job.id());
            }
        } else {
            log.warn(
                    "Cron job '{}' fire skipped — previous execution still in progress "
                            + "(scheduled={}, policy=SKIP)",
                    job.id(),
                    scheduledAt);
        }
    }

    /**
     * Acquires a global concurrency slot and runs the dispatch action immediately. If all slots
     * are taken, queues the action to run when the next slot becomes available via
     * {@link #releaseSlot()}.
     *
     * <p><b>A synchronous throw from {@code dispatchAction} releases both guards</b> — the job's
     * in-flight entry and the concurrency slot this method acquired — and is then swallowed after
     * being logged at {@code ERROR}. This is a guarantee callers previously had to provide
     * themselves; because it is provided here, a caller must <em>not</em> release either guard again
     * for the same failure. A second release would drive {@link #activeConcurrentCount} negative and
     * permanently over-admit jobs, which is a worse failure than the leak it would be trying to fix.
     * The same containment applies whether the action runs immediately or later out of the waiting
     * queue.
     *
     * <p>An {@link Error} is deliberately <em>not</em> contained: it leaves the JVM in an undefined
     * state and must propagate (the cost — a stranded guard for that one job — is accepted, and is
     * the same trade this module makes elsewhere).
     *
     * @param jobId          the cron job ID requesting the slot (used for logging)
     * @param dispatchAction the action to run once a slot is available
     */
    void acquireSlotAndRun(String jobId, Runnable dispatchAction) {
        int current = activeConcurrentCount.getAndIncrement();
        if (current < maxConcurrentJobs) {
            runContained(jobId, dispatchAction);
        } else {
            activeConcurrentCount.decrementAndGet();
            log.info("Cron job '{}' waiting for concurrency slot ({}/{})", jobId, current, maxConcurrentJobs);
            waitingForSlot.add(() -> {
                activeConcurrentCount.incrementAndGet();
                runContained(jobId, dispatchAction);
            });
        }
    }

    /**
     * Runs one dispatch action with both guards contained: a synchronous throw releases the job's
     * in-flight entry and the concurrency slot the caller just acquired, then stops propagating.
     *
     * <p>Releasing from here re-enters {@link #releaseSlot()}, which drains one waiter inline, so a
     * queued dispatch that also fails synchronously runs one stack frame deeper. The recursion is
     * bounded by the number of <em>registered</em> jobs, not by traffic: enqueueing requires the
     * in-flight guard, and a job holds that guard for as long as it sits in the queue, so a job can
     * never hold more than one waiter at a time.
     *
     * @param jobId          the cron job ID whose dispatch is running (used for logging)
     * @param dispatchAction the dispatch action to run
     */
    private void runContained(String jobId, Runnable dispatchAction) {
        try {
            dispatchAction.run();
        } catch (Exception e) {
            // Both guards are held at this point and the only path that would otherwise release them
            // is the dispatcher's completion callback — which a synchronous throw never reaches.
            log.error(
                    "Cron job '{}' dispatch threw synchronously — releasing its in-flight guard and"
                            + " concurrency slot so a later fire can run",
                    CronScheduler.forLog(jobId),
                    e);
            inFlightJobs.remove(jobId);
            releaseSlot();
        }
    }

    /**
     * Releases one global concurrency slot and immediately drains the next waiting dispatch if one
     * is queued. The drained dispatch runs through the same containment as
     * {@link #acquireSlotAndRun}, so a synchronous throw releases its guards rather than escaping
     * into this caller.
     */
    void releaseSlot() {
        activeConcurrentCount.decrementAndGet();
        Runnable next = waitingForSlot.poll();
        if (next != null) {
            next.run();
        }
    }

    /**
     * Marks a job execution as completed by removing its pending fire entry. Returns the pending
     * {@link Instant} if a QUEUE_ONE fire was queued (keeping the in-flight entry so the slot can
     * be reused without a race), or {@code null} if no pending fire exists (the caller should then
     * call {@link #removeInFlight} and {@link #releaseSlot}).
     *
     * @param jobId the cron job ID that completed
     * @return the pending fire time if a queued QUEUE_ONE fire is ready, or {@code null}
     */
    @Nullable
    Instant markCompleted(String jobId) {
        Instant pending = pendingFires.remove(jobId);
        if (pending != null) {
            // Keep in-flight — slot is reused for the queued fire to avoid a decrement+increment race
            return pending;
        }
        inFlightJobs.remove(jobId);
        return null;
    }

    /**
     * Drains the waiting dispatch queue without running any actions. Used during shutdown to
     * discard pending dispatches.
     */
    void drainQueue() {
        Runnable queued;
        while ((queued = waitingForSlot.poll()) != null) {
            log.debug("Discarded queued dispatch during shutdown");
        }
    }

    /**
     * Resets all concurrency state: in-flight set, pending fires, active count, and waiting queue.
     * Used during {@link CronScheduler#stop()} to fully clean up.
     */
    void reset() {
        inFlightJobs.clear();
        pendingFires.clear();
        activeConcurrentCount.set(0);
        waitingForSlot.clear();
    }
}
