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
    private static final int MAX_TRACE_STATE_CHARS = 512;
    private static final int PRINTABLE_ASCII_MINIMUM = 0x20;
    private static final int PRINTABLE_ASCII_MAXIMUM = 0x7E;

    /**
     * Validates lowercase W3C hexadecimal trace and span identifiers, and the bounded trace state.
     *
     * <p>{@code traceState}, when present, is non-blank, at most {@value #MAX_TRACE_STATE_CHARS}
     * characters, and made exclusively of printable ASCII (0x20–0x7E) — no control characters.
     *
     * @throws NullPointerException if a required identifier is null
     * @throws IllegalArgumentException if an identifier is not normalized W3C hexadecimal, or the
     *     trace state is blank, too long, or carries a non-printable-ASCII character
     */
    public McpTraceContext {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(spanId, "spanId");
        validateHex(traceId, TRACE_ID_LENGTH, "traceId");
        validateHex(spanId, SPAN_ID_LENGTH, "spanId");
        validateTraceState(traceState);
    }

    private static void validateTraceState(@Nullable String traceState) {
        if (traceState == null) {
            return;
        }
        if (traceState.isBlank()) {
            throw new IllegalArgumentException("traceState must not be blank when present");
        }
        if (traceState.length() > MAX_TRACE_STATE_CHARS) {
            throw new IllegalArgumentException("traceState must not exceed " + MAX_TRACE_STATE_CHARS + " characters");
        }
        if (traceState.chars().anyMatch(character -> !isPrintableAscii(character))) {
            throw new IllegalArgumentException("traceState must contain only printable ASCII characters");
        }
    }

    private static boolean isPrintableAscii(int character) {
        return character >= PRINTABLE_ASCII_MINIMUM && character <= PRINTABLE_ASCII_MAXIMUM;
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
