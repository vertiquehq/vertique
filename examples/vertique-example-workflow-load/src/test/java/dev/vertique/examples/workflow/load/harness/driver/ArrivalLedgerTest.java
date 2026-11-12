// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.driver.ArrivalLedger.Outcome;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the account of arrivals: nothing is dropped, lateness measures the generator only, and refusals, failures
 * and arrivals without an answer are counted apart.
 */
class ArrivalLedgerTest {

    private static final long MS = 1_000_000L;

    private static ArrivalLedger ledger(int perSecond, int seconds) {
        return new ArrivalLedger(LoadProfile.fixed(perSecond, 0, seconds), "main", 0);
    }

    @Test
    @DisplayName("every planned arrival is accounted for: started plus not submitted, and the outcomes add up")
    void figuresAddUp() {
        ArrivalLedger ledger = ledger(10, 1);
        for (int i = 0; i < 7; i++) {
            ledger.started(i, 1, ledger.profile().offsetNanos(i));
        }
        ledger.answered(0, Outcome.ACCEPTED, 200, null, 5 * MS);
        ledger.answered(1, Outcome.ACCEPTED, 200, null, 105 * MS);
        ledger.answered(2, Outcome.REFUSED, 503, null, 205 * MS);
        ledger.answered(3, Outcome.FAILED, 500, "http-500", 305 * MS);
        ledger.answered(4, Outcome.FAILED, 0, "timeout", 405 * MS);
        ledger.closeOutstanding();

        ArrivalLedger.Counts c = ledger.summary(50).total();

        assertEquals(10, c.planned());
        assertEquals(7, c.started());
        assertEquals(3, c.notSubmitted());
        assertEquals(2, c.accepted());
        assertEquals(1, c.refused());
        assertEquals(2, c.failed());
        assertEquals(2, c.unanswered());
        assertEquals(c.started() + c.notSubmitted(), c.planned());
        assertEquals(c.accepted() + c.refused() + c.failed() + c.unanswered(), c.started());
        assertEquals(Outcome.UNANSWERED, ledger.outcomeOf(5));
        assertEquals(Outcome.NOT_SUBMITTED, ledger.outcomeOf(9));
    }

    @Test
    @DisplayName("lateness is the start minus the schedule, with the maximum, the median and the late count")
    void lateness() {
        ArrivalLedger ledger = ledger(10, 1);
        long[] late = {0, 1, 2, 3, 60, 4, 5, 6, 7, 250};
        for (int i = 0; i < 10; i++) {
            ledger.started(i, 1, ledger.profile().offsetNanos(i) + late[i] * MS);
        }

        ArrivalLedger.Summary summary = ledger.summary(50);

        assertEquals(250.0, summary.total().latenessMaxMs(), 1e-6);
        assertEquals(2, summary.lateOverThreshold());
        assertEquals(4.0, summary.latenessP50Ms(), 1e-6);
        assertEquals(60.0, ledger.latenessMs(4), 1e-6);
    }

    @Test
    @DisplayName("an unbounded number in flight shows as the in-flight maximum while lateness stays small")
    void inFlightShows() {
        ArrivalLedger ledger = ledger(100, 1);
        for (int i = 0; i < 100; i++) {
            ledger.started(i, 1, ledger.profile().offsetNanos(i));
            ledger.answered(i, Outcome.ACCEPTED, 200, null, 5_000 * MS + i);
        }

        ArrivalLedger.Summary summary = ledger.summary(50);

        assertEquals(100, summary.maxInFlight());
        assertEquals(0.0, summary.total().latenessMaxMs(), 1e-6);
        assertTrue(summary.responseP50Ms() > 4_000);
    }

    @Test
    @DisplayName("counts are kept per segment")
    void bySegment() {
        ArrivalLedger ledger = new ArrivalLedger(LoadProfile.fixed(10, 1, 1), "main", 0);
        for (int i = 0; i < 20; i++) {
            ledger.started(i, 1, ledger.profile().offsetNanos(i));
            ledger.answered(
                    i,
                    i < 10 ? Outcome.ACCEPTED : Outcome.REFUSED,
                    200,
                    null,
                    ledger.profile().offsetNanos(i) + MS);
        }

        ArrivalLedger.Summary summary = ledger.summary(50);

        assertEquals(10, summary.bySegment().get("warmup").accepted());
        assertEquals(10, summary.bySegment().get("window").refused());
        assertEquals(List.of(10, 11, 12, 13, 14, 15, 16, 17, 18, 19), ledger.refused());
    }

    @Test
    @DisplayName("the first answer counts and a later one changes nothing")
    void firstAnswerCounts() {
        ArrivalLedger ledger = ledger(1, 1);
        ledger.started(0, 1, 0);
        ledger.answered(0, Outcome.ACCEPTED, 200, null, MS);
        ledger.answered(0, Outcome.FAILED, 500, "late", 2 * MS);

        assertEquals(Outcome.ACCEPTED, ledger.outcomeOf(0));
    }

    @Test
    @DisplayName("the arrival record has one row for each planned arrival, whatever happened to it")
    void oneRowEach(@TempDir Path dir) throws IOException {
        ArrivalLedger ledger = ledger(10, 1);
        ledger.started(0, 2, 3 * MS);
        ledger.answered(0, Outcome.ACCEPTED, 200, null, 9 * MS);
        ledger.started(1, 1, 100 * MS);

        StringWriter text = new StringWriter();
        int rows;
        try (java.io.BufferedWriter out = new java.io.BufferedWriter(text)) {
            rows = ledger.writeRows(out, true);
        }
        ledger.writeSeconds(dir.resolve("s.csv"));

        assertEquals(10, rows);
        List<String> lines = text.toString().lines().toList();
        assertEquals(11, lines.size());
        assertTrue(lines.get(1).contains("ACCEPTED"), lines.get(1));
        assertTrue(lines.get(2).contains("UNANSWERED"), lines.get(2));
        assertTrue(lines.get(3).contains("NOT_SUBMITTED"), lines.get(3));
        assertTrue(Files.readAllLines(dir.resolve("s.csv")).size() >= 2);
    }

    @Test
    @DisplayName("a replay ledger adds its offset to every time so that all phases share one clock")
    void replayBase() throws IOException {
        ArrivalLedger replay = new ArrivalLedger(
                LoadProfile.of(List.of(new LoadProfile.Segment("replay", 1, 2, 1, false))), "replay", 40_000 * MS);
        replay.origin(0, 67);
        replay.started(0, 1, 1 * MS);
        replay.answered(0, Outcome.ACCEPTED, 200, null, 6 * MS);

        StringWriter text = new StringWriter();
        try (java.io.BufferedWriter out = new java.io.BufferedWriter(text)) {
            replay.writeRows(out, false);
        }

        String first = text.toString().lines().findFirst().orElseThrow();
        assertTrue(
                first.startsWith("0,replay,replay,67,1,40000.000,40001.000,1.000,40006.000,5.000,ACCEPTED,200"), first);
    }

    @Test
    @DisplayName("the time on the wire is stamped apart from the start, and schedule-to-answer is reported beside it")
    void wireAndScheduleToAnswer() throws IOException {
        ArrivalLedger ledger = ledger(10, 1);
        for (int i = 0; i < 10; i++) {
            long scheduled = ledger.profile().offsetNanos(i);
            ledger.started(i, 1, scheduled + 30 * MS);
            ledger.wired(i, scheduled + 80 * MS);
            ledger.answered(i, Outcome.ACCEPTED, 200, null, scheduled + 130 * MS);
        }

        ArrivalLedger.Summary summary = ledger.summary(50);
        StringWriter text = new StringWriter();
        try (java.io.BufferedWriter out = new java.io.BufferedWriter(text)) {
            ledger.writeRows(out, false);
        }

        assertEquals(100.0, summary.responseP50Ms(), 1e-6, "from the start of the send");
        assertEquals(50.0, summary.wireToAnswerP50Ms(), 1e-6, "from the first byte on the wire");
        assertEquals(130.0, summary.scheduleToAnswerP99Ms(), 1e-6, "from the schedule");
        assertEquals(130.0, summary.scheduleToAnswerMaxMs(), 1e-6);
        assertTrue(text.toString().lines().findFirst().orElseThrow().endsWith(",80.000"), text.toString());
        assertEquals(130.0, summary.toJson().getDouble("scheduleToAnswerMaxMs"), 1e-6);
    }

    @Test
    @DisplayName("socket exhaustion is told from a node that refused, reset or did not answer")
    void socketFailuresAreClassified() {
        assertEquals("socket-exhaustion", LoadDriver.classify(new java.net.SocketException("Too many open files")));
        assertEquals(
                "socket-exhaustion", LoadDriver.classify(new java.net.BindException("Can't assign requested address")));
        assertEquals("timeout", LoadDriver.classify(new java.net.SocketTimeoutException("Read timed out")));
        assertEquals("connect-failed", LoadDriver.classify(new java.net.ConnectException("Connection refused")));
        assertEquals("reset", LoadDriver.classify(new java.net.SocketException("Connection reset")));
    }
}
