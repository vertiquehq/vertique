// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the configuration a node is started with and the comparison with what it reports. */
class NodeConfTest {

    private static RunSpec spec(String arm) {
        return RunSpec.parse(new JsonObject()
                .put("name", "r")
                .put("arm", arm)
                .put("nodes", 2)
                .put("load", new JsonObject().put("kind", "fixed").put("ratePerSec", 10)));
    }

    @Test
    @DisplayName("the overlay carries the arm, the pools and the name of the node's connections")
    void overlayHoldsTheRun() {
        JsonObject overlay = NodeConf.overlay(spec("OFF"), 2, "/tmp/p");

        assertEquals(
                "load-node-2",
                overlay.getJsonObject("db").getJsonObject("properties").getString("application_name"));
        assertEquals(20, overlay.getJsonObject("db").getInteger("maxPoolSize"));
        assertEquals(
                100L,
                overlay.getJsonObject("workflow").getJsonObject("executor").getLong("pollIntervalMs"));
        assertEquals(
                "POLLING",
                overlay.getJsonObject("inboxOutbox").getJsonObject("relay").getString("strategy"));
        assertEquals(
                50L, overlay.getJsonObject("inboxOutbox").getJsonObject("relay").getLong("pollingIntervalMs"));
        assertEquals(2, overlay.getJsonObject("vertx").getJsonObject("options").getInteger("eventLoopPoolSize"));
        assertEquals(
                "/tmp/p", overlay.getJsonObject("load").getJsonObject("ingress").getString("portFile"));
        assertEquals("DISABLED", overlay.getJsonObject("flyway").getString("mode"));
    }

    @Test
    @DisplayName("an accepted override is merged into the overlay")
    void overrideMerged() {
        JsonObject json = new JsonObject()
                .put("name", "r")
                .put("arm", "ON")
                .put("nodes", 1)
                .put("load", new JsonObject().put("kind", "fixed").put("ratePerSec", 10))
                .put(
                        "node",
                        new JsonObject()
                                .put(
                                        "confOverride",
                                        new JsonObject().put("db", new JsonObject().put("connectionTimeoutMs", 1000))));

        JsonObject overlay = NodeConf.overlay(RunSpec.parse(json), 1, "p");

        assertEquals(1000, overlay.getJsonObject("db").getInteger("connectionTimeoutMs"));
        assertEquals(20, overlay.getJsonObject("db").getInteger("maxPoolSize"));
    }

    @Test
    @DisplayName("a setting that both the run and an override set is found")
    void clashFound() {
        JsonObject generated = NodeConf.overlay(spec("ON"), 1, "p");
        JsonObject override = new JsonObject()
                .put("db", new JsonObject().put("maxPoolSize", 5).put("connectionTimeoutMs", 1))
                .put("workflow", new JsonObject().put("executor", new JsonObject().put("concurrency", 1)));

        assertEquals(
                java.util.Set.of("db.maxPoolSize", "workflow.executor.concurrency"),
                NodeConf.clashes(generated, override));
    }

    @Test
    @DisplayName("a node that reports what was intended has no mismatch")
    void noMismatch() {
        RunSpec spec = spec("ON");
        JsonObject effective = NodeConf.intended(spec, 1);
        effective.getJsonObject("executor").put("leaseSeconds", 30);

        assertTrue(NodeConf.mismatches(NodeConf.intended(spec, 1), effective).isEmpty());
    }

    @Test
    @DisplayName("a value the node did not pick up is named with the intended and reported values")
    void valueNotPickedUp() {
        RunSpec spec = spec("ON");
        JsonObject effective = NodeConf.intended(spec, 1);
        effective.getJsonObject("executor").put("pollIntervalMs", 1000);
        effective.getJsonObject("db").put("applicationName", "load-node-9");

        List<String> found = NodeConf.mismatches(NodeConf.intended(spec, 1), effective);

        assertEquals(2, found.size(), found.toString());
        assertTrue(found.stream()
                .anyMatch(m -> m.contains("executor.pollIntervalMs") && m.contains("5000") && m.contains("1000")));
        assertTrue(found.stream().anyMatch(m -> m.contains("db.applicationName")));
    }

    @Test
    @DisplayName("a missing section is a mismatch")
    void missingSection() {
        RunSpec spec = spec("ON");

        List<String> found = NodeConf.mismatches(NodeConf.intended(spec, 1), new JsonObject());

        assertTrue(found.stream().anyMatch(m -> m.startsWith("executor")), found.toString());
    }

    @Test
    @DisplayName("a bound observer is a mismatch for a baseline run")
    void observerBound() {
        RunSpec spec = spec("ON");
        JsonObject effective = NodeConf.intended(spec, 1);
        effective.getJsonObject("observers").put("executor", 1);

        List<String> found = NodeConf.mismatches(NodeConf.intended(spec, 1), effective);

        assertTrue(found.stream().anyMatch(m -> m.contains("observers.executor")), found.toString());
    }

    @Test
    @DisplayName("an accepted override is compared with the value the node reports for it")
    void overrideIsCompared() {
        JsonObject json = new JsonObject()
                .put("name", "r")
                .put("arm", "ON")
                .put("nodes", 1)
                .put("load", new JsonObject().put("kind", "fixed").put("ratePerSec", 10))
                .put(
                        "node",
                        new JsonObject()
                                .put(
                                        "confOverride",
                                        new JsonObject()
                                                .put(
                                                        "workflow",
                                                        new JsonObject()
                                                                .put(
                                                                        "executor",
                                                                        new JsonObject().put("leaseSeconds", 45)))
                                                .put(
                                                        "inboxOutbox",
                                                        new JsonObject()
                                                                .put(
                                                                        "relay",
                                                                        new JsonObject().put("leaseTimeoutMs", 20_000)))
                                                .put("db", new JsonObject().put("connectionTimeoutMs", 1000))));
        RunSpec spec = RunSpec.parse(json);
        JsonObject intended = NodeConf.intended(spec, 1);

        assertEquals(45, intended.getJsonObject("executor").getInteger("leaseSeconds"));
        assertEquals(20_000, intended.getJsonObject("relay").getInteger("leaseTimeoutMs"));
        assertEquals(1000, intended.getJsonObject("db").getInteger("connectionTimeoutMs"));
        JsonObject effective = NodeConf.intended(spec, 1);
        effective.getJsonObject("executor").put("leaseSeconds", 30);

        List<String> found = NodeConf.mismatches(intended, effective);

        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).contains("executor.leaseSeconds"), found.get(0));
    }

    @Test
    @DisplayName("every setting that can be overridden is reported at a place the check can read")
    void everyKnownSettingHasAPlaceInTheAnswer() {
        for (String path : KnownConfig.paths()) {
            assertTrue(KnownConfig.effectivePath(path) != null, path);
        }
    }

    @Test
    @DisplayName("the actual Vert.x pools and the JVM flags are compared, not only the configured ones")
    void actualRuntimeIsCompared() {
        RunSpec spec = spec("ON");
        JsonObject intended = NodeConf.intended(spec, 1);
        JsonObject effective = NodeConf.intended(spec, 1);
        effective.getJsonObject("vertx").getJsonObject("actual").put("eventLoopPoolSize", 28);
        effective.getJsonObject("jvm").put("inputArguments", new io.vertx.core.json.JsonArray().add("-Xmx512m"));

        List<String> found = NodeConf.mismatches(intended, effective);

        assertTrue(found.stream().anyMatch(m -> m.contains("vertx.actual.eventLoopPoolSize")), found.toString());
        assertTrue(
                found.stream().anyMatch(m -> m.contains("jvm.inputArguments") && m.contains("-Xms256m")),
                found.toString());
        assertTrue(NodeConf.jvmArguments(spec.node()).contains("-XX:ActiveProcessorCount=2"));
    }
}
