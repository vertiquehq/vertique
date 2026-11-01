// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Samples the VM that runs the database, and the database container, once per second.
 *
 * <p>One long-lived {@code docker exec} runs a small loop in the container. The container sees the VM's
 * {@code /proc/stat} and {@code /proc/meminfo}, so the VM's CPU, memory and swap come from them, and its own CPU time
 * and memory from the cgroup files. CPU figures are the difference of cumulative counters between two blocks. The
 * host timestamps each block when it arrives; a block that arrives late shows as a longer interval between rows.
 *
 * <p>When the container stalls or stops, the stream stops and the rows after it show the gap.
 */
final class VmSampler {

    private static final String LOOP = "while :; do echo @@; head -1 /proc/stat;"
            + " grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo;"
            + " grep usage_usec /sys/fs/cgroup/cpu.stat; cat /sys/fs/cgroup/memory.current; sleep 1; done";

    /**
     * One block of the loop's output, as cumulative counters.
     *
     * @param busyJiffies the jiffies the VM's CPUs were busy
     * @param totalJiffies all jiffies
     * @param memTotalKb the VM memory
     * @param memAvailableKb the VM memory available
     * @param swapUsedKb the VM swap in use
     * @param containerCpuUsec the container's CPU time
     * @param containerMemoryBytes the container's memory
     */
    record Block(
            double busyJiffies,
            double totalJiffies,
            double memTotalKb,
            double memAvailableKb,
            double swapUsedKb,
            double containerCpuUsec,
            double containerMemoryBytes) {}

    private final CollectorSpec spec;
    private final Path dir;
    private final AtomicBoolean stop;
    private Process process;
    private Thread thread;

    VmSampler(CollectorSpec spec, Path dir, AtomicBoolean stop) {
        this.spec = spec;
        this.dir = dir;
        this.stop = stop;
    }

    void start() throws IOException {
        CsvOut out = new CsvOut(
                dir.resolve("vm.csv"),
                "t_ms,interval_ms,vm_cpu_busy_pct,vm_mem_total_mb,vm_mem_available_mb,vm_swap_used_mb,"
                        + "container_cpu_pct_of_one_core,container_mem_mb");
        process = new ProcessBuilder("docker", "exec", spec.container(), "sh", "-c", LOOP)
                .redirectErrorStream(true)
                .start();
        HarnessPids.addHelper(process.pid(), "docker-exec");
        thread = Thread.ofPlatform().name("vm-sampler").daemon().start(() -> read(out));
    }

    void stop() {
        if (process != null) {
            process.destroy();
        }
        if (thread != null) {
            try {
                thread.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void read(CsvOut out) {
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> block = null;
            long blockStart = 0;
            Block previous = null;
            long previousAt = 0;
            String line;
            while ((line = in.readLine()) != null && !stop.get()) {
                if (line.equals("@@")) {
                    if (block != null) {
                        Block parsed = parse(block);
                        if (parsed != null) {
                            if (previous != null) {
                                write(out, blockStart, blockStart - previousAt, previous, parsed);
                            }
                            previous = parsed;
                            previousAt = blockStart;
                        }
                    }
                    block = new ArrayList<>();
                    blockStart = System.currentTimeMillis();
                } else if (block != null) {
                    block.add(line);
                }
            }
        } catch (IOException e) {
            // the stream ended with the container or the stop request
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing at exit
            }
        }
    }

    private static void write(CsvOut out, long at, long intervalMs, Block a, Block b) {
        double total = b.totalJiffies() - a.totalJiffies();
        double busy = total > 0 ? (b.busyJiffies() - a.busyJiffies()) / total * 100 : Double.NaN;
        double cpu =
                intervalMs > 0 ? (b.containerCpuUsec() - a.containerCpuUsec()) / 1000.0 / intervalMs * 100 : Double.NaN;
        out.row(
                at,
                intervalMs,
                busy,
                b.memTotalKb() / 1024,
                b.memAvailableKb() / 1024,
                b.swapUsedKb() / 1024,
                cpu,
                b.containerMemoryBytes() / 1024 / 1024);
    }

    /**
     * Parses one block of the loop's output.
     *
     * @param lines the lines between two markers
     * @return the block, or {@code null} when it is incomplete
     */
    static Block parse(List<String> lines) {
        double busy = Double.NaN;
        double total = Double.NaN;
        double memTotal = Double.NaN;
        double memAvailable = Double.NaN;
        double swapTotal = 0;
        double swapFree = 0;
        double cpuUsec = Double.NaN;
        double memory = Double.NaN;
        try {
            for (String line : lines) {
                String[] t = line.trim().split("\\s+");
                if (line.startsWith("cpu ")) {
                    double sum = 0;
                    double idle = 0;
                    for (int i = 1; i < t.length && i <= 8; i++) {
                        double v = Double.parseDouble(t[i]);
                        sum += v;
                        if (i == 4 || i == 5) {
                            idle += v;
                        }
                    }
                    total = sum;
                    busy = sum - idle;
                } else if (line.startsWith("MemTotal:")) {
                    memTotal = Double.parseDouble(t[1]);
                } else if (line.startsWith("MemAvailable:")) {
                    memAvailable = Double.parseDouble(t[1]);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = Double.parseDouble(t[1]);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = Double.parseDouble(t[1]);
                } else if (line.startsWith("usage_usec")) {
                    cpuUsec = Double.parseDouble(t[1]);
                } else if (t.length == 1 && line.trim().matches("\\d+")) {
                    memory = Double.parseDouble(t[0]);
                }
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
        if (Double.isNaN(busy) || Double.isNaN(memTotal) || Double.isNaN(memAvailable)) {
            return null;
        }
        return new Block(busy, total, memTotal, memAvailable, swapTotal - swapFree, cpuUsec, memory);
    }
}
