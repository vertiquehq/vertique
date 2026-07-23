// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.query;

import dev.vertique.db.exception.DbValidationException;

/**
 * Thrown when a requested page size violates the configured min/max constraints. Extends
 * {@link DbValidationException} which maps to HTTP 400 by default.
 *
 * <p>Application code can register a custom {@code ExceptionMapper<PageSizeConstraintViolationException>}
 * to produce RFC 9457 Problem Details responses with structured extension attributes:
 *
 * <pre>{@code
 * {
 *   "type": "about:blank",
 *   "title": "Bad Request",
 *   "status": 400,
 *   "detail": "pageSize 500 is out of range [1, 100]",
 *   "requestedPageSize": 500,
 *   "minPageSize": 1,
 *   "maxPageSize": 100
 * }
 * }</pre>
 *
 * @see PageCursor#withPageSize(int, int, int)
 * @see PageCursor#validatePageSize(int, int)
 */
public class PageSizeConstraintViolationException extends DbValidationException {

    private final int requestedPageSize;
    private final int minPageSize;
    private final int maxPageSize;

    /**
     * Creates a new page size constraint violation exception.
     *
     * @param requestedPageSize the page size that was requested
     * @param minPageSize       the minimum allowed page size
     * @param maxPageSize       the maximum allowed page size
     */
    public PageSizeConstraintViolationException(int requestedPageSize, int minPageSize, int maxPageSize) {
        super("pageSize " + requestedPageSize + " is out of range [" + minPageSize + ", " + maxPageSize + "]");
        this.requestedPageSize = requestedPageSize;
        this.minPageSize = minPageSize;
        this.maxPageSize = maxPageSize;
    }

    /**
     * Returns the page size that was requested.
     *
     * @return the requested page size
     */
    public int requestedPageSize() {
        return requestedPageSize;
    }

    /**
     * Returns the minimum allowed page size.
     *
     * @return the minimum page size
     */
    public int minPageSize() {
        return minPageSize;
    }

    /**
     * Returns the maximum allowed page size.
     *
     * @return the maximum page size
     */
    public int maxPageSize() {
        return maxPageSize;
    }
}
