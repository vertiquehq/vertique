// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

/**
 * Low-cardinality HTTP outcome bucket derived from the HTTP response status code.
 *
 * <p>Used as a meter tag value to group responses into coarse outcome categories without
 * creating unbounded cardinality from individual status codes.
 *
 * <p>Mapping rule: {@code statusCode / 100} determines the bucket (integer division):
 * <ul>
 *   <li>1xx → {@link #INFORMATIONAL}</li>
 *   <li>2xx → {@link #SUCCESS}</li>
 *   <li>3xx → {@link #REDIRECTION}</li>
 *   <li>4xx → {@link #CLIENT_ERROR}</li>
 *   <li>5xx → {@link #SERVER_ERROR}</li>
 *   <li>any other value → {@link #UNKNOWN}</li>
 * </ul>
 */
enum HttpOutcome {

    /** 1xx Informational responses. */
    INFORMATIONAL,

    /** 2xx Success responses. */
    SUCCESS,

    /** 3xx Redirection responses. */
    REDIRECTION,

    /** 4xx Client error responses. */
    CLIENT_ERROR,

    /** 5xx Server error responses. */
    SERVER_ERROR,

    /** Status code outside the 1xx–5xx range, or zero (pre-response failure). */
    UNKNOWN;

    /**
     * Returns the outcome bucket for the given HTTP status code.
     *
     * <p>Integer division by 100 determines the bucket; any value outside 100–599 maps to
     * {@link #UNKNOWN}.
     *
     * @param statusCode the HTTP response status code
     * @return the corresponding outcome bucket; never {@code null}
     */
    static HttpOutcome from(int statusCode) {
        return switch (statusCode / 100) {
            case 1 -> INFORMATIONAL;
            case 2 -> SUCCESS;
            case 3 -> REDIRECTION;
            case 4 -> CLIENT_ERROR;
            case 5 -> SERVER_ERROR;
            default -> UNKNOWN;
        };
    }
}
