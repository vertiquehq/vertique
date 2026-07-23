// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL scanning utility that is aware of parenthesis depth, single-quoted string literals, and
 * double-quoted identifiers. Used to detect SQL clauses at the top level of a query without
 * false-positive matches inside subqueries, CTEs, or window functions.
 *
 * <p>The scanner assigns each character a parenthesis nesting depth and only reports pattern
 * matches that occur at depth 0. Characters inside {@code '...'} (single-quoted string literals
 * with {@code ''} escape) and {@code "..."} (double-quoted identifiers with {@code ""} escape)
 * are assigned the depth of the opening quote character, so patterns inside those regions are
 * also suppressed.
 *
 * @see PgPagedQuery
 */
public final class SqlScanner {

    private SqlScanner() {}

    /**
     * Returns {@code true} if the given SQL contains a match for the pattern at top-level
     * (parenthesis depth 0), ignoring matches inside parenthesized subqueries, single-quoted
     * string literals, and double-quoted identifiers.
     *
     * @param sql     the SQL string to scan
     * @param pattern the pattern to search for at top level
     * @return {@code true} if the pattern matches at least one position at depth 0
     * @throws NullPointerException if {@code sql} or {@code pattern} is null
     */
    public static boolean containsAtTopLevel(String sql, Pattern pattern) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(pattern, "pattern");
        boolean[] suppressed = computeSuppressed(sql);
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            if (!suppressed[matcher.start()]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Computes a suppression array where {@code suppressed[i]} is {@code true} if character
     * position {@code i} is inside a parenthesized subquery (depth &gt; 0), a single-quoted string
     * literal, or a double-quoted identifier.
     *
     * <p>Positions at the top level (parenthesis depth 0) outside of any quoted context have
     * {@code suppressed[i] == false}.
     *
     * @param sql the SQL string to analyze
     * @return a boolean array of length {@code sql.length()}
     */
    private static boolean[] computeSuppressed(String sql) {
        int len = sql.length();
        boolean[] suppressed = new boolean[len];
        int currentDepth = 0;
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                // Single-quoted string literal: all characters inside are suppressed
                suppressed[i] = currentDepth > 0;
                i++;
                while (i < len) {
                    suppressed[i] = true; // content inside the literal is always suppressed
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                            // Escaped quote ''
                            i++;
                            suppressed[i] = true;
                        } else {
                            // End of string literal
                            break;
                        }
                    }
                    i++;
                }
            } else if (c == '"') {
                // Double-quoted identifier: all characters inside are suppressed
                suppressed[i] = currentDepth > 0;
                i++;
                while (i < len) {
                    suppressed[i] = true; // content inside the identifier is always suppressed
                    if (sql.charAt(i) == '"') {
                        if (i + 1 < len && sql.charAt(i + 1) == '"') {
                            // Escaped quote ""
                            i++;
                            suppressed[i] = true;
                        } else {
                            // End of quoted identifier
                            break;
                        }
                    }
                    i++;
                }
            } else if (c == '(') {
                suppressed[i] = currentDepth > 0;
                currentDepth++;
            } else if (c == ')') {
                currentDepth = Math.max(0, currentDepth - 1);
                suppressed[i] = currentDepth > 0;
            } else {
                suppressed[i] = currentDepth > 0;
            }
            i++;
        }
        return suppressed;
    }
}
