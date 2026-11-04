// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for the validation of {@link OutboxEntrySummary}, {@link OutboxEntryDetail} and {@link OutboxEntryPayload}. */
@DisplayName("outbox entry result records")
class OutboxEntryRecordsTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static String message(Runnable action) {
        return assertThrows(ValidationException.class, action::run).getMessage();
    }

    private static OutboxEntrySummary summary(
            long id,
            OutboxEntryState state,
            DestinationType type,
            String destination,
            String eventType,
            int attempt,
            int maxAttempts,
            Instant created,
            Instant updated) {
        return new OutboxEntrySummary(
                id, state, type, destination, eventType, null, null, attempt, maxAttempts, null, created, updated);
    }

    private static OutboxEntryDetail detail(
            long id,
            OutboxEntryState state,
            DestinationType type,
            String destination,
            String eventType,
            int attempt,
            int maxAttempts,
            Instant created,
            Instant updated,
            Instant available,
            String lastError) {
        return new OutboxEntryDetail(
                id,
                state,
                type,
                destination,
                eventType,
                null,
                null,
                attempt,
                maxAttempts,
                null,
                created,
                updated,
                available,
                null,
                null,
                null,
                null,
                lastError,
                false);
    }

    @Nested
    @DisplayName("OutboxEntrySummary")
    class Summary {

        @Test
        @DisplayName("a complete summary keeps every component, nullable ones included")
        void valid() {
            OutboxEntrySummary summary = new OutboxEntrySummary(
                    7,
                    OutboxEntryState.DISCARDED,
                    DestinationType.KAFKA,
                    "topic",
                    "event",
                    "Order",
                    "o-1",
                    3,
                    20,
                    "java.io.IOException",
                    AT,
                    AT.plusSeconds(1));

            assertEquals(7, summary.entryId());
            assertEquals(OutboxEntryState.DISCARDED, summary.state());
            assertEquals(DestinationType.KAFKA, summary.destinationType());
            assertEquals("topic", summary.destination());
            assertEquals("event", summary.eventType());
            assertEquals("Order", summary.aggregateType());
            assertEquals("o-1", summary.aggregateId());
            assertEquals(3, summary.attempt());
            assertEquals(20, summary.maxAttempts());
            assertEquals("java.io.IOException", summary.errorType());
            assertEquals(AT, summary.createdAt());
            assertEquals(AT.plusSeconds(1), summary.updatedAt());
        }

        @Test
        @DisplayName("nullable components may be null and zero counts are allowed")
        void nullablesAndZero() {
            OutboxEntrySummary summary =
                    summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, AT, AT);

            assertNull(summary.aggregateType());
            assertNull(summary.aggregateId());
            assertNull(summary.errorType());
        }

        @Test
        @DisplayName("each invalid component is rejected with its own message")
        void invalid() {
            assertEquals(
                    "entryId must be positive",
                    message(() ->
                            summary(0, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, AT, AT)));
            assertEquals(
                    "entryId must be positive",
                    message(() ->
                            summary(-1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, AT, AT)));
            assertEquals(
                    "state must not be null",
                    message(() -> summary(1, null, DestinationType.SERVICE, "d", "e", 0, 0, AT, AT)));
            assertEquals(
                    "destinationType must not be null",
                    message(() -> summary(1, OutboxEntryState.PENDING, null, "d", "e", 0, 0, AT, AT)));
            assertEquals(
                    "destination must not be null",
                    message(() ->
                            summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, null, "e", 0, 0, AT, AT)));
            assertEquals(
                    "eventType must not be null",
                    message(() ->
                            summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", null, 0, 0, AT, AT)));
            assertEquals(
                    "attempt must not be negative",
                    message(() ->
                            summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", -1, 0, AT, AT)));
            assertEquals(
                    "maxAttempts must not be negative",
                    message(() ->
                            summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, -1, AT, AT)));
            assertEquals(
                    "createdAt must not be null",
                    message(() ->
                            summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, null, AT)));
            assertEquals(
                    "updatedAt must not be null",
                    message(() ->
                            summary(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, AT, null)));
        }
    }

    @Nested
    @DisplayName("OutboxEntryDetail")
    class Detail {

        @Test
        @DisplayName("a complete detail keeps every component")
        void valid() {
            OutboxEntryDetail detail = new OutboxEntryDetail(
                    9,
                    OutboxEntryState.DEAD_LETTER,
                    DestinationType.SERVICE,
                    "target",
                    "event",
                    "Order",
                    "o-1",
                    5,
                    7,
                    "java.io.IOException",
                    AT,
                    AT.plusSeconds(1),
                    AT.plusSeconds(2),
                    AT.plusSeconds(3),
                    AT.plusSeconds(4),
                    AT.plusSeconds(5),
                    "node-1",
                    "boom",
                    true);

            assertEquals(9, detail.entryId());
            assertEquals(OutboxEntryState.DEAD_LETTER, detail.state());
            assertEquals(DestinationType.SERVICE, detail.destinationType());
            assertEquals("target", detail.destination());
            assertEquals("event", detail.eventType());
            assertEquals("Order", detail.aggregateType());
            assertEquals("o-1", detail.aggregateId());
            assertEquals(5, detail.attempt());
            assertEquals(7, detail.maxAttempts());
            assertEquals("java.io.IOException", detail.errorType());
            assertEquals(AT, detail.createdAt());
            assertEquals(AT.plusSeconds(1), detail.updatedAt());
            assertEquals(AT.plusSeconds(2), detail.availableAt());
            assertEquals(AT.plusSeconds(3), detail.scheduledAt());
            assertEquals(AT.plusSeconds(4), detail.publishedAt());
            assertEquals(AT.plusSeconds(5), detail.claimedAt());
            assertEquals("node-1", detail.claimedBy());
            assertEquals("boom", detail.lastError());
            assertTrue(detail.lastErrorTruncated());
        }

        @Test
        @DisplayName("the last error may be absent or exactly 4096 characters, and 4097 is rejected")
        void lastErrorLimit() {
            assertEquals(4096, OutboxEntryDetail.MAX_LAST_ERROR_LENGTH);
            OutboxEntryDetail none =
                    detail(1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, AT, AT, AT, null);
            OutboxEntryDetail atLimit = detail(
                    1, OutboxEntryState.PENDING, DestinationType.SERVICE, "d", "e", 0, 0, AT, AT, AT, "x".repeat(4096));

            assertNull(none.lastError());
            assertEquals(4096, atLimit.lastError().length());
            assertEquals(
                    "lastError must be at most 4096 characters",
                    message(() -> detail(
                            1,
                            OutboxEntryState.PENDING,
                            DestinationType.SERVICE,
                            "d",
                            "e",
                            0,
                            0,
                            AT,
                            AT,
                            AT,
                            "x".repeat(4097))));
        }

        @Test
        @DisplayName("the last error limit counts code points: 4096 supplementary characters fit and 4097 do not")
        void lastErrorLimitCountsCodePoints() {
            String emoji = "\uD83D\uDE00";

            OutboxEntryDetail atLimit = detail(
                    1,
                    OutboxEntryState.PENDING,
                    DestinationType.SERVICE,
                    "d",
                    "e",
                    0,
                    0,
                    AT,
                    AT,
                    AT,
                    emoji.repeat(4096));

            assertEquals(8192, atLimit.lastError().length());
            assertEquals(
                    "lastError must be at most 4096 characters",
                    message(() -> detail(
                            1,
                            OutboxEntryState.PENDING,
                            DestinationType.SERVICE,
                            "d",
                            "e",
                            0,
                            0,
                            AT,
                            AT,
                            AT,
                            emoji.repeat(4097))));
        }

        @Test
        @DisplayName("toString shows the entry id and state and never the last error")
        void toStringHidesLastError() {
            OutboxEntryDetail detail = detail(
                    42,
                    OutboxEntryState.DEAD_LETTER,
                    DestinationType.SERVICE,
                    "secret-destination",
                    "secret-event",
                    1,
                    2,
                    AT,
                    AT,
                    AT,
                    "secret-last-error");

            String text = detail.toString();

            assertEquals("OutboxEntryDetail[entryId=42, state=DEAD_LETTER]", text);
            assertFalse(text.contains("secret"));
        }

        @Test
        @DisplayName("each invalid component is rejected with its own message")
        void invalid() {
            OutboxEntryState state = OutboxEntryState.PENDING;
            DestinationType type = DestinationType.SERVICE;

            assertEquals(
                    "entryId must be positive",
                    message(() -> detail(0, state, type, "d", "e", 0, 0, AT, AT, AT, null)));
            assertEquals(
                    "state must not be null", message(() -> detail(1, null, type, "d", "e", 0, 0, AT, AT, AT, null)));
            assertEquals(
                    "destinationType must not be null",
                    message(() -> detail(1, state, null, "d", "e", 0, 0, AT, AT, AT, null)));
            assertEquals(
                    "destination must not be null",
                    message(() -> detail(1, state, type, null, "e", 0, 0, AT, AT, AT, null)));
            assertEquals(
                    "eventType must not be null",
                    message(() -> detail(1, state, type, "d", null, 0, 0, AT, AT, AT, null)));
            assertEquals(
                    "attempt must not be negative",
                    message(() -> detail(1, state, type, "d", "e", -1, 0, AT, AT, AT, null)));
            assertEquals(
                    "maxAttempts must not be negative",
                    message(() -> detail(1, state, type, "d", "e", 0, -1, AT, AT, AT, null)));
            assertEquals(
                    "createdAt must not be null",
                    message(() -> detail(1, state, type, "d", "e", 0, 0, null, AT, AT, null)));
            assertEquals(
                    "updatedAt must not be null",
                    message(() -> detail(1, state, type, "d", "e", 0, 0, AT, null, AT, null)));
            assertEquals(
                    "availableAt must not be null",
                    message(() -> detail(1, state, type, "d", "e", 0, 0, AT, AT, null, null)));
        }
    }

    @Nested
    @DisplayName("OutboxEntryPayload")
    class Payload {

        @Test
        @DisplayName("the payload and headers are kept")
        void valid() {
            OutboxEntryPayload payload = new OutboxEntryPayload(3, "{\"a\":1}", Map.of("h", "v"));

            assertEquals(3, payload.entryId());
            assertEquals("{\"a\":1}", payload.payloadJson());
            assertEquals(Map.of("h", "v"), payload.headers());
        }

        @Test
        @DisplayName("null headers read as an empty map and an empty payload text is accepted")
        void nullHeaders() {
            OutboxEntryPayload payload = new OutboxEntryPayload(1, "", null);

            assertTrue(payload.headers().isEmpty());
            assertEquals("", payload.payloadJson());
        }

        @Test
        @DisplayName("the headers are copied and cannot be changed")
        void headersCopied() {
            Map<String, String> source = new HashMap<>(Map.of("h", "v"));
            OutboxEntryPayload payload = new OutboxEntryPayload(1, "{}", source);

            source.put("x", "y");

            assertEquals(Map.of("h", "v"), payload.headers());
            assertThrows(
                    UnsupportedOperationException.class, () -> payload.headers().put("a", "b"));
        }

        @Test
        @DisplayName("invalid arguments are rejected")
        void invalid() {
            Map<String, String> nullValue = new HashMap<>();
            nullValue.put("h", null);
            Map<String, String> nullKey = new HashMap<>();
            nullKey.put(null, "v");

            assertEquals("entryId must be positive", message(() -> new OutboxEntryPayload(0, "{}", Map.of())));
            assertEquals("entryId must be positive", message(() -> new OutboxEntryPayload(-4, "{}", Map.of())));
            assertEquals("payloadJson must not be null", message(() -> new OutboxEntryPayload(1, null, Map.of())));
            assertEquals(
                    "headers must not contain null keys or values",
                    message(() -> new OutboxEntryPayload(1, "{}", nullValue)));
            assertEquals(
                    "headers must not contain null keys or values",
                    message(() -> new OutboxEntryPayload(1, "{}", nullKey)));
        }

        @Test
        @DisplayName("toString shows the entry id and never the content")
        void toStringHidesContent() {
            OutboxEntryPayload payload =
                    new OutboxEntryPayload(42, "{\"ssn\":\"secret-payload\"}", Map.of("auth", "secret-header"));

            String text = payload.toString();

            assertEquals("OutboxEntryPayload[entryId=42]", text);
            assertFalse(text.contains("secret"));
        }
    }
}
