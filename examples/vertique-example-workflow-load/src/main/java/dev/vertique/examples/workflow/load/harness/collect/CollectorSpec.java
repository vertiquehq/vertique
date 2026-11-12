// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import dev.vertique.examples.workflow.load.harness.spec.SpecReader;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;

/**
 * What the collector process is told, read strictly from a JSON file.
 *
 * @param outDir the directory the collector writes to
 * @param stopFile the file whose appearance ends the collector
 * @param pidsFile the file that lists the harness processes, one {@code pid role} per line
 * @param host the database host
 * @param port the database port
 * @param database the database
 * @param user the user
 * @param password the password
 * @param container the database container, for the VM sampler
 * @param sampleIntervalMs the time between two samples
 * @param depthCap the most rows a depth reading counts
 * @param lockWatch whether the lock watch runs
 * @param lockWatchIntervalMs the time between two lock watch samples
 * @param queryTimeoutMs the longest a query may take
 * @param hostSampling whether the host is sampled
 * @param vmSampling whether the database VM is sampled
 * @param topRows the processes listed per host sample
 */
record CollectorSpec(
        Path outDir,
        Path stopFile,
        Path pidsFile,
        String host,
        int port,
        String database,
        String user,
        String password,
        String container,
        long sampleIntervalMs,
        int depthCap,
        boolean lockWatch,
        long lockWatchIntervalMs,
        long queryTimeoutMs,
        boolean hostSampling,
        boolean vmSampling,
        int topRows) {

    static CollectorSpec read(JsonObject json) {
        SpecReader r = SpecReader.root(json);
        CollectorSpec spec = new CollectorSpec(
                Path.of(r.requiredString("outDir")),
                Path.of(r.requiredString("stopFile")),
                Path.of(r.requiredString("pidsFile")),
                r.requiredString("host"),
                r.requiredInt("port", 1, 65_535),
                r.requiredString("database"),
                r.requiredString("user"),
                r.requiredString("password"),
                r.string("container", ""),
                r.longValue("sampleIntervalMs", 1_000, 100, 60_000),
                r.intValue("depthCap", 100_000, 10, 100_000_000),
                r.bool("lockWatch", false),
                r.longValue("lockWatchIntervalMs", 50, 10, 10_000),
                r.longValue("queryTimeoutMs", 2_000, 100, 60_000),
                r.bool("hostSampling", true),
                r.bool("vmSampling", true),
                r.intValue("topRows", 40, 1, 200));
        r.finish();
        return spec;
    }

    JsonObject toJson() {
        return new JsonObject()
                .put("outDir", outDir.toString())
                .put("stopFile", stopFile.toString())
                .put("pidsFile", pidsFile.toString())
                .put("host", host)
                .put("port", port)
                .put("database", database)
                .put("user", user)
                .put("password", password)
                .put("container", container)
                .put("sampleIntervalMs", sampleIntervalMs)
                .put("depthCap", depthCap)
                .put("lockWatch", lockWatch)
                .put("lockWatchIntervalMs", lockWatchIntervalMs)
                .put("queryTimeoutMs", queryTimeoutMs)
                .put("hostSampling", hostSampling)
                .put("vmSampling", vmSampling)
                .put("topRows", topRows);
    }

    String jdbcUrl(String applicationName) {
        return "jdbc:postgresql://" + host + ":" + port + "/" + database + "?ApplicationName=" + applicationName
                + "&sslmode=disable&connectTimeout=3&socketTimeout=" + Math.max(2, (queryTimeoutMs + 999) / 1000 + 1)
                + "&tcpKeepAlive=true";
    }
}
