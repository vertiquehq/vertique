// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OtelConfigProperties}.
 *
 * <p>Verifies seed injection, service-name resolution priority, recursive flattening of the
 * {@code tracing.otel} subtree, and that flattened entries overwrite seeds on key collision.
 */
class OtelConfigPropertiesTest {

    // --- Test 1: Empty root → 3 seeds, unknown-service ---

    @Nested
    @DisplayName("empty root config produces seeds with unknown-service")
    class EmptyRoot {

        @Test
        @DisplayName(
                "3 seeds present: otel.metrics.exporter=none, otel.logs.exporter=none, otel.service.name=unknown-service")
        void emptyRootHasSeeds() {
            Map<String, String> props = OtelConfigProperties.properties(new JsonObject());

            assertEquals("none", props.get("otel.metrics.exporter"), "metrics exporter seed");
            assertEquals("none", props.get("otel.logs.exporter"), "logs exporter seed");
            assertEquals("unknown-service", props.get("otel.service.name"), "service name fallback");
            assertEquals(3, props.size(), "exactly 3 properties for empty root");
        }
    }

    // --- Test 2: metrics.tags.service used as service name fallback ---

    @Nested
    @DisplayName("metrics.tags.service used when no explicit tracing.otel.service.name")
    class MetricsTagsServiceFallback {

        @Test
        @DisplayName("otel.service.name=orders when metrics.tags.service=orders")
        void metricsTagsServiceFallback() {
            JsonObject root = new JsonObject()
                    .put("metrics", new JsonObject().put("tags", new JsonObject().put("service", "orders")));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertEquals("orders", props.get("otel.service.name"));
        }
    }

    // --- Test 3: tracing.otel.service.name wins over metrics.tags.service ---

    @Nested
    @DisplayName("explicit tracing.otel.service.name overrides metrics.tags.service seed")
    class ExplicitServiceName {

        @Test
        @DisplayName("flattening otel.service.name=explicit overwrites metrics.tags.service=orders seed")
        void explicitServiceNameWins() {
            JsonObject root = new JsonObject()
                    .put("metrics", new JsonObject().put("tags", new JsonObject().put("service", "orders")))
                    .put(
                            "tracing",
                            new JsonObject()
                                    .put(
                                            "otel",
                                            new JsonObject().put("service", new JsonObject().put("name", "explicit"))));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertEquals("explicit", props.get("otel.service.name"), "explicit otel config must win");
        }
    }

    // --- Test 4: Recursive flattening of nested otel config ---

    @Nested
    @DisplayName("recursive flattening joins nested keys with dots and prefixes otel.")
    class RecursiveFlattening {

        @Test
        @DisplayName("nested otel config flattened to dotted keys with otel. prefix")
        void nestedOtelConfigFlattened() {
            JsonObject root = new JsonObject()
                    .put(
                            "tracing",
                            new JsonObject()
                                    .put(
                                            "otel",
                                            new JsonObject()
                                                    .put(
                                                            "exporter",
                                                            new JsonObject()
                                                                    .put(
                                                                            "otlp",
                                                                            new JsonObject()
                                                                                    .put(
                                                                                            "endpoint",
                                                                                            "http://collector:4317")))
                                                    .put(
                                                            "traces",
                                                            new JsonObject().put("sampler", "parentbased_always_on"))));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertEquals(
                    "http://collector:4317",
                    props.get("otel.exporter.otlp.endpoint"),
                    "deeply nested endpoint flattened");
            assertEquals("parentbased_always_on", props.get("otel.traces.sampler"), "sampler flattened");
        }
    }

    // --- Test 5: Flattened entry overwrites seed ---

    @Nested
    @DisplayName("explicit flattened entry overwrites seed")
    class SeedOverride {

        @Test
        @DisplayName("tracing.otel.metrics.exporter=prometheus overwrites otel.metrics.exporter=none seed")
        void flattenedOverwritesSeed() {
            JsonObject root = new JsonObject()
                    .put(
                            "tracing",
                            new JsonObject()
                                    .put(
                                            "otel",
                                            new JsonObject()
                                                    .put("metrics", new JsonObject().put("exporter", "prometheus"))));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertEquals("prometheus", props.get("otel.metrics.exporter"), "flattened must overwrite seed");
        }
    }

    // --- Test 6: Numeric and boolean leaf values stringified ---

    @Nested
    @DisplayName("non-string leaf values are stringified")
    class LeafStringification {

        @Test
        @DisplayName("integer and boolean leaves are converted to strings")
        void numericAndBooleanLeaves() {
            JsonObject root = new JsonObject()
                    .put(
                            "tracing",
                            new JsonObject()
                                    .put(
                                            "otel",
                                            new JsonObject()
                                                    .put(
                                                            "batch",
                                                            new JsonObject()
                                                                    .put("maxSize", 512)
                                                                    .put("enabled", true))));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertEquals("512", props.get("otel.batch.maxSize"), "integer must be stringified");
            assertEquals("true", props.get("otel.batch.enabled"), "boolean must be stringified");
        }
    }

    // --- Test 7: blank service from metrics.tags falls back to unknown-service ---

    @Nested
    @DisplayName("blank metrics.tags.service falls back to unknown-service")
    class BlankServiceFallback {

        @Test
        @DisplayName("blank service name falls back to unknown-service")
        void blankServiceFallsBack() {
            JsonObject root = new JsonObject()
                    .put("metrics", new JsonObject().put("tags", new JsonObject().put("service", "   ")));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertEquals("unknown-service", props.get("otel.service.name"), "blank service should fall back");
        }
    }

    // --- Test 8: malformed metrics.tags section throws ConfigurationException ---

    @Nested
    @DisplayName("malformed metrics.tags section throws ConfigurationException")
    class MalformedMetricsTags {

        @Test
        @DisplayName("metrics.tags bound to an integer (5) → ConfigurationException from navigateObject")
        void metricsTagsScalarThrowsConfigurationException() {
            JsonObject root = new JsonObject().put("metrics", new JsonObject().put("tags", 5));

            assertThrows(ConfigurationException.class, () -> OtelConfigProperties.properties(root));
        }
    }

    // --- Test 9: non-otel tracing subtrees not included ---

    @Nested
    @DisplayName("non-otel tracing subtrees are not included")
    class NonOtelTracingIgnored {

        @Test
        @DisplayName("tracing.enabled does not appear in otel properties")
        void nonOtelTracingIgnored() {
            JsonObject root = new JsonObject()
                    .put(
                            "tracing",
                            new JsonObject()
                                    .put("enabled", true)
                                    .put(
                                            "otel",
                                            new JsonObject()
                                                    .put("traces", new JsonObject().put("sampler", "always_on"))));

            Map<String, String> props = OtelConfigProperties.properties(root);

            assertFalse(props.containsKey("otel.enabled"), "non-otel tracing key must not be included");
            assertTrue(props.containsKey("otel.traces.sampler"), "otel subtree must be included");
        }
    }
}
