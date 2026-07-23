// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RestClientAttemptCompletion} compact-constructor invariants.
 *
 * <p>Verifies that each guard in the compact constructor fires for the correct invalid inputs and
 * that valid response-only and error-only instances construct without exception. Also covers
 * {@link RestClientAttemptTarget} as a simple data-holding record.
 */
class RestClientAttemptCompletionTest {

    // --- Shared fixtures ---

    private static final RestClientAttemptTarget VALID_TARGET = new RestClientAttemptTarget("https", "svc", 8443, "/x");
    private static final Instant VALID_INSTANT = Instant.parse("2026-06-07T10:00:00Z");
    private static final RestClientResponseContext VALID_RESPONSE =
            new RestClientResponseContext(200, "OK", Buffer.buffer(), MultiMap.caseInsensitiveMultiMap());

    /**
     * Builds the minimal valid completion with a response (no error).
     *
     * @param callId         logical call id
     * @param attemptOrdinal 1-based attempt ordinal
     * @param durationMs     elapsed milliseconds
     * @param completedAt    wall-clock instant
     * @param target         the call target
     * @return the constructed completion
     */
    private static RestClientAttemptCompletion responseCompletion(
            String callId, int attemptOrdinal, long durationMs, Instant completedAt, RestClientAttemptTarget target) {
        return new RestClientAttemptCompletion(
                VALID_RESPONSE, null, callId, attemptOrdinal, durationMs, completedAt, target);
    }

    // === callId invariants ===

    @Nested
    @DisplayName("callId must not be blank or null")
    class CallIdInvariants {

        @Test
        @DisplayName("null callId throws IllegalArgumentException")
        void nullCallIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> responseCompletion(null, 1, 0L, VALID_INSTANT, VALID_TARGET),
                    "null callId must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("blank callId throws IllegalArgumentException")
        void blankCallIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> responseCompletion("   ", 1, 0L, VALID_INSTANT, VALID_TARGET),
                    "blank callId must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("empty callId throws IllegalArgumentException")
        void emptyCallIdThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> responseCompletion("", 1, 0L, VALID_INSTANT, VALID_TARGET),
                    "empty callId must throw IllegalArgumentException");
        }
    }

    // === attemptOrdinal invariants ===

    @Nested
    @DisplayName("attemptOrdinal must be >= 1")
    class AttemptOrdinalInvariants {

        @Test
        @DisplayName("attemptOrdinal = 0 throws IllegalArgumentException")
        void zeroOrdinalThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> responseCompletion("call-1", 0, 0L, VALID_INSTANT, VALID_TARGET),
                    "attemptOrdinal = 0 must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("negative attemptOrdinal throws IllegalArgumentException")
        void negativeOrdinalThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> responseCompletion("call-1", -1, 0L, VALID_INSTANT, VALID_TARGET),
                    "negative attemptOrdinal must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("attemptOrdinal = 1 is accepted")
        void ordinalOneIsAccepted() {
            assertDoesNotThrow(() -> responseCompletion("call-1", 1, 0L, VALID_INSTANT, VALID_TARGET));
        }
    }

    // === durationMs invariants ===

    @Nested
    @DisplayName("durationMs must be >= 0")
    class DurationMsInvariants {

        @Test
        @DisplayName("durationMs = -1 throws IllegalArgumentException")
        void negativeDurationThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> responseCompletion("call-1", 1, -1L, VALID_INSTANT, VALID_TARGET),
                    "durationMs < 0 must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("durationMs = 0 is accepted")
        void zeroDurationAccepted() {
            assertDoesNotThrow(() -> responseCompletion("call-1", 1, 0L, VALID_INSTANT, VALID_TARGET));
        }

        @Test
        @DisplayName("positive durationMs is accepted")
        void positiveDurationAccepted() {
            assertDoesNotThrow(() -> responseCompletion("call-1", 1, 500L, VALID_INSTANT, VALID_TARGET));
        }
    }

    // === completedAt invariants ===

    @Nested
    @DisplayName("completedAt must not be null")
    class CompletedAtInvariants {

        @Test
        @DisplayName("null completedAt throws NullPointerException")
        void nullCompletedAtThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> responseCompletion("call-1", 1, 0L, null, VALID_TARGET),
                    "null completedAt must throw NullPointerException");
        }
    }

    // === target invariants ===

    @Nested
    @DisplayName("target must not be null")
    class TargetInvariants {

        @Test
        @DisplayName("null target throws NullPointerException")
        void nullTargetThrows() {
            assertThrows(
                    NullPointerException.class,
                    () -> responseCompletion("call-1", 1, 0L, VALID_INSTANT, null),
                    "null target must throw NullPointerException");
        }
    }

    // === response/error exclusivity invariants ===

    @Nested
    @DisplayName("exactly one of {response, error} must be non-null")
    class ResponseErrorExclusivity {

        @Test
        @DisplayName("both response and error non-null throws IllegalArgumentException")
        void bothNonNullThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestClientAttemptCompletion(
                            VALID_RESPONSE, new RuntimeException("both"), "call-1", 1, 0L, VALID_INSTANT, VALID_TARGET),
                    "both response and error non-null must throw IllegalArgumentException");
        }

        @Test
        @DisplayName("both response and error null throws IllegalArgumentException")
        void bothNullThrows() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RestClientAttemptCompletion(null, null, "call-1", 1, 0L, VALID_INSTANT, VALID_TARGET),
                    "both response and error null must throw IllegalArgumentException");
        }
    }

    // === Valid construction ===

    @Nested
    @DisplayName("valid instances construct successfully and accessors return inputs")
    class ValidConstruction {

        @Test
        @DisplayName("response-only instance constructs and accessors return inputs")
        void responseOnlyConstructsOk() {
            Instant now = VALID_INSTANT;
            RestClientAttemptCompletion c =
                    assertDoesNotThrow(() -> responseCompletion("call-resp", 2, 42L, now, VALID_TARGET));

            assertEquals("call-resp", c.callId());
            assertEquals(2, c.attemptOrdinal());
            assertEquals(42L, c.durationMs());
            assertSame(now, c.completedAt());
            assertSame(VALID_TARGET, c.target());
            assertSame(VALID_RESPONSE, c.response());
            assertEquals(null, c.error());
        }

        @Test
        @DisplayName("error-only instance constructs and accessors return inputs")
        void errorOnlyConstructsOk() {
            RuntimeException ex = new RuntimeException("transport down");
            RestClientAttemptCompletion c = assertDoesNotThrow(
                    () -> new RestClientAttemptCompletion(null, ex, "call-err", 1, 5L, VALID_INSTANT, VALID_TARGET));

            assertEquals("call-err", c.callId());
            assertEquals(1, c.attemptOrdinal());
            assertEquals(5L, c.durationMs());
            assertSame(VALID_INSTANT, c.completedAt());
            assertSame(VALID_TARGET, c.target());
            assertEquals(null, c.response());
            assertSame(ex, c.error());
        }
    }

    // === RestClientAttemptTarget record ===

    @Nested
    @DisplayName("RestClientAttemptTarget holds four components; null and -1 are permitted")
    class AttemptTargetRecord {

        @Test
        @DisplayName("all-specified target: accessors return constructor inputs")
        void allSpecifiedTarget() {
            RestClientAttemptTarget t = new RestClientAttemptTarget("https", "api.example.com", 443, "/v1/{id}");
            assertEquals("https", t.scheme());
            assertEquals("api.example.com", t.host());
            assertEquals(443, t.port());
            assertEquals("/v1/{id}", t.pathTemplate());
        }

        @Test
        @DisplayName("null scheme, null host, -1 port, null pathTemplate are all permitted")
        void nullsAndNegativePortPermitted() {
            assertDoesNotThrow(() -> {
                RestClientAttemptTarget t = new RestClientAttemptTarget(null, null, -1, null);
                assertEquals(null, t.scheme());
                assertEquals(null, t.host());
                assertEquals(-1, t.port());
                assertEquals(null, t.pathTemplate());
            });
        }
    }
}
