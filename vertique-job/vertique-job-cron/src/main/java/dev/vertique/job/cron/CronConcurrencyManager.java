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
     * @param jobId          the cron job ID requesting the slot (used for logging)
     * @param dispatchAction the action to run once a slot is available
     */
    void acquireSlotAndRun(String jobId, Runnable dispatchAction) {
        int current = activeConcurrentCount.getAndIncrement();
        if (current < maxConcurrentJobs) {
            dispatchAction.run();
        } else {
            activeConcurrentCount.decrementAndGet();
            log.info("Cron job '{}' waiting for concurrency slot ({}/{})", jobId, current, maxConcurrentJobs);
            waitingForSlot.add(() -> {
                activeConcurrentCount.incrementAndGet();
                dispatchAction.run();
            });
        }
    }

    /**
     * Releases one global concurrency slot and immediately drains the next waiting dispatch if one
     * is queued.
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
