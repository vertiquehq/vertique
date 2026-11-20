// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * Parser of the {@code Retry-After} header value: delta-seconds or an HTTP-date, read against an
 * injected clock. The result is never negative and never longer than {@value #MAX_HOURS} hours.
 */
final class RetryAfter {

    private static final int MAX_HOURS = 24;
    private static final Duration CAP = Duration.ofHours(MAX_HOURS);

    private final Clock clock;

    /**
     * Creates a parser.
     *
     * @param clock the clock an HTTP-date is compared with
     */
    RetryAfter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Parses a header value.
     *
     * @param value the raw header value, or null when the header is absent
     * @return the delay in the range zero to 24 hours, or empty when the value is absent or not a
     *     delta-seconds or HTTP-date value
     */
    Optional<Duration> parse(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (isDigits(text)) {
            return Optional.of(deltaSeconds(text));
        }
        try {
            Instant when = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Duration wait = Duration.between(clock.instant(), when);
            if (wait.isNegative()) {
                return Optional.of(Duration.ZERO);
            }
            return Optional.of(wait.compareTo(CAP) > 0 ? CAP : wait);
        } catch (DateTimeParseException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Duration deltaSeconds(String digits) {
        try {
            Duration wait = Duration.ofSeconds(Long.parseLong(digits));
            return wait.compareTo(CAP) > 0 ? CAP : wait;
        } catch (NumberFormatException | ArithmeticException e) {
            return CAP;
        }
    }

    private static boolean isDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
