// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.listen;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a node's log for the warnings the product writes when a listener connection cannot be opened or is lost.
 *
 * <p>The log is the second source for a listener failure, next to the readings of the database: the relay never
 * opens its connection again after it lost it, and says so only here.
 */
public final class ListenerLogScan {

    /**
     * A matching log line.
     *
     * @param line the line number, from 1
     * @param kind {@code workflow-open-failed}, {@code workflow-lost}, {@code outbox-open-failed} or
     *     {@code outbox-lost}
     * @param timeMs the time of the line in epoch milliseconds, or 0 when the line has no readable time
     * @param text the line
     */
    public record Finding(int line, String kind, long timeMs, String text) {

        /**
         * Renders the finding.
         *
         * @return the finding as JSON
         */
        public JsonObject toJson() {
            return new JsonObject()
                    .put("line", line)
                    .put("kind", kind)
                    .put("timeMs", timeMs)
                    .put("text", text);
        }
    }

    private ListenerLogScan() {}

    /**
     * Scans a node log.
     *
     * @param log the log file
     * @return the matching lines in order; empty when the file does not exist
     * @throws IOException if the file cannot be read
     */
    public static List<Finding> scan(Path log) throws IOException {
        if (!Files.exists(log)) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        List<String> lines = Files.readAllLines(log, java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String kind = kindOf(lines.get(i));
            if (kind != null) {
                findings.add(new Finding(i + 1, kind, timeOf(lines.get(i)), lines.get(i)));
            }
        }
        return findings;
    }

    static String kindOf(String line) {
        if (line.contains("workflow command listener could not open its connection")) {
            return "workflow-open-failed";
        }
        if (line.contains("workflow command listener lost its connection")) {
            return "workflow-lost";
        }
        if (line.contains("LISTEN/NOTIFY connection failed, falling back to polling")) {
            return "outbox-open-failed";
        }
        if (line.contains("LISTEN connection closed, falling back to polling-only")) {
            return "outbox-lost";
        }
        return null;
    }

    static long timeOf(String line) {
        int space = line.indexOf(' ');
        if (space < 10) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(line.substring(0, space)).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
