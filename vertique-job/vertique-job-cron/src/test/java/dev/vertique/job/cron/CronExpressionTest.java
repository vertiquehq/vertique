// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CronExpression}.
 */
@DisplayName("CronExpression")
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    // --- Parsing validation ---

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("throws on wrong number of fields")
        void throwsOnWrongFieldCount() {
            assertThrows(IllegalArgumentException.class, () -> new CronExpression("* * * * *"));
        }

        @Test
        @DisplayName("throws on blank expression")
        void throwsOnBlankExpression() {
            assertThrows(IllegalArgumentException.class, () -> new CronExpression(""));
        }

        @Test
        @DisplayName("parses wildcard expression")
        void parsesWildcard() {
            assertNotNull(new CronExpression("* * * * * *"));
        }

        @Test
        @DisplayName("parses list expression")
        void parsesList() {
            CronExpression expr = new CronExpression("0 0,30 * * * *");
            assertTrue(expr.valuesForField(1).contains(0));
            assertTrue(expr.valuesForField(1).contains(30));
            assertEquals(2, expr.valuesForField(1).size());
        }

        @Test
        @DisplayName("parses range expression")
        void parsesRange() {
            CronExpression expr = new CronExpression("0 0 9-17 * * *");
            assertEquals(9, expr.valuesForField(2).size()); // 9,10,...,17
        }

        @Test
        @DisplayName("parses step expression */N")
        void parsesStepWildcard() {
            CronExpression expr = new CronExpression("*/10 * * * * *");
            // 0, 10, 20, 30, 40, 50
            assertEquals(6, expr.valuesForField(0).size());
        }

        @Test
        @DisplayName("parses range/step expression A-B/N")
        void parsesRangeWithStep() {
            CronExpression expr = new CronExpression("0 1-10/2 * * * *");
            // 1, 3, 5, 7, 9
            assertEquals(5, expr.valuesForField(1).size());
        }

        @Test
        @DisplayName("throws on out-of-bounds value")
        void throwsOnOutOfBoundsValue() {
            assertThrows(IllegalArgumentException.class, () -> new CronExpression("60 * * * * *"));
        }

        @Test
        @DisplayName("throws on negative step")
        void throwsOnNegativeStep() {
            assertThrows(IllegalArgumentException.class, () -> new CronExpression("*/-1 * * * * *"));
        }
    }

    // --- Next fire time computation ---

    @Nested
    @DisplayName("computeNextFireTime")
    class ComputeNextFireTime {

        @Test
        @DisplayName("every second: next is 1 second later")
        void everySecond() {
            CronExpression expr = new CronExpression("* * * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(10, nextDt.getHour());
            assertEquals(30, nextDt.getMinute());
            assertEquals(1, nextDt.getSecond());
        }

        @Test
        @DisplayName("every minute at second 0: next is in next minute")
        void everyMinute() {
            CronExpression expr = new CronExpression("0 * * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 30, 30, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(31, nextDt.getMinute());
            assertEquals(0, nextDt.getSecond());
        }

        @Test
        @DisplayName("every hour at 0:00: next is next hour")
        void everyHour() {
            CronExpression expr = new CronExpression("0 0 * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 30, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(11, nextDt.getHour());
            assertEquals(0, nextDt.getMinute());
            assertEquals(0, nextDt.getSecond());
        }

        @Test
        @DisplayName("daily at 08:00: next is tomorrow 08:00 when past that time")
        void dailyAt8am() {
            CronExpression expr = new CronExpression("0 0 8 * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 9, 0, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(2024, nextDt.getYear());
            assertEquals(1, nextDt.getMonthValue());
            assertEquals(16, nextDt.getDayOfMonth());
            assertEquals(8, nextDt.getHour());
            assertEquals(0, nextDt.getMinute());
        }

        @Test
        @DisplayName("daily at 08:00: next is today 08:00 when before that time")
        void dailyAt8amBeforeTime() {
            CronExpression expr = new CronExpression("0 0 8 * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 7, 0, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(15, nextDt.getDayOfMonth());
            assertEquals(8, nextDt.getHour());
        }

        @Test
        @DisplayName("every 30 seconds: fires at 0 and 30 seconds")
        void every30Seconds() {
            CronExpression expr = new CronExpression("0,30 * * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 0, 15, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(30, nextDt.getSecond());
        }

        @Test
        @DisplayName("first day of each month: wraps to next month")
        void firstDayOfMonth() {
            CronExpression expr = new CronExpression("0 0 0 1 * *");
            Instant from = ZonedDateTime.of(2024, 1, 2, 0, 0, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(2, nextDt.getMonthValue()); // February
            assertEquals(1, nextDt.getDayOfMonth());
        }

        @Test
        @DisplayName("year wraps correctly for December 31 to January 1")
        void yearWrap() {
            CronExpression expr = new CronExpression("0 0 0 1 1 *");
            Instant from = ZonedDateTime.of(2024, 12, 31, 23, 0, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(2025, nextDt.getYear());
            assertEquals(1, nextDt.getMonthValue());
            assertEquals(1, nextDt.getDayOfMonth());
        }

        @Test
        @DisplayName("step expression */5 on seconds fires at 0,5,10,...,55")
        void stepSeconds() {
            CronExpression expr = new CronExpression("*/5 * * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 0, 3, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, UTC);
            ZonedDateTime nextDt = next.atZone(UTC);
            assertEquals(5, nextDt.getSecond());
        }

        @Test
        @DisplayName("timezone adjustment: same instant in different timezone")
        void timezoneAdjustment() {
            // 0 0 8 * * * in Europe/Helsinki (UTC+2) means UTC 06:00
            CronExpression expr = new CronExpression("0 0 8 * * *");
            ZoneId helsinki = ZoneId.of("Europe/Helsinki");
            Instant from = ZonedDateTime.of(2024, 1, 15, 7, 0, 0, 0, UTC).toInstant();
            Instant next = expr.computeNextFireTime(from, helsinki);
            // 08:00 Helsinki = 06:00 UTC
            ZonedDateTime nextUtc = next.atZone(UTC);
            assertEquals(6, nextUtc.getHour());
        }
    }

    // --- Fire times between ---

    @Nested
    @DisplayName("computeFireTimesBetween")
    class ComputeFireTimesBetween {

        @Test
        @DisplayName("returns empty list when from is after to")
        void emptyWhenFromAfterTo() {
            CronExpression expr = new CronExpression("* * * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 0, 5, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 15, 10, 0, 3, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 1000);
            assertTrue(fires.isEmpty());
        }

        @Test
        @DisplayName("returns empty list when from equals to")
        void emptyWhenFromEqualsTo() {
            CronExpression expr = new CronExpression("* * * * * *");
            Instant instant = ZonedDateTime.of(2024, 1, 15, 10, 0, 5, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(instant, instant, UTC, 1000);
            assertTrue(fires.isEmpty());
        }

        @Test
        @DisplayName("every-second job: returns all seconds in a 5-second window")
        void everySecondInSmallWindow() {
            CronExpression expr = new CronExpression("* * * * * *");
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 15, 10, 0, 5, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 1000);
            // Fires at :01, :02, :03, :04, :05
            assertEquals(5, fires.size());
            ZonedDateTime first = fires.get(0).atZone(UTC);
            assertEquals(1, first.getSecond());
            ZonedDateTime last = fires.get(4).atZone(UTC);
            assertEquals(5, last.getSecond());
        }

        @Test
        @DisplayName("daily job: returns one fire for a 25-hour window spanning the fire time")
        void dailyJobReturnsOneFire() {
            CronExpression expr = new CronExpression("0 0 8 * * *");
            // Window: 2024-01-15 07:00 UTC to 2024-01-16 08:00 UTC (25 hours)
            Instant from = ZonedDateTime.of(2024, 1, 15, 7, 0, 0, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 16, 8, 0, 0, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 1000);
            // Should contain 2024-01-15T08:00 and 2024-01-16T08:00
            assertEquals(2, fires.size());
            ZonedDateTime fire1 = fires.get(0).atZone(UTC);
            assertEquals(15, fire1.getDayOfMonth());
            assertEquals(8, fire1.getHour());
            ZonedDateTime fire2 = fires.get(1).atZone(UTC);
            assertEquals(16, fire2.getDayOfMonth());
            assertEquals(8, fire2.getHour());
        }

        @Test
        @DisplayName("daily job: returns empty list when window does not include any fire time")
        void dailyJobReturnsEmptyWhenWindowMissesFire() {
            CronExpression expr = new CronExpression("0 0 8 * * *");
            // Window: 2024-01-15 09:00 UTC to 2024-01-16 07:00 UTC (no fire at 08:00 in window)
            Instant from = ZonedDateTime.of(2024, 1, 15, 9, 0, 0, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 16, 7, 0, 0, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 1000);
            assertTrue(fires.isEmpty());
        }

        @Test
        @DisplayName("caps result at maxResults to prevent OOM")
        void capsAtMaxResults() {
            CronExpression expr = new CronExpression("* * * * * *");
            // 10-second window with every-second fires, but cap at 3
            Instant from = ZonedDateTime.of(2024, 1, 15, 10, 0, 0, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 15, 10, 0, 10, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 3);
            assertEquals(3, fires.size());
        }

        @Test
        @DisplayName("to boundary is inclusive")
        void toBoundaryIsInclusive() {
            CronExpression expr = new CronExpression("0 0 8 * * *");
            // Window ends exactly at fire time
            Instant from = ZonedDateTime.of(2024, 1, 15, 7, 0, 0, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 15, 8, 0, 0, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 1000);
            assertEquals(1, fires.size());
            assertEquals(to, fires.get(0));
        }

        @Test
        @DisplayName("from boundary is exclusive")
        void fromBoundaryIsExclusive() {
            CronExpression expr = new CronExpression("0 0 8 * * *");
            // Window starts exactly at fire time — that time should NOT be included
            Instant from = ZonedDateTime.of(2024, 1, 15, 8, 0, 0, 0, UTC).toInstant();
            Instant to = ZonedDateTime.of(2024, 1, 16, 8, 0, 0, 0, UTC).toInstant();
            List<Instant> fires = expr.computeFireTimesBetween(from, to, UTC, 1000);
            // Only 2024-01-16T08:00 should appear
            assertEquals(1, fires.size());
            ZonedDateTime fire = fires.get(0).atZone(UTC);
            assertEquals(16, fire.getDayOfMonth());
        }
    }
}
