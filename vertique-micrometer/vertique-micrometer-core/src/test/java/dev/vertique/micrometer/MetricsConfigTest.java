// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MetricsConfig} — verifies default values and nested override deserialization
 * via the standard {@link JsonObject#mapTo(Class)} Jackson idiom used across all Vertique config VOs.
 */
class MetricsConfigTest {

    // --- Defaults ---

    @Nested
    class Defaults {

        @Test
        @DisplayName("empty JsonObject deserializes to enabled=true and all nested sub-configs with defaults")
        void emptyJsonObjectProducesDefaults() {
            MetricsConfig config = new JsonObject().mapTo(MetricsConfig.class);

            assertTrue(config.enabled());
            assertNotNull(config.jvm());
            assertTrue(config.jvm().enabled());
            assertNotNull(config.vertx());
            assertTrue(config.vertx().httpServer());
            assertTrue(config.vertx().httpClient());
            assertTrue(config.vertx().netServer());
            assertTrue(config.vertx().netClient());
            assertTrue(config.vertx().eventBus());
            assertTrue(config.vertx().datagramSocket());
            assertTrue(config.vertx().namedPools());
            assertNull(config.vertx().labels());
            assertNotNull(config.tags());
            assertNull(config.tags().service());
            assertNotNull(config.tags().extra());
            assertTrue(config.tags().extra().isEmpty());
            assertNotNull(config.cardinality());
            assertEquals(200, config.cardinality().maxTagValuesPerKey());
            assertEquals(0, config.cardinality().maxMeters());
            assertNotNull(config.security());
            assertTrue(config.security().enabled());
        }
    }

    // --- Nested overrides ---

    @Nested
    class NestedOverrides {

        @Test
        @DisplayName("jvm.enabled=false disables JVM metrics")
        void jvmEnabledOverride() {
            JsonObject json = new JsonObject().put("jvm", new JsonObject().put("enabled", false));

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertFalse(config.jvm().enabled());
            // top-level defaults still hold
            assertTrue(config.enabled());
        }

        @Test
        @DisplayName("cardinality.maxTagValuesPerKey override is applied")
        void cardinalityOverride() {
            JsonObject json = new JsonObject().put("cardinality", new JsonObject().put("maxTagValuesPerKey", 50));

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertEquals(50, config.cardinality().maxTagValuesPerKey());
            assertEquals(0, config.cardinality().maxMeters());
        }

        @Test
        @DisplayName("tags.service and tags.extra are deserialized correctly")
        void tagsOverride() {
            JsonObject json = new JsonObject()
                    .put(
                            "tags",
                            new JsonObject()
                                    .put("service", "my-service")
                                    .put(
                                            "extra",
                                            new JsonObject().put("env", "prod").put("region", "eu")));

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertEquals("my-service", config.tags().service());
            assertEquals(2, config.tags().extra().size());
            assertEquals("prod", config.tags().extra().get("env"));
            assertEquals("eu", config.tags().extra().get("region"));
        }

        @Test
        @DisplayName("vertx metrics toggles override correctly")
        void vertxMetricsOverride() {
            JsonObject json = new JsonObject()
                    .put("vertx", new JsonObject().put("httpServer", false).put("eventBus", false));

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertFalse(config.vertx().httpServer());
            assertFalse(config.vertx().eventBus());
            // other fields keep defaults
            assertTrue(config.vertx().httpClient());
            assertTrue(config.vertx().namedPools());
        }

        @Test
        @DisplayName("security.enabled=false disables security metrics")
        void securityEnabledOverride() {
            JsonObject json = new JsonObject().put("security", new JsonObject().put("enabled", false));

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertFalse(config.security().enabled());
        }

        @Test
        @DisplayName("unknown properties are ignored without error")
        void unknownPropertiesIgnored() {
            JsonObject json = new JsonObject()
                    .put("enabled", true)
                    .put("unknownProperty", "ignored")
                    .put("jvm", new JsonObject().put("enabled", false).put("anotherUnknown", 42));

            assertDoesNotThrow(() -> json.mapTo(MetricsConfig.class));
        }
    }

    // --- Hierarchical shape ---

    @Nested
    class HierarchicalShape {

        @Test
        @DisplayName("nested metrics object deserializes correctly via getJsonObject(\"metrics\").mapTo()")
        void nestedMetricsKeyDeserializesCorrectly() {
            JsonObject appConfig = new JsonObject()
                    .put(
                            "metrics",
                            new JsonObject().put("enabled", false).put("jvm", new JsonObject().put("enabled", false)));

            MetricsConfig config = appConfig.getJsonObject("metrics").mapTo(MetricsConfig.class);

            assertFalse(config.enabled());
            assertFalse(config.jvm().enabled());
        }
    }

    // --- Explicit JSON null on nested sections falls back to defaults (W2) ---

    @Nested
    @DisplayName("explicit JSON null on nested sections falls back to defaults")
    class ExplicitJsonNullFallback {

        @Test
        @DisplayName("{\"jvm\":null} → jvm falls back to default (non-null, enabled=true)")
        void jvmNullFallsBackToDefault() {
            JsonObject json = new JsonObject().putNull("jvm");

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertNotNull(config.jvm(), "jvm must not be null when JSON null is supplied");
            assertTrue(config.jvm().enabled(), "jvm.enabled must default to true");
        }

        @Test
        @DisplayName("{\"vertx\":null} → vertx falls back to default (non-null, httpServer=true)")
        void vertxNullFallsBackToDefault() {
            JsonObject json = new JsonObject().putNull("vertx");

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertNotNull(config.vertx(), "vertx must not be null when JSON null is supplied");
            assertTrue(config.vertx().httpServer(), "vertx.httpServer must default to true");
        }

        @Test
        @DisplayName("{\"tags\":null} → tags falls back to default (non-null, service=null, extra={})")
        void tagsNullFallsBackToDefault() {
            JsonObject json = new JsonObject().putNull("tags");

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertNotNull(config.tags(), "tags must not be null when JSON null is supplied");
            assertNull(config.tags().service(), "tags.service must default to null");
            assertNotNull(config.tags().extra(), "tags.extra must be non-null (empty map)");
            assertTrue(config.tags().extra().isEmpty(), "tags.extra must default to empty map");
        }

        @Test
        @DisplayName("{\"cardinality\":null} → cardinality falls back to default (maxTagValuesPerKey=200)")
        void cardinalityNullFallsBackToDefault() {
            JsonObject json = new JsonObject().putNull("cardinality");

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertNotNull(config.cardinality(), "cardinality must not be null when JSON null is supplied");
            assertEquals(200, config.cardinality().maxTagValuesPerKey(), "maxTagValuesPerKey must default to 200");
            assertEquals(0, config.cardinality().maxMeters(), "maxMeters must default to 0");
        }

        @Test
        @DisplayName("{\"security\":null} → security falls back to default (enabled=true)")
        void securityNullFallsBackToDefault() {
            JsonObject json = new JsonObject().putNull("security");

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertNotNull(config.security(), "security must not be null when JSON null is supplied");
            assertTrue(config.security().enabled(), "security.enabled must default to true");
        }

        @Test
        @DisplayName("{\"tags\":{\"extra\":null}} → tags.extra falls back to empty map")
        void tagsExtraNullFallsBackToEmptyMap() {
            JsonObject json = new JsonObject().put("tags", new JsonObject().putNull("extra"));

            MetricsConfig config = json.mapTo(MetricsConfig.class);

            assertNotNull(config.tags(), "tags must not be null");
            assertNotNull(config.tags().extra(), "tags.extra must not be null when JSON null is supplied");
            assertTrue(config.tags().extra().isEmpty(), "tags.extra must be empty map when JSON null is supplied");
        }
    }
}
