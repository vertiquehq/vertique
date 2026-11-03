// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds what a node is started with and what the run expects the node to report.
 *
 * <p>{@link #overlay(RunSpec, int, String)} is the {@code --conf} of one node: the arm's settings, the node settings,
 * the database and the name of the node's connections. {@link #intended(RunSpec, int)} has the shape of the node's
 * {@code GET /effective} answer and holds only the values the run determines, so that {@link #mismatches} can
 * compare them one by one before any load is offered.
 */
public final class NodeConf {

    private NodeConf() {}

    /**
     * Builds the {@code --conf} overlay of one node.
     *
     * @param spec the run
     * @param node the node, from 1
     * @param portFile the file the node writes its ingress port to
     * @return the overlay
     * @throws ConfigurationException if {@code confOverride} names a setting that the overlay sets already
     */
    public static JsonObject overlay(RunSpec spec, int node, String portFile) {
        NodeSpec n = spec.node();
        RunSpec.Database d = spec.database();
        JsonObject armOverlay = spec.arm().configOverlay();
        JsonObject overlay = new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", d.host())
                                .put("port", d.port())
                                .put("database", d.name())
                                .put("user", d.user())
                                .put("password", d.password())
                                .put("maxPoolSize", n.poolSize())
                                .put("properties", new JsonObject().put("application_name", RunSpec.nodeName(node))))
                .put("flyway", new JsonObject().put("mode", "DISABLED"))
                .put(
                        "workflow",
                        new JsonObject()
                                .put(
                                        "executor",
                                        new JsonObject()
                                                .put("instances", n.executorInstances())
                                                .put("concurrency", n.executorConcurrency())
                                                .put(
                                                        "pollIntervalMs",
                                                        armOverlay
                                                                .getJsonObject("workflow")
                                                                .getJsonObject("executor")
                                                                .getLong("pollIntervalMs")))
                                .put(
                                        "recording",
                                        new JsonObject()
                                                .put(
                                                        "intake",
                                                        new JsonObject()
                                                                .put("enabled", n.intakeEnabled())
                                                                .put("highMark", n.intakeHighMark())
                                                                .put("lowMark", n.intakeLowMark())
                                                                .put("sampleIntervalMs", n.intakeSampleIntervalMs()))))
                .put(
                        "inboxOutbox",
                        new JsonObject()
                                .put(
                                        "relay",
                                        armOverlay
                                                .getJsonObject("inboxOutbox")
                                                .getJsonObject("relay")
                                                .copy()
                                                .put("batchSize", n.relayBatchSize())
                                                .put("instances", n.relayInstances())))
                .put(
                        "vertx",
                        new JsonObject()
                                .put(
                                        "options",
                                        new JsonObject()
                                                .put("eventLoopPoolSize", n.eventLoopThreads())
                                                .put("workerPoolSize", n.workerThreads())
                                                .put("internalBlockingPoolSize", n.internalBlockingThreads())))
                .put(
                        "load",
                        new JsonObject()
                                .put("handlerDelayMs", n.handlerDelayMs())
                                .put("timerDelayMs", n.timerDelayMs())
                                .put(
                                        "ingress",
                                        new JsonObject()
                                                .put("host", "127.0.0.1")
                                                .put("port", 0)
                                                .put("portFile", portFile)));
        Set<String> clashes = clashes(overlay, n.confOverride());
        if (!clashes.isEmpty()) {
            throw new ConfigurationException("node.confOverride sets " + clashes
                    + ", which the run sets through its own inputs; use those inputs instead");
        }
        return overlay.mergeIn(n.confOverride(), true);
    }

    /**
     * Builds the values the run expects a node to report at {@code GET /effective}.
     *
     * @param spec the run
     * @param node the node, from 1
     * @return a tree of the shape of the answer, holding only values the run determines
     */
    public static JsonObject intended(RunSpec spec, int node) {
        NodeSpec n = spec.node();
        JsonObject arm = spec.arm().configOverlay();
        JsonObject relay = arm.getJsonObject("inboxOutbox").getJsonObject("relay");
        long executorPoll =
                arm.getJsonObject("workflow").getJsonObject("executor").getLong("pollIntervalMs");
        boolean observerNone = "none".equals(spec.observer());
        JsonObject intended = new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("applicationName", RunSpec.nodeName(node))
                                .put("maxPoolSize", n.poolSize()))
                .put("flyway", new JsonObject().put("mode", "DISABLED"))
                .put(
                        "executor",
                        new JsonObject()
                                .put("enabled", true)
                                .put("instances", n.executorInstances())
                                .put("concurrency", n.executorConcurrency())
                                .put("pollIntervalMs", executorPoll))
                .put(
                        "recording",
                        new JsonObject()
                                .put(
                                        "intake",
                                        new JsonObject()
                                                .put("enabled", n.intakeEnabled())
                                                .put("highMark", n.intakeHighMark())
                                                .put("lowMark", n.intakeLowMark())
                                                .put("sampleIntervalMs", n.intakeSampleIntervalMs())))
                .put(
                        "relay",
                        new JsonObject()
                                .put("strategy", relay.getString("strategy"))
                                .put("pollingIntervalMs", relay.getLong("pollingIntervalMs"))
                                .put("batchSize", n.relayBatchSize())
                                .put("instances", n.relayInstances()))
                .put(
                        "load",
                        new JsonObject()
                                .put("handlerDelayMs", n.handlerDelayMs())
                                .put("timerDelayMs", n.timerDelayMs()))
                .put(
                        "vertx",
                        new JsonObject()
                                .put("eventLoopPoolSize", n.eventLoopThreads())
                                .put("workerPoolSize", n.workerThreads())
                                .put("internalBlockingPoolSize", n.internalBlockingThreads()))
                .put(
                        "jvm",
                        new JsonObject()
                                .put("maxHeapMb", n.heapMaxMb())
                                .put("availableProcessors", n.activeProcessors()));
        if (observerNone) {
            intended.put("observers", new JsonObject().put("executor", 0).put("outboxPublish", 0));
        }
        // The actual runtime, not only the configured one: the launcher can apply system properties and command-line
        // options over the configuration, and the JVM can start with other flags than the run asked for.
        intended.getJsonObject("vertx")
                .put(
                        "actual",
                        new JsonObject()
                                .put("eventLoopPoolSize", n.eventLoopThreads())
                                .put("workerPoolSize", n.workerThreads())
                                .put("internalBlockingPoolSize", n.internalBlockingThreads()));
        intended.getJsonObject("jvm").put("inputArguments", jvmArguments(n));
        // Every accepted override is compared with the value the node reports for it.
        KnownConfig.leaves(n.confOverride()).forEach((path, value) -> {
            String where = KnownConfig.effectivePath(path);
            if (where == null) {
                throw new ConfigurationException("No place in the node's answer for " + path);
            }
            String[] parts = where.split("\\.");
            JsonObject target = intended;
            for (int i = 0; i < parts.length - 1; i++) {
                JsonObject next = target.getJsonObject(parts[i]);
                if (next == null) {
                    next = new JsonObject();
                    target.put(parts[i], next);
                }
                target = next;
            }
            target.put(parts[parts.length - 1], value);
        });
        return intended;
    }

    /**
     * Returns the JVM flags the node must have been started with.
     *
     * @param node the node settings
     * @return the heap and processor flags and the further flags
     */
    public static io.vertx.core.json.JsonArray jvmArguments(NodeSpec node) {
        io.vertx.core.json.JsonArray flags = new io.vertx.core.json.JsonArray()
                .add("-Xms" + node.heapMinMb() + "m")
                .add("-Xmx" + node.heapMaxMb() + "m")
                .add("-XX:ActiveProcessorCount=" + node.activeProcessors());
        node.jvmFlags().forEach(flags::add);
        return flags;
    }

    /**
     * Compares the intended values with what a node reported.
     *
     * @param intended the expected values, from {@link #intended}
     * @param effective the node's answer
     * @return one line per value that differs or is missing; empty when every intended value matches
     */
    public static List<String> mismatches(JsonObject intended, JsonObject effective) {
        List<String> found = new ArrayList<>();
        compare("", intended, effective, found);
        return found;
    }

    private static void compare(String prefix, JsonObject intended, JsonObject actual, List<String> found) {
        for (String key : intended.fieldNames()) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object want = intended.getValue(key);
            Object have = actual == null ? null : actual.getValue(key);
            if (want instanceof io.vertx.core.json.JsonArray wantArray) {
                // An array of required elements: the node's array must hold each of them.
                if (!(have instanceof io.vertx.core.json.JsonArray haveArray)
                        || !haveArray.getList().containsAll(wantArray.getList())) {
                    found.add(path + ": intended to hold " + wantArray.encode() + " but the node reported " + have);
                }
            } else if (want instanceof JsonObject wantObject) {
                if (have instanceof JsonObject haveObject) {
                    compare(path, wantObject, haveObject, found);
                } else {
                    found.add(path + ": expected an object but the node reported " + have);
                }
            } else if (!same(want, have)) {
                found.add(path + ": intended " + want + " but the node reported " + have);
            }
        }
    }

    private static boolean same(Object want, Object have) {
        if (want instanceof Number a && have instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return want == null ? have == null : want.equals(have);
    }

    /**
     * Finds the settings an override repeats that the run sets itself.
     *
     * @param generated the overlay the run builds
     * @param override the override of the specification
     * @return the dotted paths set by both
     */
    static Set<String> clashes(JsonObject generated, JsonObject override) {
        Set<String> found = new TreeSet<>();
        clashes(generated, override, "", found);
        return found;
    }

    private static void clashes(JsonObject generated, JsonObject override, String prefix, Set<String> found) {
        for (String key : override.fieldNames()) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object over = override.getValue(key);
            Object own = generated.getValue(key);
            if (own == null) {
                continue;
            }
            if (over instanceof JsonObject overObject && own instanceof JsonObject ownObject) {
                clashes(ownObject, overObject, path, found);
            } else {
                found.add(path);
            }
        }
    }
}
