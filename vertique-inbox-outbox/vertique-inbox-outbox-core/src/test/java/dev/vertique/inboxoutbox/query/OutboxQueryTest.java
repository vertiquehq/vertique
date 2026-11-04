// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ValidationException;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntryState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link OutboxQuery}: construction, validation and defaults. */
@DisplayName("OutboxQuery")
class OutboxQueryTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-02T00:00:00Z");

    private static OutboxQuery.OutboxQueryBuilder valid() {
        return OutboxQuery.builder().states(Set.of(OutboxEntryState.DEAD_LETTER));
    }

    private static String message(Consumer<OutboxQuery.OutboxQueryBuilder> customizer) {
        OutboxQuery.OutboxQueryBuilder builder = valid();
        customizer.accept(builder);
        return assertThrows(ValidationException.class, builder::build).getMessage();
    }

    @Nested
    @DisplayName("states")
    class States {

        @Test
        @DisplayName("null states are rejected")
        void nullStates() {
            ValidationException e = assertThrows(
                    ValidationException.class, () -> OutboxQuery.builder().build());

            assertEquals("states must not be null", e.getMessage());
        }

        @Test
        @DisplayName("an empty state set is rejected")
        void emptyStates() {
            assertEquals("states must not be empty", message(b -> b.states(Set.of())));
        }

        @Test
        @DisplayName("a null element is rejected")
        void nullElement() {
            Set<OutboxEntryState> withNull = new HashSet<>();
            withNull.add(OutboxEntryState.PENDING);
            withNull.add(null);

            assertEquals("states element must not be null", message(b -> b.states(withNull)));
        }

        @Test
        @DisplayName("the set is held in enum declaration order whatever order it was given in")
        void enumOrder() {
            Set<OutboxEntryState> given = new LinkedHashSet<>(List.of(
                    OutboxEntryState.DISCARDED,
                    OutboxEntryState.PUBLISHED,
                    OutboxEntryState.PENDING,
                    OutboxEntryState.DEAD_LETTER));

            OutboxQuery query = valid().states(given).build();

            assertEquals(
                    List.of(
                            OutboxEntryState.PENDING,
                            OutboxEntryState.PUBLISHED,
                            OutboxEntryState.DEAD_LETTER,
                            OutboxEntryState.DISCARDED),
                    new ArrayList<>(query.states()));
        }

        @Test
        @DisplayName("later changes to the given set do not reach the query, and the query's set cannot change")
        void copied() {
            Set<OutboxEntryState> given = EnumSet.of(OutboxEntryState.PENDING);
            OutboxQuery query = valid().states(given).build();

            given.add(OutboxEntryState.PROCESSING);

            assertEquals(Set.of(OutboxEntryState.PENDING), query.states());
            assertThrows(
                    UnsupportedOperationException.class, () -> query.states().add(OutboxEntryState.PROCESSING));
            assertThrows(
                    UnsupportedOperationException.class, () -> query.states().clear());
            assertThrows(
                    UnsupportedOperationException.class, () -> query.states().remove(OutboxEntryState.PENDING));
        }

        @Test
        @DisplayName("the set is an unmodifiable view, not a plain EnumSet")
        void unmodifiableWrapper() {
            OutboxQuery query = valid().build();

            assertEquals(
                    Collections.unmodifiableSet(EnumSet.of(OutboxEntryState.DEAD_LETTER))
                            .getClass(),
                    query.states().getClass());
        }
    }

    @Nested
    @DisplayName("sort")
    class Sort {

        @Test
        @DisplayName("an absent sort reads as UPDATED_AT_DESC")
        void defaultSort() {
            assertEquals(OutboxSort.UPDATED_AT_DESC, valid().build().sort());
            assertEquals(OutboxSort.UPDATED_AT_DESC, valid().sort(null).build().sort());
        }

        @Test
        @DisplayName("an explicit sort is kept")
        void explicitSort() {
            for (OutboxSort sort : OutboxSort.values()) {
                assertEquals(sort, valid().sort(sort).build().sort());
            }
        }
    }

    @Nested
    @DisplayName("text filters")
    class TextFilters {

        @Test
        @DisplayName("every text filter accepts a value of 255 characters and rejects 256")
        void lengthLimit() {
            String atLimit = "x".repeat(255);
            String over = "x".repeat(256);

            assertEquals(atLimit, valid().destination(atLimit).build().destination());
            assertEquals(atLimit, valid().eventType(atLimit).build().eventType());
            assertEquals(atLimit, valid().aggregateType(atLimit).build().aggregateType());
            assertEquals(atLimit, valid().aggregateId(atLimit).build().aggregateId());
            assertEquals("destination must be at most 255 characters", message(b -> b.destination(over)));
            assertEquals("eventType must be at most 255 characters", message(b -> b.eventType(over)));
            assertEquals("aggregateType must be at most 255 characters", message(b -> b.aggregateType(over)));
            assertEquals("aggregateId must be at most 255 characters", message(b -> b.aggregateId(over)));
        }

        @Test
        @DisplayName("every text filter rejects blank text")
        void blank() {
            assertEquals("destination must not be blank", message(b -> b.destination(" ")));
            assertEquals("eventType must not be blank", message(b -> b.eventType("")));
            assertEquals("aggregateType must not be blank", message(b -> b.aggregateType("  ")));
            assertEquals("aggregateId must not be blank", message(b -> b.aggregateId("\t")));
        }

        @Test
        @DisplayName("every text filter rejects control characters")
        void controlCharacters() {
            assertEquals("destination must not contain control characters", message(b -> b.destination("a\nb")));
            assertEquals("eventType must not contain control characters", message(b -> b.eventType("a\u0000b")));
            assertEquals("aggregateType must not contain control characters", message(b -> b.aggregateType("a\tb")));
            assertEquals("aggregateId must not contain control characters", message(b -> b.aggregateId("a\u007fb")));
        }

        @Test
        @DisplayName("every text filter rejects an unpaired surrogate")
        void surrogates() {
            assertEquals("destination must not contain unpaired surrogates", message(b -> b.destination("\uD800")));
            assertEquals("eventType must not contain unpaired surrogates", message(b -> b.eventType("a\uDC00")));
            assertEquals(
                    "aggregateType must not contain unpaired surrogates",
                    message(b -> b.aggregateType("\uDC00\uD800")));
            assertEquals("aggregateId must not contain unpaired surrogates", message(b -> b.aggregateId("x\uD800")));
        }

        @Test
        @DisplayName("filters that are all absent are kept absent")
        void absent() {
            OutboxQuery query = valid().build();

            assertNull(query.destinationType());
            assertNull(query.destination());
            assertNull(query.eventType());
            assertNull(query.aggregateType());
            assertNull(query.aggregateId());
            assertNull(query.createdFrom());
            assertNull(query.createdBefore());
            assertNull(query.updatedFrom());
            assertNull(query.updatedBefore());
        }
    }

    @Nested
    @DisplayName("time ranges")
    class TimeRanges {

        @Test
        @DisplayName("an instant outside year 1 to year 9999 is rejected for each of the four bounds")
        void outOfRange() {
            Instant tooEarly = Instant.parse("0001-01-01T00:00:00Z").minusSeconds(1);
            Instant tooLate = Instant.parse("9999-12-31T23:59:59Z").plusSeconds(1);
            String range = " must be between 0001-01-01T00:00:00Z and 9999-12-31T23:59:59Z";

            assertEquals("createdFrom" + range, message(b -> b.createdFrom(tooEarly)));
            assertEquals("createdBefore" + range, message(b -> b.createdBefore(tooLate)));
            assertEquals("updatedFrom" + range, message(b -> b.updatedFrom(tooEarly)));
            assertEquals("updatedBefore" + range, message(b -> b.updatedBefore(tooLate)));
        }

        @Test
        @DisplayName("the extreme valid instants are accepted")
        void extremesAccepted() {
            Instant min = Instant.parse("0001-01-01T00:00:00Z");
            Instant max = Instant.parse("9999-12-31T23:59:59Z");

            OutboxQuery query = valid().createdFrom(min).createdBefore(max).build();

            assertEquals(min, query.createdFrom());
            assertEquals(max, query.createdBefore());
        }

        @Test
        @DisplayName("a created range whose start is not before its end is rejected, equal bounds included")
        void createdOrdered() {
            assertEquals("createdFrom must be before createdBefore", message(b -> b.createdFrom(T2)
                    .createdBefore(T1)));
            assertEquals("createdFrom must be before createdBefore", message(b -> b.createdFrom(T1)
                    .createdBefore(T1)));
        }

        @Test
        @DisplayName("an updated range whose start is not before its end is rejected, equal bounds included")
        void updatedOrdered() {
            assertEquals("updatedFrom must be before updatedBefore", message(b -> b.updatedFrom(T2)
                    .updatedBefore(T1)));
            assertEquals("updatedFrom must be before updatedBefore", message(b -> b.updatedFrom(T1)
                    .updatedBefore(T1)));
        }

        @Test
        @DisplayName("ordered ranges and single bounds are accepted")
        void accepted() {
            OutboxQuery both = valid().createdFrom(T1)
                    .createdBefore(T2)
                    .updatedFrom(T1)
                    .updatedBefore(T2)
                    .build();
            OutboxQuery onlyFrom = valid().createdFrom(T2).updatedFrom(T2).build();
            OutboxQuery onlyBefore = valid().createdBefore(T1).updatedBefore(T1).build();

            assertEquals(T1, both.createdFrom());
            assertEquals(T2, both.createdBefore());
            assertEquals(T1, both.updatedFrom());
            assertEquals(T2, both.updatedBefore());
            assertEquals(T2, onlyFrom.createdFrom());
            assertEquals(T1, onlyBefore.updatedBefore());
        }

        @Test
        @DisplayName("a created range is independent of an updated range")
        void independentRanges() {
            OutboxQuery query = valid().createdFrom(T2)
                    .createdBefore(T2.plusSeconds(1))
                    .updatedFrom(T1)
                    .updatedBefore(T2)
                    .build();

            assertEquals(T2, query.createdFrom());
            assertEquals(T1, query.updatedFrom());
        }
    }

    @Nested
    @DisplayName("builder and inStates")
    class Construction {

        @Test
        @DisplayName("the builder carries every filter and toBuilder round-trips them")
        void builderCarriesEverything() {
            OutboxQuery query = OutboxQuery.builder()
                    .states(Set.of(OutboxEntryState.PENDING, OutboxEntryState.DISCARDED))
                    .destinationType(DestinationType.KAFKA)
                    .destination("topic")
                    .eventType("event")
                    .aggregateType("Order")
                    .aggregateId("o-1")
                    .createdFrom(T1)
                    .createdBefore(T2)
                    .updatedFrom(T1)
                    .updatedBefore(T2)
                    .sort(OutboxSort.CREATED_AT_ASC)
                    .build();

            assertEquals(Set.of(OutboxEntryState.PENDING, OutboxEntryState.DISCARDED), query.states());
            assertEquals(DestinationType.KAFKA, query.destinationType());
            assertEquals("topic", query.destination());
            assertEquals("event", query.eventType());
            assertEquals("Order", query.aggregateType());
            assertEquals("o-1", query.aggregateId());
            assertEquals(T1, query.createdFrom());
            assertEquals(T2, query.createdBefore());
            assertEquals(T1, query.updatedFrom());
            assertEquals(T2, query.updatedBefore());
            assertEquals(OutboxSort.CREATED_AT_ASC, query.sort());
            assertEquals(query, query.toBuilder().build());
        }

        @Test
        @DisplayName("inStates builds a query with those states, no filter and the default order")
        void inStates() {
            OutboxQuery query = OutboxQuery.inStates(OutboxEntryState.DISCARDED, OutboxEntryState.PENDING);

            assertEquals(Set.of(OutboxEntryState.PENDING, OutboxEntryState.DISCARDED), query.states());
            assertEquals(OutboxSort.UPDATED_AT_DESC, query.sort());
            assertEquals(
                    valid().states(query.states()).build(), query.toBuilder().build());
            assertNull(query.destinationType());
            assertNull(query.destination());
            assertNull(query.createdFrom());
        }

        @Test
        @DisplayName("inStates without a state, or with a null array or element, is rejected")
        void inStatesInvalid() {
            assertEquals(
                    "states must not be empty",
                    assertThrows(ValidationException.class, OutboxQuery::inStates)
                            .getMessage());
            assertEquals(
                    "states must not be null",
                    assertThrows(ValidationException.class, () -> OutboxQuery.inStates((OutboxEntryState[]) null))
                            .getMessage());
            assertEquals(
                    "states element must not be null",
                    assertThrows(ValidationException.class, () -> OutboxQuery.inStates(OutboxEntryState.PENDING, null))
                            .getMessage());
        }
    }
}
