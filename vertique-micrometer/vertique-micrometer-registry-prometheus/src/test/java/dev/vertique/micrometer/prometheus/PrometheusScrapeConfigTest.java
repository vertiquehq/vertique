// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link PrometheusScrapeConfig} deserialization and the scrape-path validation
 * logic in {@link MicrometerPrometheusModule#prometheusScrapeConfig(JsonObject)}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Default values when config is empty or keys are absent.</li>
 *   <li>Valid custom path is accepted.</li>
 *   <li>Invalid paths throw {@link ConfigurationException} with the config key and rule in the
 *       message.</li>
 *   <li>Wrong-type values (PRESENT but not the expected type) throw {@link ConfigurationException}
 *       naming the key and expected type — fail-fast rather than silently defaulting.</li>
 * </ul>
 */
class PrometheusScrapeConfigTest {

    // --- Defaults ---

    @Nested
    @DisplayName("defaults from empty config")
    class Defaults {

        @Test
        @DisplayName("empty config produces path='/metrics' and exemplarsEnabled=false")
        void emptyConfigYieldsDefaults() {
            PrometheusScrapeConfig cfg = MicrometerPrometheusModule.prometheusScrapeConfig(new JsonObject());
            assertEquals("/metrics", cfg.path(), "default path must be /metrics");
            assertFalse(cfg.exemplarsEnabled(), "default exemplarsEnabled must be false");
        }
    }

    // --- Valid custom path ---

    @Nested
    @DisplayName("valid custom path")
    class ValidPath {

        @Test
        @DisplayName("custom path '/prometheus/metrics' is accepted")
        void customPathAccepted() {
            JsonObject config = new JsonObject()
                    .put(
                            "metrics",
                            new JsonObject().put("scrape", new JsonObject().put("path", "/prometheus/metrics")));
            PrometheusScrapeConfig cfg = MicrometerPrometheusModule.prometheusScrapeConfig(config);
            assertEquals("/prometheus/metrics", cfg.path());
        }

        @Test
        @DisplayName("exemplarsEnabled=true is read from config")
        void exemplarsEnabledRead() {
            JsonObject config = new JsonObject()
                    .put(
                            "metrics",
                            new JsonObject()
                                    .put(
                                            "prometheus",
                                            new JsonObject().put("exemplars", new JsonObject().put("enabled", true))));
            PrometheusScrapeConfig cfg = MicrometerPrometheusModule.prometheusScrapeConfig(config);
            assertTrue(cfg.exemplarsEnabled(), "exemplarsEnabled must be true when configured");
        }
    }

    // --- Invalid paths ---

    @Nested
    @DisplayName("invalid paths throw ConfigurationException")
    class InvalidPaths {

        @ParameterizedTest(name = "path=''{0}'' is rejected")
        @ValueSource(strings = {"/health", "/health/x", "metrics", "/m*trics", "/a:b"})
        @DisplayName("reserved or malformed paths throw ConfigurationException")
        void invalidPathThrows(String path) {
            JsonObject config =
                    new JsonObject().put("metrics", new JsonObject().put("scrape", new JsonObject().put("path", path)));
            assertThrows(
                    ConfigurationException.class,
                    () -> MicrometerPrometheusModule.prometheusScrapeConfig(config),
                    "expected ConfigurationException for path: " + path);
        }
    }

    // --- Wrong-type values: fail-fast ---

    @Nested
    @DisplayName("wrong-type values (PRESENT but wrong type) throw ConfigurationException")
    class WrongTypeValues {

        @Test
        @DisplayName("metrics.scrape.path present as Integer (123) → ConfigurationException naming the key")
        void pathAsIntegerThrows() {
            // The key is present but with a numeric value rather than a String
            JsonObject config =
                    new JsonObject().put("metrics", new JsonObject().put("scrape", new JsonObject().put("path", 123)));
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> MicrometerPrometheusModule.prometheusScrapeConfig(config),
                    "PRESENT-but-non-String path must throw ConfigurationException");
            assertTrue(
                    ex.getMessage().contains("metrics.scrape.path"),
                    "exception message must name the offending key, got: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("String"),
                    "exception message must name the expected type 'String', got: " + ex.getMessage());
        }

        @Test
        @DisplayName(
                "metrics.prometheus.exemplars.enabled present as String (\"yes\") → ConfigurationException naming the key")
        void exemplarsEnabledAsStringThrows() {
            // The key is present but with a String value rather than a Boolean
            JsonObject config = new JsonObject()
                    .put(
                            "metrics",
                            new JsonObject()
                                    .put(
                                            "prometheus",
                                            new JsonObject().put("exemplars", new JsonObject().put("enabled", "yes"))));
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> MicrometerPrometheusModule.prometheusScrapeConfig(config),
                    "PRESENT-but-non-Boolean exemplarsEnabled must throw ConfigurationException");
            assertTrue(
                    ex.getMessage().contains("metrics.prometheus.exemplars.enabled"),
                    "exception message must name the offending key, got: " + ex.getMessage());
            assertTrue(
                    ex.getMessage().contains("Boolean"),
                    "exception message must name the expected type 'Boolean', got: " + ex.getMessage());
        }

        @Test
        @DisplayName("absent metrics.scrape.path → default '/metrics' (absent is not wrong-type)")
        void absentPathUsesDefault() {
            PrometheusScrapeConfig cfg = MicrometerPrometheusModule.prometheusScrapeConfig(new JsonObject());
            assertEquals("/metrics", cfg.path(), "absent path must default to /metrics");
        }

        @Test
        @DisplayName("absent metrics.prometheus.exemplars.enabled → default false (absent is not wrong-type)")
        void absentExemplarsUsesDefault() {
            PrometheusScrapeConfig cfg = MicrometerPrometheusModule.prometheusScrapeConfig(new JsonObject());
            assertFalse(cfg.exemplarsEnabled(), "absent exemplarsEnabled must default to false");
        }
    }

    // --- INVALID_SHAPE — intermediate segment present but not a JSON object ---

    @Nested
    @DisplayName("INVALID_SHAPE — intermediate segment present but not a JSON object → ConfigurationException")
    class InvalidShapeIntermediateSegment {

        @Test
        @DisplayName(
                "metrics.scrape = scalar string → INVALID_SHAPE on 'scrape.path' resolve → ConfigurationException naming the key")
        void scrapeAsScalarThrows() {
            // "metrics.scrape" is a string scalar, not a JsonObject — traversing into it to read
            // "scrape.path" returns INVALID_SHAPE; must throw ConfigurationException, not default.
            JsonObject config = new JsonObject().put("metrics", new JsonObject().put("scrape", "bad"));
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> MicrometerPrometheusModule.prometheusScrapeConfig(config),
                    "INVALID_SHAPE on metrics.scrape must throw ConfigurationException, not default to /metrics");
            assertTrue(
                    ex.getMessage().contains("metrics.scrape.path"),
                    "exception message must name the config key, got: " + ex.getMessage());
        }

        @Test
        @DisplayName(
                "metrics.prometheus = scalar string → INVALID_SHAPE on 'prometheus.exemplars.enabled' resolve → ConfigurationException naming the key")
        void prometheusAsScalarThrows() {
            // "metrics.prometheus" is a string scalar, not a JsonObject — INVALID_SHAPE; must throw,
            // not silently default exemplarsEnabled to false.
            JsonObject config = new JsonObject().put("metrics", new JsonObject().put("prometheus", "bad"));
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> MicrometerPrometheusModule.prometheusScrapeConfig(config),
                    "INVALID_SHAPE on metrics.prometheus must throw ConfigurationException, not default to false");
            assertTrue(
                    ex.getMessage().contains("metrics.prometheus"),
                    "exception message must name the config key, got: " + ex.getMessage());
        }
    }

    // --- S1: INVALID_SHAPE message must report the correct full dotted path ---

    @Nested
    @DisplayName("S1: INVALID_SHAPE message reports the full dotted path (not just the failing segment)")
    class InvalidShapeFullPathMessage {

        /**
         * Verifies S1: when "metrics.prometheus.exemplars" is a scalar, the INVALID_SHAPE error
         * message must name the full failing path "metrics.prometheus.exemplars" in the
         * intermediate-segment parenthetical — not the truncated "metrics.exemplars" that results
         * from using only {@code failingSegment()} without its parent path context.
         *
         * <p>The current (buggy) message looks like:
         * <pre>"metrics.prometheus.exemplars.enabled: an intermediate segment (metrics.exemplars) ..."</pre>
         * The correct message must have:
         * <pre>"... (metrics.prometheus.exemplars) ..."</pre>
         */
        @Test
        @DisplayName(
                "S1: {\"metrics\":{\"prometheus\":{\"exemplars\":\"bad\"}}} → message parenthetical names 'metrics.prometheus.exemplars', not 'metrics.exemplars'")
        void exemplarsAsScalarReportsFullPath() {
            // "metrics.prometheus.exemplars" is a scalar string, not a JsonObject.
            // resolve(metricsSection, "prometheus.exemplars.enabled") returns INVALID_SHAPE
            // with failingSegment()="exemplars" and path()="prometheus.exemplars.enabled".
            // The parenthetical in the error message must say "(metrics.prometheus.exemplars)"
            // not the truncated "(metrics.exemplars)".
            JsonObject config = new JsonObject()
                    .put("metrics", new JsonObject().put("prometheus", new JsonObject().put("exemplars", "bad")));
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class,
                    () -> MicrometerPrometheusModule.prometheusScrapeConfig(config),
                    "INVALID_SHAPE at metrics.prometheus.exemplars must throw ConfigurationException naming full path");

            String msg = ex.getMessage();
            // The parenthetical must contain the full failing path including all parent segments
            assertTrue(
                    msg.contains("metrics.prometheus.exemplars"),
                    "message must contain full path 'metrics.prometheus.exemplars', got: " + msg);
            // The parenthetical must NOT name just 'metrics.exemplars' without the prometheus parent
            // We test by checking the string "(metrics.exemplars)" does NOT appear — that's the
            // truncated form. "(metrics.prometheus.exemplars)" is the correct form.
            assertFalse(
                    msg.contains("(metrics.exemplars)"),
                    "message must NOT contain truncated '(metrics.exemplars)'; must include parent 'prometheus', got: "
                            + msg);
        }

        @Test
        @DisplayName("S1: existing scrapeAsScalarThrows still names 'metrics.scrape.path' (path already correct)")
        void scrapeAsScalarStillCorrect() {
            // Verify that the scrape path INVALID_SHAPE message is also correct (regression guard)
            JsonObject config = new JsonObject().put("metrics", new JsonObject().put("scrape", "bad"));
            ConfigurationException ex = assertThrows(
                    ConfigurationException.class, () -> MicrometerPrometheusModule.prometheusScrapeConfig(config));
            assertTrue(
                    ex.getMessage().contains("metrics.scrape.path"),
                    "scrape INVALID_SHAPE message must contain 'metrics.scrape.path', got: " + ex.getMessage());
        }
    }
}
