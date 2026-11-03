// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.DestinationType;
import java.time.Instant;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxDeadLetterQuery")
class OutboxDeadLetterQueryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");

    private static void rejects(Consumer<OutboxDeadLetterQuery.Builder> setter) {
        OutboxDeadLetterQuery.Builder builder = OutboxDeadLetterQuery.builder();
        setter.accept(builder);
        assertThrows(ValidationException.class, builder::build);
    }

    private static void accepts(Consumer<OutboxDeadLetterQuery.Builder> setter) {
        OutboxDeadLetterQuery.Builder builder = OutboxDeadLetterQuery.builder();
        setter.accept(builder);
        assertDoesNotThrow(builder::build);
    }

    @Test
    @DisplayName("builder defaults: no filters and limit 50")
    void defaults() {
        OutboxDeadLetterQuery query = OutboxDeadLetterQuery.builder().build();
        assertEquals(50, query.limit());
        assertNull(query.entryId());
        assertNull(query.destinationType());
        assertNull(query.destination());
        assertNull(query.eventType());
        assertNull(query.aggregateType());
        assertNull(query.aggregateId());
        assertNull(query.errorType());
        assertNull(query.failedFrom());
        assertNull(query.failedBefore());
        assertNull(query.createdFrom());
        assertNull(query.createdBefore());
        assertNull(query.after());
    }

    @Test
    @DisplayName("every builder setter is carried to the record")
    void carriesEverything() {
        OutboxDeadLetterKey key = new OutboxDeadLetterKey(T1, 9L);
        OutboxDeadLetterQuery query = OutboxDeadLetterQuery.builder()
                .entryId(7L)
                .destinationType(DestinationType.KAFKA)
                .destination("d")
                .eventType("e")
                .aggregateType("at")
                .aggregateId("ai")
                .errorType("et")
                .failedFrom(T0)
                .failedBefore(T1)
                .createdFrom(T0)
                .createdBefore(T1)
                .limit(200)
                .after(key)
                .build();
        assertEquals(7L, query.entryId());
        assertEquals(DestinationType.KAFKA, query.destinationType());
        assertEquals("d", query.destination());
        assertEquals("e", query.eventType());
        assertEquals("at", query.aggregateType());
        assertEquals("ai", query.aggregateId());
        assertEquals("et", query.errorType());
        assertEquals(T0, query.failedFrom());
        assertEquals(T1, query.failedBefore());
        assertEquals(T0, query.createdFrom());
        assertEquals(T1, query.createdBefore());
        assertEquals(200, query.limit());
        assertEquals(key, query.after());
    }

    @Test
    @DisplayName("limit accepts 1 and 200, rejects 0 and 201")
    void limitBounds() {
        accepts(b -> b.limit(1));
        accepts(b -> b.limit(200));
        rejects(b -> b.limit(0));
        rejects(b -> b.limit(201));
        rejects(b -> b.limit(-1));
    }

    @Test
    @DisplayName("entry id accepts 1, rejects 0 and negatives")
    void entryIdBounds() {
        accepts(b -> b.entryId(1L));
        accepts(b -> b.entryId(Long.MAX_VALUE));
        rejects(b -> b.entryId(0L));
        rejects(b -> b.entryId(-5L));
    }

    @Test
    @DisplayName("255-character string filters accept 255, reject 256, empty and blank")
    void textBounds() {
        String max = "a".repeat(255);
        String over = "a".repeat(256);
        accepts(b -> b.destination(max));
        accepts(b -> b.eventType(max));
        accepts(b -> b.aggregateType(max));
        accepts(b -> b.aggregateId(max));
        rejects(b -> b.destination(over));
        rejects(b -> b.eventType(over));
        rejects(b -> b.aggregateType(over));
        rejects(b -> b.aggregateId(over));
        rejects(b -> b.destination(""));
        rejects(b -> b.eventType("  "));
        rejects(b -> b.aggregateType("\t"));
        rejects(b -> b.aggregateId(" "));
    }

    @Test
    @DisplayName("error type accepts 500, rejects 501 and blank")
    void errorTypeBounds() {
        accepts(b -> b.errorType("e".repeat(500)));
        rejects(b -> b.errorType("e".repeat(501)));
        rejects(b -> b.errorType(""));
        rejects(b -> b.errorType(" "));
    }

    @Test
    @DisplayName("length counts code points, not UTF-16 units")
    void codePoints() {
        accepts(b -> b.destination("😀".repeat(255)));
        rejects(b -> b.destination("😀".repeat(256)));
    }

    @Test
    @DisplayName("time ranges require from < before when both are set")
    void ranges() {
        accepts(b -> b.failedFrom(T0));
        accepts(b -> b.failedBefore(T0));
        accepts(b -> b.failedFrom(T0).failedBefore(T1));
        rejects(b -> b.failedFrom(T0).failedBefore(T0));
        rejects(b -> b.failedFrom(T1).failedBefore(T0));
        accepts(b -> b.createdFrom(T0).createdBefore(T1));
        rejects(b -> b.createdFrom(T0).createdBefore(T0));
        rejects(b -> b.createdFrom(T1).createdBefore(T0));
    }

    @Test
    @DisplayName("validation messages never echo the offending value")
    void messagesDoNotEchoValues() {
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> OutboxDeadLetterQuery.builder().destination(" ".repeat(3)).build());
        assertEquals("destination must not be blank", e.getMessage());
    }

    @Test
    @DisplayName("records with equal components are equal")
    void equality() {
        BiFunction<Integer, Long, OutboxDeadLetterQuery> make = (limit, id) ->
                OutboxDeadLetterQuery.builder().limit(limit).entryId(id).build();
        assertEquals(make.apply(5, 1L), make.apply(5, 1L));
    }
}
