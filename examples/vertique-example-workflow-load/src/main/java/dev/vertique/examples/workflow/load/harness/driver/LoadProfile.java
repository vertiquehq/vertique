// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * The open-loop schedule of a run: consecutive segments, each offering new starts at a fixed rate.
 *
 * <p>Arrival {@code k} of the run has a scheduled time that depends only on the profile, never on how the system
 * answered earlier arrivals. A segment with burst {@code b} offers its arrivals in groups of {@code b} at one
 * instant, at the same average rate. A segment with rate zero offers nothing and only lets time pass.
 */
public final class LoadProfile {

    /**
     * One stretch of the schedule.
     *
     * @param label the name of the stretch, carried by every arrival in it
     * @param durationSec the length in seconds
     * @param ratePerSec the new starts per second; zero for a pause
     * @param burst the arrivals offered at one instant
     * @param window whether the stretch belongs to the measurement window
     */
    public record Segment(String label, double durationSec, double ratePerSec, int burst, boolean window) {

        /** Validates the segment. */
        public Segment {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("A segment needs a label");
            }
            if (!(durationSec > 0) || Double.isInfinite(durationSec)) {
                throw new IllegalArgumentException("Segment " + label + " needs a positive duration");
            }
            if (!(ratePerSec >= 0) || Double.isInfinite(ratePerSec)) {
                throw new IllegalArgumentException("Segment " + label + " needs a rate of zero or more");
            }
            if (burst < 1) {
                throw new IllegalArgumentException("Segment " + label + " needs a burst of at least 1");
            }
        }

        /**
         * Returns the number of arrivals of the segment.
         *
         * @return the rate times the duration, rounded
         */
        public long arrivals() {
            return Math.round(ratePerSec * durationSec);
        }
    }

    private final List<Segment> segments;
    private final double[] startSec;
    private final long[] firstIndex;
    private final long total;

    private LoadProfile(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        this.startSec = new double[segments.size() + 1];
        this.firstIndex = new long[segments.size() + 1];
        for (int i = 0; i < segments.size(); i++) {
            startSec[i + 1] = startSec[i] + segments.get(i).durationSec();
            firstIndex[i + 1] = firstIndex[i] + segments.get(i).arrivals();
        }
        this.total = firstIndex[segments.size()];
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A profile offers at most " + Integer.MAX_VALUE + " arrivals");
        }
    }

    /**
     * Creates a profile.
     *
     * @param segments the segments in order; may be empty
     * @return the profile
     */
    public static LoadProfile of(List<Segment> segments) {
        return new LoadProfile(segments);
    }

    /**
     * Creates the profile of a fixed load: a warm-up that is not measured, then the measured window.
     *
     * @param ratePerSec the new starts per second
     * @param warmupSec the unmeasured seconds first
     * @param windowSec the measured seconds
     * @return the profile
     */
    public static LoadProfile fixed(double ratePerSec, double warmupSec, double windowSec) {
        List<Segment> list = new ArrayList<>();
        if (warmupSec > 0) {
            list.add(new Segment("warmup", warmupSec, ratePerSec, 1, false));
        }
        list.add(new Segment("window", windowSec, ratePerSec, 1, true));
        return of(list);
    }

    /**
     * Creates the profile of a staircase: equal steps with a rate that grows by a fixed amount.
     *
     * @param startRatePerSec the rate of the first step
     * @param stepRatePerSec the growth per step
     * @param steps the number of steps
     * @param stepSec the length of a step in seconds
     * @return the profile; the steps are called {@code step-1}, {@code step-2} and so on
     */
    public static LoadProfile staircase(double startRatePerSec, double stepRatePerSec, int steps, double stepSec) {
        List<Segment> list = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            list.add(new Segment("step-" + (i + 1), stepSec, startRatePerSec + i * stepRatePerSec, 1, true));
        }
        return of(list);
    }

    /**
     * Returns the number of arrivals the profile offers.
     *
     * @return the count
     */
    public int total() {
        return (int) total;
    }

    /**
     * Returns the number of segments.
     *
     * @return the count
     */
    public int segmentCount() {
        return segments.size();
    }

    /**
     * Returns a segment.
     *
     * @param index the position of the segment
     * @return the segment
     */
    public Segment segment(int index) {
        return segments.get(index);
    }

    /**
     * Returns the segments.
     *
     * @return the segments in order
     */
    public List<Segment> segments() {
        return segments;
    }

    /**
     * Returns the start of a segment.
     *
     * @param index the position of the segment
     * @return the offset from the start of the run in seconds
     */
    public double segmentStartSec(int index) {
        return startSec[index];
    }

    /**
     * Returns the end of a segment.
     *
     * @param index the position of the segment
     * @return the offset from the start of the run in seconds
     */
    public double segmentEndSec(int index) {
        return startSec[index + 1];
    }

    /**
     * Returns the position of the segment an arrival belongs to.
     *
     * @param arrival the index of the arrival
     * @return the position of its segment
     */
    public int segmentOf(int arrival) {
        if (arrival < 0 || arrival >= total) {
            throw new IndexOutOfBoundsException("No arrival " + arrival + " in a profile of " + total);
        }
        int low = 0;
        int high = segments.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (firstIndex[mid] <= arrival) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        // The largest segment that starts at or before the arrival is never empty: an empty one shares its first index
        // with its successor, which would qualify as well.
        return low;
    }

    /**
     * Returns the scheduled time of an arrival.
     *
     * @param arrival the index of the arrival
     * @return the offset from the start of the run in nanoseconds
     */
    public long offsetNanos(int arrival) {
        int s = segmentOf(arrival);
        Segment segment = segments.get(s);
        long inSegment = arrival - firstIndex[s];
        double group = (double) (inSegment / segment.burst());
        double seconds = startSec[s] + group * segment.burst() / segment.ratePerSec();
        return Math.round(seconds * 1e9);
    }

    /**
     * Returns the end of the schedule.
     *
     * @return the offset from the start of the run in seconds
     */
    public double endSec() {
        return startSec[segments.size()];
    }

    /**
     * Returns the start of the measurement window.
     *
     * @return the offset in seconds of the first window segment, or the end of the schedule when none is a window
     */
    public double windowStartSec() {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).window()) {
                return startSec[i];
            }
        }
        return endSec();
    }

    /**
     * Returns the end of the measurement window.
     *
     * @return the offset in seconds of the end of the last window segment, or the end of the schedule when none is
     *     a window
     */
    public double windowEndSec() {
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).window()) {
                return startSec[i + 1];
            }
        }
        return endSec();
    }

    /**
     * Returns the profile as JSON.
     *
     * @return an array with one object per segment
     */
    public JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (Segment segment : segments) {
            array.add(new JsonObject()
                    .put("label", segment.label())
                    .put("durationSec", segment.durationSec())
                    .put("ratePerSec", segment.ratePerSec())
                    .put("burst", segment.burst())
                    .put("window", segment.window()));
        }
        return array;
    }
}
