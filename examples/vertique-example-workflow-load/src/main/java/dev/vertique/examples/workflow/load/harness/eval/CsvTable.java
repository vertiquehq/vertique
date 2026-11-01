// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.load.harness.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A CSV file read into memory: a header and rows of text, with typed access by column name.
 *
 * <p>A missing file reads as an empty table, so an evaluator can ask a source that never ran and get no rows.
 */
public final class CsvTable {

    private final Map<String, Integer> columns = new HashMap<>();
    private final List<String[]> rows = new ArrayList<>();

    private CsvTable() {}

    /**
     * Reads a file.
     *
     * @param file the CSV file
     * @return the table; empty when the file does not exist
     * @throws IOException if the file exists and cannot be read
     */
    public static CsvTable read(Path file) throws IOException {
        CsvTable table = new CsvTable();
        if (!Files.exists(file)) {
            return table;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return table;
        }
        String[] header = split(lines.get(0));
        for (int i = 0; i < header.length; i++) {
            table.columns.put(header[i], i);
        }
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).isEmpty()) {
                table.rows.add(split(lines.get(i)));
            }
        }
        return table;
    }

    /**
     * Parses CSV text.
     *
     * @param header the header line
     * @param lines the data lines
     * @return the table
     */
    public static CsvTable of(String header, List<String> lines) {
        CsvTable table = new CsvTable();
        String[] names = split(header);
        for (int i = 0; i < names.length; i++) {
            table.columns.put(names[i], i);
        }
        lines.forEach(line -> table.rows.add(split(line)));
        return table;
    }

    /**
     * Returns the number of rows.
     *
     * @return the row count
     */
    public int size() {
        return rows.size();
    }

    /**
     * Returns whether the table has a column.
     *
     * @param name the column
     * @return {@code true} when present
     */
    public boolean has(String name) {
        return columns.containsKey(name);
    }

    /**
     * Returns a cell as text.
     *
     * @param row the row, from 0
     * @param column the column name
     * @return the text; empty when the cell is absent
     */
    public String text(int row, String column) {
        Integer index = columns.get(column);
        String[] cells = rows.get(row);
        return index == null || index >= cells.length ? "" : cells[index];
    }

    /**
     * Returns a cell as a number.
     *
     * @param row the row, from 0
     * @param column the column name
     * @return the value; {@code NaN} when the cell is empty or not a number
     */
    public double number(int row, String column) {
        String text = text(row, column);
        if (text.isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Returns a whole column as numbers.
     *
     * @param column the column name
     * @return one value per row
     */
    public double[] numbers(String column) {
        double[] values = new double[rows.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = number(i, column);
        }
        return values;
    }

    private static String[] split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }
}
