// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.management;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link HealthCheckResult} factory methods, data normalization, and
 * immutability contract.
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
