// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that the pauses of a JVM are read out of its unified log, with the end time of each. */
class JvmLogTest {

    private static final List<String> LINES = List.of(
            "[2026-11-01T12:25:08.726+0000][0.003s] Using G1",
            "[2026-11-01T12:25:08.825+0000][0.103s] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 16M->7M(258M) 1.264ms",
            "[2026-11-01T12:25:08.825+0000][0.103s] Safepoint \"G1CollectForAllocation\", Time since last: 92782542 ns,"
                    + " Reaching safepoint: 750 ns, Cleanup: 59333 ns, At safepoint: 1305708 ns, Total: 1365791 ns",
            "[2026-11-01T12:25:10.000+0000][1.277s][info][gc] GC(7) Pause Full (System.gc()) 120M->40M(512M) 85.500ms",
            "[2026-11-01T12:25:11.000+0000][2.277s][info][safepoint   ] Safepoint \"ICBufferFull\", Time since last: 10 ns,"
                    + " Reaching safepoint: 20 ns, Cleanup: 30 ns, At safepoint: 2700000000 ns, Total: 2700000050 ns");

    @Test
    @DisplayName("collections and safepoints at or above the minimum are kept, shorter ones are not")
    void keepsLongPauses() {
        List<JvmLog.Pause> pauses = JvmLog.parse(LINES, 20);

        assertEquals(2, pauses.size());
        assertEquals("gc", pauses.get(0).kind());
        assertEquals(85.5, pauses.get(0).durationMs(), 1e-9);
        assertTrue(pauses.get(0).label().startsWith("Pause Full"), pauses.get(0).label());
        assertEquals("safepoint", pauses.get(1).kind());
        assertEquals(2700.00005, pauses.get(1).durationMs(), 1e-6);
        assertEquals("ICBufferFull", pauses.get(1).label());
    }

    @Test
    @DisplayName("a minimum of zero keeps every pause and its time is the end of the pause")
    void keepsEverything() {
        List<JvmLog.Pause> pauses = JvmLog.parse(LINES, 0);

        assertEquals(4, pauses.size());
        long end = java.time.OffsetDateTime.parse("2026-11-01T12:25:08.825+00:00")
                .toInstant()
                .toEpochMilli();
        assertEquals(end, pauses.get(0).endMs());
        assertEquals(end - 1.264, pauses.get(0).startMs(), 1e-6);
    }

    @Test
    @DisplayName("a file is read plain or compressed, and a missing file reads as no pauses")
    void readsFiles(@TempDir Path dir) throws IOException {
        Path plain = dir.resolve("a.log");
        Files.write(plain, LINES);
        Path gz = dir.resolve("b.log.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(String.join("\n", LINES).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        assertEquals(2, JvmLog.read(plain, 20).size());
        assertEquals(2, JvmLog.read(gz, 20).size());
        assertTrue(JvmLog.read(dir.resolve("none.log"), 20).isEmpty());
    }
}
