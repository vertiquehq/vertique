// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.spi.event;

import java.time.Duration;
import java.util.Objects;

/** Shared validation for redacted cache event payloads. */
final class EventValidation {
    private static final int MAX_LABEL_LENGTH = 64;

    private EventValidation() {}

    static void requireLabel(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    static String boundedLabel(String value, String field) {
        requireLabel(value, field);
        return value.length() <= MAX_LABEL_LENGTH ? value : value.substring(0, MAX_LABEL_LENGTH);
    }

    static void requireElapsed(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }

    static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
