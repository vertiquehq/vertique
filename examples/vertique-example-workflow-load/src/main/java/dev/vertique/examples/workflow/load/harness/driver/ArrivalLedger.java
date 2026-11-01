// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import dev.vertique.examples.workflow.load.harness.metrics.Quantiles;
import io.vertx.core.json.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The account of every arrival a driver planned: when it was scheduled, when it started, how it was answered.
 *
 * <p>Nothing is dropped. The ledger holds one slot for every planned arrival, so an arrival that never started shows
 * as not submitted and one that never got an answer shows as unanswered; both are counted, and the figures always add
 * up: {@code started + notSubmitted = planned} and {@code accepted + refused + failed + unanswered = started}.
 *
 * <p>Lateness is the time between an arrival's scheduled time and the moment the driver began to send it. It
 * measures the generator and nothing else: how long the node took to answer is the response time, a result of the
 * system. An unbounded number of starts in flight therefore shows as response time and in-flight count, while a
 * generator that cannot keep its schedule shows as lateness or as arrivals that never started.
 *
 * <p>All times are nanoseconds from the start of the run.
 */
public final class ArrivalLedger {

    /** How an arrival ended. */
    public enum Outcome {
        /** The driver never started the arrival. */
        NOT_SUBMITTED,
        /** The node recorded the start, or had recorded it before. */
        ACCEPTED,
        /** The node's intake limit refused the start. */
        REFUSED,
        /** The start failed in any other way. */
        FAILED,
        /** The start was sent and no answer had come when the driver stopped waiting. */
        UNANSWERED
    }

    /**
     * The counts of one stretch of the schedule.
     *
     * @param planned the arrivals scheduled
     * @param started the arrivals the driver began to send
     * @param notSubmitted the arrivals that never started
     * @param accepted the arrivals the node accepted
     * @param refused the arrivals the intake limit refused
     * @param failed the arrivals that failed otherwise
     * @param unanswered the arrivals without an answer
     * @param latenessMaxMs the greatest lateness in milliseconds, zero when nothing started
     */
    public record Counts(
            int planned,
            int started,
            int notSubmitted,
            int accepted,
            int refused,
            int failed,
            int unanswered,
            double latenessMaxMs) {

        JsonObject toJson() {
            return new JsonObject()
                    .put("planned", planned)
                    .put("started", started)
                    .put("notSubmitted", notSubmitted)
                    .put("accepted", accepted)
                    .put("refused", refused)
                    .put("failed", failed)
                    .put("unanswered", unanswered)
                    .put("latenessMaxMs", latenessMaxMs);
        }
    }

    /**
     * The totals of a ledger.
     *
     * @param total the counts of all arrivals
     * @param bySegment the counts per segment label
     * @param latenessP50Ms the median lateness in milliseconds
     * @param latenessP99Ms the 99th percentile of lateness in milliseconds
     * @param lateOverThreshold the arrivals that started later than the threshold
     * @param lateThresholdMs the threshold in milliseconds
     * @param responseP50Ms the median response time of answered arrivals in milliseconds
     * @param responseP99Ms the 99th percentile of the response time in milliseconds
     * @param responseMaxMs the greatest response time in milliseconds
     * @param maxInFlight the most arrivals started and not yet answered at one moment
     * @param wireToAnswerP50Ms the median of the time from the request on the wire to the answer, in milliseconds
     * @param wireToAnswerP99Ms the 99th percentile of that time
     * @param scheduleToAnswerP50Ms the median of the time from the schedule to the answer, in milliseconds
     * @param scheduleToAnswerP99Ms the 99th percentile of that time
     * @param scheduleToAnswerMaxMs the greatest of that time
     * @param failureDetails the failed arrivals by reason
     */
    public record Summary(
            Counts total,
            Map<String, Counts> bySegment,
            double latenessP50Ms,
            double latenessP99Ms,
            int lateOverThreshold,
            double lateThresholdMs,
            double responseP50Ms,
            double responseP99Ms,
            double responseMaxMs,
            int maxInFlight,
            double wireToAnswerP50Ms,
            double wireToAnswerP99Ms,
            double scheduleToAnswerP50Ms,
            double scheduleToAnswerP99Ms,
            double scheduleToAnswerMaxMs,
            Map<String, Integer> failureDetails) {

        /**
         * Renders the summary.
         *
         * @return the summary as JSON
         */
        public JsonObject toJson() {
            JsonObject segments = new JsonObject();
            bySegment.forEach((label, counts) -> segments.put(label, counts.toJson()));
            JsonObject details = new JsonObject();
            failureDetails.forEach(details::put);
            return total.toJson()
                    .put("bySegment", segments)
                    .put("latenessP50Ms", finite(latenessP50Ms))
                    .put("latenessP99Ms", finite(latenessP99Ms))
                    .put("lateOverThreshold", lateOverThreshold)
                    .put("lateThresholdMs", lateThresholdMs)
                    .put("responseP50Ms", finite(responseP50Ms))
                    .put("responseP99Ms", finite(responseP99Ms))
                    .put("responseMaxMs", finite(responseMaxMs))
                    .put("maxInFlight", maxInFlight)
                    .put("acknowledgementLatencyNote", "responseP*Ms run from the start of the send, connect included")
                    .put("wireToAnswerP50Ms", finite(wireToAnswerP50Ms))
                    .put("wireToAnswerP99Ms", finite(wireToAnswerP99Ms))
                    .put("scheduleToAnswerP50Ms", finite(scheduleToAnswerP50Ms))
                    .put("scheduleToAnswerP99Ms", finite(scheduleToAnswerP99Ms))
                    .put("scheduleToAnswerMaxMs", finite(scheduleToAnswerMaxMs))
                    .put("failureDetails", details);
        }
    }

    private static Double finite(double value) {
        return Double.isNaN(value) ? null : value;
    }

    private final LoadProfile profile;
    private final String phase;
    private final long baseNs;
    private final int planned;
    private final long[] startedNs;
    private final long[] wiredNs;
    private final long[] answeredNs;
    private final byte[] outcome;
    private final short[] status;
    private final String[] detail;
    private final int[] origin;
    private final short[] node;

    /**
     * Creates the ledger of a schedule.
     *
     * @param profile the schedule
     * @param phase the label of the phase, {@code main} or {@code replay}
     * @param baseNs where the schedule starts, in nanoseconds from the start of the run; the rows add it to every
     *     time so that all phases share one clock
     */
    public ArrivalLedger(LoadProfile profile, String phase, long baseNs) {
        this.profile = profile;
        this.phase = phase;
        this.baseNs = baseNs;
        this.planned = profile.total();
        this.startedNs = new long[planned];
        this.wiredNs = new long[planned];
        this.answeredNs = new long[planned];
        this.outcome = new byte[planned];
        this.status = new short[planned];
        this.detail = new String[planned];
        this.origin = new int[planned];
        this.node = new short[planned];
        Arrays.fill(startedNs, -1);
        Arrays.fill(wiredNs, -1);
        Arrays.fill(answeredNs, -1);
        Arrays.fill(origin, -1);
        Arrays.fill(node, (short) -1);
    }

    /**
     * Returns the schedule.
     *
     * @return the profile
     */
    public LoadProfile profile() {
        return profile;
    }

    /**
     * Returns the number of planned arrivals.
     *
     * @return the count
     */
    public int planned() {
        return planned;
    }

    /**
     * Records that the driver began to send an arrival.
     *
     * @param index the arrival
     * @param targetNode the node it goes to, from 1
     * @param nanosSinceStart the moment it began, in nanoseconds from the start of the run
     */
    public synchronized void started(int index, int targetNode, long nanosSinceStart) {
        if (startedNs[index] < 0) {
            startedNs[index] = nanosSinceStart;
            node[index] = (short) targetNode;
        }
    }

    /**
     * Records the moment the first byte of an arrival's request is about to be written, after any connect. The gap
     * between {@link #started} and this moment is the driver's connection and socket cost, apart from the node's answer.
     *
     * @param index the arrival
     * @param nanosSinceStart the moment, in nanoseconds from the start of the run
     */
    public synchronized void wired(int index, long nanosSinceStart) {
        if (wiredNs[index] < 0) {
            wiredNs[index] = nanosSinceStart;
        }
    }

    /**
     * Records how an arrival ended; the first answer counts.
     *
     * @param index the arrival
     * @param result how it ended
     * @param httpStatus the HTTP status, or 0 when there was none
     * @param reason the reason of a failure, or {@code null}
     * @param nanosSinceStart the moment it ended, in nanoseconds from the start of the run
     */
    public synchronized void answered(int index, Outcome result, int httpStatus, String reason, long nanosSinceStart) {
        if (outcome[index] == Outcome.NOT_SUBMITTED.ordinal() && answeredNs[index] < 0) {
            outcome[index] = (byte) result.ordinal();
            status[index] = (short) httpStatus;
            detail[index] = reason;
            answeredNs[index] = nanosSinceStart;
        }
    }

    /**
     * Records which earlier arrival a replay repeats.
     *
     * @param index the replay arrival
     * @param originIndex the index of the refused arrival in the main ledger
     */
    public synchronized void origin(int index, int originIndex) {
        origin[index] = originIndex;
    }

    /**
     * Returns the number of started arrivals that have no answer yet.
     *
     * @return the count
     */
    public synchronized int outstanding() {
        int count = 0;
        for (int i = 0; i < planned; i++) {
            if (startedNs[i] >= 0 && answeredNs[i] < 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns the outcome of an arrival.
     *
     * @param index the arrival
     * @return how it ended; {@link Outcome#UNANSWERED} while it has started and has no answer
     */
    public synchronized Outcome outcomeOf(int index) {
        return effective(index);
    }

    /**
     * Returns the arrivals that were refused.
     *
     * @return the indexes in order
     */
    public synchronized List<Integer> refused() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < planned; i++) {
            if (outcome[i] == Outcome.REFUSED.ordinal()) {
                list.add(i);
            }
        }
        return list;
    }

    /** Marks every arrival that started and never got an answer as unanswered. */
    public synchronized void closeOutstanding() {
        for (int i = 0; i < planned; i++) {
            if (startedNs[i] >= 0 && answeredNs[i] < 0 && outcome[i] == Outcome.NOT_SUBMITTED.ordinal()) {
                outcome[i] = (byte) Outcome.UNANSWERED.ordinal();
            }
        }
    }

    /**
     * Returns the lateness of an arrival.
     *
     * @param index the arrival
     * @return milliseconds between its schedule and its start, or {@code NaN} when it never started
     */
    public synchronized double latenessMs(int index) {
        return startedNs[index] < 0 ? Double.NaN : (startedNs[index] - profile.offsetNanos(index)) / 1e6;
    }

    /**
     * Summarizes the ledger.
     *
     * @param lateThresholdMs an arrival that started later than this many milliseconds counts as late
     * @return the summary
     */
    public synchronized Summary summary(double lateThresholdMs) {
        double[] lateness = new double[planned];
        int n = 0;
        int late = 0;
        List<Double> responses = new ArrayList<>();
        List<Double> wireToAnswer = new ArrayList<>();
        List<Double> scheduleToAnswer = new ArrayList<>();
        Map<String, Integer> details = new LinkedHashMap<>();
        Map<String, int[]> perSegment = new LinkedHashMap<>();
        Map<String, double[]> perSegmentLate = new LinkedHashMap<>();
        for (int s = 0; s < profile.segmentCount(); s++) {
            perSegment.put(profile.segment(s).label(), new int[7]);
            perSegmentLate.put(profile.segment(s).label(), new double[1]);
        }
        int[] all = new int[7];
        double latenessMax = 0;
        for (int i = 0; i < planned; i++) {
            String label = profile.segment(profile.segmentOf(i)).label();
            int[] seg = perSegment.get(label);
            seg[0]++;
            all[0]++;
            if (startedNs[i] < 0) {
                seg[2]++;
                all[2]++;
                continue;
            }
            seg[1]++;
            all[1]++;
            double l = (startedNs[i] - profile.offsetNanos(i)) / 1e6;
            lateness[n++] = l;
            latenessMax = Math.max(latenessMax, l);
            perSegmentLate.get(label)[0] = Math.max(perSegmentLate.get(label)[0], l);
            if (l > lateThresholdMs) {
                late++;
            }
            int slot;
            Outcome o = effective(i);
            if (answeredNs[i] >= 0) {
                responses.add((answeredNs[i] - startedNs[i]) / 1e6);
                scheduleToAnswer.add((answeredNs[i] - profile.offsetNanos(i)) / 1e6);
                if (wiredNs[i] >= 0) {
                    wireToAnswer.add((answeredNs[i] - wiredNs[i]) / 1e6);
                }
            }
            switch (o) {
                case ACCEPTED -> slot = 3;
                case REFUSED -> slot = 4;
                case FAILED -> {
                    slot = 5;
                    details.merge(detail[i] == null ? "unspecified" : detail[i], 1, Integer::sum);
                }
                default -> slot = 6;
            }
            seg[slot]++;
            all[slot]++;
        }
        double[] startedLateness = Arrays.copyOf(lateness, n);
        double[] resp = responses.stream().mapToDouble(Double::doubleValue).toArray();
        double[] wire = wireToAnswer.stream().mapToDouble(Double::doubleValue).toArray();
        double[] sched =
                scheduleToAnswer.stream().mapToDouble(Double::doubleValue).toArray();
        Map<String, Counts> bySegment = new LinkedHashMap<>();
        perSegment.forEach(
                (label, c) -> bySegment.put(label, counts(c, perSegmentLate.get(label)[0])));
        return new Summary(
                counts(all, latenessMax),
                bySegment,
                Quantiles.of(startedLateness, 0.5),
                Quantiles.of(startedLateness, 0.99),
                late,
                lateThresholdMs,
                Quantiles.of(resp, 0.5),
                Quantiles.of(resp, 0.99),
                resp.length == 0 ? Double.NaN : Arrays.stream(resp).max().getAsDouble(),
                maxInFlight(),
                Quantiles.of(wire, 0.5),
                Quantiles.of(wire, 0.99),
                Quantiles.of(sched, 0.5),
                Quantiles.of(sched, 0.99),
                sched.length == 0 ? Double.NaN : Arrays.stream(sched).max().getAsDouble(),
                details);
    }

    private Outcome effective(int index) {
        Outcome recorded = Outcome.values()[outcome[index]];
        return recorded == Outcome.NOT_SUBMITTED && startedNs[index] >= 0 ? Outcome.UNANSWERED : recorded;
    }

    private static Counts counts(int[] c, double latenessMax) {
        return new Counts(c[0], c[1], c[2], c[3], c[4], c[5], c[6], latenessMax);
    }

    private int maxInFlight() {
        long[] events = new long[planned * 2];
        int m = 0;
        for (int i = 0; i < planned; i++) {
            if (startedNs[i] >= 0) {
                events[m++] = startedNs[i] << 1 | 1;
                if (answeredNs[i] >= 0) {
                    events[m++] = answeredNs[i] << 1;
                }
            }
        }
        Arrays.sort(events, 0, m);
        int current = 0;
        int max = 0;
        // At equal times an end (low bit 0) sorts before a start (low bit 1), so a request that ends as another
        // begins does not count twice.
        for (int i = 0; i < m; i++) {
            current += (events[i] & 1) == 1 ? 1 : -1;
            max = Math.max(max, current);
        }
        return max;
    }

    /**
     * Writes one row per second of the run.
     *
     * @param file the CSV file
     * @throws IOException if the file cannot be written
     */
    public synchronized void writeSeconds(Path file) throws IOException {
        int seconds = (int) Math.ceil(profile.endSec()) + 1;
        long lastNs = 0;
        for (int i = 0; i < planned; i++) {
            lastNs = Math.max(lastNs, Math.max(startedNs[i], answeredNs[i]));
        }
        seconds = Math.max(seconds, (int) (lastNs / 1_000_000_000L) + 1);
        int[] scheduled = new int[seconds];
        int[] started = new int[seconds];
        int[] accepted = new int[seconds];
        int[] refused = new int[seconds];
        int[] failed = new int[seconds];
        double[] maxLate = new double[seconds];
        int[] inFlight = new int[seconds];
        for (int i = 0; i < planned; i++) {
            int sch = (int) (profile.offsetNanos(i) / 1_000_000_000L);
            scheduled[sch]++;
            if (startedNs[i] >= 0) {
                started[(int) (startedNs[i] / 1_000_000_000L)]++;
                maxLate[sch] = Math.max(maxLate[sch], (startedNs[i] - profile.offsetNanos(i)) / 1e6);
            }
            if (answeredNs[i] >= 0) {
                int sec = (int) (answeredNs[i] / 1_000_000_000L);
                switch (effective(i)) {
                    case ACCEPTED -> accepted[sec]++;
                    case REFUSED -> refused[sec]++;
                    case FAILED -> failed[sec]++;
                    default -> {
                        // no answer to count
                    }
                }
            }
        }
        // In flight at each whole second: started at or before it and not answered by it.
        for (int i = 0; i < planned; i++) {
            if (startedNs[i] < 0) {
                continue;
            }
            int from = (int) (startedNs[i] / 1_000_000_000L) + 1;
            int to = answeredNs[i] < 0 ? seconds - 1 : (int) (answeredNs[i] / 1_000_000_000L);
            for (int s = from; s <= to && s < seconds; s++) {
                inFlight[s]++;
            }
        }
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("sec,scheduled,started,accepted,refused,failed,max_late_ms,in_flight\n");
            for (int s = 0; s < seconds; s++) {
                out.write(String.format(
                        Locale.ROOT,
                        "%d,%d,%d,%d,%d,%d,%.3f,%d%n",
                        s,
                        scheduled[s],
                        started[s],
                        accepted[s],
                        refused[s],
                        failed[s],
                        maxLate[s],
                        inFlight[s]));
            }
        }
    }

    /**
     * Appends a row for every arrival to a CSV file; every planned arrival gets exactly one row.
     *
     * @param out the writer
     * @param withHeader whether to write the header row first
     * @return the number of rows written
     * @throws IOException if writing fails
     */
    public synchronized int writeRows(BufferedWriter out, boolean withHeader) throws IOException {
        if (withHeader) {
            out.write("idx,phase,segment,origin,node,scheduled_ms,started_ms,late_ms,answered_ms,response_ms,"
                    + "outcome,status,detail,wired_ms\n");
        }
        for (int i = 0; i < planned; i++) {
            long scheduledNs = profile.offsetNanos(i);
            boolean began = startedNs[i] >= 0;
            boolean answered = answeredNs[i] >= 0;
            out.write(String.format(
                    Locale.ROOT,
                    "%d,%s,%s,%s,%s,%.3f,%s,%s,%s,%s,%s,%d,%s,%s%n",
                    i,
                    phase,
                    profile.segment(profile.segmentOf(i)).label(),
                    origin[i] < 0 ? "" : Integer.toString(origin[i]),
                    node[i] < 0 ? "" : Short.toString(node[i]),
                    (scheduledNs + baseNs) / 1e6,
                    began ? String.format(Locale.ROOT, "%.3f", (startedNs[i] + baseNs) / 1e6) : "",
                    began ? String.format(Locale.ROOT, "%.3f", (startedNs[i] - scheduledNs) / 1e6) : "",
                    answered ? String.format(Locale.ROOT, "%.3f", (answeredNs[i] + baseNs) / 1e6) : "",
                    answered && began ? String.format(Locale.ROOT, "%.3f", (answeredNs[i] - startedNs[i]) / 1e6) : "",
                    effective(i),
                    status[i],
                    detail[i] == null ? "" : detail[i].replace(',', ';'),
                    wiredNs[i] >= 0 ? String.format(Locale.ROOT, "%.3f", (wiredNs[i] + baseNs) / 1e6) : ""));
        }
        return planned;
    }
}
