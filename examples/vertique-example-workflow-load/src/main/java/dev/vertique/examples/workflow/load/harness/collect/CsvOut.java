// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.collect;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** A CSV file that is flushed after every row, so a crash loses at most the row being written. */
final class CsvOut implements Closeable {

    private final BufferedWriter out;

    CsvOut(Path file, String header) throws IOException {
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(header);
        out.write('\n');
        out.flush();
    }

    synchronized void row(Object... cells) {
        try {
            StringBuilder line = new StringBuilder(128);
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    line.append(',');
                }
                line.append(cell(cells[i]));
            }
            line.append('\n');
            out.write(line.toString());
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double d) {
            return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.3f", d);
        }
        String text = value.toString();
        if (text.indexOf(',') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('"') >= 0) {
            return '"' + text.replace("\"", "\"\"").replace('\n', ' ') + '"';
        }
        return text;
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
