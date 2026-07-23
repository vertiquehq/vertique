// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.exception;

/**
 * Thrown when a query returns an unexpected number of rows. For example, {@code one()} expects at
 * most one row; receiving two or more indicates a bug in the query (missing WHERE clause, wrong
 * index, etc.).
 */
public class IncorrectResultSizeDataAccessException extends InvalidDataAccessUsageException {

    private final int expectedSize;
    private final int actualSize;

    /**
     * Constructs a new exception.
     *
     * @param message      the detail message
     * @param expectedSize the expected number of rows
     * @param actualSize   the actual number of rows, or {@code -1} if unknown
     */
    public IncorrectResultSizeDataAccessException(String message, int expectedSize, int actualSize) {
        super(message);
        this.expectedSize = expectedSize;
        this.actualSize = actualSize;
    }

    /**
     * Returns the expected number of rows.
     *
     * @return the expected row count
     */
    public int expectedSize() {
        return expectedSize;
    }

    /**
     * Returns the actual number of rows, or {@code -1} if unknown.
     *
     * @return the actual row count, or {@code -1}
     */
    public int actualSize() {
        return actualSize;
    }
}
