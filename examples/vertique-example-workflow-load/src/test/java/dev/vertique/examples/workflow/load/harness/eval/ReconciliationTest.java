// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the reconciliation of the driver's account with the durable starts, including lost responses. */
class ReconciliationTest {

    private static final String HEADER =
            "idx,phase,segment,origin,node,scheduled_ms,started_ms,late_ms,answered_ms,response_ms,outcome,status,detail,wired_ms";

    private static JsonObject reconcile(Path dir, List<String> arrivals, List<String> durable) throws IOException {
        Files.writeString(dir.resolve("arrivals.csv"), HEADER + "\n" + String.join("\n", arrivals) + "\n");
        try (var out = new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(dir.resolve("d.gz"))), StandardCharsets.UTF_8)) {
            for (String line : durable) {
                out.write(line + "\n");
            }
        }
        return Reconciliation.compute(dir.resolve("arrivals.csv"), dir.resolve("d.gz"), "r");
    }

    private static String row(int idx, String phase, String origin, String outcome) {
        return idx + "," + phase + ",w," + origin + ",1,0,0,0,0,0," + outcome + ",200,,";
    }

    @Test
    @DisplayName(
            "acknowledged starts are durable, a refusal replayed and accepted counts once, and a lost response is told from a lost write")
    void classes(@TempDir Path dir) throws IOException {
        JsonObject r = reconcile(
                dir,
                List.of(
                        row(0, "main", "", "ACCEPTED"),
                        row(1, "main", "", "REFUSED"),
                        row(2, "main", "", "FAILED"),
                        row(3, "main", "", "UNANSWERED"),
                        row(4, "main", "", "FAILED"),
                        row(5, "main", "", "NOT_SUBMITTED"),
                        row(0, "replay", "1", "ACCEPTED")),
                List.of("r-0,COMPLETED", "r-1,COMPLETED", "r-2,WAITING", "r-3,COMPLETED"));

        assertEquals(6, r.getInteger("keys"));
        assertEquals(2, r.getInteger("acknowledgedAndDurable"), "keys 0 and 1 (refused, then replayed and accepted)");
        assertEquals(0, r.getInteger("acknowledgedNotDurable"));
        assertEquals(
                2,
                r.getInteger("noAnswerButDurable"),
                "keys 2 and 3: the node recorded them and the driver never heard");
        assertEquals(1, r.getInteger("noAnswerAndNotDurable"), "key 4");
        assertEquals(1, r.getInteger("notSubmitted"));
        assertEquals(3L, r.getJsonObject("finalOutcomeOfDurableStarts").getLong("COMPLETED"));
        assertEquals(1L, r.getJsonObject("finalOutcomeOfDurableStarts").getLong("WAITING"));
        assertTrue(r.getBoolean("consistent"));
    }

    @Test
    @DisplayName(
            "an acknowledged start that is not durable, and a durable start the driver never sent, are inconsistencies")
    void inconsistencies(@TempDir Path dir) throws IOException {
        JsonObject r = reconcile(
                dir,
                List.of(row(0, "main", "", "ACCEPTED"), row(1, "main", "", "REFUSED")),
                List.of("r-1,WAITING", "r-9,WAITING"));

        assertEquals(1, r.getInteger("acknowledgedNotDurable"));
        assertEquals(1, r.getInteger("refusedOnlyButDurable"));
        assertEquals(1, r.getInteger("durableNotSentByThisDriver"));
        assertFalse(r.getBoolean("consistent"));
    }
}
