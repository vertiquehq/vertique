// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import dev.vertique.core.exception.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Shared validation helpers for the administration types. Every failure is a
 * {@link ValidationException} whose message names the field and the bound but never echoes a value, so
 * no reason, actor or payload text can leak through an exception message.
 */
final class AdminChecks {

    /** Largest page size accepted by the list and history queries. */
    static final int MAX_LIMIT = 200;

    /** Page size used when a query does not set one. */
    static final int DEFAULT_LIMIT = 50;

    private AdminChecks() {}

    static <T> T required(T value, String name) {
        if (value == null) {
            throw new ValidationException(name + " must not be null");
        }
        return value;
    }

    static <T> Optional<T> optional(Optional<T> value) {
        return value == null ? Optional.empty() : value;
    }

    static OptionalLong optionalLong(OptionalLong value) {
        return value == null ? OptionalLong.empty() : value;
    }

    static <T> List<T> copyOf(List<T> values, String name) {
        required(values, name);
        for (T value : values) {
            if (value == null) {
                throw new ValidationException(name + " must not contain null");
            }
        }
        return List.copyOf(values);
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

    static int limit(int value) {
        if (value < 1 || value > MAX_LIMIT) {
            throw new ValidationException("limit must be between 1 and " + MAX_LIMIT);
        }
        return value;
    }

    /**
     * Required text of 1 to {@code max} code points that is not all whitespace and holds no control character and
     * no unpaired surrogate. The failure names the field and never echoes the value.
     */
    static String text(String value, String name, int max) {
        return text(value, name, max, false);
    }

    /**
     * Like {@link #text(String, String, int)}, but a tab, line feed and carriage return are allowed, for free text
     * such as a reason.
     */
    static String multilineText(String value, String name, int max) {
        return text(value, name, max, true);
    }

    private static String text(String value, String name, int max, boolean allowLineBreaks) {
        required(value, name);
        if (value.isBlank()) {
            throw new ValidationException(name + " must not be blank");
        }
        clean(value, name, allowLineBreaks);
        return bounded(value, name, max);
    }

    private static void clean(String value, String name, boolean allowLineBreaks) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            if (Character.isISOControl(codePoint)
                    && !(allowLineBreaks && (codePoint == '\t' || codePoint == '\n' || codePoint == '\r'))) {
                throw new ValidationException(name + " must not contain control characters");
            }
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw new ValidationException(name + " must not contain unpaired surrogates");
            }
            index += Character.charCount(codePoint);
        }
    }

    /** Text that is either absent or valid per {@link #text(String, String, int)}. */
    static String optionalText(String value, String name, int max) {
        return value == null ? null : text(value, name, max);
    }

    /** Possibly empty text of at most {@code max} code points; absence is the caller's decision. */
    static String bounded(String value, String name, int max) {
        if (value.codePointCount(0, value.length()) > max) {
            throw new ValidationException(name + " must be at most " + max + " characters");
        }
        return value;
    }

    /** Earliest instant a query or key may carry. */
    static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");

    /** Latest instant a query or key may carry. */
    static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59Z");

    /** Requires an instant, when present, to lie within {@link #MIN_INSTANT} and {@link #MAX_INSTANT}. */
    static Instant instant(Instant value, String name) {
        if (value != null && (value.isBefore(MIN_INSTANT) || value.isAfter(MAX_INSTANT))) {
            throw new ValidationException(name + " must be between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z");
        }
        return value;
    }

    /** Requires a number to be at most {@code max}. */
    static long atMost(long value, long max, String name) {
        if (value > max) {
            throw new ValidationException(name + " must be at most " + max);
        }
        return value;
    }

    /** Requires {@code from < before} when both are present. */
    static void ordered(Instant from, Instant before, String fromName, String beforeName) {
        if (from != null && before != null && !from.isBefore(before)) {
            throw new ValidationException(fromName + " must be before " + beforeName);
        }
    }

    /** Requires the action, disposition and result revision to agree: a retry requeues and has a result revision. */
    static void consistent(OutboxAdminAction action, OutboxAdminDisposition disposition, OptionalLong resultRevision) {
        boolean retry = action == OutboxAdminAction.RETRY;
        if (retry != (disposition == OutboxAdminDisposition.REQUEUED)) {
            throw new ValidationException("disposition does not match action");
        }
        if (retry != resultRevision.isPresent()) {
            throw new ValidationException("resultRevision must be present for a retry and absent for a discard");
        }
        if (retry) {
            nonNegative(resultRevision.getAsLong(), "resultRevision");
        }
    }
}
