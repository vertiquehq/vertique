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
    /** What follows the decorators of a collection line: {@code GC(n)} and the start of the pause's label. */
    private static final Pattern GC_START = Pattern.compile("\\s*GC\\(\\d+\\)\\s+(?=Pause )");

    /** What follows the decorators of a safepoint line. */
    private static final Pattern SAFEPOINT = Pattern.compile("\\s*Safepoint \"([^\"]+)\".*Total: (\\d+) ns");

    private static final String PAUSE_LABEL = "Pause ";

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
            int firstClose = line.startsWith("[") ? line.indexOf(']', 1) : -1;
            if (firstClose < 2) {
                continue;
            }
            String stamp = line.substring(1, firstClose);
            int rest = afterDecorators(line, firstClose + 1);
            Matcher start = GC_START.matcher(line).region(rest, line.length());
            if (start.lookingAt()) {
                Collection gc = collection(line, start.end());
                if (gc != null) {
                    if (gc.ms() >= minMs) {
                        pauses.add(new Pause(time(stamp), gc.ms(), "gc", gc.label()));
                    }
                    continue;
                }
            }
            Matcher sp = SAFEPOINT.matcher(line).region(rest, line.length());
            if (sp.lookingAt()) {
                double ms = Long.parseLong(sp.group(2)) / 1e6;
                if (ms >= minMs) {
                    pauses.add(new Pause(time(stamp), ms, "safepoint", sp.group(1)));
                }
            }
        }
        return pauses;
    }

    /** The label and the duration of a collection line. */
    private record Collection(String label, double ms) {}

    /**
     * Reads {@code Pause <label> <number>ms} from the label start to the end of the line, with white space allowed
     * before the end. The label is the shortest text that leaves white space and a number before the {@code ms}; it is
     * found from the end of the line, so that the work stays linear in the length of the line.
     */
    private static Collection collection(String line, int labelStart) {
        int minLabelEnd = labelStart + PAUSE_LABEL.length();
        int end = line.length();
        while (end > labelStart && isSpace(line.charAt(end - 1))) {
            end--;
        }
        int numberEnd = end - 2;
        if (numberEnd < minLabelEnd || !line.startsWith("ms", numberEnd)) {
            return null;
        }
        int numberStart = numberEnd;
        while (numberStart > minLabelEnd && isNumberChar(line.charAt(numberStart - 1))) {
            numberStart--;
        }
        int labelEnd = numberStart;
        while (labelEnd > minLabelEnd && isSpace(line.charAt(labelEnd - 1))) {
            labelEnd--;
        }
        if (numberStart == numberEnd || labelEnd == numberStart) {
            return null;
        }
        String label = line.substring(labelStart, labelEnd);
        if (label.chars().anyMatch(JvmLog::isLineTerminator)) {
            return null;
        }
        return new Collection(label, Double.parseDouble(line.substring(numberStart, numberEnd)));
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    private static boolean isNumberChar(char c) {
        return c >= '0' && c <= '9' || c == '.';
    }

    private static boolean isLineTerminator(int c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /** Returns the position after the bracketed decorators that start at {@code from}. */
    private static int afterDecorators(String line, int from) {
        int next = from;
        while (next < line.length() && line.charAt(next) == '[') {
            int close = line.indexOf(']', next + 1);
            if (close < 0) {
                break;
            }
            next = close + 1;
        }
        return next;
    }

    private static long time(String text) {
        return OffsetDateTime.parse(text, TIME).toInstant().toEpochMilli();
    }
}
