// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.Json;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DestinationType} — factory validation, canonical constant identity,
 * equality/hashCode by id, {@code HashMap} key semantics, and Jackson JSON wire form.
 */
@DisplayName("DestinationType")
class DestinationTypeTest {

    // --- Validation / factory ---

    @Nested
    @DisplayName("built-in constants return canonical instances")
    class CanonicalConstants {

        @Test
        @DisplayName("of(\"SERVICE\") returns the SERVICE constant")
        void serviceReturnsCanonical() {
            assertSame(DestinationType.SERVICE, DestinationType.of("SERVICE"));
        }

        @Test
        @DisplayName("of(\"KAFKA\") returns the KAFKA constant")
        void kafkaReturnsCanonical() {
            assertSame(DestinationType.KAFKA, DestinationType.of("KAFKA"));
        }

        @Test
        @DisplayName("of(\"DELAYED_JOB\") returns the DELAYED_JOB constant")
        void delayedJobReturnsCanonical() {
            assertSame(DestinationType.DELAYED_JOB, DestinationType.of("DELAYED_JOB"));
        }
    }

    @Nested
    @DisplayName("unknown but valid ids succeed")
    class ValidUnknownIds {

        @Test
        @DisplayName("of(\"camel\") succeeds and id() equals the input")
        void camelCaseIdSucceeds() {
            DestinationType dt = assertDoesNotThrow(() -> DestinationType.of("camel"));
            assertEquals("camel", dt.id());
        }

        @Test
        @DisplayName("of(\"my-dest_1\") succeeds and id() equals the input")
        void mixedCharsIdSucceeds() {
            DestinationType dt = assertDoesNotThrow(() -> DestinationType.of("my-dest_1"));
            assertEquals("my-dest_1", dt.id());
        }

        @Test
        @DisplayName("of(32-char id) succeeds — boundary is inclusive")
        void thirtyTwoCharIdSucceeds() {
            String maxId = "a".repeat(32);
            DestinationType dt = assertDoesNotThrow(() -> DestinationType.of(maxId));
            assertEquals(maxId, dt.id());
        }
    }

    @Nested
    @DisplayName("malformed ids throw IllegalArgumentException")
    class MalformedIds {

        @Test
        @DisplayName("empty string throws IllegalArgumentException")
        void emptyStringThrows() {
            assertThrows(IllegalArgumentException.class, () -> DestinationType.of(""));
        }

        @Test
        @DisplayName("33-char string throws IllegalArgumentException")
        void thirtyThreeCharStringThrows() {
            assertThrows(IllegalArgumentException.class, () -> DestinationType.of("a".repeat(33)));
        }

        @Test
        @DisplayName("id with space throws IllegalArgumentException")
        void spaceThrows() {
            assertThrows(IllegalArgumentException.class, () -> DestinationType.of("bad id"));
        }

        @Test
        @DisplayName("id with exclamation mark throws IllegalArgumentException")
        void exclamationThrows() {
            assertThrows(IllegalArgumentException.class, () -> DestinationType.of("bad!"));
        }

        @Test
        @DisplayName("id with slash throws IllegalArgumentException")
        void slashThrows() {
            assertThrows(IllegalArgumentException.class, () -> DestinationType.of("x/y"));
        }
    }

    @Nested
    @DisplayName("null id throws NullPointerException")
    class NullId {

        @Test
        @DisplayName("of(null) throws NullPointerException")
        void nullIdThrowsNpe() {
            assertThrows(NullPointerException.class, () -> DestinationType.of(null));
        }
    }

    @Nested
    @DisplayName("error message is bounded for oversized ids")
    class BoundedErrorMessage {

        @Test
        @DisplayName("IllegalArgumentException message is < 120 chars for a 300-char invalid id")
        void oversizedIdMessageIsTruncated() {
            String oversizedId = "x".repeat(300);
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> DestinationType.of(oversizedId));
            assertTrue(
                    ex.getMessage().length() < 120,
                    "Message must be bounded to prevent log amplification; actual length: "
                            + ex.getMessage().length());
        }

        @Test
        @DisplayName("control characters in a rejected id are stripped from the message (no forged log lines)")
        void controlCharactersAreStrippedFromMessage() {
            // A CR/LF in the rejected id (untrusted @JsonCreator path) must not survive into the
            // exception message, or it could forge log lines when the message is logged.
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> DestinationType.of("bad\r\nINJECTED: line"));
            assertFalse(ex.getMessage().contains("\n"), "newline must be stripped from the echoed id");
            assertFalse(ex.getMessage().contains("\r"), "carriage return must be stripped from the echoed id");
        }
    }

    // --- Identity (by id) ---

    @Nested
    @DisplayName("equality is by id, not reference")
    class EqualityByid {

        @Test
        @DisplayName("two distinct instances with the same unknown id are equal")
        void distinctInstancesWithSameIdAreEqual() {
            DestinationType a = DestinationType.of("custom");
            DestinationType b = DestinationType.of("custom");
            assertEquals(a, b);
        }

        @Test
        @DisplayName("two distinct instances with the same unknown id have equal hashCodes")
        void distinctInstancesWithSameIdHaveEqualHashCode() {
            DestinationType a = DestinationType.of("custom");
            DestinationType b = DestinationType.of("custom");
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("different ids are not equal")
        void differentIdsAreNotEqual() {
            assertFalse(DestinationType.of("SERVICE").equals(DestinationType.of("KAFKA")));
        }

        @Test
        @DisplayName("equals(null) returns false")
        void equalsNullReturnsFalse() {
            assertFalse(DestinationType.SERVICE.equals(null));
        }

        @Test
        @DisplayName("equals(String) returns false — different type")
        void equalsStringReturnsFalse() {
            // noinspection EqualsBetweenInconvertibleTypes
            assertFalse(DestinationType.SERVICE.equals("SERVICE"));
        }
    }

    @Nested
    @DisplayName("HashMap key semantics")
    class HashMapKeys {

        @Test
        @DisplayName("put with instance a, get with instance b (same id) retrieves the value")
        void putWithAGetWithBRetrievesValue() {
            DestinationType a = DestinationType.of("custom");
            DestinationType b = DestinationType.of("custom");
            Map<DestinationType, String> map = new HashMap<>();
            map.put(a, "hello");

            assertEquals("hello", map.get(b));
        }

        @Test
        @DisplayName("map keyed by of(\"KAFKA\") is hit by the KAFKA constant")
        void kafkaConstantHitsOfKafkaKey() {
            Map<DestinationType, String> map = new HashMap<>();
            map.put(DestinationType.of("KAFKA"), "value");

            assertEquals("value", map.get(DestinationType.KAFKA));
        }
    }

    @Nested
    @DisplayName("toString and id() return the id")
    class StringRepresentation {

        @Test
        @DisplayName("toString() returns the id for a built-in")
        void toStringReturnsIdForBuiltIn() {
            assertEquals("KAFKA", DestinationType.KAFKA.toString());
        }

        @Test
        @DisplayName("id() returns the id for a built-in")
        void idReturnsIdForBuiltIn() {
            assertEquals("KAFKA", DestinationType.KAFKA.id());
        }

        @Test
        @DisplayName("toString() and id() agree for an unknown id")
        void toStringAndIdAgreeForUnknownId() {
            DestinationType dt = DestinationType.of("camel");
            assertEquals(dt.id(), dt.toString());
        }
    }

    // --- JSON wire form ---

    @Nested
    @DisplayName("JSON serialization (@JsonValue)")
    class JsonSerialization {

        @Test
        @DisplayName("Json.encode(KAFKA) produces the bare JSON string \"KAFKA\"")
        void encodeKafkaProducesBareString() {
            assertEquals("\"KAFKA\"", Json.encode(DestinationType.KAFKA));
        }

        @Test
        @DisplayName("Json.encode produces the id for an unknown valid type")
        void encodeUnknownTypeProducesId() {
            assertEquals("\"camel\"", Json.encode(DestinationType.of("camel")));
        }
    }

    @Nested
    @DisplayName("JSON deserialization (@JsonCreator)")
    class JsonDeserialization {

        @Test
        @DisplayName("decoding \"KAFKA\" returns the canonical KAFKA constant")
        void decodeKafkaReturnsCanonical() {
            DestinationType decoded = Json.decodeValue("\"KAFKA\"", DestinationType.class);
            assertSame(DestinationType.KAFKA, decoded);
        }

        @Test
        @DisplayName("round-trip of an unknown valid id equals the original")
        void roundTripUnknownIdPreservesEquality() {
            DestinationType original = DestinationType.of("camel");
            DestinationType roundTripped = Json.decodeValue(Json.encode(original), DestinationType.class);
            assertEquals(original, roundTripped);
        }

        @Test
        @DisplayName("decoding a malformed id throws (Jackson wraps the IllegalArgumentException)")
        void decodingMalformedIdThrows() {
            // "bad id" contains a space — of() will throw IllegalArgumentException; Jackson wraps it.
            assertThrows(Exception.class, () -> Json.decodeValue("\"bad id\"", DestinationType.class));
        }

        @Test
        @DisplayName("decoding JSON null returns null (Jackson default for non-primitive types)")
        void decodeNullReturnsNull() {
            // Jackson maps JSON null to Java null for object types when no
            // @JsonCreator special-casing is present; the NPE contract covers the
            // direct of(null) path, not this Jackson-driven path.
            DestinationType result = Json.decodeValue("null", DestinationType.class);
            assertNull(result);
        }
    }
}
