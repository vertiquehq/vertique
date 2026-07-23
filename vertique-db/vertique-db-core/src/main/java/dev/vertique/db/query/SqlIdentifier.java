// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import dev.vertique.db.exception.InvalidDataAccessUsageException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Utility for validating and quoting SQL identifiers to prevent SQL injection via identifier
 * interpolation. Supports simple identifiers ({@code name}) and dot-qualified identifiers
 * ({@code table.column}, {@code schema.table.column}).
 *
 * <p>Use {@link #validate(String)} at entry points (constructors, builder methods) to fail-fast
 * on suspicious input. Use {@link #quote(String)} at SQL composition sites for defense-in-depth.
 *
 * @see OrderKey
 * @see PagedQuery
 */
public final class SqlIdentifier {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private SqlIdentifier() {}

    /**
     * Validates that the given string is a safe SQL identifier. Accepts letters, digits, and
     * underscores, with the first character being a letter or underscore. Dot-qualified names
     * are supported with up to three segments ({@code schema.table.column}).
     *
     * @param identifier the identifier to validate
     * @return the identifier unchanged (for fluent chaining)
     * @throws NullPointerException            if identifier is null
     * @throws InvalidDataAccessUsageException if the identifier is blank or contains unsafe
     *     characters
     */
    public static String validate(String identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        if (identifier.isBlank()) {
            throw new InvalidDataAccessUsageException("SQL identifier must not be blank");
        }
        String[] segments = identifier.split("\\.", -1);
        if (segments.length > 3) {
            throw new InvalidDataAccessUsageException(
                    "SQL identifier has too many dot-separated segments (max 3): " + identifier);
        }
        for (String seg : segments) {
            if (!SEGMENT_PATTERN.matcher(seg).matches()) {
                throw new InvalidDataAccessUsageException("Invalid SQL identifier: " + identifier);
            }
        }
        return identifier;
    }

    /**
     * Quotes a SQL identifier using standard double-quote escaping. Embedded double-quote characters
     * are doubled per the SQL standard. Dot-qualified names are quoted per segment.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>{@code name} → {@code "name"}
     *   <li>{@code t.name} → {@code "t"."name"}
     *   <li>{@code a"b} → {@code "a""b"}
     * </ul>
     *
     * @param identifier the identifier to quote
     * @return the quoted identifier
     * @throws NullPointerException if identifier is null
     */
    public static String quote(String identifier) {
        Objects.requireNonNull(identifier, "identifier must not be null");
        String[] segments = identifier.split("\\.", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append('.');
            sb.append('"').append(segments[i].replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    /**
     * Validates that the given column names are all safe SQL identifiers, non-duplicate, and that
     * at least one name is present. Combines {@link #validate(String)} with duplicate detection.
     *
     * @param columns the column names to validate
     * @throws IllegalArgumentException        if {@code columns} is empty or contains duplicates
     * @throws InvalidDataAccessUsageException if any column name fails {@link #validate(String)}
     */
    public static void validateColumnNames(String... columns) {
        if (columns.length == 0) {
            throw new IllegalArgumentException("At least one column is required");
        }
        Set<String> seen = new HashSet<>();
        for (String col : columns) {
            validate(col);
            if (!seen.add(col)) {
                throw new IllegalArgumentException("Duplicate column name: " + col);
            }
        }
    }
}
