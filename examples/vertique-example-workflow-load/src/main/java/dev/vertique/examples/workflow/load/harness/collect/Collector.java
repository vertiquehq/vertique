// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The collector: a separate process that samples the database, the host and the database VM on one clock until it is
 * told to stop.
 *
 * <p>Every source writes its own CSV file with a host timestamp in epoch milliseconds, so a dip in one source can be
 * read across the others:
 *
 * <ul>
 *   <li>{@code seconds.csv}: queue depths, database counters, connections by application name and the collector's own
 *       tick lateness, from SQL;</li>
 *   <li>{@code listeners.csv}: the dedicated listeners as the database shows them;</li>
 *   <li>{@code lockwatch.csv} and {@code lockwatch-summary.json}: sessions that waited on object {@code 1262:0}, when
 *       the watch is on;</li>
 *   <li>{@code host.csv} and {@code procs.csv}: host load, CPU, memory, swap and pressure, and the CPU and resident
 *       memory of every harness process;</li>
 *   <li>{@code vm.csv}: the CPU and memory of the database VM and of the database container.</li>
 * </ul>
 *
 * <p>The collector writes {@code ready} once all its sources run. It stops when the stop file appears and exits with
 * 0 after flushing.
 */
public final class Collector {

    private Collector() {}

    /**
     * Runs the collector.
     *
     * @param args {@code --spec <file>}
     * @throws Exception if the collector cannot start
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--spec")) {
            System.err.println("usage: --spec <collector-spec.json>");
            System.exit(2);
        }
        CollectorSpec spec = CollectorSpec.read(new JsonObject(Files.readString(Path.of(args[1]))));
        Files.createDirectories(spec.outDir());
        AtomicBoolean stop = new AtomicBoolean();
        SqlSampler sql = new SqlSampler(spec, spec.outDir(), stop);
        Thread sqlThread = Thread.ofPlatform().name("sql-sampler").start(sql);
        Thread lockThread = null;
        if (spec.lockWatch()) {
            lockThread = Thread.ofPlatform().name("lock-watch").start(new LockWatch(spec, spec.outDir(), stop));
        }
        HostSampler host = null;
        if (spec.hostSampling()) {
            host = new HostSampler(spec, spec.outDir(), stop);
            host.start();
        }
        VmSampler vm = null;
        if (spec.vmSampling() && !spec.container().isBlank()) {
            vm = new VmSampler(spec, spec.outDir(), stop);
            vm.start();
        }
        Files.writeString(spec.outDir().resolve("ready"), Long.toString(System.currentTimeMillis()));
        waitForStop(spec.stopFile());
        stop.set(true);
        join(sqlThread);
        join(lockThread);
        if (host != null) {
            host.stop();
        }
        if (vm != null) {
            vm.stop();
        }
        Files.writeString(spec.outDir().resolve("stopped"), Long.toString(System.currentTimeMillis()));
        System.exit(0);
    }

    private static void waitForStop(Path stopFile) throws InterruptedException {
        while (!Files.exists(stopFile)) {
            Thread.sleep(100);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(10_000);
        }
    }

    /**
     * Checks that a directory holds what a finished collector leaves.
     *
     * @param dir the collector's output directory
     * @return {@code true} when the collector wrote its stopped marker
     * @throws IOException if the directory cannot be read
     */
    public static boolean finished(Path dir) throws IOException {
        return Files.exists(dir.resolve("stopped"));
    }
}
