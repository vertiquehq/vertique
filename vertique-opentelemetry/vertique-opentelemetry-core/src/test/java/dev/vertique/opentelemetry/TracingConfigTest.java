// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TracingConfig} deserialization and default values.
 *
 * <p>Verifies that the config is correctly populated from JSON and that all defaults match the
 * documented values, including the explicit-null-skip behaviour for nested object fields.
 */
class TracingConfigTest {

    // --- Test 1: Empty object gives all defaults ---

    @Nested
    @DisplayName("empty JSON gives all defaults")
    class Defaults {

        @Test
        @DisplayName("enabled=true and security.spanEvents=true by default")
        void defaultsFromEmptyJson() {
            TracingConfig config = new JsonObject().mapTo(TracingConfig.class);

            assertTrue(config.enabled(), "enabled should default to true");
            assertTrue(config.security().spanEvents(), "security.spanEvents should default to true");
        }
    }

    // --- Test 2: enabled=false ---

    @Nested
    @DisplayName("enabled=false disables tracing")
    class Disabled {

        @Test
        @DisplayName("enabled=false parsed correctly")
        void enabledFalse() {
            TracingConfig config = new JsonObject().put("enabled", false).mapTo(TracingConfig.class);

            assertFalse(config.enabled(), "enabled should be false");
            assertTrue(config.security().spanEvents(), "security.spanEvents should still default to true");
        }
    }

    // --- Test 3: security.spanEvents=false ---

    @Nested
    @DisplayName("security sub-config override")
    class SecurityConfig {

        @Test
        @DisplayName("security.spanEvents=false parsed correctly")
        void spanEventsFalse() {
            TracingConfig config = new JsonObject()
                    .put("security", new JsonObject().put("spanEvents", false))
                    .mapTo(TracingConfig.class);

            assertTrue(config.enabled(), "enabled should still default to true");
            assertFalse(config.security().spanEvents(), "security.spanEvents should be false");
        }
    }

    // --- Test 4: explicit null on security falls back to default (W2-tracing fix) ---

    @Nested
    @DisplayName("explicit JSON null on nested object field falls back to default")
    class ExplicitNullSkip {

        /**
         * Verifies that {@code {"security":null}} does NOT set the field to {@code null} —
         * instead the {@code @Builder.Default} value is retained (non-null, spanEvents=true).
         * Without {@code @JsonSetter(nulls = Nulls.SKIP)}, Jackson would overwrite the default
         * with {@code null}, causing {@code config.security().spanEvents()} to NPE.
         */
        @Test
        @DisplayName("explicit null on security retains default SecurityConfig (non-null, spanEvents=true)")
        void explicitNullSecurityRetainsDefault() {
            TracingConfig config = new JsonObject().putNull("security").mapTo(TracingConfig.class);

            assertNotNull(config.security(), "security must not be null when JSON provides explicit null");
            assertTrue(
                    config.security().spanEvents(),
                    "security.spanEvents must still be true (default retained) when JSON provides null");
        }

        /**
         * Smoke test: constructing a {@link SecuritySpanEventObserver} with an explicit-null
         * security config must not throw, because the field is non-null after deserialization.
         */
        @Test
        @DisplayName("explicit null on security does not cause NPE in SecuritySpanEventObserver")
        void explicitNullSecurityDoesNotNpeInObserver() {
            TracingConfig config = new JsonObject().putNull("security").mapTo(TracingConfig.class);

            // This would throw NullPointerException before the fix
            SecuritySpanEventObserver observer = new SecuritySpanEventObserver(config);
            assertNotNull(
                    observer, "SecuritySpanEventObserver must be constructible with explicit-null security config");
        }
    }
}
