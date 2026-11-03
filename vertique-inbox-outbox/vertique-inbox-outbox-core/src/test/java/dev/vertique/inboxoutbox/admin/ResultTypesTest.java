// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.DestinationType;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Result types")
class ResultTypesTest {

    private static final Instant NOW = Fixtures.NOW;

    private static OutboxDeadLetterSummary summary(
            long id,
            long revision,
            DestinationType type,
            String destination,
            String event,
            int attempt,
            int max,
            Instant created,
            Instant failed) {
        return new OutboxDeadLetterSummary(
                id, revision, type, destination, event, null, null, attempt, max, null, created, failed);
    }

    @Test
    @DisplayName("summary keeps components and allows null aggregate and error type")
    void summaryValid() {
        OutboxDeadLetterSummary s = summary(1L, 0L, DestinationType.SERVICE, "d", "e", 0, 0, NOW, NOW);
        assertEquals(1L, s.entryId());
        assertEquals(0L, s.revision());
        assertEquals(DestinationType.SERVICE, s.destinationType());
        assertEquals("d", s.destination());
        assertEquals("e", s.eventType());
        assertNull(s.aggregateType());
        assertNull(s.aggregateId());
        assertNull(s.errorType());
        assertEquals(0, s.attempt());
        assertEquals(0, s.maxAttempts());
        assertEquals(NOW, s.createdAt());
        assertEquals(NOW, s.failedAt());
    }

    @Test
    @DisplayName("summary rejects out-of-range and missing values")
    void summaryInvalid() {
        DestinationType k = DestinationType.KAFKA;
        assertThrows(ValidationException.class, () -> summary(0L, 0L, k, "d", "e", 0, 0, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, -1L, k, "d", "e", 0, 0, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, null, "d", "e", 0, 0, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, k, null, "e", 0, 0, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, k, "d", null, 0, 0, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, k, "d", "e", -1, 0, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, k, "d", "e", 0, -1, NOW, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, k, "d", "e", 0, 0, null, NOW));
        assertThrows(ValidationException.class, () -> summary(1L, 0L, k, "d", "e", 0, 0, NOW, null));
    }

    private static OutboxDeadLetterDetail detail(
            Instant available, Optional<Instant> scheduled, String lastError, boolean truncated) {
        return new OutboxDeadLetterDetail(
                1L,
                2L,
                DestinationType.KAFKA,
                "d",
                "e",
                "at",
                "ai",
                3,
                4,
                "et",
                NOW,
                NOW,
                available,
                scheduled,
                lastError,
                truncated);
    }

    @Test
    @DisplayName("detail keeps components; null scheduledAt reads as empty")
    void detailValid() {
        OutboxDeadLetterDetail d = detail(NOW, null, "boom", true);
        assertEquals(1L, d.entryId());
        assertEquals(2L, d.revision());
        assertEquals(DestinationType.KAFKA, d.destinationType());
        assertEquals("d", d.destination());
        assertEquals("e", d.eventType());
        assertEquals("at", d.aggregateType());
        assertEquals("ai", d.aggregateId());
        assertEquals(3, d.attempt());
        assertEquals(4, d.maxAttempts());
        assertEquals("et", d.errorType());
        assertEquals(NOW, d.createdAt());
        assertEquals(NOW, d.failedAt());
        assertEquals(NOW, d.availableAt());
        assertTrue(d.scheduledAt().isEmpty());
        assertEquals("boom", d.lastError());
        assertTrue(d.lastErrorTruncated());
        assertEquals(
                Optional.of(NOW), detail(NOW, Optional.of(NOW), null, false).scheduledAt());
        assertNull(detail(NOW, Optional.empty(), null, false).lastError());
    }

    @Test
    @DisplayName("detail last error accepts 4096 characters and rejects 4097")
    void detailLastErrorBound() {
        assertDoesNotThrow(() -> detail(NOW, Optional.empty(), "x".repeat(4096), false));
        assertThrows(ValidationException.class, () -> detail(NOW, Optional.empty(), "x".repeat(4097), false));
    }

    @Test
    @DisplayName("detail rejects missing and out-of-range values")
    void detailInvalid() {
        assertThrows(ValidationException.class, () -> detail(null, Optional.empty(), null, false));
        DestinationType k = DestinationType.KAFKA;
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        0L, 0L, k, "d", "e", null, null, 0, 0, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, -1L, k, "d", "e", null, null, 0, 0, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, null, "d", "e", null, null, 0, 0, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, k, null, "e", null, null, 0, 0, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, k, "d", null, null, null, 0, 0, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, k, "d", "e", null, null, -1, 0, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, k, "d", "e", null, null, 0, -1, null, NOW, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, k, "d", "e", null, null, 0, 0, null, null, NOW, NOW, Optional.empty(), null, false));
        assertThrows(
                ValidationException.class,
                () -> new OutboxDeadLetterDetail(
                        1L, 0L, k, "d", "e", null, null, 0, 0, null, NOW, null, NOW, Optional.empty(), null, false));
    }

    @Test
    @DisplayName("content copies headers immutably and null headers read as empty")
    void contentHeaders() {
        Map<String, String> source = new HashMap<>(Map.of("k", "v"));
        OutboxDeadLetterContent c = new OutboxDeadLetterContent(1L, 0L, "{}", source);
        source.put("other", "x");
        assertEquals(Map.of("k", "v"), c.headers());
        assertThrows(UnsupportedOperationException.class, () -> c.headers().put("a", "b"));
        assertTrue(new OutboxDeadLetterContent(1L, 0L, "{}", null).headers().isEmpty());
        assertEquals("{}", c.payloadJson());
        assertEquals(1L, c.entryId());
        assertEquals(0L, c.revision());
    }

    @Test
    @DisplayName("content rejects null header keys and values, missing payload and bad ids")
    void contentInvalid() {
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("k", null);
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "v");
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterContent(1L, 0L, "{}", nullValue));
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterContent(1L, 0L, "{}", nullKey));
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterContent(1L, 0L, null, Map.of()));
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterContent(0L, 0L, "{}", Map.of()));
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterContent(1L, -1L, "{}", Map.of()));
    }

    @Test
    @DisplayName("content toString hides payload and headers")
    void contentToString() {
        OutboxDeadLetterContent c = new OutboxDeadLetterContent(1L, 0L, "{\"secret\":1}", Map.of("hk", "hv"));
        assertFalse(c.toString().contains("secret"));
        assertFalse(c.toString().contains("hk"));
    }

    private static OutboxAdminReceipt receipt(
            UUID op,
            long entry,
            OutboxAdminAction action,
            OutboxAdminDisposition disposition,
            long prev,
            OptionalLong result,
            Instant at) {
        return new OutboxAdminReceipt(op, entry, action, disposition, prev, result, at);
    }

    @Test
    @DisplayName("retry receipt requires REQUEUED and a result revision; keeps components")
    void retryReceipt() {
        UUID op = UUID.randomUUID();
        OutboxAdminReceipt r =
                receipt(op, 1L, OutboxAdminAction.RETRY, OutboxAdminDisposition.REQUEUED, 2L, OptionalLong.of(3L), NOW);
        assertEquals(op, r.operationId());
        assertEquals(1L, r.entryId());
        assertEquals(OutboxAdminAction.RETRY, r.action());
        assertEquals(OutboxAdminDisposition.REQUEUED, r.disposition());
        assertEquals(2L, r.previousRevision());
        assertEquals(OptionalLong.of(3L), r.resultRevision());
        assertEquals(NOW, r.recordedAt());
        assertThrows(
                ValidationException.class,
                () -> receipt(
                        op,
                        1L,
                        OutboxAdminAction.RETRY,
                        OutboxAdminDisposition.DISCARDED,
                        2L,
                        OptionalLong.of(3L),
                        NOW));
        assertThrows(
                ValidationException.class,
                () -> receipt(
                        op,
                        1L,
                        OutboxAdminAction.RETRY,
                        OutboxAdminDisposition.REQUEUED,
                        2L,
                        OptionalLong.empty(),
                        NOW));
        assertThrows(
                ValidationException.class,
                () -> receipt(op, 1L, OutboxAdminAction.RETRY, OutboxAdminDisposition.REQUEUED, 2L, null, NOW));
        assertThrows(
                ValidationException.class,
                () -> receipt(
                        op,
                        1L,
                        OutboxAdminAction.RETRY,
                        OutboxAdminDisposition.REQUEUED,
                        2L,
                        OptionalLong.of(-1L),
                        NOW));
    }

    @Test
    @DisplayName("discard receipt requires DISCARDED and no result revision")
    void discardReceipt() {
        UUID op = UUID.randomUUID();
        OutboxAdminReceipt r = receipt(
                op, 1L, OutboxAdminAction.DISCARD, OutboxAdminDisposition.DISCARDED, 0L, OptionalLong.empty(), NOW);
        assertTrue(r.resultRevision().isEmpty());
        assertThrows(
                ValidationException.class,
                () -> receipt(
                        op,
                        1L,
                        OutboxAdminAction.DISCARD,
                        OutboxAdminDisposition.REQUEUED,
                        0L,
                        OptionalLong.empty(),
                        NOW));
        assertThrows(
                ValidationException.class,
                () -> receipt(
                        op,
                        1L,
                        OutboxAdminAction.DISCARD,
                        OutboxAdminDisposition.DISCARDED,
                        0L,
                        OptionalLong.of(1L),
                        NOW));
    }

    @Test
    @DisplayName("receipt rejects missing and out-of-range components")
    void receiptInvalid() {
        UUID op = UUID.randomUUID();
        OutboxAdminAction a = OutboxAdminAction.DISCARD;
        OutboxAdminDisposition d = OutboxAdminDisposition.DISCARDED;
        OptionalLong none = OptionalLong.empty();
        assertThrows(ValidationException.class, () -> receipt(null, 1L, a, d, 0L, none, NOW));
        assertThrows(ValidationException.class, () -> receipt(op, 0L, a, d, 0L, none, NOW));
        assertThrows(ValidationException.class, () -> receipt(op, 1L, null, d, 0L, none, NOW));
        assertThrows(ValidationException.class, () -> receipt(op, 1L, a, null, 0L, none, NOW));
        assertThrows(ValidationException.class, () -> receipt(op, 1L, a, d, -1L, none, NOW));
        assertThrows(ValidationException.class, () -> receipt(op, 1L, a, d, 0L, none, null));
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static OutboxAdminHistoryEntry history(
            UUID op,
            long entry,
            OutboxAdminActor actor,
            String reason,
            int attempt,
            int max,
            DestinationType type,
            String destination,
            String event) {
        return new OutboxAdminHistoryEntry(
                op,
                entry,
                OutboxAdminAction.DISCARD,
                OutboxAdminDisposition.DISCARDED,
                0L,
                OptionalLong.empty(),
                NOW,
                actor,
                reason,
                attempt,
                max,
                null,
                type,
                destination,
                event,
                null,
                null);
    }

    @Test
    @DisplayName("history entry keeps components")
    void historyValid() {
        OutboxAdminHistoryEntry e = Fixtures.historyEntry();
        assertEquals(1L, e.entryId());
        assertEquals(OutboxAdminAction.DISCARD, e.action());
        assertEquals(OutboxAdminDisposition.DISCARDED, e.disposition());
        assertEquals(2L, e.previousRevision());
        assertTrue(e.resultRevision().isEmpty());
        assertEquals(NOW, e.recordedAt());
        assertEquals(Fixtures.actor(), e.actor());
        assertEquals("because", e.reason());
        assertEquals(3, e.attempt());
        assertEquals(3, e.maxAttempts());
        assertEquals("java.io.IOException", e.errorType());
        assertEquals(DestinationType.KAFKA, e.destinationType());
        assertEquals("topic", e.destination());
        assertEquals("order.placed", e.eventType());
        assertEquals("Order", e.aggregateType());
        assertEquals("o-1", e.aggregateId());
        OutboxAdminHistoryEntry nullable =
                history(UUID.randomUUID(), 1L, Fixtures.actor(), "r", 0, 0, DestinationType.KAFKA, "d", "e");
        assertNull(nullable.errorType());
        assertNull(nullable.aggregateType());
        assertNull(nullable.aggregateId());
    }

    @Test
    @DisplayName("history entry rejects missing and out-of-range components")
    void historyInvalid() {
        UUID op = UUID.randomUUID();
        OutboxAdminActor actor = Fixtures.actor();
        DestinationType k = DestinationType.KAFKA;
        assertThrows(ValidationException.class, () -> history(null, 1L, actor, "r", 0, 0, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 0L, actor, "r", 0, 0, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, null, "r", 0, 0, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, " ", 0, 0, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, "x".repeat(1025), 0, 0, k, "d", "e"));
        assertDoesNotThrow(() -> history(op, 1L, actor, "x".repeat(1024), 0, 0, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, "r", -1, 0, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, "r", 0, -1, k, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, "r", 0, 0, null, "d", "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, "r", 0, 0, k, null, "e"));
        assertThrows(ValidationException.class, () -> history(op, 1L, actor, "r", 0, 0, k, "d", null));
    }

    @Test
    @DisplayName("history entry checks action, disposition and result revision agree")
    void historyConsistency() {
        assertThrows(
                ValidationException.class,
                () -> new OutboxAdminHistoryEntry(
                        UUID.randomUUID(),
                        1L,
                        OutboxAdminAction.RETRY,
                        OutboxAdminDisposition.REQUEUED,
                        0L,
                        null,
                        NOW,
                        Fixtures.actor(),
                        "r",
                        0,
                        0,
                        null,
                        DestinationType.KAFKA,
                        "d",
                        "e",
                        null,
                        null));
        OutboxAdminHistoryEntry retry = new OutboxAdminHistoryEntry(
                UUID.randomUUID(),
                1L,
                OutboxAdminAction.RETRY,
                OutboxAdminDisposition.REQUEUED,
                0L,
                OptionalLong.of(1L),
                NOW,
                Fixtures.actor(),
                "r",
                0,
                0,
                null,
                DestinationType.KAFKA,
                "d",
                "e",
                null,
                null);
        assertEquals(OptionalLong.of(1L), retry.resultRevision());
    }

    @Test
    @DisplayName("history entry toString hides reason and actor")
    void historyToString() {
        OutboxAdminHistoryEntry e = history(
                UUID.randomUUID(),
                1L,
                new OutboxAdminActor("user", "alice"),
                "secret reason",
                0,
                0,
                DestinationType.KAFKA,
                "d",
                "e");
        assertFalse(e.toString().contains("secret reason"));
        assertFalse(e.toString().contains("alice"));
    }
}
