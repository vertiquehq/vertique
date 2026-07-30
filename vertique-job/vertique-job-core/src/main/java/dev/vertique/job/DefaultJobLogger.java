// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * <p>The deque removes the first and third hazards outright. It bounds the second only while a
 * {@link JobLogFlusher} is actually draining this buffer: in the no-op modes documented on
 * {@link JobLogFlusher} (no repository bound, untracked cron fire, foreign {@link JobContext})
 * nothing ever claims, and during a sustained write outage {@link #nack(List)} returns every failed
 * batch to the head — so the buffer is bounded in practice, not guaranteed.
 *
 * <p>Every message is normalized at this producer boundary (see {@link #normalize(String)}) so that
 * nothing a handler can log makes the persisting write fail deterministically. This class is the
 * complete producer set for anything a {@link JobLogFlusher} can claim — a foreign
 * {@link JobContext} yields a no-op flusher — so normalizing here covers the whole reachable path
 * without constraining the public {@link LogEntry} record.
 *
 * <p>Instances are created per execution by {@link DefaultJobContext}.
 */
public class DefaultJobLogger implements JobLogger {

    /**
     * Maximum number of entries one {@link #claim()} hands over. Bounding the batch keeps a single
     * pipelined INSERT proportionate and stops a write outage from re-sending an ever-larger failing
     * batch on every flush tick.
     */
    private static final int MAX_CLAIM_BATCH = 500;

    /**
     * Maximum number of characters retained from a log message before {@link #normalize(String)}
     * truncates it.
     */
    private static final int MAX_MESSAGE_CHARS = 8192;

    /** Stand-in stored for a {@code null} message, because {@code job_logs.message} is NOT NULL. */
    private static final String NULL_MESSAGE = "<null>";

    /** Guards {@link #buffer} and {@link #flushInFlight}. */
    private final Object lock = new Object();

    private final Deque<LogEntry> buffer = new ArrayDeque<>();

    /**
     * Whether a batch handed out by {@link #claim()} is still outstanding. Set only by a claim that
     * drained at least one entry, and cleared by {@link #ack()} or {@link #nack(List)}. The claimed
     * entries themselves are held by the flusher, not here — retaining a second reference would pin
     * them for the whole in-flight window without ever being read.
     */
    private boolean flushInFlight;

    @Override
    public void info(String message) {
        append(new LogEntry("INFO", normalize(message), Instant.now()));
    }

    @Override
    public void warn(String message) {
        append(new LogEntry("WARN", normalize(message), Instant.now()));
    }

    @Override
    public void error(String message) {
        append(new LogEntry("ERROR", normalize(message), Instant.now()));
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

    // --- Message normalization ---

    /**
     * Makes a caller-supplied message unconditionally persistable, so that no single entry can wedge
     * the flush loop for the whole execution.
     *
     * <p>A message that the write rejects is not merely lost: {@link JobLogFlusher} nacks the batch,
     * the next claim re-includes the same entry, and the write fails again — no entry for that
     * execution ever persists and the buffer grows without bound. The three hazards handled here are
     * therefore each load-bearing:
     *
     * <ul>
     *   <li>{@code null} becomes {@value #NULL_MESSAGE}, because {@code job_logs.message} is
     *       {@code TEXT NOT NULL};
     *   <li>C0 control characters are stripped, because PostgreSQL rejects {@code U+0000} in a
     *       {@code TEXT} value and a job payload field logged verbatim can carry one. {@code \n},
     *       {@code \r} and {@code \t} are kept — multi-line output stays readable;
     *   <li>anything beyond {@value #MAX_MESSAGE_CHARS} characters is truncated with a marker
     *       naming how many characters were dropped, never splitting a surrogate pair.
     * </ul>
     *
     * @param message the caller-supplied message; may be {@code null}
     * @return a non-{@code null} message safe to write to {@code job_logs.message}
     */
    private static String normalize(String message) {
        if (message == null) {
            return NULL_MESSAGE;
        }
        String cleaned = stripControlCharacters(message);
        if (cleaned.length() <= MAX_MESSAGE_CHARS) {
            return cleaned;
        }
        // Back off one char rather than cut a surrogate pair in half: a lone surrogate is not
        // encodable as UTF-8 and would corrupt the stored text.
        int end = Character.isHighSurrogate(cleaned.charAt(MAX_MESSAGE_CHARS - 1))
                ? MAX_MESSAGE_CHARS - 1
                : MAX_MESSAGE_CHARS;
        return cleaned.substring(0, end) + "…[truncated " + (cleaned.length() - end) + " chars]";
    }

    /**
     * Removes C0 control characters other than tab, newline and carriage return, allocating only
     * when the message actually contains one.
     *
     * @param message the message to scan; never {@code null}
     * @return the message with strippable control characters removed
     */
    private static String stripControlCharacters(String message) {
        StringBuilder stripped = null;
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            boolean strippable = c < 0x20 && c != '\n' && c != '\r' && c != '\t';
            if (strippable) {
                if (stripped == null) {
                    stripped = new StringBuilder(message.length()).append(message, 0, i);
                }
            } else if (stripped != null) {
                stripped.append(c);
            }
        }
        return stripped == null ? message : stripped.toString();
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
     * <p>At most {@value #MAX_CLAIM_BATCH} entries are drained from the head; the remainder stays
     * buffered for the next claim. Without that cap the whole buffer becomes one pipelined batch
     * INSERT, and during a write outage every tick re-sends an ever-larger batch that fails again.
     *
     * @return the claimed batch in insertion order, at most {@value #MAX_CLAIM_BATCH} entries, or an
     *     empty list when nothing can be claimed
     */
    List<LogEntry> claim() {
        synchronized (lock) {
            if (flushInFlight || buffer.isEmpty()) {
                return List.of();
            }
            int size = Math.min(buffer.size(), MAX_CLAIM_BATCH);
            List<LogEntry> batch = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                batch.add(buffer.pollFirst());
            }
            flushInFlight = true;
            return List.copyOf(batch);
        }
    }

    /**
     * Acknowledges that the in-flight batch was persisted successfully, discarding it and
     * clearing the in-flight state so the next {@link #claim()} can proceed.
     */
    void ack() {
        synchronized (lock) {
            flushInFlight = false;
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
            flushInFlight = false;
        }
    }
}
