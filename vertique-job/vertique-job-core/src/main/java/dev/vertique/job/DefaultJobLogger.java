// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory implementation of {@link JobLogger}.
 *
 * <p>Entries are appended to a {@link CopyOnWriteArrayList} which provides safe concurrent
 * writes without explicit synchronisation and stable snapshot reads.
 *
 * <p>Instances are created per execution by {@link DefaultJobContext}.
 */
public class DefaultJobLogger implements JobLogger {

    private final CopyOnWriteArrayList<LogEntry> buffer = new CopyOnWriteArrayList<>();

    @Override
    public void info(String message) {
        buffer.add(new LogEntry("INFO", message, Instant.now()));
    }

    @Override
    public void warn(String message) {
        buffer.add(new LogEntry("WARN", message, Instant.now()));
    }

    @Override
    public void error(String message) {
        buffer.add(new LogEntry("ERROR", message, Instant.now()));
    }

    @Override
    public List<LogEntry> entries() {
        return Collections.unmodifiableList(buffer);
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
     * only through {@link #nack(List)}.
     *
     * @return the claimed batch in insertion order, or an empty list when nothing can be claimed
     */
    List<LogEntry> claim() {
        return List.of();
    }

    /**
     * Acknowledges that the in-flight batch was persisted successfully, discarding it and
     * clearing the in-flight state so the next {@link #claim()} can proceed.
     */
    void ack() {
        // Intentionally not implemented — see the claim/ack drain slice.
    }

    /**
     * Returns a failed batch to the front of the buffer, ahead of any entries appended while the
     * flush was in flight, and clears the in-flight state so the next {@link #claim()} can
     * proceed. Entries are never dropped on a known write failure.
     *
     * @param batch the previously claimed batch that failed to persist
     */
    void nack(List<LogEntry> batch) {
        // Intentionally not implemented — see the claim/ack drain slice.
    }
}
