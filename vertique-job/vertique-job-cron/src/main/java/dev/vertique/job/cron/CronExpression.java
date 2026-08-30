// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Parses a 6-field cron expression and computes the next fire time.
 *
 * <p>Field order: {@code second minute hour day-of-month month day-of-week}
 *
 * <p>Supported field syntax:
 * <ul>
 *   <li>{@code *} — every value in the field's range</li>
 *   <li>{@code N} — a specific value (e.g. {@code 5})</li>
 *   <li>{@code A-B} — a range of values (e.g. {@code 1-5})</li>
 *   <li>{@code A,B,C} — a list of values (e.g. {@code 1,3,5})</li>
 *   <li>{@code * /N} (no space) - every N-th value starting from the minimum (e.g. every 5 seconds)</li>
 *   <li>{@code A-B/N} - every N-th value in the range A-B (e.g. {@code 1-10/2})</li>
 * </ul>
 *
 * <p>Field ranges:
 * <ul>
 *   <li>second: {@code 0–59}</li>
 *   <li>minute: {@code 0–59}</li>
 *   <li>hour: {@code 0–23}</li>
 *   <li>day-of-month: {@code 1–31}</li>
 *   <li>month: {@code 1–12}</li>
 *   <li>day-of-week: {@code 0–6} where 0 = Sunday</li>
 * </ul>
 *
 * <p>Day-of-week: use numeric values only. 0 = Sunday, 1 = Monday, …, 6 = Saturday.
 * Both 0 and 7 represent Sunday for compatibility — only 0 is advertised.
 */
public class CronExpression {

    // --- Field indices ---
    private static final int IDX_SECOND = 0;
    private static final int IDX_MINUTE = 1;
    private static final int IDX_HOUR = 2;
    private static final int IDX_DAY = 3;
    private static final int IDX_MONTH = 4;
    private static final int IDX_DOW = 5;

    private static final int[] FIELD_MIN = {0, 0, 0, 1, 1, 0};
    private static final int[] FIELD_MAX = {59, 59, 23, 31, 12, 7};

    private final String raw;
    private final TreeSet<Integer>[] fields;
    private final boolean dayOfMonthUnrestricted;
    private final boolean dayOfWeekUnrestricted;

    /**
     * Parses a 6-field cron expression.
     *
     * @param expression the cron expression string (must not be blank)
     * @throws IllegalArgumentException if the expression is invalid or cannot be parsed
     */
    @SuppressWarnings("unchecked")
    public CronExpression(String expression) {
        this.raw = expression;
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                    "Cron expression must have exactly 6 fields (second minute hour day month weekday), got: "
                            + expression);
        }
        this.fields = new TreeSet[6];
        for (int i = 0; i < 6; i++) {
            this.fields[i] = parseField(parts[i], FIELD_MIN[i], FIELD_MAX[i], i);
        }
        this.dayOfMonthUnrestricted = "*".equals(parts[IDX_DAY]);
        this.dayOfWeekUnrestricted = "*".equals(parts[IDX_DOW]);
        // Normalize day-of-week: 7 is an alias for 0 (both mean Sunday)
        if (this.fields[IDX_DOW].remove(Integer.valueOf(7))) {
            this.fields[IDX_DOW].add(0);
        }
    }

    /**
     * Returns the raw cron expression string.
     *
     * @return the original expression
     */
    public String expression() {
        return raw;
    }

    /**
     * Computes the next fire time strictly after {@code from} in the given timezone.
     *
     * <p>The algorithm advances field-by-field (month → day → hour → minute → second),
     * resetting smaller fields when a larger field is incremented. It limits the search to
     * 4 years to prevent infinite loops for impossible expressions (e.g. {@code 0 0 0 31 2 *}).
     *
     * @param from the reference instant (the next fire time is strictly after this instant)
     * @param zone the timezone for evaluating the expression
     * @return the next fire instant
     * @throws IllegalStateException if no next fire time can be found within 4 years
     */
    public Instant computeNextFireTime(Instant from, ZoneId zone) {
        ZonedDateTime candidate = from.atZone(zone).plusSeconds(1).withNano(0);
        ZonedDateTime limit = candidate.plusYears(4);

        while (candidate.isBefore(limit)) {
            // Check month
            int month = candidate.getMonthValue(); // 1–12
            if (!fields[IDX_MONTH].contains(month)) {
                int nextMonth = nextValueAfter(fields[IDX_MONTH], month, 1, 12);
                if (nextMonth > month) {
                    candidate = candidate
                            .withMonth(nextMonth)
                            .withDayOfMonth(1)
                            .withHour(0)
                            .withMinute(0)
                            .withSecond(0);
                } else {
                    // wrap to next year
                    candidate = candidate
                            .plusYears(1)
                            .withMonth(nextMonth)
                            .withDayOfMonth(1)
                            .withHour(0)
                            .withMinute(0)
                            .withSecond(0);
                }
                continue;
            }

            // Check day-of-month and day-of-week
            int dayOfMonth = candidate.getDayOfMonth();
            int dayOfWeek = candidate.getDayOfWeek().getValue() % 7; // convert: Mon=1..Sun=0
            boolean dayOk = matchesCronDay(dayOfMonth, dayOfWeek);
            if (!dayOk) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }

            // Check hour
            int hour = candidate.getHour();
            if (!fields[IDX_HOUR].contains(hour)) {
                int nextHour = nextValueAfter(fields[IDX_HOUR], hour, 0, 23);
                if (nextHour > hour) {
                    candidate = candidate.withHour(nextHour).withMinute(0).withSecond(0);
                } else {
                    // wrap to next day
                    candidate = candidate
                            .plusDays(1)
                            .withHour(nextHour)
                            .withMinute(0)
                            .withSecond(0);
                }
                continue;
            }

            // Check minute
            int minute = candidate.getMinute();
            if (!fields[IDX_MINUTE].contains(minute)) {
                int nextMinute = nextValueAfter(fields[IDX_MINUTE], minute, 0, 59);
                if (nextMinute > minute) {
                    candidate = candidate.withMinute(nextMinute).withSecond(0);
                } else {
                    // wrap to next hour
                    candidate = candidate.plusHours(1).withMinute(nextMinute).withSecond(0);
                }
                continue;
            }

            // Check second
            int second = candidate.getSecond();
            if (!fields[IDX_SECOND].contains(second)) {
                int nextSecond = nextValueAfter(fields[IDX_SECOND], second, 0, 59);
                if (nextSecond > second) {
                    candidate = candidate.withSecond(nextSecond);
                } else {
                    // wrap to next minute
                    candidate = candidate.plusMinutes(1).withSecond(nextSecond);
                }
                continue;
            }

            // All fields match
            return candidate.toInstant();
        }

        throw new IllegalStateException("No next fire time found within 4 years for expression: " + raw);
    }

    /**
     * Applies standard cron semantics for the day-of-month and day-of-week fields.
     *
     * <p>When both fields are restricted, either matching field is sufficient. If one field is
     * unrestricted ({@code *}), only the other field controls the day; if both are unrestricted,
     * every day matches.
     *
     * @param dayOfMonth the candidate day of the month
     * @param dayOfWeek the candidate day of the week, where Sunday is {@code 0}
     * @return whether the candidate date matches the cron day fields
     */
    private boolean matchesCronDay(int dayOfMonth, int dayOfWeek) {
        boolean dayOfMonthMatches = fields[IDX_DAY].contains(dayOfMonth);
        boolean dayOfWeekMatches = fields[IDX_DOW].contains(dayOfWeek);
        if (dayOfMonthUnrestricted) {
            return dayOfWeekUnrestricted || dayOfWeekMatches;
        }
        if (dayOfWeekUnrestricted) {
            return dayOfMonthMatches;
        }
        return dayOfMonthMatches || dayOfWeekMatches;
    }

    // --- Field parsing ---

    /**
     * Parses one cron field into a sorted set of matching integer values.
     *
     * @param field  the field string to parse
     * @param min    the minimum valid value for this field
     * @param max    the maximum valid value for this field
     * @param fieldIndex the field index (used only for error messages)
     * @return a sorted set of all matching values within {@code [min, max]}
     */
    private TreeSet<Integer> parseField(String field, int min, int max, int fieldIndex) {
        TreeSet<Integer> values = new TreeSet<>();
        for (String part : field.split(",")) {
            parseFieldPart(part.trim(), min, max, values, fieldIndex);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cron field " + fieldIndex + " '" + field
                    + "' produced no values in range [" + min + "," + max + "]");
        }
        return values;
    }

    /**
     * Parses one comma-separated part of a cron field, supporting {@code *}, ranges, and steps.
     *
     * @param part       the part to parse (e.g. {@code "*"}, {@code "5"}, {@code "1-5"}, step syntax, range/step syntax)
     * @param min        the minimum valid value
     * @param max        the maximum valid value
     * @param values     the set to add matching values into
     * @param fieldIndex the field index (used for error messages)
     */
    private void parseFieldPart(String part, int min, int max, TreeSet<Integer> values, int fieldIndex) {
        int step = 1;
        String rangePart;

        // Detect step: "range/step"
        int slashIdx = part.indexOf('/');
        if (slashIdx >= 0) {
            step = Integer.parseInt(part.substring(slashIdx + 1));
            if (step <= 0) {
                throw new IllegalArgumentException(
                        "Step value must be positive in cron field " + fieldIndex + ": " + part);
            }
            rangePart = part.substring(0, slashIdx);
        } else {
            rangePart = part;
        }

        int rangeMin;
        int rangeMax;

        if ("*".equals(rangePart)) {
            rangeMin = min;
            rangeMax = max;
        } else {
            int dashIdx = rangePart.indexOf('-');
            if (dashIdx >= 0) {
                rangeMin = Integer.parseInt(rangePart.substring(0, dashIdx));
                rangeMax = Integer.parseInt(rangePart.substring(dashIdx + 1));
            } else {
                // Single value
                int val = Integer.parseInt(rangePart);
                rangeMin = val;
                rangeMax = slashIdx >= 0 ? max : val; // "5/2" means starting from 5 with step 2
            }
        }

        // Clamp to field range
        if (rangeMin < min || rangeMax > max || rangeMin > rangeMax) {
            throw new IllegalArgumentException("Cron field " + fieldIndex + " range [" + rangeMin + "," + rangeMax
                    + "] is out of bounds [" + min + "," + max + "] in: " + part);
        }

        for (int v = rangeMin; v <= rangeMax; v += step) {
            values.add(v);
        }
    }

    // --- Value navigation ---

    /**
     * Finds the next value in the set that is greater than or equal to {@code current}, wrapping
     * to the minimum if none is found.
     *
     * @param set     the sorted set of valid values
     * @param current the current (possibly invalid) value
     * @param min     the field minimum (fallback on wrap)
     * @param max     the field maximum (not used directly, set handles bounds)
     * @return the next matching value, which may be less than {@code current} on wrap
     */
    private int nextValueAfter(TreeSet<Integer> set, int current, int min, int max) {
        // Find the ceiling value >= current in the set
        Integer ceiling = set.ceiling(current);
        if (ceiling != null) {
            return ceiling;
        }
        // Wrap to the first element
        return set.first();
    }

    /**
     * Computes all fire times between {@code from} (exclusive) and {@code to} (inclusive).
     *
     * <p>Used for misfire detection to find fires that should have occurred in a time window.
     * For example, calling this with {@code from = lastFiredAt} and {@code to = now} returns
     * the list of ticks that were missed while the application was down.
     *
     * <p>The result list is capped at {@code maxResults} to prevent out-of-memory issues when
     * the window is large and the expression fires frequently (e.g. every second for many days).
     *
     * @param from       start of window (exclusive)
     * @param to         end of window (inclusive)
     * @param zone       timezone for evaluating the expression
     * @param maxResults maximum number of fire times to return; once reached, the list is returned
     *                   as-is (callers can detect truncation by comparing the list size)
     * @return list of fire instants in chronological order, may be empty
     */
    public List<Instant> computeFireTimesBetween(Instant from, Instant to, ZoneId zone, int maxResults) {
        if (!from.isBefore(to)) {
            return List.of();
        }
        List<Instant> fires = new ArrayList<>();
        Instant cursor = from;
        while (true) {
            Instant next;
            try {
                next = computeNextFireTime(cursor, zone);
            } catch (IllegalStateException e) {
                // No fire time found (impossible expression within search range)
                break;
            }
            if (next.isAfter(to)) {
                break;
            }
            fires.add(next);
            if (fires.size() >= maxResults) {
                break;
            }
            cursor = next;
        }
        return fires;
    }

    /**
     * Returns all valid values for a specific field index as an unmodifiable sorted list.
     * Intended for testing and diagnostics.
     *
     * @param fieldIndex the field index (0=second, 1=minute, 2=hour, 3=day, 4=month, 5=dow)
     * @return sorted list of valid values
     */
    List<Integer> valuesForField(int fieldIndex) {
        return new ArrayList<>(fields[fieldIndex]);
    }

    @Override
    public String toString() {
        return "CronExpression{" + raw + "}";
    }
}
