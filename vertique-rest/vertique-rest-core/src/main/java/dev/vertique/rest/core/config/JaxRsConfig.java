// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * JAX-RS routing configuration for the framework. Deserialized from the {@code "jaxrs"} section
 * of the application config JSON, consolidating mount path, OpenAPI spec location, media type
 * validation mode, default response headers, and caching behaviour into a single typed object.
 *
 * <p>All fields have sensible defaults. Applications override individual fields by providing
 * them in the {@code "jaxrs"} section of their config:
 *
 * <pre>{@code
 * {
 *   "jaxrs": {
 *     "basePath": "/api/*",
 *     "openapiPath": "openapi.json",
 *     "mediaTypeValidation": "STRICT",
 *     "validationMode": "aggregate",
 *     "autoEtag": false,
 *     "defaultHeaders": {
 *       "cacheControl": "no-store",
 *       "strictTransportSecurity": "max-age=31536000"
 *     }
 *   }
 * }
 * }</pre>
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class JaxRsConfig {

    /**
     * API mount path for the default JAX-RS router mount. The sub-router created by
     * {@link dev.vertique.rest.core.router.RouterMount} is attached at this path prefix.
     * Defaults to {@code "/*"} (root mount, backward compatible).
     */
    @Builder.Default
    private final String basePath = "/*";

    /**
     * Classpath location of the OpenAPI specification file. Used by the opt-in
     * {@code openapi-contract} validation strategy ({@code vertique-rest-openapi-validation})
     * to load the contract at runtime. The default path {@code "openapi.json"} matches what
     * {@code swagger-maven-plugin-jakarta} writes at build time. This field is irrelevant when
     * using the default {@code web-validation} strategy — {@code openapi.json} is
     * documentation-only in that configuration.
     */
    @Builder.Default
    private final String openapiPath = "openapi.json";

    /**
     * Media type validation mode. Controls how the framework reports mismatches between
     * declared {@code @Consumes}/{@code @Produces} annotations and the registered
     * {@link dev.vertique.rest.core.request.RequestBodyDecoder}/{@link dev.vertique.rest.core.response.ResponseBodyEncoder} implementations.
     *
     * <ul>
     *   <li>{@code "WARN"} (default) — log a warning for each unsupported media type</li>
     *   <li>{@code "STRICT"} — throw a {@link dev.vertique.rest.core.RestConfigurationException} on the first mismatch</li>
     *   <li>{@code "OFF"} — skip validation entirely</li>
     * </ul>
     */
    @Builder.Default
    private final String mediaTypeValidation = "WARN";

    /**
     * Stable id of the request-validation strategy selected at router-build time. The framework
     * resolves this id against the registered
     * {@code Set<RequestValidationStrategy>} (matching on each strategy's {@code id()}); when no
     * strategy with this id is registered, startup fails with a
     * {@link dev.vertique.rest.core.RestConfigurationException} listing the available ids. Built-in ids
     * are {@code "web-validation"} (the default vertx-json-schema gate, provided by
     * {@code vertique-rest-validation}), {@code "none"} (no validation), and the opt-in
     * {@code "openapi-contract"} strategy. Defaults to {@code "web-validation"}.
     */
    @Builder.Default
    private final String validationStrategy = "web-validation";

    /**
     * Validation error collection mode for the {@code web-validation} gate. Controls whether the
     * gate collects all schema violations before returning a 400 response, or stops at the first
     * violation:
     *
     * <ul>
     *   <li>{@code "aggregate"} (default) — all body and parameter violations are collected into
     *       a single {@link dev.vertique.rest.core.RestValidationException} before failing, giving
     *       clients a complete picture of what is wrong with the request.</li>
     *   <li>{@code "failFast"} — the gate stops at the first violation encountered (params first,
     *       then body), returning a single-entry error list. Useful when schema validation is
     *       expensive or when clients are expected to fix one error at a time.</li>
     * </ul>
     *
     * <p>The configured mode is resolved when the gate handler is built at startup (via
     * {@link dev.vertique.rest.validation.WebValidationStrategy}). Defaults to {@code "aggregate"}.
     */
    @Builder.Default
    private final String validationMode = "aggregate";

    /**
     * Default security and cache-control headers applied to every HTTP response by
     * {@link dev.vertique.rest.core.middleware.DefaultHeadersMiddleware}. Defaults to the
     * standard set ({@code Cache-Control: no-store}, {@code X-Content-Type-Options: nosniff},
     * {@code X-Frame-Options: DENY}).
     */
    @Builder.Default
    private final DefaultHeadersConfig defaultHeaders =
            DefaultHeadersConfig.builder().build();

    /**
     * Whether the framework should automatically compute and attach a weak ETag to responses
     * that have a serializable entity but no explicit {@code ETag} header. When {@code true},
     * a weak ETag derived from a hash of the serialized body is added before precondition
     * evaluation. Defaults to {@code false}.
     */
    @Builder.Default
    private final boolean autoEtag = false;

    /**
     * Server-Sent Events configuration controlling keep-alive behavior and default channel buffer
     * settings. Defaults to a 15-second keep-alive interval with a 256-event buffer using the
     * {@link dev.vertique.rest.core.sse.BufferOverflowPolicy#FAIL} overflow policy.
     */
    @Builder.Default
    private final SseConfig sse = SseConfig.builder().build();

    /**
     * Default JSON mapper profile id applied to request-body (de)serialization at every JAX-RS
     * resource method that does not select a profile of its own (config key {@code jaxrs.jsonProfile}).
     * The effective profile per method is resolved as method-level {@code @JsonProfile} &rarr;
     * class-level {@code @JsonProfile} &rarr; this config value &rarr; the global
     * {@code json.jsonProfile} default &rarr; the reserved {@code vertx} profile.
     *
     * <p>A {@code null} or blank value (the default) means "no JAX-RS-level default" — resolution falls
     * through to the global {@code json.jsonProfile} default and ultimately the reserved {@code vertx}
     * profile (the zero-config behavior backed by Vert.x's {@code DatabindCodec.mapper()}). A non-blank
     * value must match a profile registered in the
     * {@link dev.vertique.core.json.JsonMapperProfileRegistry}, otherwise startup fails fast.
     */
    @Nullable
    private final String jsonProfile;
}
