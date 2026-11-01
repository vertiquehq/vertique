// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a run is a valid measurement.
 *
 * <p>Validity describes the measurement and nothing else. A run is invalid only when
 *
 * <ul>
 *   <li>the generator did not keep its schedule or did not finish,</li>
 *   <li>the collector or the host sampler did not record,</li>
 *   <li>the configuration was not what the run intended, or the listeners were not in place before the load, or</li>
 *   <li>processes outside the harness used the host for long enough to be independent evidence of interference.</li>
 * </ul>
 *
 * <p>Engine errors, database stalls and outages, deadlocks, timeouts, a node that did not become ready or ran out of
 * resources or died, and a listener that was not in place or was lost are results. They never appear here, however bad
 * they are.
 */
public final class Validity {

    private Validity() {}

    /**
     * What the driver reported.
     *
     * @param summaryPresent whether the driver wrote its summary
     * @param planned the arrivals scheduled
     * @param started the arrivals the driver began to send
     * @param notSubmitted the arrivals that never started
     * @param latenessMaxMs the greatest lateness in milliseconds
     * @param lateOverThreshold the arrivals that started later than the late-arrival threshold
     * @param recorderLoss whether the arrival record lost rows
     */
    public record GeneratorFacts(
            boolean summaryPresent,
            long planned,
            long started,
            long notSubmitted,
            double latenessMaxMs,
            long lateOverThreshold,
            boolean recorderLoss) {}

    /**
     * What the collector and the host sampler recorded.
     *
     * <p>The collector's liveness is its heartbeat, written on schedule whatever the database answers. A database that
     * does not answer is not a collector failure: it shows in the system results, as outstanding or unavailable
     * database samples at each beat.
     *
     * @param collectorFinished whether the collector wrote its stopped marker
     * @param expectedBeats the heartbeats the window should hold
     * @param beats the heartbeats the collector wrote inside the window
     * @param maxGapMs the longest silence between two heartbeats inside the window
     * @param recorderErrors the rows the collector failed to write
     * @param hostRequired whether the run asked for host sampling
     * @param hostExpectedSeconds the seconds the window should hold
     * @param hostSeconds the host samples inside the window
     * @param independentRunSeconds the longest run of consecutive host samples in the window in which one identifiable
     *     process outside the harness, the database VM and the system used the CPU above the limit
     * @param jvmLogsMissing the processes whose JVM log is missing or empty
     */
    public record CollectionFacts(
            boolean collectorFinished,
            long expectedBeats,
            long beats,
            long maxGapMs,
            long recorderErrors,
            boolean hostRequired,
            long hostExpectedSeconds,
            long hostSeconds,
            long independentRunSeconds,
            List<String> jvmLogsMissing) {}

    /**
     * What the run knew about its configuration.
     *
     * <p>Only a configuration error that was established independently of the system's behavior counts: a node that
     * reports other values than the run intended, or a node that exited with a configuration error of its own. A node that
     * did not become ready, a listener that was not in place, or a database that went away are failures to operate the
     * intended configuration, which are results.
     *
     * @param mismatches the intended values the nodes did not report
     * @param startupConfigurationError a configuration error a node reported by itself at startup, or empty
     * @param preconditionFailure a failure of the harness's own environment before any node started, or empty
     */
    public record ConfigurationFacts(
            List<String> mismatches, String startupConfigurationError, String preconditionFailure) {}

    /**
     * A rule a run broke.
     *
     * @param rule the name of the rule
     * @param detail what was found
     */
    public record Violation(String rule, String detail) {}

    /**
     * The verdict on a run's validity.
     *
     * @param valid whether the run is a valid measurement
     * @param violations the rules it broke; empty when valid
     */
    public record Result(boolean valid, List<Violation> violations) {

        /**
         * Renders the verdict.
         *
         * @return the verdict as JSON
         */
        public JsonObject toJson() {
            JsonArray array = new JsonArray();
            violations.forEach(
                    v -> array.add(new JsonObject().put("rule", v.rule()).put("detail", v.detail())));
            return new JsonObject().put("validity", valid ? "valid" : "invalid").put("violations", array);
        }
    }

    /**
     * Judges a run.
     *
     * @param generator the driver's report
     * @param collection the collector's and sampler's coverage
     * @param configuration what the run knew about its configuration
     * @param limits the thresholds
     * @return the verdict
     */
    public static Result evaluate(
            GeneratorFacts generator, CollectionFacts collection, ConfigurationFacts configuration, Thresholds limits) {
        List<Violation> found = new ArrayList<>();
        if (!generator.summaryPresent()) {
            found.add(new Violation("generator.did-not-finish", "the driver wrote no summary"));
        } else {
            if (generator.latenessMaxMs() > limits.generatorMaxLatenessMs()) {
                found.add(new Violation(
                        "generator.lateness",
                        "greatest lateness " + round(generator.latenessMaxMs()) + " ms exceeds "
                                + limits.generatorMaxLatenessMs() + " ms"));
            }
            double lateFraction =
                    generator.started() == 0 ? 0 : (double) generator.lateOverThreshold() / generator.started();
            if (lateFraction > limits.maxLateArrivalFraction()) {
                found.add(new Violation(
                        "generator.late-arrivals",
                        generator.lateOverThreshold() + " of " + generator.started() + " arrivals started more than "
                                + limits.lateArrivalMs() + " ms late"));
            }
            if (generator.notSubmitted() > limits.maxNotSubmitted()) {
                found.add(new Violation(
                        "generator.not-submitted",
                        generator.notSubmitted() + " of " + generator.planned() + " scheduled arrivals never started"));
            }
            if (generator.recorderLoss()) {
                found.add(new Violation("generator.recorder-loss", "the arrival record lost rows"));
            }
        }
        if (!collection.collectorFinished()) {
            found.add(new Violation("collector.did-not-finish", "the collector did not stop cleanly"));
        }
        if (collection.expectedBeats() > 0
                && (double) collection.beats() / collection.expectedBeats() < limits.collectorMinCoverage()) {
            found.add(new Violation(
                    "collector.heartbeat-coverage",
                    collection.beats() + " of " + collection.expectedBeats() + " heartbeats inside the window"));
        }
        if (collection.maxGapMs() > limits.collectorMaxGapMs()) {
            found.add(new Violation(
                    "collector.heartbeat-gap", "silent for " + collection.maxGapMs() + " ms inside the window"));
        }
        if (collection.recorderErrors() > 0) {
            found.add(new Violation("collector.recorder", collection.recorderErrors() + " rows could not be written"));
        }
        if (collection.hostRequired()
                && collection.hostExpectedSeconds() > 0
                && (double) collection.hostSeconds() / collection.hostExpectedSeconds() < limits.hostMinCoverage()) {
            found.add(new Violation(
                    "host-sampler.coverage",
                    collection.hostSeconds() + " of " + collection.hostExpectedSeconds()
                            + " seconds inside the window"));
        }
        if (!collection.jvmLogsMissing().isEmpty()) {
            found.add(new Violation("collector.jvm-log-missing", "no JVM log for " + collection.jvmLogsMissing()));
        }
        if (collection.independentRunSeconds() > limits.outsideCpuMaxSeconds()) {
            found.add(new Violation(
                    "interference.independent-process",
                    "one identifiable process outside the harness used more than " + limits.outsideCpuPercent()
                            + "% of a core for " + collection.independentRunSeconds() + " consecutive seconds"));
        }
        if (!configuration.preconditionFailure().isEmpty()) {
            found.add(new Violation("harness.precondition", configuration.preconditionFailure()));
        }
        if (!configuration.startupConfigurationError().isEmpty()) {
            found.add(new Violation("configuration.startup", configuration.startupConfigurationError()));
        }
        if (!configuration.mismatches().isEmpty()) {
            found.add(new Violation(
                    "configuration.mismatch", "intended values differ from the nodes: " + configuration.mismatches()));
        }
        return new Result(found.isEmpty(), List.copyOf(found));
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
