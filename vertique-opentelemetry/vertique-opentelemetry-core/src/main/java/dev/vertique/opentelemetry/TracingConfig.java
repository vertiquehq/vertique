// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the OpenTelemetry tracing subsystem.
 *
 * <p>Deserialized from the {@code tracing} section of the application configuration:
 *
 * <pre>{@code
 * {
 *   "tracing": {
 *     "enabled": true,
 *     "security": { "spanEvents": true },
 *     "otel": { ... }
 *   }
 * }
 * }</pre>
 *
 * <p>The {@code tracing.otel} subtree is read raw by {@link OtelConfigProperties} and forwarded to
 * {@code AutoConfiguredOpenTelemetrySdk} as a properties supplier — it is not modeled here. All
 * fields have sensible defaults and are optional in the configuration. Unknown properties are
 * silently ignored to allow forward-compatible configuration files.
 *
 * @see OpenTelemetryBootstrapContributor
 * @see OtelConfigProperties
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
public class TracingConfig {

    /** Whether tracing is enabled globally (default {@code true}). */
    @Builder.Default
    private final boolean enabled = true;

    /**
     * Security-span configuration (default: span events enabled).
     *
     * <p>{@code @JsonSetter(nulls = Nulls.SKIP)} ensures that an explicit JSON {@code null} value
     * for this field is treated the same as a missing key — the {@code @Builder.Default} value is
     * retained rather than overwriting the field with {@code null}, which would cause
     * {@code config.security().spanEvents()} to throw a {@link NullPointerException}.
     */
    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    private final SecurityConfig security = SecurityConfig.builder().build();

    // --- Nested config classes ---

    /**
     * Configuration for security-related span events emitted onto active spans.
     */
    @Getter
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE)
    public static class SecurityConfig {

        /**
         * Whether security span events (e.g. auth failures, permission checks) are emitted onto
         * active spans (default {@code true}).
         */
        @Builder.Default
        private final boolean spanEvents = true;
    }
}
