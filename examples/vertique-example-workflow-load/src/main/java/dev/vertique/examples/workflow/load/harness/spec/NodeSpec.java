// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.spec;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.List;

/**
 * The settings every node of a run shares: the JVM, the Vert.x thread pools, the connection pool, the executor and
 * relay, the workload timing and the intake limit.
 *
 * <p>Nodes of one run never differ, and the settings do not change with the node count: a node gets the same heap
 * and the same pools whether it is one of one or one of sixteen.
 *
 * @param heapMinMb the initial heap in MiB
 * @param heapMaxMb the maximum heap in MiB
 * @param activeProcessors the processor count the JVM assumes, so that its ergonomics do not depend on the host
 * @param eventLoopThreads the Vert.x event-loop threads
 * @param workerThreads the Vert.x worker threads
 * @param internalBlockingThreads the Vert.x internal blocking threads
 * @param jvmFlags further JVM flags, each starting with {@code -X}
 * @param poolSize the database connections of the node's pool
 * @param executorConcurrency the commands one executor instance runs at once
 * @param executorInstances the executor instances of a node
 * @param relayInstances the relay instances of a node
 * @param relayBatchSize the outbox rows one relay claim takes
 * @param handlerDelayMs the time the dispatch handler waits before it records its callback
 * @param timerDelayMs the length of the workload's timer step
 * @param intakeEnabled whether the intake limit is on
 * @param intakeHighMark the due commands at which intake is refused
 * @param intakeLowMark the due commands at which intake is admitted again
 * @param intakeSampleIntervalMs the time between two samples of the intake limit
 * @param confOverride further product settings, checked against the settings the harness knows
 */
public record NodeSpec(
        int heapMinMb,
        int heapMaxMb,
        int activeProcessors,
        int eventLoopThreads,
        int workerThreads,
        int internalBlockingThreads,
        List<String> jvmFlags,
        int poolSize,
        int executorConcurrency,
        int executorInstances,
        int relayInstances,
        int relayBatchSize,
        long handlerDelayMs,
        long timerDelayMs,
        boolean intakeEnabled,
        int intakeHighMark,
        int intakeLowMark,
        long intakeSampleIntervalMs,
        JsonObject confOverride) {

    /** The JVM flags a node gets unless the specification names others. */
    public static final List<String> DEFAULT_JVM_FLAGS =
            List.of("-XX:+UseG1GC", "-XX:ParallelGCThreads=2", "-XX:ConcGCThreads=1", "-XX:+ExitOnOutOfMemoryError");

    /**
     * Reads the node settings from a specification section.
     *
     * @param reader the reader of the {@code node} object
     * @return the settings
     * @throws ConfigurationException if an input is unknown or out of bounds
     */
    public static NodeSpec read(SpecReader reader) {
        int heapMin = reader.intValue("heapMinMb", 256, 16, 65_536);
        int heapMax = reader.intValue("heapMaxMb", 512, 16, 65_536);
        if (heapMin > heapMax) {
            throw new ConfigurationException("Input " + reader.join("heapMinMb") + " must not exceed heapMaxMb");
        }
        int active = reader.intValue("activeProcessors", 2, 1, 256);
        int eventLoop = reader.intValue("eventLoopThreads", 2, 1, 256);
        int worker = reader.intValue("workerThreads", 4, 1, 1_000);
        int internal = reader.intValue("internalBlockingThreads", 2, 1, 1_000);
        List<String> flags = reader.stringList("jvmFlags", DEFAULT_JVM_FLAGS);
        for (String flag : flags) {
            if (!flag.startsWith("-X") || flag.contains(" ") || flag.startsWith("-Xmx") || flag.startsWith("-Xms")) {
                throw new ConfigurationException("Input " + reader.join("jvmFlags") + " holds '" + flag
                        + "'; flags must start with -X, hold no space and leave the heap to heapMinMb and heapMaxMb");
            }
        }
        int pool = reader.intValue("poolSize", 20, 1, 500);
        int concurrency = reader.intValue("executorConcurrency", 8, 1, 1_000);
        int executorInstances = reader.intValue("executorInstances", 1, 1, 64);
        int relayInstances = reader.intValue("relayInstances", 1, 1, 64);
        int batch = reader.intValue("relayBatchSize", 50, 1, 10_000);
        long handler = reader.longValue("handlerDelayMs", 20, 0, 10_000);
        long timer = reader.longValue("timerDelayMs", 1_000, 1, 600_000);
        SpecReader intake = reader.object("intake");
        boolean intakeEnabled = intake.bool("enabled", true);
        int high = intake.intValue("highMark", 10_000, 2, 10_000_000);
        int low = intake.intValue("lowMark", 5_000, 1, 10_000_000);
        long interval = intake.longValue("sampleIntervalMs", 1_000, 100, 3_600_000);
        if (low >= high) {
            throw new ConfigurationException("Input " + intake.join("lowMark") + " must be less than highMark");
        }
        JsonObject override = reader.rawObject("confOverride");
        KnownConfig.validate(override);
        return new NodeSpec(
                heapMin,
                heapMax,
                active,
                eventLoop,
                worker,
                internal,
                List.copyOf(flags),
                pool,
                concurrency,
                executorInstances,
                relayInstances,
                batch,
                handler,
                timer,
                intakeEnabled,
                high,
                low,
                interval,
                override);
    }
}
