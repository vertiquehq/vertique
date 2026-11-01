// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.driver;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.examples.workflow.load.harness.spec.SpecReader;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the driver process is told, read strictly from a JSON file.
 *
 * @param name the name of the run; part of every key the driver starts
 * @param outDir the directory the driver writes its files to
 * @param startAtEpochMs the wall-clock time of the first arrival's schedule origin
 * @param ingresses the node ingresses, in the order the fixed distribution rule uses them
 * @param profile the schedule
 * @param recoverySec the seconds after the schedule in which nothing is offered
 * @param replayRatePerSec the rate at which refused starts are replayed afterwards
 * @param requestTimeoutMs the longest a start may take before it counts as failed
 * @param graceSec the wait for outstanding starts after the last arrival
 * @param lateArrivalMs an arrival that starts more than this many milliseconds late counts as late
 */
public record DriverSpec(
        String name,
        Path outDir,
        long startAtEpochMs,
        List<Ingress> ingresses,
        LoadProfile profile,
        int recoverySec,
        double replayRatePerSec,
        int requestTimeoutMs,
        int graceSec,
        double lateArrivalMs) {

    /**
     * A node ingress.
     *
     * @param node the node, from 1
     * @param host the host
     * @param port the port
     */
    public record Ingress(int node, String host, int port) {}

    /**
     * Reads a specification.
     *
     * @param json the file content
     * @return the specification
     * @throws ConfigurationException on an unknown or invalid input
     */
    public static DriverSpec read(JsonObject json) {
        SpecReader r = SpecReader.root(json);
        String name = r.requiredString("name");
        Path outDir = Path.of(r.requiredString("outDir"));
        long startAt = r.longValue("startAtEpochMs", 0, 0, Long.MAX_VALUE);
        List<Ingress> ingresses = new ArrayList<>();
        for (SpecReader i : r.objects("ingresses")) {
            ingresses.add(new Ingress(
                    i.requiredInt("node", 1, 1_000), i.requiredString("host"), i.requiredInt("port", 1, 65_535)));
        }
        if (ingresses.isEmpty()) {
            throw new ConfigurationException("The driver needs at least one ingress");
        }
        List<LoadProfile.Segment> segments = new ArrayList<>();
        for (SpecReader s : r.objects("segments")) {
            segments.add(new LoadProfile.Segment(
                    s.requiredString("label"),
                    s.requiredDouble("durationSec", 0.001, 86_400),
                    s.requiredDouble("ratePerSec", 0, 1_000_000),
                    s.intValue("burst", 1, 1, 100_000),
                    s.bool("window", true)));
        }
        int recovery = r.intValue("recoverySec", 30, 0, 3_600);
        double replayRate = r.doubleValue("replayRatePerSec", 50, 0.001, 100_000);
        int timeout = r.intValue("requestTimeoutMs", 10_000, 100, 600_000);
        int grace = r.intValue("graceSec", 20, 1, 3_600);
        double late = r.doubleValue("lateArrivalMs", 50, 0, 60_000);
        r.finish();
        return new DriverSpec(
                name,
                outDir,
                startAt,
                List.copyOf(ingresses),
                LoadProfile.of(segments),
                recovery,
                replayRate,
                timeout,
                grace,
                late);
    }

    /**
     * Renders the specification in the form {@link #read} accepts.
     *
     * @return the JSON
     */
    public JsonObject toJson() {
        JsonArray ingressArray = new JsonArray();
        for (Ingress ingress : ingresses) {
            ingressArray.add(new JsonObject()
                    .put("node", ingress.node())
                    .put("host", ingress.host())
                    .put("port", ingress.port()));
        }
        return new JsonObject()
                .put("name", name)
                .put("outDir", outDir.toString())
                .put("startAtEpochMs", startAtEpochMs)
                .put("ingresses", ingressArray)
                .put("segments", profile.toJson())
                .put("recoverySec", recoverySec)
                .put("replayRatePerSec", replayRatePerSec)
                .put("requestTimeoutMs", requestTimeoutMs)
                .put("graceSec", graceSec)
                .put("lateArrivalMs", lateArrivalMs);
    }
}
