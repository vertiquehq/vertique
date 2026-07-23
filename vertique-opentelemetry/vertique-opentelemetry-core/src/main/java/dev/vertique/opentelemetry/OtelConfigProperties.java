// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import dev.vertique.core.config.JsonConfigPaths;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces an OpenTelemetry SDK property map from the application's root {@link JsonObject}.
 *
 * <p>The resulting map is intended for use with
 * {@code AutoConfiguredOpenTelemetrySdkBuilder.addPropertiesSupplier(Supplier)}, which is the
 * <em>lowest</em>-precedence layer: environment variables and system properties always override it.
 *
 * <h2>Property construction rules</h2>
 * <ol>
 *   <li><strong>Seeds:</strong> {@code otel.metrics.exporter=none} and
 *       {@code otel.logs.exporter=none} are always injected first — metrics are Micrometer-owned
 *       and logs are not managed by this module.</li>
 *   <li><strong>Service name:</strong> {@code otel.service.name} is seeded from the first
 *       non-blank of: {@code tracing.otel.service.name} (via the flattening step),
 *       {@code metrics.tags.service} from root config, or the literal {@code "unknown-service"}.
 *       The {@code OTEL_SERVICE_NAME} environment variable will override this via autoconfigure's
 *       own precedence — no special handling is required here.</li>
 *   <li><strong>Flattening:</strong> the {@code tracing.otel} subtree is recursively flattened;
 *       nested {@link JsonObject} values are joined with {@code .}; leaf values are stringified;
 *       all keys are prefixed with {@code otel.}. Flattened entries overwrite seeds on key
 *       collision, so explicit config beats seeded defaults.</li>
 * </ol>
 */
final class OtelConfigProperties {

    /** Prevent instantiation — this is a static utility class. */
    private OtelConfigProperties() {}

    /**
     * Builds the OpenTelemetry property map from the application root configuration.
     *
     * @param rootConfig the full application root config; never {@code null}
     * @return a mutable map of {@code otel.*} properties suitable for
     *         {@code addPropertiesSupplier}; never {@code null}
     * @throws dev.vertique.core.exception.ConfigurationException when any traversed config
     *     section (e.g. {@code metrics.tags} or {@code tracing.otel}) is present but not a
     *     JSON object
     */
    static Map<String, String> properties(JsonObject rootConfig) {
        // Use LinkedHashMap to keep insertion order visible in debug output
        Map<String, String> props = new LinkedHashMap<>();

        // --- Step 1: Seeds ---
        props.put("otel.metrics.exporter", "none");
        props.put("otel.logs.exporter", "none");

        // --- Step 2: Service name seed ---
        // Resolution order: metrics.tags.service → "unknown-service"
        // (tracing.otel.service.name overwrites this in the flatten step)
        String serviceName = resolveServiceName(rootConfig);
        props.put("otel.service.name", serviceName);

        // --- Step 3: Flatten tracing.otel subtree (overwrites seeds on collision) ---
        // navigateObject throws ConfigurationException when a segment is present but not a JsonObject
        JsonObject otelSubtree = JsonConfigPaths.navigateObject(rootConfig, "tracing", "otel");
        flatten(otelSubtree, "otel", props);

        return props;
    }

    /**
     * Resolves the service name from the root config, using {@code metrics.tags.service} as the
     * first fallback and {@code "unknown-service"} as the final fallback.
     *
     * <p>Throws {@link dev.vertique.core.exception.ConfigurationException} when
     * {@code metrics} or {@code metrics.tags} is present but not a JSON object.
     *
     * @param rootConfig the full application root config
     * @return a non-null, non-blank service name
     * @throws dev.vertique.core.exception.ConfigurationException when a config section is
     *     present but not a JSON object
     */
    private static String resolveServiceName(JsonObject rootConfig) {
        // navigateObject throws ConfigurationException when a segment is present but not a JsonObject
        JsonObject tagsSection = JsonConfigPaths.navigateObject(rootConfig, "metrics", "tags");
        String fromMetrics = tagsSection.getString("service", null);
        if (fromMetrics != null && !fromMetrics.isBlank()) {
            return fromMetrics;
        }
        return "unknown-service";
    }

    /**
     * Recursively flattens a {@link JsonObject} subtree into the target map.
     *
     * <p>Nested objects are recursed with a dotted-prefix path. Leaf values are converted to
     * strings via {@link Object#toString()}. All resulting keys are prefixed with {@code prefix}.
     *
     * @param obj    the JSON object to flatten; never {@code null}
     * @param prefix the current key prefix (e.g. {@code "otel"} or {@code "otel.exporter"})
     * @param target the map to write into
     */
    private static void flatten(JsonObject obj, String prefix, Map<String, String> target) {
        for (String key : obj.fieldNames()) {
            Object value = obj.getValue(key);
            String qualifiedKey = prefix + "." + key;
            if (value instanceof JsonObject nested) {
                flatten(nested, qualifiedKey, target);
            } else if (value != null) {
                target.put(qualifiedKey, value.toString());
            }
        }
    }
}
