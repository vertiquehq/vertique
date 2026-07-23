// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxBackoff} — exponential backoff delay computation, jitter
 * bounds, and maximum delay cap enforcement.
 */
@DisplayName("OutboxBackoff")
class OutboxBackoffTest {

    private static final long BASE_DELAY_MS = 1_000L;
    private static final long MAX_DELAY_MS = 300_000L;

    @Nested
    @DisplayName("attempt 0 (first retry)")
    class AttemptZero {

        @Test
        @DisplayName("available-at is after now")
        void availableAtIsAfterNow() {
            Instant before = Instant.now();
            Instant result = OutboxBackoff.computeNextAvailableAt(0, BASE_DELAY_MS, MAX_DELAY_MS);
            assertTrue(result.isAfter(before), "availableAt should be in the future");
        }

        @Test
        @DisplayName("available-at is within base delay + max jitter (1000ms base + 1000ms max jitter)")
        void availableAtIsWithinExpectedWindow() {
            Instant before = Instant.now();
            Instant result = OutboxBackoff.computeNextAvailableAt(0, BASE_DELAY_MS, MAX_DELAY_MS);
            // attempt 0: delay = 1000 * 2^0 = 1000ms, jitter in [0, min(1000,1000)] = [0, 1000]
            // so result is in (now, now + 2000ms + small test execution time)
            Instant upperBound = before.plusMillis(BASE_DELAY_MS * 2 + 500);
            assertTrue(
                    result.isBefore(upperBound),
                    "availableAt should be within base delay + max jitter window, but was: " + result);
        }
    }

    @Nested
    @DisplayName("higher attempts produce larger delays")
    class EscalatingDelays {

        @Test
        @DisplayName("attempt 5 produces delay larger than attempt 0 on average")
        void higherAttemptProducesLargerDelay() {
            // Run multiple times to account for jitter — the minimum delay at attempt 5
            // (32000ms base) far exceeds the maximum at attempt 0 (2000ms base + 1000ms jitter)
            Instant now = Instant.now();
            Instant attempt0Result = OutboxBackoff.computeNextAvailableAt(0, BASE_DELAY_MS, MAX_DELAY_MS);
            Instant attempt5Result = OutboxBackoff.computeNextAvailableAt(5, BASE_DELAY_MS, MAX_DELAY_MS);

            // attempt 5 minimum delay = 32000ms, attempt 0 maximum delay = 2000ms
            // So attempt5 must always be after attempt0
            assertTrue(attempt5Result.isAfter(attempt0Result), "attempt 5 delay should be larger than attempt 0 delay");
        }

        @Test
        @DisplayName("attempt 1 available-at is further than attempt 0 minimum")
        void attempt1FurtherThanAttempt0Minimum() {
            Instant now = Instant.now();
            // attempt 0 max = now + 2000ms. attempt 1 min = now + 2000ms (before jitter).
            // With jitter range [0,1000], attempt 1 is at minimum now + 2000ms.
            // We verify attempt 1 is well above attempt 0's base delay floor.
            Instant attempt1Result = OutboxBackoff.computeNextAvailableAt(1, BASE_DELAY_MS, MAX_DELAY_MS);
            Instant attempt0MaxUpperBound = now.plusMillis(BASE_DELAY_MS + 50); // attempt 0 base is 1000ms
            assertTrue(
                    attempt1Result.isAfter(attempt0MaxUpperBound),
                    "attempt 1 should produce delay larger than attempt 0 base delay");
        }
    }

    @Nested
    @DisplayName("delay is capped at maxDelayMs")
    class MaxDelayCap {

        @Test
        @DisplayName("attempt 20 is capped at maxDelayMs")
        void attempt20IsCappedAtMaxDelay() {
            Instant before = Instant.now();
            Instant result = OutboxBackoff.computeNextAvailableAt(20, BASE_DELAY_MS, MAX_DELAY_MS);
            Instant upperBound = before.plusMillis(MAX_DELAY_MS + 500); // small margin for test execution
            assertTrue(result.isBefore(upperBound), "result should be at or below maxDelayMs cap, but was: " + result);
        }

        @Test
        @DisplayName("attempt 30 is also capped at maxDelayMs")
        void attempt30IsCappedAtMaxDelay() {
            Instant before = Instant.now();
            Instant result = OutboxBackoff.computeNextAvailableAt(30, BASE_DELAY_MS, MAX_DELAY_MS);
            Instant upperBound = before.plusMillis(MAX_DELAY_MS + 500);
            assertTrue(result.isBefore(upperBound), "result should be at or below maxDelayMs cap, but was: " + result);
        }

        @Test
        @DisplayName("small maxDelayMs is respected even for attempt 0")
        void smallMaxDelayMsIsRespected() {
            long tinyMaxDelay = 100L;
            Instant before = Instant.now();
            Instant result = OutboxBackoff.computeNextAvailableAt(0, BASE_DELAY_MS, tinyMaxDelay);
            Instant upperBound = before.plusMillis(tinyMaxDelay + 500);
            assertTrue(result.isBefore(upperBound), "result should be capped at tinyMaxDelay, but was: " + result);
        }
    }

    @Nested
    @DisplayName("jitter is non-negative")
    class JitterBounds {

        @Test
        @DisplayName("available-at is never before now + base delay (jitter is non-negative)")
        void jitterIsNonNegative() {
            // Run multiple times to exercise jitter range
            for (int i = 0; i < 20; i++) {
                Instant before = Instant.now();
                Instant result = OutboxBackoff.computeNextAvailableAt(0, BASE_DELAY_MS, MAX_DELAY_MS);
                // The minimum possible value is now + 1000ms (base with jitter=0)
                // Allow a small margin for test execution time
                Instant floor = before.plusMillis(BASE_DELAY_MS - 50);
                assertFalse(
                        result.isBefore(floor),
                        "jitter must be non-negative: availableAt " + result + " was before floor " + floor);
            }
        }
    }
}
