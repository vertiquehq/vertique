// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultJobLogger}.
 */
@DisplayName("DefaultJobLogger")
class DefaultJobLoggerTest {

    /** {@code U+0000} — the character PostgreSQL rejects outright in a {@code TEXT} column. */
    private static final char NUL = (char) 0x00;

    /** {@code U+0007} — a C0 control character that is merely noise in persisted output. */
    private static final char BELL = (char) 0x07;

    /** {@code U+001B} — the ANSI escape introducer, stripped so stored logs cannot drive a terminal. */
    private static final char ESCAPE = (char) 0x1B;

    private DefaultJobLogger logger;

    @BeforeEach
    void setUp() {
        logger = new DefaultJobLogger();
    }

    @Test
    @DisplayName("starts empty")
    void startsEmpty() {
        assertTrue(logger.entries().isEmpty());
    }

    @Test
    @DisplayName("info adds INFO entry")
    void infoAddsInfoEntry() {
        logger.info("test message");
        assertEquals(1, logger.entries().size());
        assertEquals("INFO", logger.entries().get(0).level());
        assertEquals("test message", logger.entries().get(0).message());
    }

    @Test
    @DisplayName("warn adds WARN entry")
    void warnAddsWarnEntry() {
        logger.warn("warning");
        assertEquals("WARN", logger.entries().get(0).level());
    }

    @Test
    @DisplayName("error adds ERROR entry")
    void errorAddsErrorEntry() {
        logger.error("error occurred");
        assertEquals("ERROR", logger.entries().get(0).level());
    }

    @Test
    @DisplayName("entries preserves insertion order")
    void entriesPreservesOrder() {
        logger.info("first");
        logger.warn("second");
        logger.error("third");
        assertEquals(3, logger.entries().size());
        assertEquals("INFO", logger.entries().get(0).level());
        assertEquals("WARN", logger.entries().get(1).level());
        assertEquals("ERROR", logger.entries().get(2).level());
    }

    @Test
    @DisplayName("each entry has a non-null loggedAt timestamp")
    void entriesHaveTimestamps() {
        logger.info("timestamped");
        assertTrue(logger.entries().get(0).loggedAt() != null);
    }

    // --- Message normalization ---

    @Test
    @DisplayName("NUL and other C0 control characters are stripped from the message")
    void stripsNulAndControlCharacters() {
        // Remotely reachable: a job payload field logged verbatim by a handler. PostgreSQL rejects
        // NUL in a TEXT column, so one such entry would fail every later batch write for the
        // execution — the batch is nacked, re-claimed, and fails again forever.
        logger.info("payload" + NUL + "injected" + BELL + "and" + ESCAPE + "escaped");

        String message = logger.entries().get(0).message();

        assertEquals(-1, message.indexOf(NUL), "a NUL byte makes the TEXT column write fail permanently");
        assertEquals("payloadinjectedandescaped", message);
    }

    @Test
    @DisplayName("a null message is replaced by a non-null placeholder")
    void nullMessageBecomesPlaceholder() {
        logger.warn(null);

        LogEntry entry = logger.entries().get(0);
        assertNotNull(entry.message(), "job_logs.message is NOT NULL — a null must never reach the write");
        assertEquals("<null>", entry.message());
    }

    @Test
    @DisplayName("an oversized message is truncated with a marker naming the dropped characters")
    void oversizedMessageIsTruncated() {
        String oversized = "x".repeat(20_000);

        logger.error(oversized);

        String message = logger.entries().get(0).message();
        assertTrue(message.length() < oversized.length(), "an oversized message must be truncated");
        assertTrue(message.startsWith("x".repeat(8192)), "the retained prefix must be the head of the message");
        assertTrue(message.endsWith("…[truncated 11808 chars]"), "actual tail: " + message.substring(8100));
    }

    @Test
    @DisplayName("newlines, carriage returns and tabs survive normalization")
    void newlinesAndTabsSurvive() {
        logger.info("line one\nline two\r\n\tindented");

        assertEquals("line one\nline two\r\n\tindented", logger.entries().get(0).message());
    }

    // --- Claim / ack drain protocol ---

    @Test
    @DisplayName("claim caps the batch and leaves the remainder buffered in order")
    void claimCapsBatchSize() {
        for (int i = 0; i < 1200; i++) {
            logger.info("entry-" + i);
        }

        List<LogEntry> first = logger.claim();

        assertEquals(500, first.size(), "an uncapped claim turns an outage into an ever-larger failing batch");
        assertEquals("entry-0", first.get(0).message());
        assertEquals("entry-499", first.get(499).message());
        assertEquals(700, logger.entries().size(), "entries beyond the cap must stay buffered");

        logger.ack();
        List<LogEntry> second = logger.claim();

        assertEquals(500, second.size());
        assertEquals("entry-500", second.get(0).message(), "the next claim must resume where the last one stopped");
        assertEquals("entry-999", second.get(499).message());
        assertEquals(200, logger.entries().size());
    }

    @Test
    @DisplayName("claim returns the buffered entries in insertion order")
    void claimReturnsBufferedEntriesInOrder() {
        logger.info("a");
        logger.warn("b");
        logger.error("c");

        List<LogEntry> batch = logger.claim();

        assertEquals(3, batch.size());
        assertEquals("a", batch.get(0).message());
        assertEquals("b", batch.get(1).message());
        assertEquals("c", batch.get(2).message());
    }

    @Test
    @DisplayName("ack discards the claimed batch so it is never re-claimed")
    void ackDiscardsClaimedBatch() {
        logger.info("first");
        logger.info("second");

        List<LogEntry> first = logger.claim();
        assertEquals(2, first.size(), "first claim must hand over both buffered entries");

        logger.ack();

        // A cursor-only implementation that never removes acked entries would re-deliver them here.
        assertTrue(logger.claim().isEmpty(), "second claim after ack must be empty");

        // Append after the ack and claim again: the batch must be gone, not merely masked by the
        // single-flight guard. Without this, a no-op ack() still passes — the guard alone would
        // make the claim above empty.
        logger.info("third");
        List<LogEntry> afterAck = logger.claim();
        assertEquals(1, afterAck.size(), "acked entries must not be re-delivered alongside new ones");
        assertEquals("third", afterAck.get(0).message());
    }

    @Test
    @DisplayName("nack returns the failed batch ahead of newer entries")
    void nackRetainsBatchAheadOfNewerEntries() {
        logger.info("old");

        List<LogEntry> batch = logger.claim();
        assertEquals(1, batch.size(), "first claim must hand over the buffered entry");

        logger.nack(batch);
        logger.info("new");

        List<LogEntry> retried = logger.claim();

        assertEquals(2, retried.size());
        assertEquals("old", retried.get(0).message(), "nacked entry must be re-delivered first");
        assertEquals("new", retried.get(1).message());
    }

    @Test
    @DisplayName("claim is single-flight until the outstanding batch is acked")
    void claimIsSingleFlight() {
        logger.info("in flight");

        List<LogEntry> inFlight = logger.claim();
        assertEquals(1, inFlight.size(), "first claim must hand over the buffered entry");

        logger.info("appended during flush");
        assertTrue(logger.claim().isEmpty(), "claim must be empty while a batch is in flight");

        logger.ack();

        List<LogEntry> afterAck = logger.claim();
        assertEquals(1, afterAck.size(), "ack must clear the in-flight state");
        assertEquals("appended during flush", afterAck.get(0).message());
    }

    @Test
    @DisplayName("entries returns a snapshot, not a live view of the buffer")
    void entriesReturnsSnapshotNotLiveView() {
        logger.info("captured");
        List<LogEntry> snapshot = logger.entries();
        assertEquals(1, snapshot.size());

        logger.info("appended after snapshot");

        assertEquals(1, snapshot.size(), "snapshot must not observe entries appended later");
    }

    @Test
    @DisplayName("concurrent appends are never lost across a claim/ack drain")
    void concurrentAppendsAreNotLost() throws InterruptedException {
        int threads = 4;
        int perThread = 250;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        logger.info("t" + threadIndex + "-" + i);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "appender threads must finish");
        } finally {
            pool.shutdownNow();
        }

        int drained = 0;
        int rounds = 0;
        int maxRounds = threads * perThread + 10;
        while (rounds < maxRounds) {
            rounds++;
            List<LogEntry> batch = logger.claim();
            if (batch.isEmpty()) {
                break;
            }
            drained += batch.size();
            logger.ack();
        }

        assertTrue(rounds < maxRounds, "drain must terminate on an empty claim");
        assertEquals(threads * perThread, drained, "every appended entry must be drained exactly once");
    }
}
