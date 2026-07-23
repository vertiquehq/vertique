// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SourceConfigValues#positiveInt(String, JsonObject, String, int)}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Absent field returns the supplied default.</li>
 *   <li>Zero value throws, naming the field.</li>
 *   <li>Negative value throws, naming the field.</li>
 *   <li>Non-numeric string throws, naming the field.</li>
 *   <li>Positive integer is returned unchanged.</li>
 *   <li>The thrown exception carries the expected {@code sourceName}.</li>
 * </ul>
 */
class SourceConfigValuesTest {

    // --- positiveInt ---

    @Nested
    @DisplayName("positiveInt")
    class PositiveInt {

        @Test
        @DisplayName("absent field returns default value")
        void absentFieldReturnsDefault() {
            int result = SourceConfigValues.positiveInt("src", new JsonObject(), "timeoutMs", 5000);
            assertEquals(5000, result);
        }

        @Test
        @DisplayName("positive value is returned unchanged")
        void positiveValueReturned() {
            JsonObject config = new JsonObject().put("timeoutMs", 3000);
            assertEquals(3000, SourceConfigValues.positiveInt("src", config, "timeoutMs", 5000));
        }

        @Test
        @DisplayName("value of 1 is accepted as positive")
        void valueOneAccepted() {
            JsonObject config = new JsonObject().put("timeoutMs", 1);
            assertEquals(1, SourceConfigValues.positiveInt("src", config, "timeoutMs", 5000));
        }

        @Test
        @DisplayName("zero throws naming the field")
        void zeroThrowsNamingField() {
            JsonObject config = new JsonObject().put("timeoutMs", 0);
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("my-source", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
            assertEquals("my-source", ex.sourceName());
        }

        @Test
        @DisplayName("negative value throws naming the field")
        void negativeThrowsNamingField() {
            JsonObject config = new JsonObject().put("timeoutMs", -1);
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("my-source", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("non-numeric string throws naming the field")
        void nonNumericStringThrowsNamingField() {
            JsonObject config = new JsonObject().put("timeoutMs", "not-a-number");
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("my-source", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
            assertEquals("my-source", ex.sourceName());
        }

        @Test
        @DisplayName("fractional Double (5000.5) is rejected — silent truncation not allowed")
        void fractionalDoubleRejected() {
            JsonObject config = new JsonObject().put("timeoutMs", 5000.5);
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("src", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("exact integral Double (5000.0) is accepted")
        void exactIntegralDoubleAccepted() {
            JsonObject config = new JsonObject().put("timeoutMs", 5000.0);
            assertEquals(5000, SourceConfigValues.positiveInt("src", config, "timeoutMs", 1));
        }

        @Test
        @DisplayName("Long within int range (5000L) is accepted")
        void longWithinRangeAccepted() {
            JsonObject config = new JsonObject().put("timeoutMs", 5000L);
            assertEquals(5000, SourceConfigValues.positiveInt("src", config, "timeoutMs", 1));
        }

        @Test
        @DisplayName("Long overflow (3_000_000_000L) is rejected")
        void longOverflowRejected() {
            JsonObject config = new JsonObject().put("timeoutMs", 3_000_000_000L);
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("src", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("BigDecimal 1.5 (fractional) is rejected naming the field")
        void bigDecimalFractionalRejected() {
            JsonObject config = new JsonObject().put("timeoutMs", new java.math.BigDecimal("1.5"));
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("src", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("BigInteger 2^40 (> Integer.MAX_VALUE) is rejected naming the field")
        void bigIntegerOversizedRejected() {
            // 2^40 = 1_099_511_627_776 — far exceeds Integer.MAX_VALUE
            JsonObject config = new JsonObject().put("timeoutMs", java.math.BigInteger.TWO.pow(40));
            var ex = assertThrows(
                    ConfigPropertySourceException.class,
                    () -> SourceConfigValues.positiveInt("src", config, "timeoutMs", 5000));
            assertTrue(ex.getMessage().contains("timeoutMs"), "error must name the field");
        }

        @Test
        @DisplayName("BigDecimal 5000 (exact integral) is accepted")
        void bigDecimalExactIntegralAccepted() {
            JsonObject config = new JsonObject().put("timeoutMs", new java.math.BigDecimal("5000"));
            assertEquals(5000, SourceConfigValues.positiveInt("src", config, "timeoutMs", 1));
        }
    }
}
