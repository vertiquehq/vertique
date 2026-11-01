// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.examples.workflow.load.harness.driver.LoadProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * The load a run offers.
 *
 * <ul>
 *   <li>{@code none} offers nothing; the nodes only run, and {@code windowSec} says how long the quiet window lasts.</li>
 *   <li>{@code fixed} offers one rate: a warm-up that is not measured, then the measured window.</li>
 *   <li>{@code profile} offers the listed segments, each with its own rate, burst and window flag.</li>
 *   <li>{@code staircase} offers steps of equal length with a rate that grows by a fixed amount.</li>
 * </ul>
 *
 * <p>Only the inputs that belong to the chosen kind are accepted; an input of another kind is rejected as unknown.
 * Rates are new starts per second. Each start records three commands, so the load in command-equivalents per second
 * is three times the rate.
 *
 * @param kind the kind of load
 * @param profile the schedule
 * @param stepMeasureSkipSec for a staircase, the seconds at the start of each step that are not measured
 */
public record LoadSpec(Kind kind, LoadProfile profile, double stepMeasureSkipSec) {

    /** The kinds of load. */
    public enum Kind {
        /** No arrivals. */
        NONE,
        /** One rate, a warm-up and a window. */
        FIXED,
        /** An explicit list of segments. */
        PROFILE,
        /** Steps of growing rate. */
        STAIRCASE
    }

    /** The command-equivalents of one start: the start, the callback signal and the timer firing. */
    public static final int COMMANDS_PER_INSTANCE = 3;

    /**
     * Reads the load from a specification section.
     *
     * @param reader the reader of the {@code load} object
     * @return the load
     * @throws ConfigurationException if an input is missing, out of bounds or does not belong to the kind
     */
    public static LoadSpec read(SpecReader reader) {
        Kind kind = reader.enumValue("kind", Kind.class, Kind.NONE);
        return switch (kind) {
            case NONE ->
                new LoadSpec(
                        kind,
                        LoadProfile.of(List.of(new LoadProfile.Segment(
                                "quiet", reader.doubleValue("windowSec", 60, 1, 86_400), 0, 1, true))),
                        0);
            case FIXED -> {
                double rate = reader.requiredDouble("ratePerSec", 0.001, 1_000_000);
                double warmup = reader.doubleValue("warmupSec", 30, 0, 86_400);
                double window = reader.doubleValue("windowSec", 150, 1, 86_400);
                yield new LoadSpec(kind, LoadProfile.fixed(rate, warmup, window), 0);
            }
            case PROFILE -> {
                List<LoadProfile.Segment> segments = new ArrayList<>();
                for (SpecReader segment : reader.objects("segments")) {
                    segments.add(new LoadProfile.Segment(
                            segment.requiredString("label"),
                            segment.doubleValue("durationSec", 0, 0.001, 86_400),
                            segment.doubleValue("ratePerSec", 0, 0, 1_000_000),
                            segment.intValue("burst", 1, 1, 100_000),
                            segment.bool("window", true)));
                }
                if (segments.isEmpty()) {
                    throw new ConfigurationException(
                            "Input " + reader.join("segments") + " needs at least one segment for kind profile");
                }
                yield new LoadSpec(kind, LoadProfile.of(segments), 0);
            }
            case STAIRCASE -> {
                double start = reader.requiredDouble("startRatePerSec", 0.001, 1_000_000);
                double step = reader.requiredDouble("stepRatePerSec", 0.001, 1_000_000);
                int steps = reader.requiredInt("steps", 1, 200);
                double stepSec = reader.doubleValue("stepSec", 60, 1, 86_400);
                double skip = reader.doubleValue("measureSkipSec", 10, 0, 86_400);
                if (skip >= stepSec) {
                    throw new ConfigurationException(
                            "Input " + reader.join("measureSkipSec") + " must be less than the step length");
                }
                yield new LoadSpec(kind, LoadProfile.staircase(start, step, steps, stepSec), skip);
            }
        };
    }
}
