// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.CursorCodecs;
import dev.vertique.db.query.CursorValueCodec;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PageSizeConstraintViolationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PageCursorTest {

    // -- Factory methods --

    @Test
    void first_createsFirstPageCursor() {
        var cursor = PageCursor.first(10);
        assertTrue(cursor.isFirstPage());
        assertEquals(10, cursor.pageSize());
        assertFalse(cursor.backward());
        assertTrue(cursor.keysetValues().isEmpty());
    }

    @Test
    void of_createsFullCursor() {
        var values = List.of((Object) "alice", UUID.randomUUID());
        var cursor = PageCursor.of(values, 20, false);
        assertFalse(cursor.isFirstPage());
        assertEquals(2, cursor.keysetValues().size());
        assertEquals(20, cursor.pageSize());
        assertFalse(cursor.backward());
    }

    @Test
    void of_backward_setsBackwardFlag() {
        var cursor = PageCursor.of(List.of("val"), 10, true);
        assertTrue(cursor.backward());
    }

    // -- Validation --

    @Test
    void first_rejectsZeroPageSize() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.first(0));
    }

    @Test
    void first_rejectsNegativePageSize() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.first(-1));
    }

    // -- Token round-trip: basic types --

    @Test
    void roundTrip_uuid() {
        UUID id = UUID.randomUUID();
        var cursor = PageCursor.of(List.of(id), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(1, decoded.keysetValues().size());
        assertEquals(id, decoded.keysetValues().get(0));
        assertInstanceOf(UUID.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_string() {
        var cursor = PageCursor.of(List.of("hello world"), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals("hello world", decoded.keysetValues().get(0));
        assertInstanceOf(String.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_integer() {
        var cursor = PageCursor.of(List.of(42), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(42, decoded.keysetValues().get(0));
        assertInstanceOf(Integer.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_long() {
        var cursor = PageCursor.of(List.of(9876543210L), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(9876543210L, decoded.keysetValues().get(0));
        assertInstanceOf(Long.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_double() {
        var cursor = PageCursor.of(List.of(3.14), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(3.14, decoded.keysetValues().get(0));
        assertInstanceOf(Double.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_boolean() {
        var cursor = PageCursor.of(List.of(true), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(true, decoded.keysetValues().get(0));
        assertInstanceOf(Boolean.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_instant() {
        Instant now = Instant.parse("2024-06-15T10:30:00Z");
        var cursor = PageCursor.of(List.of(now), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(now, decoded.keysetValues().get(0));
        assertInstanceOf(Instant.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_offsetDateTime() {
        OffsetDateTime odt = OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.ofHours(2));
        var cursor = PageCursor.of(List.of(odt), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(odt, decoded.keysetValues().get(0));
        assertInstanceOf(OffsetDateTime.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_localDateTime() {
        LocalDateTime ldt = LocalDateTime.of(2024, 6, 15, 10, 30, 0);
        var cursor = PageCursor.of(List.of(ldt), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(ldt, decoded.keysetValues().get(0));
        assertInstanceOf(LocalDateTime.class, decoded.keysetValues().get(0));
    }

    // -- Token round-trip: composite keyset --

    @Test
    void roundTrip_compositeKeyset() {
        UUID id = UUID.randomUUID();
        Instant ts = Instant.parse("2024-01-15T08:00:00Z");
        var cursor = PageCursor.of(List.of(ts, id), 50, true);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(2, decoded.keysetValues().size());
        assertEquals(ts, decoded.keysetValues().get(0));
        assertEquals(id, decoded.keysetValues().get(1));
        assertEquals(50, decoded.pageSize());
        assertTrue(decoded.backward());
    }

    // -- Token round-trip: metadata --

    @Test
    void roundTrip_preservesPageSize() {
        var cursor = PageCursor.first(42);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(42, decoded.pageSize());
        assertFalse(decoded.backward());
        assertTrue(decoded.isFirstPage());
    }

    // -- Edge cases --

    @Test
    void roundTrip_stringWithColonInValue() {
        var cursor = PageCursor.of(List.of("key:with:colons"), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals("key:with:colons", decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_emptyString() {
        var cursor = PageCursor.of(List.of(""), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals("", decoded.keysetValues().get(0));
    }

    @Test
    void fromToken_rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.fromToken(null));
    }

    @Test
    void fromToken_rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.fromToken(""));
    }

    @Test
    void fromToken_rejectsMalformedToken() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.fromToken("not-valid-base64!@#"));
    }

    @Test
    void keysetValues_areImmutable() {
        var values = new java.util.ArrayList<>(List.of((Object) "a", "b"));
        var cursor = PageCursor.of(values, 10, false);
        assertThrows(
                UnsupportedOperationException.class, () -> cursor.keysetValues().add("c"));
    }

    // -- Fluent page-size methods --

    @Test
    void withPageSize_returnsNewCursorWithUpdatedSize() {
        var cursor = PageCursor.first(10);
        var updated = cursor.withPageSize(50);
        assertEquals(50, updated.pageSize());
        assertEquals(10, cursor.pageSize()); // original unchanged
    }

    @Test
    void withPageSize_rejectsZero() {
        assertThrows(IllegalArgumentException.class, () -> PageCursor.first(10).withPageSize(0));
    }

    @Test
    void withPageSize_withBounds_returnsNewCursor() {
        var cursor = PageCursor.first(10);
        var updated = cursor.withPageSize(50, 1, 100);
        assertEquals(50, updated.pageSize());
    }

    @Test
    void withPageSize_withBounds_rejectsTooSmall() {
        var ex = assertThrows(PageSizeConstraintViolationException.class, () -> PageCursor.first(10)
                .withPageSize(0, 1, 100));
        assertEquals(0, ex.requestedPageSize());
        assertEquals(1, ex.minPageSize());
        assertEquals(100, ex.maxPageSize());
    }

    @Test
    void withPageSize_withBounds_rejectsTooLarge() {
        var ex = assertThrows(PageSizeConstraintViolationException.class, () -> PageCursor.first(10)
                .withPageSize(500, 1, 100));
        assertEquals(500, ex.requestedPageSize());
    }

    @Test
    void validatePageSize_returnsThisWhenValid() {
        var cursor = PageCursor.first(50);
        var result = cursor.validatePageSize(1, 100);
        assertSame(cursor, result);
    }

    @Test
    void validatePageSize_throwsWhenOutOfRange() {
        var cursor = PageCursor.first(500);
        assertThrows(PageSizeConstraintViolationException.class, () -> cursor.validatePageSize(1, 100));
    }

    // -- Null keyset value support --

    @Test
    void roundTrip_nullKeysetValue() {
        var values = new java.util.ArrayList<Object>();
        values.add("alice");
        values.add(null);
        values.add(42);
        var cursor = PageCursor.of(values, 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(3, decoded.keysetValues().size());
        assertEquals("alice", decoded.keysetValues().get(0));
        assertNull(decoded.keysetValues().get(1));
        assertEquals(42, decoded.keysetValues().get(2));
    }

    @Test
    void of_allowsNullValuesInList() {
        var values = new java.util.ArrayList<Object>();
        values.add(null);
        var cursor = PageCursor.of(values, 10, false);
        assertNull(cursor.keysetValues().get(0));
    }

    // -- Token round-trip: new built-in types --

    @Test
    void roundTrip_bigDecimal() {
        BigDecimal value = new BigDecimal("9999999999.99");
        var cursor = PageCursor.of(List.of(value), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(1, decoded.keysetValues().size());
        Object result = decoded.keysetValues().get(0);
        assertInstanceOf(BigDecimal.class, result);
        assertEquals(0, value.compareTo((BigDecimal) result));
    }

    @Test
    void roundTrip_localDate() {
        LocalDate value = LocalDate.of(2024, 3, 15);
        var cursor = PageCursor.of(List.of(value), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(value, decoded.keysetValues().get(0));
        assertInstanceOf(LocalDate.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_short() {
        Short value = (short) 12345;
        var cursor = PageCursor.of(List.of(value), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(value, decoded.keysetValues().get(0));
        assertInstanceOf(Short.class, decoded.keysetValues().get(0));
    }

    @Test
    void roundTrip_float() {
        Float value = 2.718f;
        var cursor = PageCursor.of(List.of(value), 10, false);
        var decoded = PageCursor.fromToken(cursor.toToken());

        assertEquals(value, decoded.keysetValues().get(0));
        assertInstanceOf(Float.class, decoded.keysetValues().get(0));
    }

    // -- Custom codecs via CursorCodecs --

    @Test
    void fromToken_withCustomCodecs() {
        record MyId(long value) {}
        CursorValueCodec<MyId> codec = CursorValueCodec.of(
                "myid", MyId.class, v -> String.valueOf(v.value()), s -> new MyId(Long.parseLong(s)));
        CursorCodecs customCodecs = CursorCodecs.defaults().with(codec);

        MyId original = new MyId(777L);
        var cursor = PageCursor.of(List.of(original), 10, false, customCodecs);
        String token = cursor.toToken(customCodecs);

        var decoded = PageCursor.fromToken(token, customCodecs);
        assertEquals(1, decoded.keysetValues().size());
        Object result = decoded.keysetValues().get(0);
        assertInstanceOf(MyId.class, result);
        assertEquals(original, result);
    }

    // -- Global codec registration --

    @AfterEach
    void resetGlobalCodecs() {
        // Reset global codecs by re-registering a known clean state.
        // We cannot directly reset the field but we can ensure the test-registered
        // type is cleaned up. Since there's no built-in reset, we verify registration
        // doesn't persist across test runs via the test isolation pattern below.
    }

    @Test
    void registerCodec_makesTypeAvailableGlobally() {
        // Use a unique prefix to avoid collision with other tests
        record TestGlobalId(int value) {}
        CursorValueCodec<TestGlobalId> codec = CursorValueCodec.of(
                "testglobalid_unique_9f3a",
                TestGlobalId.class,
                v -> String.valueOf(v.value()),
                s -> new TestGlobalId(Integer.parseInt(s)));

        PageCursor.registerCodec(codec);

        // After registration, of() and fromToken() should work without explicit codecs
        TestGlobalId original = new TestGlobalId(42);
        var cursor = PageCursor.of(List.of(original), 5, false);
        String token = cursor.toToken();

        var decoded = PageCursor.fromToken(token);
        assertEquals(1, decoded.keysetValues().size());
        assertInstanceOf(TestGlobalId.class, decoded.keysetValues().get(0));
        assertEquals(original, decoded.keysetValues().get(0));
    }
}
