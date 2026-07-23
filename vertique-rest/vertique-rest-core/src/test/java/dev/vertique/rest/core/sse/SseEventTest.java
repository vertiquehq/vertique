// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.sse;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SseEvent}.
 *
 * <p>Verifies static factory methods, builder construction, and equality semantics.
 */
class SseEventTest {

    // --- Static factories ---

    @Nested
    @DisplayName("of(Object)")
    class Of {

        @Test
        @DisplayName("Should create event with data only, all other fields null")
        void shouldCreateEventWithDataOnly() {
            SseEvent event = SseEvent.of("hello");

            assertEquals("hello", event.data());
            assertNull(event.id());
            assertNull(event.event());
            assertNull(event.comment());
            assertNull(event.retryMs());
        }

        @Test
        @DisplayName("Should accept structured object as data")
        void shouldAcceptStructuredObjectData() {
            record Payload(String key, int value) {}
            Payload payload = new Payload("test", 42);

            SseEvent event = SseEvent.of(payload);

            assertSame(payload, event.data());
            assertNull(event.id());
            assertNull(event.event());
            assertNull(event.comment());
            assertNull(event.retryMs());
        }
    }

    // --- comment(String) ---

    @Nested
    @DisplayName("comment(String)")
    class Comment {

        @Test
        @DisplayName("Should create event with comment only, all other fields null")
        void shouldCreateCommentOnlyEvent() {
            SseEvent event = SseEvent.comment("heartbeat");

            assertEquals("heartbeat", event.comment());
            assertNull(event.id());
            assertNull(event.event());
            assertNull(event.data());
            assertNull(event.retryMs());
        }
    }

    // --- Builder ---

    @Nested
    @DisplayName("builder()")
    class Builder {

        @Test
        @DisplayName("Should create event with all fields set")
        void shouldCreateEventWithAllFields() {
            SseEvent event = SseEvent.builder()
                    .id("42")
                    .event("user-updated")
                    .data("payload")
                    .comment("annotation")
                    .retryMs(5000L)
                    .build();

            assertEquals("42", event.id());
            assertEquals("user-updated", event.event());
            assertEquals("payload", event.data());
            assertEquals("annotation", event.comment());
            assertEquals(5000L, event.retryMs());
        }

        @Test
        @DisplayName("Should create event with only id and event type set")
        void shouldCreateEventWithIdAndEventType() {
            SseEvent event = SseEvent.builder().id("1").event("created").build();

            assertEquals("1", event.id());
            assertEquals("created", event.event());
            assertNull(event.data());
            assertNull(event.comment());
            assertNull(event.retryMs());
        }
    }

    // --- Equality and hashCode ---

    @Nested
    @DisplayName("equals() and hashCode()")
    class Equality {

        @Test
        @DisplayName("Should be equal when all fields match")
        void shouldBeEqualWhenAllFieldsMatch() {
            SseEvent a = SseEvent.builder()
                    .id("1")
                    .event("ping")
                    .data("payload")
                    .comment("note")
                    .retryMs(3000L)
                    .build();

            SseEvent b = SseEvent.builder()
                    .id("1")
                    .event("ping")
                    .data("payload")
                    .comment("note")
                    .retryMs(3000L)
                    .build();

            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when data differs")
        void shouldNotBeEqualWhenDataDiffers() {
            SseEvent a = SseEvent.of("hello");
            SseEvent b = SseEvent.of("world");

            assertNotEquals(a, b);
        }

        @Test
        @DisplayName("Should be equal to itself")
        void shouldBeEqualToItself() {
            SseEvent event = SseEvent.of("data");
            assertEquals(event, event);
        }

        @Test
        @DisplayName("Should not be equal to null")
        void shouldNotBeEqualToNull() {
            SseEvent event = SseEvent.of("data");
            assertNotEquals(null, event);
        }
    }
}
