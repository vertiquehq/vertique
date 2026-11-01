// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads the pauses out of a JVM's {@code -Xlog:gc,safepoint} output.
 *
 * <p>Both kinds of line are kept as separate events, because a collection pause happens inside a safepoint and the
 * two overlap; a reader must not add them. The time of a pause is the time its log line was written, which is the end
 * of the pause.
 */
public final class JvmLog {

    /**
     * A pause of the JVM.
     *
     * @param endMs the end of the pause in epoch milliseconds
     * @param durationMs the length of the pause in milliseconds
     * @param kind {@code gc} or {@code safepoint}
     * @param label what paused: the collection or the safepoint operation
     */
    public record Pause(long endMs, double durationMs, String kind, String label) {

        /**
         * Returns the start of the pause.
         *
         * @return the start in epoch milliseconds
         */
        public double startMs() {
            return endMs - durationMs;
        }

        /**
         * Renders the pause.
         *
         * @return the pause as JSON
         */
        public JsonObject toJson() {
            return new JsonObject()
                    .put("endMs", endMs)
                    .put("durationMs", durationMs)
                    .put("kind", kind)
                    .put("label", label);
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final Pattern GC =
            Pattern.compile("^\\[([^\\]]+)\\](?:\\[[^\\]]*\\])*\\s*GC\\(\\d+\\)\\s+(Pause .*?)\\s+([0-9.]+)ms\\s*$");
    private static final Pattern SAFEPOINT =
            Pattern.compile("^\\[([^\\]]+)\\](?:\\[[^\\]]*\\])*\\s*Safepoint \"([^\"]+)\".*Total: (\\d+) ns");

    private JvmLog() {}

    /**
     * Reads a log file, plain or gzip-compressed.
     *
     * @param file the log
     * @param minMs the shortest pause to keep, in milliseconds
     * @return the pauses in file order; empty when the file does not exist
     * @throws IOException if the file cannot be read
     */
    public static List<Pause> read(Path file, double minMs) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        if (file.getFileName().toString().endsWith(".gz")) {
            try (var in = new GZIPInputStream(Files.newInputStream(file))) {
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .toList();
            }
        } else {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        return parse(lines, minMs);
    }

    /**
     * Parses log lines.
     *
     * @param lines the lines
     * @param minMs the shortest pause to keep, in milliseconds
     * @return the pauses in order
     */
    public static List<Pause> parse(List<String> lines, double minMs) {
        List<Pause> pauses = new ArrayList<>();
        for (String line : lines) {
            Matcher gc = GC.matcher(line);
            if (gc.matches()) {
                double ms = Double.parseDouble(gc.group(3));
                if (ms >= minMs) {
                    pauses.add(new Pause(time(gc.group(1)), ms, "gc", gc.group(2)));
                }
                continue;
            }
            Matcher sp = SAFEPOINT.matcher(line);
            if (sp.find()) {
                double ms = Long.parseLong(sp.group(3)) / 1e6;
                if (ms >= minMs) {
                    pauses.add(new Pause(time(sp.group(1)), ms, "safepoint", sp.group(2)));
                }
            }
        }
        return pauses;
    }

    private static long time(String text) {
        return OffsetDateTime.parse(text, TIME).toInstant().toEpochMilli();
    }
}
