// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the reading of the host tools' output: {@code top}, {@code ps} and {@code sysctl}. */
class TopParserTest {

    private static final List<String> BLOCK = List.of(
            "Processes: 891 total, 3 running, 888 sleeping, 4811 threads ",
            "2026/11/01 14:01:25",
            "Load Avg: 0.43, 1.02, 2.08 ",
            "CPU usage: 1.61% user, 2.64% sys, 95.74% idle ",
            "SharedLibs: 1285M resident, 192M data, 169M linkedit.",
            "MemRegions: 0 total, 0B resident, 845M private, 13G shared.",
            "PhysMem: 57G used (4173M wired, 1646M compressor), 6793M unused.",
            "VM: 439T vsize, 57G framework vsize, 2861900(0) swapins, 5732142(0) swapouts.",
            "Networks: packets: 441981980/189G in, 788351405/564G out.",
            "Disks: 101789310/3752G read, 164169993/3697G written.",
            "",
            "PID    USER COMMAND          %CPU MEM   ",
            "1228   mika WallpaperAerials 10.0 21M-  ",
            "44365  root top              6.9  5232K+",
            "613    mika Google Chrome He 5.1  535M  ");

    @Test
    @DisplayName("a block of top gives load, CPU, memory, swap counters and the busiest processes")
    void parsesBlock() {
        TopParser.Sample sample = TopParser.parse(BLOCK).orElseThrow();

        assertEquals(0.43, sample.load1());
        assertEquals(1.61, sample.cpuUser());
        assertEquals(2.64, sample.cpuSys());
        assertEquals(95.74, sample.cpuIdle());
        assertEquals(57 * 1024.0, sample.usedMb());
        assertEquals(4173.0, sample.wiredMb());
        assertEquals(1646.0, sample.compressorMb());
        assertEquals(6793.0, sample.unusedMb());
        assertEquals(2861900L, sample.swapIns());
        assertEquals(5732142L, sample.swapOuts());
        assertEquals(3, sample.rows().size());
        assertEquals("Google Chrome He", sample.rows().get(2).command());
        assertEquals("root", sample.rows().get(1).user());
        assertEquals("mika", sample.rows().get(2).user());
        assertEquals(613, sample.rows().get(2).pid());
        assertEquals(5.1, sample.rows().get(2).cpuPercent());
        assertEquals(21.0, sample.rows().get(0).memoryMb());
        assertEquals(5232.0 / 1024, sample.rows().get(1).memoryMb(), 1e-9);
    }

    @Test
    @DisplayName("a block without the CPU line is not a sample")
    void incompleteBlock() {
        Optional<TopParser.Sample> sample = TopParser.parse(
                BLOCK.stream().filter(l -> !l.startsWith("CPU usage")).toList());

        assertTrue(sample.isEmpty());
    }

    @Test
    @DisplayName("sizes convert to MiB")
    void sizes() {
        assertEquals(1.0, TopParser.mb("1M"));
        assertEquals(1024.0, TopParser.mb("1G"));
        assertEquals(1.0 / 1024, TopParser.mb("1K"));
        assertEquals(1024.0 * 1024, TopParser.mb("1T"));
        assertEquals(1.0, TopParser.mb("1048576B"));
        assertEquals(2038.0, TopParser.mb("2038.00M"));
    }

    @Test
    @DisplayName("the CPU time column of ps converts to seconds")
    void cpuTime() {
        assertEquals(0.01, TopParser.cpuSeconds("0:00.01"), 1e-9);
        assertEquals(754.34, TopParser.cpuSeconds("12:34.34"), 1e-9);
        assertEquals(3723.45, TopParser.cpuSeconds("1:02:03.45"), 1e-9);
        assertEquals(86_400 + 60.5, TopParser.cpuSeconds("1-00:01:00.50"), 1e-9);
    }

    @Test
    @DisplayName("sysctl gives swap in use, free memory percentage and pressure level")
    void sysctl() {
        double[] values =
                TopParser.sysctl(List.of("total = 3072.00M  used = 2038.00M  free = 1034.00M  (encrypted)", "90", "1"));

        assertEquals(2038.0, values[0]);
        assertEquals(3072.0, values[1]);
        assertEquals(90.0, values[2]);
        assertEquals(1.0, values[3]);
    }
}
