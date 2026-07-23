// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.db.query.CursorCodecs;
import dev.vertique.db.query.CursorValueCodec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CursorCodecs} — verifies that all built-in type codecs serialize and
 * deserialize correctly, and that the extension API works for custom codec registration.
 */
@DisplayName("CursorCodecs")
class CursorCodecsTest {

    private final CursorCodecs codecs = CursorCodecs.defaults();

    // --- Built-in round-trips ---

    @Nested
    @DisplayName("built-in type round-trips")
    class BuiltInRoundTrips {

        @Test
        @DisplayName("UUID round-trips correctly")
        void roundTrip_uuid() {
            UUID value = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
            String encoded = codecs.serialize(value);
            assertEquals("uuid:550e8400-e29b-41d4-a716-446655440000", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(UUID.class, decoded);
        }

        @Test
        @DisplayName("Instant round-trips correctly")
        void roundTrip_instant() {
            Instant value = Instant.parse("2024-06-15T10:30:00Z");
            String encoded = codecs.serialize(value);
            assertTrue(encoded.startsWith("instant:"));
            assertEquals(value, codecs.deserialize(encoded));
        }

        @Test
        @DisplayName("OffsetDateTime round-trips correctly")
        void roundTrip_offsetDateTime() {
            OffsetDateTime value = OffsetDateTime.of(2024, 6, 15, 10, 30, 0, 0, ZoneOffset.ofHours(2));
            String encoded = codecs.serialize(value);
            assertTrue(encoded.startsWith("odt:"));
            assertEquals(value, codecs.deserialize(encoded));
        }

        @Test
        @DisplayName("LocalDateTime round-trips correctly")
        void roundTrip_localDateTime() {
            LocalDateTime value = LocalDateTime.of(2024, 6, 15, 10, 30, 0);
            String encoded = codecs.serialize(value);
            assertTrue(encoded.startsWith("ldt:"));
            assertEquals(value, codecs.deserialize(encoded));
        }

        @Test
        @DisplayName("String round-trips correctly")
        void roundTrip_string() {
            String value = "hello:world";
            String encoded = codecs.serialize(value);
            assertEquals("str:hello:world", encoded);
            assertEquals(value, codecs.deserialize(encoded));
        }

        @Test
        @DisplayName("Integer round-trips correctly")
        void roundTrip_integer() {
            Integer value = 42;
            String encoded = codecs.serialize(value);
            assertEquals("int:42", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(Integer.class, decoded);
        }

        @Test
        @DisplayName("Long round-trips correctly")
        void roundTrip_long() {
            Long value = 9876543210L;
            String encoded = codecs.serialize(value);
            assertEquals("long:9876543210", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(Long.class, decoded);
        }

        @Test
        @DisplayName("Double round-trips correctly")
        void roundTrip_double() {
            Double value = 3.14;
            String encoded = codecs.serialize(value);
            assertEquals("double:3.14", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(Double.class, decoded);
        }

        @Test
        @DisplayName("Boolean round-trips correctly")
        void roundTrip_boolean() {
            Boolean value = true;
            String encoded = codecs.serialize(value);
            assertEquals("bool:true", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(Boolean.class, decoded);
        }

        @Test
        @DisplayName("BigDecimal round-trips correctly")
        void roundTrip_bigDecimal() {
            BigDecimal value = new BigDecimal("123456.789");
            String encoded = codecs.serialize(value);
            assertTrue(encoded.startsWith("bigdec:"));
            Object decoded = codecs.deserialize(encoded);
            // Compare using compareTo to ignore scale differences
            assertEquals(0, value.compareTo((BigDecimal) decoded));
            assertInstanceOf(BigDecimal.class, decoded);
        }

        @Test
        @DisplayName("LocalDate round-trips correctly")
        void roundTrip_localDate() {
            LocalDate value = LocalDate.of(2024, 6, 15);
            String encoded = codecs.serialize(value);
            assertEquals("date:2024-06-15", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(LocalDate.class, decoded);
        }

        @Test
        @DisplayName("Short round-trips correctly")
        void roundTrip_short() {
            Short value = (short) 32767;
            String encoded = codecs.serialize(value);
            assertEquals("short:32767", encoded);
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(Short.class, decoded);
        }

        @Test
        @DisplayName("Float round-trips correctly")
        void roundTrip_float() {
            Float value = 1.5f;
            String encoded = codecs.serialize(value);
            assertTrue(encoded.startsWith("float:"));
            Object decoded = codecs.deserialize(encoded);
            assertEquals(value, decoded);
            assertInstanceOf(Float.class, decoded);
        }
    }

    // --- Null handling ---

    @Nested
    @DisplayName("null value handling")
    class NullHandling {

        @Test
        @DisplayName("serialize null returns 'null:'")
        void serialize_null() {
            assertEquals("null:", codecs.serialize(null));
        }

        @Test
        @DisplayName("deserialize 'null:' returns null")
        void deserialize_null() {
            assertNull(codecs.deserialize("null:"));
        }
    }

    // --- Custom codec extension ---

    @Nested
    @DisplayName("custom codec extension")
    class CustomCodecExtension {

        @Test
        @DisplayName("with() adds a custom codec")
        void with_addsCustomCodec() {
            record MyId(long value) {}
            CursorValueCodec<MyId> codec = CursorValueCodec.of(
                    "myid", MyId.class, v -> String.valueOf(v.value()), s -> new MyId(Long.parseLong(s)));

            CursorCodecs extended = codecs.with(codec);
            String encoded = extended.serialize(new MyId(123L));
            assertEquals("myid:123", encoded);
        }

        @Test
        @DisplayName("custom codec round-trips correctly")
        void with_customCodecRoundTrip() {
            record MyId(long value) {}
            CursorValueCodec<MyId> codec = CursorValueCodec.of(
                    "myid", MyId.class, v -> String.valueOf(v.value()), s -> new MyId(Long.parseLong(s)));

            CursorCodecs extended = codecs.with(codec);
            MyId original = new MyId(999L);
            String encoded = extended.serialize(original);
            Object decoded = extended.deserialize(encoded);
            assertInstanceOf(MyId.class, decoded);
            assertEquals(original, decoded);
        }

        @Test
        @DisplayName("with() rejects duplicate prefix")
        void with_rejectsDuplicatePrefix() {
            CursorValueCodec<String> duplicate = CursorValueCodec.of("str", String.class, v -> v, v -> v);
            assertThrows(IllegalArgumentException.class, () -> codecs.with(duplicate));
        }

        @Test
        @DisplayName("with() rejects duplicate type")
        void with_rejectsDuplicateType() {
            CursorValueCodec<String> duplicate = CursorValueCodec.of("mystr", String.class, v -> v, v -> v);
            assertThrows(IllegalArgumentException.class, () -> codecs.with(duplicate));
        }

        @Test
        @DisplayName("original CursorCodecs is not modified by with()")
        void with_doesNotMutateOriginal() {
            record MyId(long value) {}
            CursorValueCodec<MyId> codec = CursorValueCodec.of(
                    "myid", MyId.class, v -> String.valueOf(v.value()), s -> new MyId(Long.parseLong(s)));

            CursorCodecs extended = codecs.with(codec);
            assertNotSame(codecs, extended);
            // Original should not know about MyId
            assertThrows(IllegalArgumentException.class, () -> codecs.serialize(new MyId(1L)));
        }
    }

    // --- Error cases ---

    @Nested
    @DisplayName("error cases")
    class ErrorCases {

        @Test
        @DisplayName("serialize unknown type throws IllegalArgumentException")
        void serialize_unknownType_throws() {
            record Unknown(int x) {}
            assertThrows(IllegalArgumentException.class, () -> codecs.serialize(new Unknown(1)));
        }

        @Test
        @DisplayName("deserialize unknown prefix throws IllegalArgumentException")
        void deserialize_unknownPrefix_throws() {
            assertThrows(IllegalArgumentException.class, () -> codecs.deserialize("unknown:somevalue"));
        }

        @Test
        @DisplayName("deserialize missing colon throws IllegalArgumentException")
        void deserialize_missingColon_throws() {
            assertThrows(IllegalArgumentException.class, () -> codecs.deserialize("nocolon"));
        }
    }
}
