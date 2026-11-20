// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
        @DisplayName("headers is an empty map when the builder is given null")
        void headersNullIsNormalisedToEmptyMap() {
            OutboxEntry entry = minimalEntry().headers(null).build();
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

    @Nested
    @DisplayName("header copy")
    class HeaderCopy {

        @Test
        @DisplayName("mutating the source map after build does not change the entry")
        void sourceMapMutationDoesNotReachEntry() {
            Map<String, String> source = new LinkedHashMap<>();
            source.put("x-tenant", "acme");

            OutboxEntry entry = minimalEntry().headers(source).build();
            source.put("x-late", "added");
            source.remove("x-tenant");

            assertEquals(Map.of("x-tenant", "acme"), entry.headers());
        }

        @Test
        @DisplayName("the accessor's map rejects mutation")
        void headersMapIsUnmodifiable() {
            Map<String, String> source = new LinkedHashMap<>();
            source.put("x-tenant", "acme");
            OutboxEntry entry = minimalEntry().headers(source).build();

            assertThrows(
                    UnsupportedOperationException.class, () -> entry.headers().put("x-new", "value"));
            assertThrows(
                    UnsupportedOperationException.class, () -> entry.headers().remove("x-tenant"));
        }

        @Test
        @DisplayName("the copy keeps the iteration order of the given map")
        void copyKeepsIterationOrder() {
            Map<String, String> source = new LinkedHashMap<>();
            source.put("x-third", "3");
            source.put("x-first", "1");
            source.put("x-second", "2");

            OutboxEntry entry = minimalEntry().headers(source).build();

            assertEquals(
                    List.of("x-third", "x-first", "x-second"),
                    List.copyOf(entry.headers().keySet()));
        }

        @Test
        @DisplayName("a null key or value is carried, so publish can reject it by name")
        void copyCarriesNullKeysAndValues() {
            Map<String, String> source = new LinkedHashMap<>();
            source.put("x-null-value", null);
            source.put(null, "value");

            OutboxEntry entry = minimalEntry().headers(source).build();

            assertEquals(source, entry.headers());
        }
    }

    @Nested
    @DisplayName("attempt limit")
    class AttemptLimit {

        @Test
        @DisplayName("an entry that sets none reports 20 and has no own limit")
        void unsetReportsDefault() {
            OutboxEntry entry = minimalEntry().build();

            assertEquals(20, entry.maxAttempts());
            assertFalse(entry.hasOwnMaxAttempts());
        }

        @Test
        @DisplayName("an entry that sets a limit reports it and has an own limit")
        void setReportsOwn() {
            OutboxEntry entry = minimalEntry().maxAttempts(3).build();

            assertEquals(3, entry.maxAttempts());
            assertTrue(entry.hasOwnMaxAttempts());
        }

        @Test
        @DisplayName("an own limit below 1 is rejected; 1 is accepted")
        void ownLimitBelowOneIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> minimalEntry().maxAttempts(0));
            assertThrows(IllegalArgumentException.class, () -> minimalEntry().maxAttempts(-1));
            assertEquals(1, minimalEntry().maxAttempts(1).build().maxAttempts());
        }

        @Test
        @DisplayName("an own limit below 1 in JSON is rejected")
        void ownLimitBelowOneInJsonIsRejected() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(
                            minimalEntry().maxAttempts(3).build())
                    .replace("\"maxAttempts\":3", "\"maxAttempts\":0");
            assertThrows(
                    com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> mapper.readValue(json, OutboxEntry.class));
        }

        @Test
        @DisplayName("an own limit equal to the built-in default is still an own limit")
        void ownLimitEqualToDefault() {
            OutboxEntry entry = minimalEntry().maxAttempts(20).build();

            assertTrue(entry.hasOwnMaxAttempts());
            assertEquals(20, entry.withDefaultMaxAttempts(5).maxAttempts());
        }

        @Test
        @DisplayName("withDefaultMaxAttempts gives an entry without a limit the default and carries every other field")
        void withDefaultFillsUnset() {
            OutboxEntry entry = fullEntry()
                    .headers(Map.of("x-trace-id", "abc123"))
                    .scheduledAt(Instant.parse("2030-01-01T00:00:00Z"))
                    .scopeKey("tenant-a")
                    .build();

            OutboxEntry filled = entry.withDefaultMaxAttempts(7);

            assertEquals(7, filled.maxAttempts());
            assertTrue(filled.hasOwnMaxAttempts());
            assertEquals(entry.aggregateType(), filled.aggregateType());
            assertEquals(entry.aggregateId(), filled.aggregateId());
            assertEquals(entry.eventType(), filled.eventType());
            assertEquals(entry.destinationType(), filled.destinationType());
            assertEquals(entry.destination(), filled.destination());
            assertEquals(entry.payload(), filled.payload());
            assertEquals(entry.headers(), filled.headers());
            assertEquals(entry.scheduledAt(), filled.scheduledAt());
            assertEquals(entry.delayedJob(), filled.delayedJob());
            assertEquals(entry.availableAt(), filled.availableAt());
            assertEquals(entry.scopeKey(), filled.scopeKey());
        }

        @Test
        @DisplayName("withDefaultMaxAttempts returns the same entry when it has its own limit")
        void withDefaultKeepsOwn() {
            OutboxEntry entry = minimalEntry().maxAttempts(3).build();

            assertSame(entry, entry.withDefaultMaxAttempts(7));
        }

        @Test
        @DisplayName("a JSON round trip keeps an unset limit unset and a set limit set")
        void jsonRoundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            OutboxEntry unset =
                    mapper.readValue(mapper.writeValueAsString(minimalEntry().build()), OutboxEntry.class);
            OutboxEntry own = mapper.readValue(
                    mapper.writeValueAsString(minimalEntry().maxAttempts(3).build()), OutboxEntry.class);

            assertFalse(unset.hasOwnMaxAttempts());
            assertTrue(own.hasOwnMaxAttempts());
            assertEquals(3, own.maxAttempts());
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
