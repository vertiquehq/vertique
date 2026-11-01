// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.examples.workflow.load.harness.eval.Validity.CollectionFacts;
import dev.vertique.examples.workflow.load.harness.eval.Validity.ConfigurationFacts;
import dev.vertique.examples.workflow.load.harness.eval.Validity.GeneratorFacts;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the rule that decides whether a run is a valid measurement: only generator, collector, configuration and
 * independently evidenced outside failures make a run invalid. That no behavior of the system can is proved on whole runs
 * in {@code RunEvaluatorTest}, where the database stops answering and nodes die and the verdict stays valid.
 */
class ValidityTest {

    private final Thresholds limits = Thresholds.defaults();

    private static GeneratorFacts goodGenerator() {
        return new GeneratorFacts(true, 6000, 6000, 0, 12.0, 0, false);
    }

    private static CollectionFacts collection(
            long beats, long gap, long recorderErrors, long hostSeconds, long independent) {
        return new CollectionFacts(true, 30, beats, gap, recorderErrors, true, 30, hostSeconds, independent, List.of());
    }

    private static CollectionFacts goodCollection() {
        return collection(30, 1010, 0, 28, 0);
    }

    private static ConfigurationFacts goodConfiguration() {
        return new ConfigurationFacts(List.of(), "", "");
    }

    private Validity.Result judge(GeneratorFacts g, CollectionFacts c, ConfigurationFacts f) {
        return Validity.evaluate(g, c, f, limits);
    }

    private static List<String> rules(Validity.Result result) {
        return result.violations().stream().map(Validity.Violation::rule).toList();
    }

    @Test
    @DisplayName("a run whose generator, collector and configuration held is valid")
    void healthyRunIsValid() {
        Validity.Result result = judge(goodGenerator(), goodCollection(), goodConfiguration());

        assertTrue(result.valid());
        assertTrue(result.violations().isEmpty());
        assertEquals("valid", result.toJson().getString("validity"));
    }

    @Test
    @DisplayName("a generator that was late by more than the limit makes the run invalid")
    void generatorLateness() {
        Validity.Result result =
                judge(new GeneratorFacts(true, 6000, 6000, 0, 2984, 118, false), goodCollection(), goodConfiguration());

        assertFalse(result.valid());
        assertTrue(rules(result).contains("generator.lateness"), rules(result).toString());
        assertTrue(
                rules(result).contains("generator.late-arrivals"), rules(result).toString());
    }

    @Test
    @DisplayName("arrivals that never started make the run invalid")
    void generatorMissingArrivals() {
        Validity.Result result =
                judge(new GeneratorFacts(true, 6000, 5990, 10, 5, 0, false), goodCollection(), goodConfiguration());

        assertEquals(List.of("generator.not-submitted"), rules(result));
    }

    @Test
    @DisplayName("a lost row in the arrival record, or a missing summary, makes the run invalid")
    void generatorRecorder() {
        assertEquals(
                List.of("generator.recorder-loss"),
                rules(judge(new GeneratorFacts(true, 10, 10, 0, 1, 0, true), goodCollection(), goodConfiguration())));
        assertEquals(
                List.of("generator.did-not-finish"),
                rules(judge(new GeneratorFacts(false, 0, 0, 0, 0, 0, false), goodCollection(), goodConfiguration())));
    }

    @Test
    @DisplayName("a late arrival fraction under the limit is tolerated")
    void fewLateArrivalsTolerated() {
        Validity.Result result =
                judge(new GeneratorFacts(true, 6000, 6000, 0, 90, 5, false), goodCollection(), goodConfiguration());

        assertTrue(result.valid(), rules(result).toString());
    }

    @Test
    @DisplayName("a collector that missed heartbeats, went silent, lost rows or did not stop makes the run invalid")
    void collectorFailure() {
        Validity.Result result = judge(
                goodGenerator(),
                new CollectionFacts(false, 30, 18, 9000, 3, true, 30, 28, 0, List.of()),
                goodConfiguration());

        assertEquals(
                List.of(
                        "collector.did-not-finish",
                        "collector.heartbeat-coverage",
                        "collector.heartbeat-gap",
                        "collector.recorder"),
                rules(result));
    }

    @Test
    @DisplayName("a host sampler with too few seconds, or a missing JVM log, makes the run invalid")
    void samplerAndLogs() {
        Validity.Result result = judge(
                goodGenerator(),
                new CollectionFacts(true, 30, 30, 1010, 0, true, 30, 20, 0, List.of("node-3")),
                goodConfiguration());

        assertEquals(List.of("host-sampler.coverage", "collector.jvm-log-missing"), rules(result));
    }

    @Test
    @DisplayName("one identifiable outside process busy for more than ten consecutive seconds makes the run invalid")
    void independentInterference() {
        assertTrue(judge(goodGenerator(), collection(30, 1010, 0, 28, 10), goodConfiguration())
                .valid());
        Validity.Result result = judge(goodGenerator(), collection(30, 1010, 0, 28, 11), goodConfiguration());

        assertEquals(List.of("interference.independent-process"), rules(result));
    }

    @Test
    @DisplayName(
            "a configuration that differs from the intended one, a node's own configuration error or a missing environment is invalid")
    void configuration() {
        Validity.Result result = judge(
                goodGenerator(),
                goodCollection(),
                new ConfigurationFacts(
                        List.of("node-1 executor.pollIntervalMs: intended 5000 but 1000"),
                        "nodes: node 2 reported a configuration error",
                        "docker: the container is not running"));

        assertEquals(List.of("harness.precondition", "configuration.startup", "configuration.mismatch"), rules(result));
    }
}
