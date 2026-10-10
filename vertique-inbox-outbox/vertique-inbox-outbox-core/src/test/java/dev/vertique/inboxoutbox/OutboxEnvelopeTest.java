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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the header copy {@link OutboxEnvelope} takes at construction. */
@DisplayName("OutboxEnvelope")
class OutboxEnvelopeTest {

    @Test
    @DisplayName("mutating the source map after construction does not change the envelope")
    void sourceMapMutationDoesNotReachEnvelope() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("x-tenant", "acme");

        OutboxEnvelope envelope = envelopeWith(source);
        source.put("x-late", "added");
        source.remove("x-tenant");

        assertEquals(Map.of("x-tenant", "acme"), envelope.headers());
    }

    @Test
    @DisplayName("the accessor's map rejects mutation")
    void headersMapIsUnmodifiable() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("x-tenant", "acme");
        OutboxEnvelope envelope = envelopeWith(source);

        assertThrows(
                UnsupportedOperationException.class, () -> envelope.headers().put("x-new", "value"));
        assertThrows(
                UnsupportedOperationException.class, () -> envelope.headers().remove("x-tenant"));
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
                List.copyOf(envelopeWith(source).headers().keySet()));
    }

    @Test
    @DisplayName("a null map is an empty map")
    void nullMapIsEmpty() {
        assertTrue(envelopeWith(null).headers().isEmpty());
    }

    private static OutboxEnvelope envelopeWith(Map<String, String> headers) {
        return new OutboxEnvelope(
                42L,
                "Order",
                "order-123",
                "order.placed",
                "orders/handle-order-placed",
                new JsonObject().put("orderId", "order-123"),
                headers,
                OutboxMetadata.empty(),
                null,
                0,
                Instant.EPOCH);
    }
}
