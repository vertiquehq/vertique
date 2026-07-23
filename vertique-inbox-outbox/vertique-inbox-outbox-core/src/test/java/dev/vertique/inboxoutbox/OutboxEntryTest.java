// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboxEntry} builder construction, field defaults, and immutability
 * invariants.
 */
@DisplayName("OutboxEntry")
class OutboxEntryTest {

    @Nested
    @DisplayName("builder with all fields")
    class AllFields {

        @Test
        @DisplayName("sets aggregateType")
        void setsAggregateType() {
            OutboxEntry entry = fullEntry().build();
            assertEquals("Order", entry.aggregateType());
        }

        @Test
        @DisplayName("sets aggregateId")
        void setsAggregateId() {
            OutboxEntry entry = fullEntry().build();
            assertEquals("order-123", entry.aggregateId());
        }

        @Test
        @DisplayName("sets eventType")
        void setsEventType() {
            OutboxEntry entry = fullEntry().build();
            assertEquals("order.placed", entry.eventType());
        }

        @Test
        @DisplayName("sets destinationType")
        void setsDestinationType() {
            OutboxEntry entry = fullEntry().build();
            assertEquals(DestinationType.SERVICE, entry.destinationType());
        }

        @Test
        @DisplayName("sets destination")
        void setsDestination() {
            OutboxEntry entry = fullEntry().build();
            assertEquals("orders/handle-order-placed", entry.destination());
        }

        @Test
        @DisplayName("sets payload")
        void setsPayload() {
            JsonObject payload = new JsonObject().put("orderId", "order-123");
            OutboxEntry entry = fullEntry().payload(payload).build();
            assertEquals(payload, entry.payload());
        }

        @Test
        @DisplayName("sets headers")
        void setsHeaders() {
            Map<String, String> headers = Map.of("x-trace-id", "abc123");
            OutboxEntry entry = fullEntry().headers(headers).build();
            assertEquals("abc123", entry.headers().get("x-trace-id"));
        }

        @Test
        @DisplayName("sets scheduledAt")
        void setsScheduledAt() {
            Instant scheduledAt = Instant.now().plusSeconds(60);
            OutboxEntry entry = fullEntry().scheduledAt(scheduledAt).build();
            assertEquals(scheduledAt, entry.scheduledAt());
        }

        @Test
        @DisplayName("sets availableAt")
        void setsAvailableAt() {
            Instant availableAt = Instant.now().plusSeconds(30);
            OutboxEntry entry = fullEntry().availableAt(availableAt).build();
            assertEquals(availableAt, entry.availableAt());
        }
    }

    @Nested
    @DisplayName("builder with minimal required fields")
    class MinimalFields {

        @Test
        @DisplayName("builds entry with only required fields set")
        void buildsWithRequiredFieldsOnly() {
            OutboxEntry entry = minimalEntry().build();
            assertNotNull(entry);
            assertEquals("order.placed", entry.eventType());
            assertEquals(DestinationType.SERVICE, entry.destinationType());
            assertEquals("orders/handle-order-placed", entry.destination());
        }

        @Test
        @DisplayName("aggregateType defaults to null when not set")
        void aggregateTypeDefaultsToNull() {
            OutboxEntry entry = minimalEntry().build();
            assertNull(entry.aggregateType());
        }

        @Test
        @DisplayName("aggregateId defaults to null when not set")
        void aggregateIdDefaultsToNull() {
            OutboxEntry entry = minimalEntry().build();
            assertNull(entry.aggregateId());
        }

        @Test
        @DisplayName("scheduledAt defaults to null when not set")
        void scheduledAtDefaultsToNull() {
            OutboxEntry entry = minimalEntry().build();
            assertNull(entry.scheduledAt());
        }

        @Test
        @DisplayName("availableAt defaults to null when not set")
        void availableAtDefaultsToNull() {
            OutboxEntry entry = minimalEntry().build();
            assertNull(entry.availableAt());
        }
    }

    @Nested
    @DisplayName("default headers")
    class DefaultHeaders {

        @Test
        @DisplayName("headers is empty map when not set")
        void headersDefaultsToEmptyMap() {
            OutboxEntry entry = minimalEntry().build();
            assertNotNull(entry.headers());
            assertTrue(entry.headers().isEmpty());
        }

        @Test
        @DisplayName("default headers map is not null")
        void defaultHeadersIsNotNull() {
            OutboxEntry entry = minimalEntry().build();
            assertNotNull(entry.headers());
        }
    }

    // --- Helpers ---

    private OutboxEntry.OutboxEntryBuilder minimalEntry() {
        return OutboxEntry.builder()
                .eventType("order.placed")
                .destinationType(DestinationType.SERVICE)
                .destination("orders/handle-order-placed")
                .payload(new JsonObject().put("orderId", "order-123"));
    }

    private OutboxEntry.OutboxEntryBuilder fullEntry() {
        return minimalEntry().aggregateType("Order").aggregateId("order-123");
    }
}
