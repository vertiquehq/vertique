// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.interceptor;

import jakarta.annotation.Nullable;
import java.util.Objects;

/** A normalized W3C trace reference without baggage. */
public record McpTraceContext(
        String traceId,
        String spanId,
        boolean sampled,
        @Nullable String traceState) {

    private static final int TRACE_ID_LENGTH = 32;
    private static final int SPAN_ID_LENGTH = 16;

    /**
     * Validates lowercase W3C hexadecimal trace and span identifiers.
     *
     * @throws NullPointerException if a required identifier is null
     * @throws IllegalArgumentException if an identifier is not normalized W3C hexadecimal
     */
    public McpTraceContext {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
        validateHex(traceId, TRACE_ID_LENGTH, "traceId");
        validateHex(spanId, SPAN_ID_LENGTH, "spanId");
        if (traceState != null && traceState.isBlank()) {
            throw new IllegalArgumentException("traceState must not be blank when present");
        }
    }

    private static void validateHex(String value, int expectedLength, String field) {
        if (value.length() != expectedLength || value.chars().anyMatch(character -> !isLowercaseAsciiHex(character))) {
            throw new IllegalArgumentException(
                    field + " must be a lowercase hexadecimal value of length " + expectedLength);
        }
        if (value.chars().allMatch(character -> character == '0')) {
            throw new IllegalArgumentException(field + " must not be all zeroes");
        }
    }

    private static boolean isLowercaseAsciiHex(int character) {
        return (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
    }
}
