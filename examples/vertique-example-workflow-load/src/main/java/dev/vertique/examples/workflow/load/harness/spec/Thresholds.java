// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

/**
 * The thresholds that decide whether a run is valid and how its figures are judged.
 *
 * <p>They are fixed before a measurement set starts and recorded in the manifest of every run. A run's own
 * specification may name any of them, so a changed value is visible; the defaults are the values the harness
 * ships with.
 *
 * <p>Validity covers the measurement only. The generator, collector and configuration thresholds, and the outside
 * interference thresholds, decide {@code invalid}. The remaining thresholds judge the system's behavior and never
 * make a run invalid.
 *
 * @param generatorMaxLatenessMs the greatest lateness of any arrival, in milliseconds; above it the generator failed
 * @param lateArrivalMs an arrival that starts more than this many milliseconds after its schedule is late
 * @param maxLateArrivalFraction the share of arrivals that may be late before the generator failed
 * @param maxNotSubmitted the number of scheduled arrivals that may be left unsent
 * @param collectorMinCoverage the share of sampling ticks the collector must have written inside the window
 * @param collectorMaxGapMs the longest silence of the collector inside the window, in milliseconds
 * @param hostMinCoverage the share of seconds the host sampler must have written inside the window
 * @param outsideCpuPercent the CPU use, in percent of one core, of one identifiable process that is not part of the
 *     harness, not the database VM and not a system process, that counts as interference in a second
 * @param outsideCpuMaxSeconds the consecutive seconds of such interference by the same process that make a run invalid
 * @param completionRateBar the share of the offered starts that must complete for a load to count as carried
 * @param depthSlopeFraction the growth of the due depth per second, as a share of the offered starts per second,
 *     that still counts as flat
 * @param dipFractionOfTypical a second whose completions fall below this share of the typical rate (the 75th percentile) of its stretch is part of a dip
 * @param dipMinSeconds the consecutive low seconds that make a dip sustained
 * @param excursionMs a second whose recorded-to-segment-start 95th percentile exceeds this many milliseconds is part
 *     of an excursion
 * @param excursionMinSeconds the consecutive slow seconds that make an excursion sustained
 * @param jvmPauseReportMs the shortest JVM pause that is kept as an event, in milliseconds
 * @param pauseAttributionMs the shortest JVM pause that explains a stall it overlaps, in milliseconds
 * @param cpuSaturationPercent the CPU use of the host or VM, in percent, that is reported as saturation
 * @param hostLoadWarn the one-minute host load average that is reported as saturation
 * @param g3RecordedToStartP95Ms the limit of the 95th percentile of recorded to segment transaction start
 * @param g3TimerLatenessP95Ms the limit of the 95th percentile of timer transaction-start lateness
 * @param g2Ratio the share of the best smaller node count that the largest node count must reach
 * @param g1RecoverySeconds the time the due depth has to return to the low mark after the load falls
 * @param minValidRuns the valid runs a point needs before a gate is judged
 * @param maxExcludedFraction the share of the cohort's commands that may be left out of a latency quantile
 *     (retried, deferred, unsettled, parked, dead, failed, or never created) before the quantile cannot support a gate
 * @param lockWatchMinCoverage the share of the lock watch's expected samples that must have succeeded for a gate to
 *     rest on it
 * @param lockWatchMaxErrorFraction the share of lock watch samples that may have failed for a gate to rest on it
 * @param minDepthReadingFraction the share of the seconds of a stretch that must have a depth reading for a gate or a
 *     slope to rest on it
 * @param maxAttemptsPerReplicate the attempts one replicate may take: the first and two retries
 */
public record Thresholds(
        double generatorMaxLatenessMs,
        double lateArrivalMs,
        double maxLateArrivalFraction,
        int maxNotSubmitted,
        double collectorMinCoverage,
        long collectorMaxGapMs,
        double hostMinCoverage,
        double outsideCpuPercent,
        int outsideCpuMaxSeconds,
        double completionRateBar,
        double depthSlopeFraction,
        double dipFractionOfTypical,
        int dipMinSeconds,
        double excursionMs,
        int excursionMinSeconds,
        double jvmPauseReportMs,
        double pauseAttributionMs,
        double cpuSaturationPercent,
        double hostLoadWarn,
        double g3RecordedToStartP95Ms,
        double g3TimerLatenessP95Ms,
        double g2Ratio,
        int g1RecoverySeconds,
        int minValidRuns,
        double maxExcludedFraction,
        double lockWatchMinCoverage,
        double lockWatchMaxErrorFraction,
        double minDepthReadingFraction,
        int maxAttemptsPerReplicate) {

    /**
     * Returns the thresholds the harness ships with.
     *
     * @return the defaults
     */
    public static Thresholds defaults() {
        return read(SpecReader.root(new io.vertx.core.json.JsonObject()));
    }

    /**
     * Reads thresholds from a JSON object and rejects any key that is not a threshold.
     *
     * @param json the thresholds; absent ones take the defaults
     * @return the thresholds
     */
    public static Thresholds parse(io.vertx.core.json.JsonObject json) {
        SpecReader reader = SpecReader.root(json.copy());
        Thresholds thresholds = read(reader);
        reader.finish();
        return thresholds;
    }

    /**
     * Reads the thresholds from a specification section.
     *
     * @param reader the reader of the {@code thresholds} object
     * @return the thresholds, each from the section or the default
     */
    public static Thresholds read(SpecReader reader) {
        return new Thresholds(
                reader.doubleValue("generatorMaxLatenessMs", 250, 1, 60_000),
                reader.doubleValue("lateArrivalMs", 50, 0, 60_000),
                reader.doubleValue("maxLateArrivalFraction", 0.001, 0, 1),
                reader.intValue("maxNotSubmitted", 0, 0, Integer.MAX_VALUE),
                reader.doubleValue("collectorMinCoverage", 0.95, 0, 1),
                reader.longValue("collectorMaxGapMs", 3_000, 1, 600_000),
                reader.doubleValue("hostMinCoverage", 0.9, 0, 1),
                reader.doubleValue("outsideCpuPercent", 100, 0, 100_000),
                reader.intValue("outsideCpuMaxSeconds", 10, 0, 1_000_000),
                reader.doubleValue("completionRateBar", 0.99, 0, 1),
                reader.doubleValue("depthSlopeFraction", 0.005, 0, 1),
                reader.doubleValue("dipFractionOfTypical", 0.5, 0, 1),
                reader.intValue("dipMinSeconds", 2, 1, 3_600),
                reader.doubleValue("excursionMs", 1_000, 1, 3_600_000),
                reader.intValue("excursionMinSeconds", 2, 1, 3_600),
                reader.doubleValue("jvmPauseReportMs", 20, 0, 3_600_000),
                reader.doubleValue("pauseAttributionMs", 100, 0, 3_600_000),
                reader.doubleValue("cpuSaturationPercent", 95, 0, 100),
                reader.doubleValue("hostLoadWarn", 14, 0, 10_000),
                reader.doubleValue("g3RecordedToStartP95Ms", 100, 0, 3_600_000),
                reader.doubleValue("g3TimerLatenessP95Ms", 1_000, 0, 3_600_000),
                reader.doubleValue("g2Ratio", 0.9, 0, 1),
                reader.intValue("g1RecoverySeconds", 60, 1, 3_600),
                reader.intValue("minValidRuns", 2, 1, 100),
                reader.doubleValue("maxExcludedFraction", 0.01, 0, 1),
                reader.doubleValue("lockWatchMinCoverage", 0.9, 0, 1),
                reader.doubleValue("lockWatchMaxErrorFraction", 0.05, 0, 1),
                reader.doubleValue("minDepthReadingFraction", 0.9, 0, 1),
                reader.intValue("maxAttemptsPerReplicate", 3, 1, 10));
    }
}
