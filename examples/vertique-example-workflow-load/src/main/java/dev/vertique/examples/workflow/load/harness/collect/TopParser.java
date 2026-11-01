// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads one block of the macOS {@code top} output, and the small outputs of {@code ps} and {@code sysctl}. */
final class TopParser {

    /**
     * One process row of {@code top}.
     *
     * @param pid the process id
     * @param user the owner of the process
     * @param command the command, as {@code top} shortens it
     * @param cpuPercent the CPU use in percent of one core over the interval
     * @param memoryMb the resident memory in MiB
     */
    record Row(int pid, String user, String command, double cpuPercent, double memoryMb) {}

    /**
     * One sample of the whole host.
     *
     * @param load1 the one-minute load average
     * @param cpuUser the user CPU in percent of all cores
     * @param cpuSys the system CPU in percent of all cores
     * @param cpuIdle the idle CPU in percent of all cores
     * @param usedMb the physical memory in use
     * @param wiredMb the wired memory
     * @param compressorMb the memory held by the compressor
     * @param unusedMb the unused memory
     * @param swapIns the pages swapped in since boot
     * @param swapOuts the pages swapped out since boot
     * @param rows the busiest processes
     */
    record Sample(
            double load1,
            double cpuUser,
            double cpuSys,
            double cpuIdle,
            double usedMb,
            double wiredMb,
            double compressorMb,
            double unusedMb,
            long swapIns,
            long swapOuts,
            List<Row> rows) {}

    private static final Pattern LOAD = Pattern.compile("Load Avg:\\s*([0-9.]+),");
    private static final Pattern CPU =
            Pattern.compile("CPU usage:\\s*([0-9.]+)% user,\\s*([0-9.]+)% sys,\\s*([0-9.]+)% idle");
    private static final Pattern MEM =
            Pattern.compile("PhysMem:\\s*(\\S+) used \\((\\S+) wired,\\s*(\\S+) compressor\\),\\s*(\\S+) unused");
    private static final Pattern SWAP = Pattern.compile("(\\d+)\\(\\d+\\) swapins,\\s*(\\d+)\\(\\d+\\) swapouts");

    private TopParser() {}

    /**
     * Parses a block from its header up to the line before the next header.
     *
     * @param lines the lines of one block
     * @return the sample, or empty when the block lacks the load, CPU or memory lines
     */
    static Optional<Sample> parse(List<String> lines) {
        Double load = null;
        double[] cpu = null;
        double[] mem = null;
        long[] swap = new long[] {0, 0};
        List<Row> rows = new ArrayList<>();
        boolean inTable = false;
        for (String line : lines) {
            if (inTable) {
                row(line).ifPresent(rows::add);
                continue;
            }
            if (line.startsWith("PID")) {
                inTable = true;
                continue;
            }
            Matcher m = LOAD.matcher(line);
            if (m.find()) {
                load = Double.parseDouble(m.group(1));
                continue;
            }
            m = CPU.matcher(line);
            if (m.find()) {
                cpu = new double[] {
                    Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))
                };
                continue;
            }
            m = MEM.matcher(line);
            if (m.find()) {
                mem = new double[] {mb(m.group(1)), mb(m.group(2)), mb(m.group(3)), mb(m.group(4))};
                continue;
            }
            m = SWAP.matcher(line);
            if (m.find()) {
                swap = new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
            }
        }
        if (load == null || cpu == null || mem == null) {
            return Optional.empty();
        }
        return Optional.of(
                new Sample(load, cpu[0], cpu[1], cpu[2], mem[0], mem[1], mem[2], mem[3], swap[0], swap[1], rows));
    }

    static Optional<Row> row(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 5) {
            return Optional.empty();
        }
        try {
            int pid = Integer.parseInt(tokens[0]);
            double cpu = Double.parseDouble(tokens[tokens.length - 2]);
            double mem = mb(tokens[tokens.length - 1].replaceAll("[+-]$", ""));
            String user = tokens[1];
            String command = String.join(" ", java.util.Arrays.copyOfRange(tokens, 2, tokens.length - 2));
            return Optional.of(new Row(pid, user, command, cpu, mem));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Converts a size such as {@code 4173M} to MiB.
     *
     * @param text a number with an optional unit of {@code B}, {@code K}, {@code M}, {@code G} or {@code T}
     * @return the size in MiB
     */
    static double mb(String text) {
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) {
            return 0;
        }
        char unit = t.charAt(t.length() - 1);
        double scale;
        String number;
        switch (unit) {
            case 'B' -> {
                scale = 1.0 / (1024 * 1024);
                number = t.substring(0, t.length() - 1);
            }
            case 'K' -> {
                scale = 1.0 / 1024;
                number = t.substring(0, t.length() - 1);
            }
            case 'M' -> {
                scale = 1;
                number = t.substring(0, t.length() - 1);
            }
            case 'G' -> {
                scale = 1024;
                number = t.substring(0, t.length() - 1);
            }
            case 'T' -> {
                scale = 1024 * 1024;
                number = t.substring(0, t.length() - 1);
            }
            default -> {
                scale = 1.0 / (1024 * 1024);
                number = t;
            }
        }
        return Double.parseDouble(number) * scale;
    }

    /**
     * Converts the {@code TIME} column of {@code ps}, such as {@code 1:02.34} or {@code 1:02:03.45}, to seconds.
     *
     * @param text the column
     * @return the CPU time in seconds
     */
    static double cpuSeconds(String text) {
        String t = text.trim();
        double days = 0;
        int dash = t.indexOf('-');
        if (dash > 0) {
            days = Double.parseDouble(t.substring(0, dash));
            t = t.substring(dash + 1);
        }
        String[] parts = t.split(":");
        double seconds = 0;
        for (String part : parts) {
            seconds = seconds * 60 + Double.parseDouble(part);
        }
        return days * 86_400 + seconds;
    }

    /**
     * Reads the three lines of {@code sysctl -n vm.swapusage kern.memorystatus_level kern.memorystatus_vm_pressure_level}.
     *
     * @param lines the output lines
     * @return swap used and total in MiB, the free memory percentage, and the pressure level (1 normal, 2 warning,
     *     4 critical); values that are missing are {@code NaN}
     */
    static double[] sysctl(List<String> lines) {
        double used = Double.NaN;
        double total = Double.NaN;
        double free = Double.NaN;
        double level = Double.NaN;
        for (String line : lines) {
            Matcher m = Pattern.compile("total = (\\S+)\\s+used = (\\S+)").matcher(line);
            if (m.find()) {
                total = mb(m.group(1));
                used = mb(m.group(2));
            } else if (line.trim().matches("\\d+")) {
                if (Double.isNaN(free)) {
                    free = Double.parseDouble(line.trim());
                } else {
                    level = Double.parseDouble(line.trim());
                }
            }
        }
        return new double[] {used, total, free, level};
    }
}
