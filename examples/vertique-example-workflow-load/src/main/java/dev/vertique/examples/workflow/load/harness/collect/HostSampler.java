// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import dev.vertique.examples.workflow.load.harness.Executables;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Samples the host once per second: load, CPU, memory, swap, memory pressure, the busiest processes and the CPU and
 * resident memory of every harness process.
 *
 * <p>One long-lived {@code top} process streams the whole-host figures; a second thread reads the harness processes
 * with {@code ps} so that per-process CPU comes from the difference of cumulative CPU time and is exact for the
 * second. CPU used by processes that are not part of the harness and not the database VM is summed as outside CPU,
 * which is what a run names when it calls interference independently evidenced.
 *
 * <p>The sampler uses macOS tools. On another system the commands fail, no rows are written, and the run reports
 * the host source as missing.
 */
final class HostSampler {

    private final CollectorSpec spec;
    private final Path dir;
    private final AtomicBoolean stop;
    private Process top;
    private Thread topThread;
    private Thread psThread;
    private CsvOut hostOut;
    private CsvOut procsOut;

    HostSampler(CollectorSpec spec, Path dir, AtomicBoolean stop) {
        this.spec = spec;
        this.dir = dir;
        this.stop = stop;
    }

    void start() throws IOException {
        try {
            hostOut = new CsvOut(
                    dir.resolve("host.csv"),
                    "t_ms,load1,cpu_user,cpu_sys,cpu_idle,mem_used_mb,mem_wired_mb,mem_compressor_mb,mem_unused_mb,"
                            + "swapins,swapouts,swap_used_mb,swap_total_mb,mem_free_pct,pressure_level,system_cpu_pct,"
                            + "vm_process_cpu_pct,harness_cpu_pct,independent_cpu_pct,unattributed_cpu_pct,rows_seen,"
                            + "indep_pid,indep_command,indep_user,indep_cpu_pct");
            procsOut = new CsvOut(dir.resolve("procs.csv"), "t_ms,pid,role,rss_kb,cpu_s");
            top = new ProcessBuilder(
                            Executables.path("top"),
                            "-l",
                            "0",
                            "-s",
                            "1",
                            "-n",
                            Integer.toString(spec.topRows()),
                            "-o",
                            "cpu",
                            "-stats",
                            "pid,user,command,cpu,rsize")
                    .redirectErrorStream(true)
                    .start();
            HarnessPids.addHelper(top.pid(), "top");
            topThread = Thread.ofPlatform().name("host-top").daemon().start(() -> readTop(hostOut));
            psThread = Thread.ofPlatform().name("host-ps").daemon().start(() -> readProcs(procsOut));
        } catch (IOException | RuntimeException e) {
            // Nothing took over the files: close what was opened before the failure.
            closeQuietly(hostOut);
            closeQuietly(procsOut);
            throw e;
        }
    }

    private static void closeQuietly(CsvOut out) {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing after a failed start
            }
        }
    }

    void stop() {
        if (top != null) {
            top.destroy();
        }
        for (Thread t : new Thread[] {topThread, psThread}) {
            if (t != null) {
                try {
                    t.join(3_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void readTop(CsvOut out) {
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(top.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> block = null;
            long blockStart = 0;
            boolean dropped = false;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("Processes:")) {
                    if (block != null) {
                        if (dropped) {
                            List<String> done = block;
                            long at = blockStart;
                            TopParser.parse(done).ifPresent(sample -> write(out, at, sample));
                        } else {
                            // The first block of top reports averages since boot, so it is dropped.
                            dropped = true;
                        }
                    }
                    block = new ArrayList<>();
                    blockStart = System.currentTimeMillis();
                }
                if (block != null) {
                    block.add(line);
                }
            }
        } catch (IOException e) {
            // top ended: the stop request, or the tool is missing
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing at exit
            }
        }
    }

    private static final int CPUS = Runtime.getRuntime().availableProcessors();

    private void write(CsvOut out, long timeMs, TopParser.Sample sample) {
        Set<Integer> harness = HarnessPids.read(spec.pidsFile()).keySet();
        double system = 0;
        double vmProcess = 0;
        double harnessCpu = 0;
        double independent = 0;
        double listed = 0;
        TopParser.Row busiest = null;
        for (TopParser.Row row : sample.rows()) {
            listed += row.cpuPercent();
            if (harness.contains(row.pid()) || HarnessPids.isHelper(row.pid())) {
                harnessCpu += row.cpuPercent();
            } else if (isVm(row.command())) {
                vmProcess += row.cpuPercent();
            } else if (isSystem(row)) {
                system += row.cpuPercent();
            } else {
                independent += row.cpuPercent();
                if (busiest == null || row.cpuPercent() > busiest.cpuPercent()) {
                    busiest = row;
                }
            }
        }
        // CPU the host spent that none of the listed processes accounts for: processes below the list, and the kernel.
        double unattributed = Math.max(0, (100 - sample.cpuIdle()) * CPUS - listed);
        double[] sysctl = readSysctl();
        out.row(
                timeMs,
                sample.load1(),
                sample.cpuUser(),
                sample.cpuSys(),
                sample.cpuIdle(),
                sample.usedMb(),
                sample.wiredMb(),
                sample.compressorMb(),
                sample.unusedMb(),
                sample.swapIns(),
                sample.swapOuts(),
                sysctl[0],
                sysctl[1],
                sysctl[2],
                sysctl[3],
                system,
                vmProcess,
                harnessCpu,
                independent,
                unattributed,
                sample.rows().size(),
                busiest == null ? "" : busiest.pid(),
                busiest == null ? "" : busiest.command(),
                busiest == null ? "" : busiest.user(),
                busiest == null ? "" : busiest.cpuPercent());
    }

    /** The processes of the database VM: the hypervisor service and the Docker application. */
    private static boolean isVm(String command) {
        String c = command.toLowerCase(java.util.Locale.ROOT);
        return c.startsWith("com.apple.virtua") || c.startsWith("com.docker") || c.startsWith("docker");
    }

    /**
     * System activity: the kernel and processes of the system accounts. It is kept as evidence and never counts as
     * interference, because a run's own load makes the kernel busy.
     */
    private static boolean isSystem(TopParser.Row row) {
        return row.user().equals("root")
                || row.user().startsWith("_")
                || row.command().equals("kernel_task");
    }

    private static double[] readSysctl() {
        try {
            Process p = new ProcessBuilder(
                            Executables.path("sysctl"),
                            "-n",
                            "vm.swapusage",
                            "kern.memorystatus_level",
                            "kern.memorystatus_vm_pressure_level")
                    .redirectErrorStream(true)
                    .start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader in =
                    new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    lines.add(line);
                }
            }
            p.waitFor();
            return TopParser.sysctl(lines);
        } catch (IOException e) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
    }

    private void readProcs(CsvOut out) {
        long next = System.currentTimeMillis();
        try {
            while (!stop.get()) {
                long now = System.currentTimeMillis();
                if (now < next) {
                    Thread.sleep(Math.min(100, next - now));
                    continue;
                }
                next += 1_000;
                Map<Integer, String> roles = HarnessPids.read(spec.pidsFile());
                if (roles.isEmpty()) {
                    continue;
                }
                String list = String.join(
                        ",", roles.keySet().stream().map(String::valueOf).toList());
                Process p = new ProcessBuilder(Executables.path("ps"), "-o", "pid=,rss=,time=", "-p", list)
                        .redirectErrorStream(true)
                        .start();
                Map<Integer, String[]> found = new HashMap<>();
                try (BufferedReader in =
                        new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        String[] t = line.trim().split("\\s+");
                        if (t.length == 3) {
                            found.put(Integer.parseInt(t[0]), t);
                        }
                    }
                }
                p.waitFor();
                for (Map.Entry<Integer, String[]> e : found.entrySet()) {
                    String[] t = e.getValue();
                    out.row(now, e.getKey(), roles.get(e.getKey()), t[1], TopParser.cpuSeconds(t[2]));
                }
            }
        } catch (IOException | NumberFormatException e) {
            // ps missing or unreadable: no rows follow, and the run reports the source as missing
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing at exit
            }
        }
    }
}
