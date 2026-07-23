// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.sse.job;

import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory job store that drives simulated progress via a Vert.x periodic timer.
 *
 * <p>Each job starts in {@link JobStatus#PENDING} and advances through {@link JobStatus#RUNNING}
 * to {@link JobStatus#DONE} (or {@link JobStatus#FAILED}) over a configurable number of ticks.
 * Subscribers receive events synchronously when they subscribe (replay of buffered history) and
 * then live as the timer fires on the Vert.x event loop.
 *
 * <p>The periodic timer is started on the first subscriber and cancelled when the job reaches a
 * terminal state or when the last subscriber unsubscribes before the job finishes.
 */
@Singleton
@Slf4j
public class JobService {

    // --- Inner types ---

    /**
     * Handle returned from {@link #subscribe} that allows the caller to stop receiving events.
     */
    @FunctionalInterface
    public interface Subscription {

        /**
         * Removes this listener from the job's fan-out list. If this was the last listener and the
         * job has not yet reached a terminal state, the periodic timer is also cancelled.
         */
        void unsubscribe();
    }

    /** Per-job mutable state; all mutations must be performed under {@code synchronized(this)}. */
    private static final class JobState {

        final AtomicInteger seq = new AtomicInteger(0);
        final List<JobProgressEvent> history = new ArrayList<>();
        final List<Consumer<JobProgressEvent>> listeners = new ArrayList<>();

        @Nullable
        Long timerId;

        boolean terminal = false;
    }

    // --- Fields ---

    private final Vertx vertx;
    private final long stepIntervalMs;
    private final int stepCount;
    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    // --- Constructor ---

    /**
     * Creates a {@link JobService} driven by the given Vert.x instance.
     *
     * @param vertx  the Vert.x instance used to schedule the periodic progress timer
     * @param config the job pipeline configuration supplying step interval and step count
     */
    @Inject
    public JobService(Vertx vertx, JobConfig config) {
        this.vertx = vertx;
        this.stepIntervalMs = config.stepIntervalMs();
        this.stepCount = config.stepCount();
    }

    // --- Public API ---

    /**
     * Creates a new job and seeds its history with a {@link JobStatus#PENDING} event.
     *
     * @return the unique job identifier
     */
    public String createJob() {
        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState();
        JobProgressEvent pending = new JobProgressEvent(jobId, 0, JobStatus.PENDING, 0, "Job created");
        state.history.add(pending);
        jobs.put(jobId, state);
        log.debug("Created job {}", jobId);
        return jobId;
    }

    /**
     * Returns the most recent progress event for the given job, or empty if the job does not exist.
     *
     * @param jobId the job identifier
     * @return an {@link Optional} containing the latest event, or empty if the job is unknown
     */
    public Optional<JobProgressEvent> current(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            return Optional.empty();
        }
        synchronized (state) {
            if (state.history.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(state.history.get(state.history.size() - 1));
        }
    }

    /**
     * Subscribes to progress events for the given job.
     *
     * <p>Events already in the job history with {@code seq > lastEventId} are replayed
     * synchronously before the listener is attached to the live fan-out list. If the job is not
     * yet running, the periodic timer is started on the first subscription.
     *
     * @param jobId           the job identifier
     * @param lastEventId     the value of the {@code Last-Event-ID} header (may be {@code null});
     *                        events with {@code seq <= lastEventId} are skipped in replay
     * @param listener        callback invoked for each event, including replayed and live events
     * @return a {@link Subscription} that can be used to stop receiving events
     */
    public Subscription subscribe(String jobId, @Nullable String lastEventId, Consumer<JobProgressEvent> listener) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            return () -> {};
        }

        int seqFloor = parseLastEventId(lastEventId);

        List<JobProgressEvent> toReplay;
        boolean shouldStart;

        boolean isTerminal;
        synchronized (state) {
            isTerminal = state.terminal;
            List<JobProgressEvent> filtered =
                    state.history.stream().filter(e -> e.seq() > seqFloor).toList();
            // When reconnecting past the terminal event, surface the terminal event anyway so
            // the subscriber can complete its stream; otherwise the channel would hang open.
            if (filtered.isEmpty() && isTerminal && !state.history.isEmpty()) {
                filtered = List.of(state.history.get(state.history.size() - 1));
            }
            toReplay = filtered;
            // Only register the listener for live fan-out if the job is still active. For terminal
            // jobs the replay IS the entire contract — keeping the listener would leak it because
            // the caller's channel.onClose unsubscribe can race with synchronous completion.
            if (!isTerminal) {
                state.listeners.add(listener);
            }
            shouldStart = !isTerminal && state.timerId == null && state.listeners.size() == 1;
            if (shouldStart) {
                state.timerId = -1L;
            }
        }

        for (JobProgressEvent event : toReplay) {
            listener.accept(event);
        }

        if (shouldStart) {
            long timerId = vertx.setPeriodic(stepIntervalMs, id -> tick(jobId, state));
            synchronized (state) {
                state.timerId = timerId;
            }
        }

        if (isTerminal) {
            return () -> {};
        }
        return () -> removeListener(jobId, state, listener);
    }

    /**
     * Emits a {@link JobStatus#FAILED} terminal event for the given job, cancels its timer, and
     * notifies all active subscribers.
     *
     * @param jobId   the job identifier
     * @param message human-readable description of the failure
     */
    public void failJob(String jobId, String message) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            return;
        }
        List<Consumer<JobProgressEvent>> snapshot;
        JobProgressEvent event;

        synchronized (state) {
            if (state.terminal) {
                return;
            }
            int currentSeq = state.seq.incrementAndGet();
            event = new JobProgressEvent(jobId, currentSeq, JobStatus.FAILED, 0, message);
            state.history.add(event);
            state.terminal = true;
            snapshot = List.copyOf(state.listeners);
            cancelTimer(state);
        }

        for (Consumer<JobProgressEvent> listener : snapshot) {
            listener.accept(event);
        }
    }

    /**
     * Returns the number of listeners currently registered for the given job.
     *
     * <p>Visible for testing only — not part of the public API contract. Callers that want to
     * observe subscriber activity should use {@link #subscribe} and count events delivered to
     * their listener instead.
     *
     * @param jobId the job identifier
     * @return current listener count, or {@code 0} if the job is unknown
     */
    public int listenerCount(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.listeners.size();
        }
    }

    /**
     * Returns {@code true} if the job's periodic timer is currently running.
     *
     * <p>Visible for testing only — not part of the public API contract.
     *
     * @param jobId the job identifier
     * @return {@code true} if a timer is active for this job
     */
    public boolean isTimerRunning(String jobId) {
        JobState state = jobs.get(jobId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.timerId != null && state.timerId != -1L;
        }
    }

    // --- Private helpers ---

    /**
     * Advances the job one step, appending a progress event to history and fanning out to listeners.
     *
     * @param jobId the job identifier
     * @param state the mutable job state
     */
    private void tick(String jobId, JobState state) {
        List<Consumer<JobProgressEvent>> snapshot;
        JobProgressEvent event;

        synchronized (state) {
            if (state.terminal) {
                cancelTimer(state);
                return;
            }
            int currentSeq = state.seq.incrementAndGet();
            boolean done = currentSeq >= stepCount;
            int percent = done ? 100 : currentSeq * 100 / stepCount;
            JobStatus status = done ? JobStatus.DONE : JobStatus.RUNNING;
            event = new JobProgressEvent(jobId, currentSeq, status, percent, null);
            state.history.add(event);
            if (done) {
                state.terminal = true;
                cancelTimer(state);
            }
            snapshot = List.copyOf(state.listeners);
        }

        for (Consumer<JobProgressEvent> listener : snapshot) {
            listener.accept(event);
        }
    }

    /**
     * Removes a listener from the job's fan-out list. When the last listener is removed and the
     * job has not yet reached a terminal state, the periodic timer is cancelled. History is
     * preserved so that later reconnects (including {@code Last-Event-ID} replay) still work.
     *
     * @param jobId    the job identifier
     * @param state    the mutable job state
     * @param listener the listener to remove
     */
    private void removeListener(String jobId, JobState state, Consumer<JobProgressEvent> listener) {
        synchronized (state) {
            state.listeners.remove(listener);
            if (state.listeners.isEmpty() && !state.terminal) {
                log.debug("Last subscriber unsubscribed before terminal state for job {}; cancelling timer", jobId);
                cancelTimer(state);
            }
        }
    }

    /**
     * Cancels the periodic timer if one is running. Must be called under the state's monitor.
     *
     * @param state the mutable job state
     */
    private void cancelTimer(JobState state) {
        if (state.timerId != null && state.timerId != -1L) {
            vertx.cancelTimer(state.timerId);
        }
        state.timerId = null;
    }

    /**
     * Parses the {@code Last-Event-ID} header value into an integer sequence floor.
     *
     * <p>Returns {@code -1} if the value is {@code null}, blank, or not a valid integer, which
     * causes all buffered events to be replayed.
     *
     * @param lastEventId the raw header value; may be {@code null}
     * @return the parsed sequence number, or {@code -1} if parsing fails
     */
    private static int parseLastEventId(@Nullable String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(lastEventId.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
