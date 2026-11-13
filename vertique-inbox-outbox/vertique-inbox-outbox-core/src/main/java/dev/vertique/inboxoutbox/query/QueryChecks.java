// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import dev.vertique.core.exception.ValidationException;
import java.time.Instant;
import java.util.Map;

/**
 * Shared validation helpers for the query types. Every failure is a {@link ValidationException} whose message
 * names the field and the bound but never echoes a value.
 */
final class QueryChecks {

    /** Earliest instant a query may carry. */
    static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");

    /** Latest instant a query may carry. */
    static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    private QueryChecks() {}

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new ValidationException(name + " must not be null");
        }
        return value;
    }

    static long positive(long value, String name) {
        if (value <= 0) {
            throw new ValidationException(name + " must be positive");
        }
        return value;
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new ValidationException(name + " must not be negative");
        }
        return value;
    }

    static long range(long value, long max, String name) {
        positive(value, name);
        if (value > max) {
            throw new ValidationException(name + " must be at most " + max);
        }
        return value;
    }

    /**
     * Text of 1 to {@code max} code points that is not all whitespace and holds no control character and no
     * unpaired surrogate; absent text stays absent.
     */
    static String optionalText(String value, String name, int max) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank");
        }
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new ValidationException(name + " must not contain control characters");
            }
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw new ValidationException(name + " must not contain unpaired surrogates");
            }
            index += Character.charCount(codePoint);
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw new ValidationException(name + " must be at most " + max + " characters");
        }
        return value;
    }

    /** Requires an instant, when present, to lie within {@link #MIN_INSTANT} and {@link #MAX_INSTANT}. */
    static Instant instant(Instant value, String name) {
        if (value != null && (value.isBefore(MIN_INSTANT) || value.isAfter(MAX_INSTANT))) {
            throw new ValidationException(name + " must be between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z");
        }
        return value;
    }

    /** Requires {@code from < before} when both are present. */
    static void ordered(Instant from, Instant before, String fromName, String beforeName) {
        if (from != null && before != null && !from.isBefore(before)) {
            throw new ValidationException(fromName + " must be before " + beforeName);
        }
    }

    static Map<String, String> copyOf(Map<String, String> values, String name) {
        if (values == null) {
            return Map.of();
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new ValidationException(name + " must not contain null keys or values");
            }
        }
        return Map.copyOf(values);
    }
}
