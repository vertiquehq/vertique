// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the header copy and the scope default {@link OutboxRecord} applies at construction. */
@DisplayName("OutboxRecord")
class OutboxRecordTest {

    @Test
    @DisplayName("mutating the source map after construction does not change the record")
    void sourceMapMutationDoesNotReachRecord() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("x-tenant", "acme");

        OutboxRecord record = recordWith(source);
        source.put("x-late", "added");
        source.remove("x-tenant");

        assertEquals(Map.of("x-tenant", "acme"), record.headers());
    }

    @Test
    @DisplayName("the accessor's map rejects mutation")
    void headersMapIsUnmodifiable() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("x-tenant", "acme");
        OutboxRecord record = recordWith(source);

        assertThrows(UnsupportedOperationException.class, () -> record.headers().put("x-new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> record.headers().remove("x-tenant"));
    }

    @Test
    @DisplayName("the copy keeps the iteration order of the given map")
    void copyKeepsIterationOrder() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("x-third", "3");
        source.put("x-first", "1");
        source.put("x-second", "2");

        assertEquals(
                List.of("x-third", "x-first", "x-second"),
                List.copyOf(recordWith(source).headers().keySet()));
    }

    @Test
    @DisplayName("a null map is an empty map")
    void nullMapIsEmpty() {
        assertTrue(recordWith(null).headers().isEmpty());
    }

    @Test
    @DisplayName("the previous-arity constructor and a null scope key in the canonical form both read the default")
    void previousArityYieldsDefault() {
        // Given: a record from the previous-arity constructor, and one from the canonical form with null
        OutboxRecord previousArity = recordWith(Map.of());
        OutboxRecord canonicalWithNull = canonicalRecordWith(null);

        // When/Then: both report the default scope
        assertEquals("default", previousArity.scopeKey());
        assertEquals("default", canonicalWithNull.scopeKey());
    }

    private static OutboxRecord canonicalRecordWith(String scopeKey) {
        return new OutboxRecord(
                42L,
                UUID.randomUUID(),
                "Order",
                "order-123",
                "order.placed",
                "orders/handle-order-placed",
                DestinationType.SERVICE,
                new JsonObject().put("orderId", "order-123"),
                Map.of(),
                OutboxMetadata.empty(),
                null,
                Instant.EPOCH,
                OutboxEntryState.PENDING,
                0,
                20,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                scopeKey);
    }

    private static OutboxRecord recordWith(Map<String, String> headers) {
        return new OutboxRecord(
                42L,
                UUID.randomUUID(),
                "Order",
                "order-123",
                "order.placed",
                "orders/handle-order-placed",
                DestinationType.SERVICE,
                new JsonObject().put("orderId", "order-123"),
                headers,
                OutboxMetadata.empty(),
                null,
                Instant.EPOCH,
                OutboxEntryState.PENDING,
                0,
                20,
                null,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
