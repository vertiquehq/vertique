// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import dev.vertique.examples.workflow.load.harness.listen.ListenerEvent;
import dev.vertique.examples.workflow.load.harness.spec.Thresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Collects what every source recorded around a stall, on the one clock, and names what coincided with it.
 *
 * <p>The slice holds the driver, the collector's heartbeat and SQL readings, the host, the database VM, every harness
 * process and the JVM pauses of every JVM that overlap the waiting interval or sit two seconds on either side. The list
 * of coincident conditions is evidence of what happened at the same time. It is never a cause: the cause of every
 * stall is reported as unresolved, whether or not something coincided, a causal conclusion that rests on a stall is
 * inconclusive, and nothing here presumes a host problem or any other explanation.
 *
 * <p>A latency excursion has two times. Its commands were recorded earlier, at the origin, and their wait ended later,
 * when they settled. Under a backlog the wait happens in between, so the slice covers the whole waiting interval from the
 * earliest origin to the end of the last wait, and both ends are reported.
 */
public final class Evidence {

    private static final long MARGIN_MS = 2_000;
    private static final long FLAG_SLACK_MS = 1_000;

    /** The aligned data of a run that evidence is cut from. */
    public static final class Sources {
        final CsvTable driverSeconds;
        final long driverStartMs;
        final CsvTable ticks;
        final CsvTable heartbeat;
        final CsvTable host;
        final CsvTable vm;
        final CsvTable procs;
        final CsvTable lockwatch;
        final Map<String, List<JvmLog.Pause>> pauses;
        final List<ListenerEvent> listenerEvents;

        /**
         * Creates the sources.
         *
         * @param driverSeconds the driver's per-second table
         * @param driverStartMs the host time of the driver's schedule origin
         * @param ticks the collector's SQL table
         * @param heartbeat the collector's heartbeat table
         * @param host the host table
         * @param vm the VM table
         * @param procs the process table
         * @param lockwatch the lock watch rows
         * @param pauses the JVM pauses by source
         * @param listenerEvents the listener events
         */
        public Sources(
                CsvTable driverSeconds,
                long driverStartMs,
                CsvTable ticks,
                CsvTable heartbeat,
                CsvTable host,
                CsvTable vm,
                CsvTable procs,
                CsvTable lockwatch,
                Map<String, List<JvmLog.Pause>> pauses,
                List<ListenerEvent> listenerEvents) {
            this.driverSeconds = driverSeconds;
            this.driverStartMs = driverStartMs;
            this.ticks = ticks;
            this.heartbeat = heartbeat;
            this.host = host;
            this.vm = vm;
            this.procs = procs;
            this.lockwatch = lockwatch;
            this.pauses = pauses;
            this.listenerEvents = listenerEvents;
        }
    }

    private Evidence() {}

    private static Double finite(double value) {
        return Double.isNaN(value) ? null : value;
    }

    /**
     * Cuts the evidence around a stretch of time.
     *
     * @param kind {@code dip} or {@code excursion}
     * @param fromMs the start of the stretch in epoch milliseconds: for a dip the first low second, for an excursion the
     *     first second in which a slow wait ended
     * @param toMs the end of the stretch in epoch milliseconds
     * @param originMs the earliest moment a command of the stretch was recorded; the same as {@code fromMs} for a dip
     * @param s the sources
     * @param limits the thresholds
     * @return the stretch with its slices and the conditions that coincided with it
     */
    public static JsonObject collect(String kind, long fromMs, long toMs, long originMs, Sources s, Thresholds limits) {
        long waitFrom = Math.min(fromMs, originMs);
        long a = waitFrom - MARGIN_MS;
        long b = toMs + MARGIN_MS;
        long flagFrom = waitFrom - FLAG_SLACK_MS;
        long flagTo = toMs + FLAG_SLACK_MS;
        TreeSet<String> flags = new TreeSet<>();

        JsonArray driver = new JsonArray();
        for (int i = 0; i < s.driverSeconds.size(); i++) {
            long t = s.driverStartMs + (long) (s.driverSeconds.number(i, "sec") * 1000);
            if (t >= a && t < b) {
                driver.add(new JsonObject()
                        .put("tMs", t)
                        .put("scheduled", finite(s.driverSeconds.number(i, "scheduled")))
                        .put("started", finite(s.driverSeconds.number(i, "started")))
                        .put("accepted", finite(s.driverSeconds.number(i, "accepted")))
                        .put("refused", finite(s.driverSeconds.number(i, "refused")))
                        .put("failed", finite(s.driverSeconds.number(i, "failed")))
                        .put("maxLateMs", finite(s.driverSeconds.number(i, "max_late_ms")))
                        .put("inFlight", finite(s.driverSeconds.number(i, "in_flight"))));
                if (t >= flagFrom && t < flagTo && s.driverSeconds.number(i, "max_late_ms") > limits.lateArrivalMs()) {
                    flags.add("generator-late");
                }
            }
        }

        JsonArray collector = new JsonArray();
        for (int i = 0; i < s.ticks.size(); i++) {
            double t = s.ticks.number(i, "t_ms");
            if (t >= a && t < b) {
                boolean ok = "true".equals(s.ticks.text(i, "db_ok"));
                collector.add(new JsonObject()
                        .put("tMs", (long) t)
                        .put("lateMs", finite(s.ticks.number(i, "late_ms")))
                        .put("dbOk", ok)
                        .put("error", s.ticks.text(i, "error"))
                        .put("due", finite(s.ticks.number(i, "due")))
                        .put("outboxPending", finite(s.ticks.number(i, "outbox_pending")))
                        .put("active", finite(s.ticks.number(i, "active")))
                        .put("idleInTransaction", finite(s.ticks.number(i, "idle_in_transaction")))
                        .put("lockWaiting", finite(s.ticks.number(i, "lock_waiting")))
                        .put("xactCommit", finite(s.ticks.number(i, "xact_commit")))
                        .put("deadlocks", finite(s.ticks.number(i, "deadlocks")))
                        .put("backends", finite(s.ticks.number(i, "backends"))));
                if (t >= flagFrom && t < flagTo) {
                    if (!ok) {
                        flags.add("database-unanswered");
                    }
                    if (s.ticks.number(i, "late_ms") > 500) {
                        flags.add("collector-late");
                    }
                    if (s.ticks.number(i, "lock_waiting") > 0) {
                        flags.add("sessions-waiting-on-locks");
                    }
                }
            }
        }

        JsonArray heartbeat = new JsonArray();
        for (int i = 0; i < s.heartbeat.size(); i++) {
            double t = s.heartbeat.number(i, "t_ms");
            if (t >= a && t < b) {
                heartbeat.add(new JsonObject()
                        .put("tMs", (long) t)
                        .put("lateMs", finite(s.heartbeat.number(i, "late_ms")))
                        .put("workerState", s.heartbeat.text(i, "worker_state"))
                        .put("workerBusyMs", finite(s.heartbeat.number(i, "worker_busy_ms")))
                        .put("sinceLastSuccessMs", finite(s.heartbeat.number(i, "since_last_success_ms")))
                        .put("consecutiveFailures", finite(s.heartbeat.number(i, "consecutive_failures"))));
                if (t >= flagFrom && t < flagTo) {
                    if (s.heartbeat.number(i, "since_last_success_ms") > 3_000) {
                        flags.add("database-samples-unavailable");
                    }
                    if (s.heartbeat.number(i, "late_ms") > 500) {
                        flags.add("collector-heartbeat-late");
                    }
                }
            }
        }

        JsonArray host = new JsonArray();
        int hostRows = 0;
        int hostBusy = 0;
        double swapFirst = Double.NaN;
        double swapLast = Double.NaN;
        for (int i = 0; i < s.host.size(); i++) {
            double t = s.host.number(i, "t_ms");
            if (t >= a && t < b) {
                host.add(new JsonObject()
                        .put("tMs", (long) t)
                        .put("load1", s.host.number(i, "load1"))
                        .put("cpuIdle", finite(s.host.number(i, "cpu_idle")))
                        .put("memUnusedMb", finite(s.host.number(i, "mem_unused_mb")))
                        .put("swapouts", finite(s.host.number(i, "swapouts")))
                        .put("swapUsedMb", finite(s.host.number(i, "swap_used_mb")))
                        .put("pressureLevel", finite(s.host.number(i, "pressure_level")))
                        .put("systemCpuPercent", finite(s.host.number(i, "system_cpu_pct")))
                        .put("independentCpuPercent", finite(s.host.number(i, "independent_cpu_pct")))
                        .put("unattributedCpuPercent", finite(s.host.number(i, "unattributed_cpu_pct")))
                        .put(
                                "busiestIndependent",
                                s.host.text(i, "indep_command") + "[" + s.host.text(i, "indep_pid") + "] "
                                        + s.host.text(i, "indep_user") + " " + s.host.text(i, "indep_cpu_pct") + "%"));
                if (t >= flagFrom && t < flagTo) {
                    hostRows++;
                    if (100 - s.host.number(i, "cpu_idle") >= limits.cpuSaturationPercent()) {
                        hostBusy++;
                    }
                    if (s.host.number(i, "load1") > limits.hostLoadWarn()) {
                        flags.add("host-load-above-" + (int) limits.hostLoadWarn());
                    }
                    if (s.host.number(i, "pressure_level") >= 2) {
                        flags.add("host-memory-pressure");
                    }
                    if (s.host.number(i, "indep_cpu_pct") >= limits.outsideCpuPercent()) {
                        flags.add("independent-process:" + s.host.text(i, "indep_command") + "["
                                + s.host.text(i, "indep_pid") + "]");
                    }
                    if (s.host.number(i, "system_cpu_pct") >= 100) {
                        flags.add("system-cpu-high");
                    }
                    if (s.host.number(i, "unattributed_cpu_pct") >= 200) {
                        flags.add("unattributed-cpu-high");
                    }
                    double swap = s.host.number(i, "swapouts");
                    if (!Double.isNaN(swap)) {
                        swapFirst = Double.isNaN(swapFirst) ? swap : swapFirst;
                        swapLast = swap;
                    }
                }
            }
        }
        if (hostRows > 0 && hostBusy * 2 >= hostRows) {
            flags.add("host-cpu-saturated");
        }
        if (!Double.isNaN(swapFirst) && swapLast > swapFirst) {
            flags.add("host-swapping");
        }

        JsonArray vm = new JsonArray();
        int vmRows = 0;
        int vmBusy = 0;
        for (int i = 0; i < s.vm.size(); i++) {
            double t = s.vm.number(i, "t_ms");
            if (t >= a && t < b) {
                vm.add(new JsonObject()
                        .put("tMs", (long) t)
                        .put("intervalMs", finite(s.vm.number(i, "interval_ms")))
                        .put("vmCpuBusyPercent", finite(s.vm.number(i, "vm_cpu_busy_pct")))
                        .put("vmMemAvailableMb", finite(s.vm.number(i, "vm_mem_available_mb")))
                        .put("vmSwapUsedMb", finite(s.vm.number(i, "vm_swap_used_mb")))
                        .put("containerCpuPercentOfOneCore", finite(s.vm.number(i, "container_cpu_pct_of_one_core")))
                        .put("containerMemMb", finite(s.vm.number(i, "container_mem_mb"))));
                if (t >= flagFrom && t < flagTo) {
                    vmRows++;
                    if (s.vm.number(i, "vm_cpu_busy_pct") >= limits.cpuSaturationPercent()) {
                        vmBusy++;
                    }
                    if (s.vm.number(i, "interval_ms") > 3_000) {
                        flags.add("vm-sampler-gap");
                    }
                }
            }
        }
        if (vmRows > 0 && vmBusy * 2 >= vmRows) {
            flags.add("vm-cpu-saturated");
        }

        JsonObject processes = processRates(s.procs, a, b);

        JsonObject pauses = new JsonObject();
        s.pauses.forEach((source, list) -> {
            JsonArray array = new JsonArray();
            for (JvmLog.Pause p : list) {
                if (p.endMs() >= a && p.startMs() < b) {
                    array.add(p.toJson());
                    if (p.endMs() >= flagFrom
                            && p.startMs() < flagTo
                            && p.durationMs() >= limits.pauseAttributionMs()) {
                        flags.add("jvm-pause:" + source);
                    }
                }
            }
            if (!array.isEmpty()) {
                pauses.put(source, array);
            }
        });

        JsonObject lockWaiters = new JsonObject();
        Map<String, Integer> byApp = new TreeMap<>();
        for (int i = 0; i < s.lockwatch.size(); i++) {
            double t = s.lockwatch.number(i, "t_ms");
            if (t >= a && t < b) {
                byApp.merge(s.lockwatch.text(i, "application"), 1, Integer::sum);
                if (t >= flagFrom && t < flagTo) {
                    flags.add("waiters-on-1262:0");
                }
            }
        }
        byApp.forEach(lockWaiters::put);

        JsonArray listeners = new JsonArray();
        for (ListenerEvent e : s.listenerEvents) {
            if (e.timeMs() >= a && e.timeMs() < b) {
                listeners.add(e.toJson());
                if (e.timeMs() >= flagFrom && e.timeMs() < flagTo) {
                    flags.add("listener-" + e.type().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }

        return new JsonObject()
                .put("kind", kind)
                .put("cause", "unresolved")
                .put("fromMs", fromMs)
                .put("toMs", toMs)
                .put("originFromMs", originMs)
                .put("waitingIntervalMs", new JsonArray().add(waitFrom).add(toMs))
                .put("coincidentWithinMs", FLAG_SLACK_MS)
                .put("coincident", new JsonArray(new ArrayList<Object>(flags)))
                .put("noCoincidentCondition", flags.isEmpty())
                .put(
                        "note",
                        "coincident conditions happened at the same time; they are not shown to be the cause, and the cause stays unresolved")
                .put("driver", driver)
                .put("collector", collector)
                .put("heartbeat", heartbeat)
                .put("host", host)
                .put("vm", vm)
                .put("processes", processes)
                .put("jvmPauses", pauses)
                .put("lockWaitersByApplication", lockWaiters)
                .put("listenerEvents", listeners);
    }

    private static JsonObject processRates(CsvTable procs, long a, long b) {
        Map<Integer, List<double[]>> byPid = new HashMap<>();
        Map<Integer, String> roles = new HashMap<>();
        for (int i = 0; i < procs.size(); i++) {
            double t = procs.number(i, "t_ms");
            if (t >= a - 1_500 && t < b) {
                int pid = (int) procs.number(i, "pid");
                byPid.computeIfAbsent(pid, k -> new ArrayList<>())
                        .add(new double[] {t, procs.number(i, "cpu_s"), procs.number(i, "rss_kb")});
                roles.put(pid, procs.text(i, "role"));
            }
        }
        JsonObject out = new JsonObject();
        byPid.forEach((pid, samples) -> {
            JsonArray array = new JsonArray();
            for (int i = 1; i < samples.size(); i++) {
                double[] p = samples.get(i - 1);
                double[] q = samples.get(i);
                if (q[0] >= a) {
                    double dt = (q[0] - p[0]) / 1000.0;
                    array.add(new JsonObject()
                            .put("tMs", (long) q[0])
                            .put("cpuPercentOfOneCore", dt > 0 ? (q[1] - p[1]) / dt * 100 : null)
                            .put("rssMb", q[2] / 1024));
                }
            }
            out.put(roles.get(pid) + "#" + pid, array);
        });
        return out;
    }
}
