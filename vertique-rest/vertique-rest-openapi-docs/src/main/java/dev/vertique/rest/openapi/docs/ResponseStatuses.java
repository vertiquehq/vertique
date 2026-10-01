// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.util.Comparator;
import java.util.Map;

/**
 * The status keys of a Responses Object: which keys are valid, the order they are published in,
 * which declared statuses keep the inferred content, and the description a response publishes when
 * none is declared.
 *
 * <p>A valid key is {@code default}, a three-digit code from {@code 100} to {@code 599}, or an
 * uppercase range key {@code 1XX} to {@code 5XX}. Keys are published numeric codes ascending, then
 * range keys ascending, then {@code default}.
 */
final class ResponseStatuses {

    /** The key of the response that stands for every status not declared otherwise. */
    static final String DEFAULT = "default";

    /** The description of a {@code default} response. */
    static final String RUNTIME_DESCRIPTION = "Response determined at runtime";

    /** Orders valid status keys: numeric codes ascending, then range keys ascending, then {@code default}. */
    static final Comparator<String> ORDER =
            Comparator.comparingInt(ResponseStatuses::rank).thenComparing(Comparator.naturalOrder());

    /** The reason phrases of the registered status codes. */
    private static final Map<Integer, String> REASON_PHRASES = Map.ofEntries(
            Map.entry(100, "Continue"),
            Map.entry(101, "Switching Protocols"),
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(202, "Accepted"),
            Map.entry(203, "Non-Authoritative Information"),
            Map.entry(204, "No Content"),
            Map.entry(205, "Reset Content"),
            Map.entry(206, "Partial Content"),
            Map.entry(300, "Multiple Choices"),
            Map.entry(301, "Moved Permanently"),
            Map.entry(302, "Found"),
            Map.entry(303, "See Other"),
            Map.entry(304, "Not Modified"),
            Map.entry(305, "Use Proxy"),
            Map.entry(307, "Temporary Redirect"),
            Map.entry(308, "Permanent Redirect"),
            Map.entry(400, "Bad Request"),
            Map.entry(401, "Unauthorized"),
            Map.entry(402, "Payment Required"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(406, "Not Acceptable"),
            Map.entry(407, "Proxy Authentication Required"),
            Map.entry(408, "Request Timeout"),
            Map.entry(409, "Conflict"),
            Map.entry(410, "Gone"),
            Map.entry(411, "Length Required"),
            Map.entry(412, "Precondition Failed"),
            Map.entry(413, "Content Too Large"),
            Map.entry(414, "URI Too Long"),
            Map.entry(415, "Unsupported Media Type"),
            Map.entry(416, "Range Not Satisfiable"),
            Map.entry(417, "Expectation Failed"),
            Map.entry(421, "Misdirected Request"),
            Map.entry(422, "Unprocessable Content"),
            Map.entry(426, "Upgrade Required"),
            Map.entry(500, "Internal Server Error"),
            Map.entry(501, "Not Implemented"),
            Map.entry(502, "Bad Gateway"),
            Map.entry(503, "Service Unavailable"),
            Map.entry(504, "Gateway Timeout"),
            Map.entry(505, "HTTP Version Not Supported"));

    /** The phrases of the status classes, indexed by the class digit. */
    private static final String[] CLASS_PHRASES = {
        null, "Informational", "Success", "Redirection", "Client error", "Server error"
    };

    private ResponseStatuses() {}

    /**
     * Tells whether a declared status is a valid key of a Responses Object.
     *
     * @param status the declared status
     * @return {@code true} for {@code default}, a three-digit code from {@code 100} to {@code 599},
     *     or an uppercase range key from {@code 1XX} to {@code 5XX}
     */
    static boolean isValid(String status) {
        return DEFAULT.equals(status) || isCode(status) || isRange(status);
    }

    /**
     * Tells whether a declared status without content receives the inferred content of an inferable
     * return type: a success code other than {@code 204} and {@code 205}, or the range key {@code 2XX}.
     *
     * @param status a valid status key
     * @return whether the status keeps the inferred content
     */
    static boolean keepsInferredContent(String status) {
        if ("2XX".equals(status)) {
            return true;
        }
        if (!isCode(status)) {
            return false;
        }
        int code = Integer.parseInt(status);
        return code >= 200 && code <= 299 && code != 204 && code != 205;
    }

    /**
     * Returns the description a response publishes when it declares none.
     *
     * @param status a valid status key
     * @return the reason phrase of a registered code, the phrase of its class for any other code or a
     *     range key, and {@value #RUNTIME_DESCRIPTION} for {@code default}
     */
    static String reasonPhrase(String status) {
        if (DEFAULT.equals(status)) {
            return RUNTIME_DESCRIPTION;
        }
        if (isCode(status)) {
            String phrase = REASON_PHRASES.get(Integer.parseInt(status));
            if (phrase != null) {
                return phrase;
            }
        }
        return CLASS_PHRASES[status.charAt(0) - '0'];
    }

    /** Whether the status is a three-digit code from {@code 100} to {@code 599}. */
    private static boolean isCode(String status) {
        return status.length() == 3
                && status.charAt(0) >= '1'
                && status.charAt(0) <= '5'
                && status.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** Whether the status is an uppercase range key from {@code 1XX} to {@code 5XX}. */
    private static boolean isRange(String status) {
        return status.length() == 3
                && status.charAt(0) >= '1'
                && status.charAt(0) <= '5'
                && status.charAt(1) == 'X'
                && status.charAt(2) == 'X';
    }

    /** Ranks a valid key: numeric codes first, then range keys, then {@code default}. */
    private static int rank(String status) {
        if (isCode(status)) {
            return 0;
        }
        return isRange(status) ? 1 : 2;
    }
}
