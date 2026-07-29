// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe in-memory implementation of {@link JobLogger}.
 *
 * <p>Entries are buffered in an {@link ArrayDeque} guarded by a private monitor. Cross-thread
 * safety is required rather than optional: the periodic flusher drains the buffer from a different
 * Vert.x context than the handler that appends to it, because handler service verticles may run on
 * WORKER threads.
 *
 * <p>A {@link java.util.concurrent.CopyOnWriteArrayList} is deliberately <em>not</em> used here:
 *
 * <ul>
 *   <li>it copies the whole backing array on every append, which is O(N&sup2;) for a chatty job;
 *   <li>a read cursor over it can never remove anything, so the buffer would grow unboundedly for
 *       the life of the execution;
 *   <li>its {@code subList} throws {@link java.util.ConcurrentModificationException} when the
 *       backing array changes mid-read, which makes it unusable for a batch drain.
 * </ul>
 *
 * <p>The deque plus removal on {@link #ack()} avoids all three.
 *
 * <p>Instances are created per execution by {@link DefaultJobContext}.
 */
public class DefaultJobLogger implements JobLogger {

    /** Guards {@link #buffer} and {@link #inFlightBatch}. */
    private final Object lock = new Object();

    private final Deque<LogEntry> buffer = new ArrayDeque<>();

    /**
     * The batch handed out by the last successful {@link #claim()}, or {@code null} when no flush is
     * in flight. A non-{@code null} value is the in-flight marker: it is set only by a claim that
     * drained at least one entry, and cleared by {@link #ack()} or {@link #nack(List)}.
     */
    private List<LogEntry> inFlightBatch;

    @Override
    public void info(String message) {
        append(new LogEntry("INFO", message, Instant.now()));
    }

    @Override
    public void warn(String message) {
        append(new LogEntry("WARN", message, Instant.now()));
    }

    @Override
    public void error(String message) {
        append(new LogEntry("ERROR", message, Instant.now()));
    }

    /**
     * Appends one entry to the tail of the buffer under the lock.
     *
     * @param entry the entry to buffer
     */
    private void append(LogEntry entry) {
        synchronized (lock) {
            buffer.addLast(entry);
        }
    }

    /**
     * Returns a defensive copy of the entries <em>still buffered</em>. Entries already persisted by
     * a {@link #claim()}/{@link #ack()} cycle are no longer included, so this is not a running
     * transcript of the whole execution.
     *
     * @return an immutable snapshot copy of the buffered entries, in insertion order
     */
    @Override
    public List<LogEntry> entries() {
        synchronized (lock) {
            return List.copyOf(buffer);
        }
    }

    // --- Claim / ack drain protocol ---

    /**
     * Claims the currently buffered entries as a single batch for flushing, marking a flush as
     * in flight.
     *
     * <p>The protocol is single-flight: a claim returns an empty list when nothing is buffered
     * <em>or</em> when a previously claimed batch has not yet been acknowledged via
     * {@link #ack()} or returned via {@link #nack(List)}. Claimed entries are removed from the
     * buffer so that a subsequent claim never re-delivers them; delivery becomes at-least-once
     * only through {@link #nack(List)}. An empty claim leaves the in-flight state untouched, so a
     * later claim can still succeed.
     *
     * @return the claimed batch in insertion order, or an empty list when nothing can be claimed
     */
    List<LogEntry> claim() {
        synchronized (lock) {
            if (inFlightBatch != null || buffer.isEmpty()) {
                return List.of();
            }
            List<LogEntry> batch = List.copyOf(buffer);
            buffer.clear();
            inFlightBatch = batch;
            return batch;
        }
    }

    /**
     * Acknowledges that the in-flight batch was persisted successfully, discarding it and
     * clearing the in-flight state so the next {@link #claim()} can proceed.
     */
    void ack() {
        synchronized (lock) {
            inFlightBatch = null;
        }
    }

    /**
     * Returns a failed batch to the front of the buffer, ahead of any entries appended while the
     * flush was in flight, and clears the in-flight state so the next {@link #claim()} can
     * proceed. Entries are never dropped on a known write failure. The batch's own order is
     * preserved, so the next claim yields the nacked entries first and the newer ones after them.
     *
     * <p>A {@code null} or empty batch only clears the in-flight state.
     *
     * @param batch the previously claimed batch that failed to persist; may be {@code null}
     */
    void nack(List<LogEntry> batch) {
        synchronized (lock) {
            if (batch != null) {
                // Push back-to-front so the batch keeps its internal order at the head of the deque.
                for (int i = batch.size() - 1; i >= 0; i--) {
                    buffer.addFirst(batch.get(i));
                }
            }
            inFlightBatch = null;
        }
    }
}
