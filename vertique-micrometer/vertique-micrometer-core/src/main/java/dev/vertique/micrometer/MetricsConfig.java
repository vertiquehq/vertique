// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the Micrometer metrics subsystem.
 *
 * <p>Deserialized from the {@code metrics} section of the application configuration:
 *
 * <pre>{@code
 * {
 *   "metrics": {
 *     "enabled": true,
 *     "jvm": { "enabled": true },
 *     "vertx": {
 *       "httpServer": true,
 *       "httpClient": true,
 *       "netServer": true,
 *       "netClient": true,
 *       "eventBus": true,
 *       "datagramSocket": true,
 *       "namedPools": true,
 *       "labels": null
 *     },
 *     "tags": { "service": null, "extra": {} },
 *     "cardinality": { "maxTagValuesPerKey": 200, "maxMeters": 0 },
 *     "security": { "enabled": true }
 *   }
 * }
 * }</pre>
 *
 * <p>All fields have sensible defaults and are optional in the configuration. Unknown properties
 * are silently ignored to allow forward-compatible configuration files.
 *
 * @see MicrometerAssembly
 * @see MeterRegistryProvider
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
public class MetricsConfig {

    /** Whether metrics collection is enabled globally (default {@code true}). */
    @Builder.Default
    private final boolean enabled = true;

    /**
     * JVM-level metrics configuration (default: enabled).
     *
     * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null} value
     * for this field is treated the same as a missing key — the {@code @Builder.Default} value is
     * retained rather than overwriting the field with {@code null}.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private final JvmConfig jvm = JvmConfig.builder().build();

    /**
     * Vert.x framework metrics configuration (default: all subsystems enabled).
     *
     * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null} value
     * for this field is treated the same as a missing key — the {@code @Builder.Default} value is
     * retained rather than overwriting the field with {@code null}.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private final VertxMetricsConfig vertx = VertxMetricsConfig.builder().build();

    /**
     * Common tag configuration applied to all meters (default: no service name, no extra tags).
     *
     * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null} value
     * for this field is treated the same as a missing key — the {@code @Builder.Default} value is
     * retained rather than overwriting the field with {@code null}.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private final TagsConfig tags = TagsConfig.builder().build();

    /**
     * Cardinality limiting configuration (default: max 200 tag values per key, no meter limit).
     *
     * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null} value
     * for this field is treated the same as a missing key — the {@code @Builder.Default} value is
     * retained rather than overwriting the field with {@code null}.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private final CardinalityConfig cardinality = CardinalityConfig.builder().build();

    /**
     * Security-metrics configuration (default: enabled).
     *
     * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null} value
     * for this field is treated the same as a missing key — the {@code @Builder.Default} value is
     * retained rather than overwriting the field with {@code null}.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private final SecurityConfig security = SecurityConfig.builder().build();

    // --- Nested config classes ---

    /**
     * Configuration for JVM-level metrics binders (memory, GC, threads, class loader, processor).
     */
    @Getter
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
    public static class JvmConfig {

        /** Whether JVM metrics binders are registered (default {@code true}). */
        @Builder.Default
        private final boolean enabled = true;
    }

    /**
     * Configuration controlling which Vert.x subsystems have metrics collected.
     *
     * <p>All subsystem toggles default to {@code true}. The optional {@code labels} list
     * restricts which label keys are emitted for Vert.x meters; when {@code null} the default
     * Vert.x label set is used.
     */
    @Getter
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
    public static class VertxMetricsConfig {

        /** Collect HTTP server metrics (default {@code true}). */
        @Builder.Default
        private final boolean httpServer = true;

        /** Collect HTTP client metrics (default {@code true}). */
        @Builder.Default
        private final boolean httpClient = true;

        /** Collect net server metrics (default {@code true}). */
        @Builder.Default
        private final boolean netServer = true;

        /** Collect net client metrics (default {@code true}). */
        @Builder.Default
        private final boolean netClient = true;

        /** Collect event-bus metrics (default {@code true}). */
        @Builder.Default
        private final boolean eventBus = true;

        /** Collect datagram-socket metrics (default {@code true}). */
        @Builder.Default
        private final boolean datagramSocket = true;

        /** Collect named-pool metrics (default {@code true}). */
        @Builder.Default
        private final boolean namedPools = true;

        /**
         * Explicit list of Vert.x label keys to emit; {@code null} means "use the Vert.x default
         * label set". An empty list suppresses all labels.
         *
         * <p>NOTE: this field intentionally keeps its {@code null} default. An explicit JSON
         * {@code null} is semantically valid here and means "use Vert.x defaults" — the same as
         * the absent case. No {@code @JsonSetter(nulls=SKIP)} is applied because {@code null} is
         * the intended sentinel value.
         */
        @Builder.Default
        private final List<String> labels = null;
    }

    /**
     * Configuration for common tags applied to every meter in the composite registry.
     *
     * <p>{@code service} provides a canonical service-name tag. {@code extra} is a free-form map
     * of additional tag key-value pairs.
     */
    @Getter
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
    public static class TagsConfig {

        /**
         * The {@code service} tag value applied to all meters. When {@code null} or blank the
         * assembly falls back to the {@code OTEL_SERVICE_NAME} environment variable, then to
         * {@code "unknown-service"}.
         */
        @Builder.Default
        private final String service = null;

        /**
         * Additional tag key-value pairs applied to all meters. Defaults to an empty map.
         *
         * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null}
         * for this field falls back to the {@code @Builder.Default} empty map rather than making
         * the field {@code null} and causing a {@link NullPointerException} when the assembly
         * iterates {@code extra().forEach(...)}.
         */
        @Builder.Default
        @JsonSetter(nulls = Nulls.SKIP)
        private final Map<String, String> extra = Collections.emptyMap();
    }

    /**
     * Configuration for cardinality-limiting guards on the composite registry.
     *
     * <p>These settings provide safety bounds on high-cardinality label usage. A value of
     * {@code 0} for {@code maxMeters} means "no limit".
     */
    @Getter
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
    public static class CardinalityConfig {

        /** Maximum number of distinct tag values per key before new values are capped (default 200). */
        @Builder.Default
        private final int maxTagValuesPerKey = 200;

        /**
         * Maximum total number of meters allowed in the composite. {@code 0} means unlimited
         * (default {@code 0}).
         */
        @Builder.Default
        private final int maxMeters = 0;
    }

    /**
     * Configuration for security-related metrics collection (e.g. auth failure rates, JWT
     * validation counters).
     */
    @Getter
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
    public static class SecurityConfig {

        /** Whether security metrics are collected (default {@code true}). */
        @Builder.Default
        private final boolean enabled = true;
    }
}
