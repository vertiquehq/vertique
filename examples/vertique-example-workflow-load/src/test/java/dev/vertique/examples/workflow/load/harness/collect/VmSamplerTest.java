// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the reading of the loop that samples the database VM, and the list of harness processes. */
class VmSamplerTest {

    @Test
    @DisplayName("a block gives busy and total jiffies, memory, swap and the container's CPU and memory")
    void parsesBlock() {
        VmSampler.Block block = VmSampler.parse(List.of(
                "cpu  100 0 50 800 40 0 10 0 0 0",
                "MemTotal:        8123456 kB",
                "MemAvailable:    6650000 kB",
                "SwapTotal:       1048576 kB",
                "SwapFree:       1000000 kB",
                "usage_usec 123456789",
                "170000000"));

        assertEquals(1000.0 - 840.0, block.busyJiffies());
        assertEquals(1000.0, block.totalJiffies());
        assertEquals(8123456.0, block.memTotalKb());
        assertEquals(6650000.0, block.memAvailableKb());
        assertEquals(48576.0, block.swapUsedKb());
        assertEquals(123456789.0, block.containerCpuUsec());
        assertEquals(170000000.0, block.containerMemoryBytes());
    }

    @Test
    @DisplayName("a block without the CPU line is incomplete")
    void incomplete() {
        assertNull(VmSampler.parse(List.of("MemTotal: 1 kB", "MemAvailable: 1 kB")));
    }

    @Test
    @DisplayName("the process list gives the role of each pid and ignores a half-written line")
    void pidList(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("pids.txt");

        HarnessPids.add(file, 101, "node-1");
        HarnessPids.add(file, 102, "driver");
        Files.writeString(file, "10", java.nio.file.StandardOpenOption.APPEND);

        Map<Integer, String> pids = HarnessPids.read(file);
        assertEquals("node-1", pids.get(101));
        assertEquals("driver", pids.get(102));
        assertEquals(2, pids.size(), "a line without a role is ignored");
        assertEquals(0, HarnessPids.read(dir.resolve("none")).size());
    }
}
