// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests of the {@code Retry-After} parser. */
class RetryAfterTest {

    /** Sun, 06 Nov 1994 08:49:37 GMT. */
    private static final Instant NOW = Instant.parse("1994-11-06T08:49:37Z");

    @Test
    @DisplayName("HTTP-date and garbage parse against a fixed clock and cap at 24 hours")
    void httpDateAndGarbageParseAgainstAFixedClockAndCapAtTwentyFourHours() {
        // Given the parser with a fixed clock at Sun, 06 Nov 1994 08:49:37 GMT
        RetryAfter parser = new RetryAfter(Clock.fixed(NOW, ZoneOffset.UTC));

        // When it parses a future date, a past date, garbage, delta-seconds and an overflowing delta
        Optional<Duration> future = parser.parse("Sun, 06 Nov 1994 08:50:37 GMT");
        Optional<Duration> past = parser.parse("Sun, 06 Nov 1994 08:00:00 GMT");
        Optional<Duration> garbage = parser.parse("soon");
        Optional<Duration> delta = parser.parse("7");
        Optional<Duration> huge = parser.parse("999999999");

        // Then the results are 60 s, zero, empty, 7 s and the 24 hour cap
        assertThat(future).contains(Duration.ofSeconds(60));
        assertThat(past).contains(Duration.ZERO);
        assertThat(garbage).isEmpty();
        assertThat(delta).contains(Duration.ofSeconds(7));
        assertThat(huge).contains(Duration.ofHours(24));
    }

    @Test
    @DisplayName("the parser never reads the system clock")
    void theParserNeverReadsTheSystemClock() {
        // Given two parsers whose fixed clocks differ by an hour
        RetryAfter early = new RetryAfter(Clock.fixed(NOW, ZoneOffset.UTC));
        RetryAfter later = new RetryAfter(Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));

        // When both parse the same HTTP-date
        String date = "Sun, 06 Nov 1994 09:49:37 GMT";

        // Then each answers relative to its own clock
        assertThat(early.parse(date)).contains(Duration.ofHours(1));
        assertThat(later.parse(date)).contains(Duration.ZERO);
    }

    @Test
    @DisplayName("absent, blank, negative and oversized values are bounded")
    void absentBlankNegativeAndFarFutureValuesAreBounded() {
        // Given the parser with a fixed clock
        RetryAfter parser = new RetryAfter(Clock.fixed(NOW, ZoneOffset.UTC));

        // When it parses null, blank, a negative delta, zero, a date beyond a day
        // and a delta beyond long range

        // Then absent and unparseable values are empty and the rest are bounded to [0, 24 h]
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("  ")).isEmpty();
        assertThat(parser.parse("-5")).isEmpty();
        assertThat(parser.parse("0")).contains(Duration.ZERO);
        assertThat(parser.parse("Tue, 08 Nov 1994 08:49:37 GMT")).contains(Duration.ofHours(24));
        assertThat(parser.parse("99999999999999999999999999"))
                .isIn(Optional.empty(), Optional.of(Duration.ofHours(24)));
    }
}
