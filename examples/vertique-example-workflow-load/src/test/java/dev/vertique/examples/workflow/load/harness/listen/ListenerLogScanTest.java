// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that the warnings the product writes about its listener connections are found in a node's log. */
class ListenerLogScanTest {

    @Test
    @DisplayName("each listener warning is recognized with its kind and time")
    void findsWarnings(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("node.log");
        Files.write(
                log,
                List.of(
                        "2026-11-01T14:25:46.198+02:00 [t] INFO  x - nothing",
                        "2026-11-01T14:25:46.198+02:00 [t] WARN  d.v.w.p.e.CommandNotificationListener - The workflow command listener lost its connection; executors poll until it is open again",
                        "2026-11-01T14:25:47.000+02:00 [t] WARN  x - The workflow command listener could not open its connection; executors poll (java.io.IOException)",
                        "2026-11-01T14:25:48.000+02:00 [t] WARN  d.v.i.postgresql.OutboxRelay - OutboxRelay: LISTEN connection closed, falling back to polling-only nodeId=a",
                        "2026-11-01T14:25:49.000+02:00 [t] WARN  d.v.i.postgresql.OutboxRelay - OutboxRelay: LISTEN/NOTIFY connection failed, falling back to polling. Reason: x"));

        List<ListenerLogScan.Finding> findings = ListenerLogScan.scan(log);

        assertEquals(
                List.of("workflow-lost", "workflow-open-failed", "outbox-lost", "outbox-open-failed"),
                findings.stream().map(ListenerLogScan.Finding::kind).toList());
        assertEquals(2, findings.get(0).line());
        assertTrue(findings.get(0).timeMs() > 0);
        assertEquals(802, findings.get(1).timeMs() - findings.get(0).timeMs());
    }

    @Test
    @DisplayName("a missing log has no finding")
    void missingLog(@TempDir Path dir) throws IOException {
        assertTrue(ListenerLogScan.scan(dir.resolve("none.log")).isEmpty());
    }
}
