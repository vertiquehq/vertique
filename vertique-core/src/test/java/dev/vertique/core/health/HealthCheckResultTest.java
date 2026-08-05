// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.health;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link HealthCheckResult} factory methods, data normalization, and
 * immutability contract.
 *
 * <p>Also pins the null-tolerance contract of the DOWN factories: a null error
 * string must not blow up, and {@link HealthCheckResult#down(Throwable)} must
 * derive a usable {@code "error"} value from a cause whose message is absent.
 */
class HealthCheckResultTest {

    // --- UP factory methods ---

    @Nested
    class UpFactories {

        @Test
        @DisplayName("up() returns UP with empty data")
        void upNoData() {
            HealthCheckResult result = HealthCheckResult.up();
            assertEquals(HealthStatus.UP, result.status());
            assertTrue(result.data().isEmpty());
        }

        @Test
        @DisplayName("up(Map) returns UP with provided data")
        void upWithData() {
            Map<String, Object> data = Map.of("pool.active", 2);
            HealthCheckResult result = HealthCheckResult.up(data);
            assertEquals(HealthStatus.UP, result.status());
            assertEquals(2, result.data().get("pool.active"));
        }
    }

    // --- DOWN factory methods ---

    @Nested
    class DownFactories {

        @Test
        @DisplayName("down() returns DOWN with empty data")
        void downNoData() {
            HealthCheckResult result = HealthCheckResult.down();
            assertEquals(HealthStatus.DOWN, result.status());
            assertTrue(result.data().isEmpty());
        }

        @Test
        @DisplayName("down(String) returns DOWN with error in data")
        void downWithError() {
            HealthCheckResult result = HealthCheckResult.down("connection refused");
            assertEquals(HealthStatus.DOWN, result.status());
            assertEquals("connection refused", result.data().get("error"));
        }

        @Test
        @DisplayName("down(Map) returns DOWN with provided data")
        void downWithData() {
            Map<String, Object> data = Map.of("host", "db.local", "error", "timeout");
            HealthCheckResult result = HealthCheckResult.down(data);
            assertEquals(HealthStatus.DOWN, result.status());
            assertEquals("timeout", result.data().get("error"));
        }

        @Test
        @DisplayName("down(String) with null error returns DOWN with empty data instead of throwing")
        void downWithNullErrorHasEmptyData() {
            HealthCheckResult result = assertDoesNotThrow(() -> HealthCheckResult.down((String) null));
            assertEquals(HealthStatus.DOWN, result.status());
            assertTrue(result.data().isEmpty());
            assertFalse(result.data().containsKey("error"));
        }
    }

    // --- DOWN from a Throwable cause ---

    @Nested
    class DownFromThrowable {

        @Test
        @DisplayName("down(Throwable) uses the cause message as the error value")
        void downWithThrowableUsesMessage() {
            RuntimeException cause = new RuntimeException("boom");
            HealthCheckResult result = HealthCheckResult.down(cause);
            assertEquals(HealthStatus.DOWN, result.status());
            assertEquals("boom", result.data().get("error"));
        }

        @Test
        @DisplayName("down(Throwable) falls back to the class name when the message is null")
        void downWithThrowableFallsBackToClassName() {
            IllegalStateException cause = new IllegalStateException();
            HealthCheckResult result = HealthCheckResult.down(cause);
            assertEquals("java.lang.IllegalStateException", result.data().get("error"));
        }

        @Test
        @DisplayName("down(Throwable) fallback for an anonymous cause is non-blank")
        void downWithAnonymousThrowableFallbackIsNotBlank() {
            // Anonymous subclass: getSimpleName() would be "", so the fallback must use getName().
            RuntimeException cause = new RuntimeException() {};
            assertNull(cause.getMessage());

            HealthCheckResult result = HealthCheckResult.down(cause);

            Object error = result.data().get("error");
            assertInstanceOf(String.class, error);
            assertFalse(((String) error).isBlank());
            assertTrue(
                    ((String) error).contains("HealthCheckResultTest"),
                    "fallback should be the binary class name, which nests under the test class");
        }

        @Test
        @DisplayName("down(Throwable) passes a blank message through unnormalized")
        void downWithBlankMessagePassesThrough() {
            RuntimeException cause = new RuntimeException("   ");
            HealthCheckResult result = HealthCheckResult.down(cause);
            assertEquals("   ", result.data().get("error"));
        }

        @Test
        @DisplayName("down(Throwable) rejects a null cause")
        void downWithNullThrowableRejected() {
            assertThrows(NullPointerException.class, () -> HealthCheckResult.down((Throwable) null));
        }
    }

    // --- Data normalization and immutability ---

    @Nested
    class DataHandling {

        @Test
        @DisplayName("null data is normalized to empty map")
        void nullDataNormalized() {
            HealthCheckResult result = new HealthCheckResult(HealthStatus.UP, null);
            assertNotNull(result.data());
            assertTrue(result.data().isEmpty());
        }

        @Test
        @DisplayName("data map is defensively copied and unmodifiable")
        void dataDefensivelyCopied() {
            HealthCheckResult result = HealthCheckResult.up(Map.of("key", "value"));
            assertThrows(
                    UnsupportedOperationException.class, () -> result.data().put("new", "value"));
        }
    }
}
