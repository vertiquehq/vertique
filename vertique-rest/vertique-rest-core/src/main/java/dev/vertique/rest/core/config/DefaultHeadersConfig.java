// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.util.Collections;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

/**
 * Configuration for the default security and cache-control response headers applied to every
 * HTTP response by {@link dev.vertique.rest.core.middleware.DefaultHeadersMiddleware}.
 *
 * <p>Known fields map to well-known security headers. Unknown JSON keys captured via
 * {@link JsonAnyGetter} are emitted as additional custom headers.
 *
 * <p>Example configuration:
 *
 * <pre>{@code
 * {
 *   "jaxrs": {
 *     "defaultHeaders": {
 *       "cacheControl": "no-cache, no-store",
 *       "strictTransportSecurity": "max-age=31536000; includeSubDomains",
 *       "X-Custom-Header": "custom-value"
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Setting a known field to {@code null} or an empty string suppresses the corresponding
 * header. Known built-in fields and their defaults:
 * <ul>
 *   <li>{@code cacheControl} → {@code Cache-Control: no-store}</li>
 *   <li>{@code contentTypeOptions} → {@code X-Content-Type-Options: nosniff}</li>
 *   <li>{@code frameOptions} → {@code X-Frame-Options: DENY}</li>
 *   <li>{@code strictTransportSecurity} → absent (not set by default)</li>
 *   <li>{@code referrerPolicy} → absent (not set by default)</li>
 * </ul>
 *
 * <p>Additional headers not matching any known field name are captured as custom headers and
 * applied as-is to every response.
 */
@Getter
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE)
public class DefaultHeadersConfig {

    /**
     * Value for the {@code Cache-Control} response header. Set to {@code null} or blank to
     * suppress the header entirely. Defaults to {@code "no-store"}.
     */
    @Builder.Default
    private final String cacheControl = "no-store";

    /**
     * Value for the {@code X-Content-Type-Options} response header. Set to {@code null} or blank
     * to suppress. Defaults to {@code "nosniff"}.
     */
    @Builder.Default
    private final String contentTypeOptions = "nosniff";

    /**
     * Value for the {@code X-Frame-Options} response header. Set to {@code null} or blank to
     * suppress. Defaults to {@code "DENY"}.
     */
    @Builder.Default
    private final String frameOptions = "DENY";

    /**
     * Value for the {@code Strict-Transport-Security} response header. {@code null} by default
     * (header is not emitted). Example: {@code "max-age=31536000; includeSubDomains"}.
     */
    private final String strictTransportSecurity;

    /**
     * Value for the {@code Referrer-Policy} response header. {@code null} by default
     * (header is not emitted). Example: {@code "no-referrer"}.
     */
    private final String referrerPolicy;

    /**
     * Additional custom headers to apply to every response, captured from unknown JSON keys.
     * Keys are header names; values are header values. Applied after the known headers.
     */
    @Singular("header")
    @JsonAnyGetter
    private final Map<String, String> additionalHeaders;

    // --- Header map building ---

    /**
     * Returns a combined map of all headers to set, merging the known named headers
     * with {@link #additionalHeaders() additionalHeaders}.
     *
     * <p>Known headers with a {@code null} or blank value are omitted. Additional headers
     * are included as-is. If both a known header and an additional header have the same
     * header name, the additional header takes precedence (overwrites the known one).
     *
     * @return unmodifiable map of header name to header value, never {@code null}
     */
    public Map<String, String> toHeaderMap() {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (cacheControl != null && !cacheControl.isBlank()) {
            map.put("Cache-Control", cacheControl);
        }
        if (contentTypeOptions != null && !contentTypeOptions.isBlank()) {
            map.put("X-Content-Type-Options", contentTypeOptions);
        }
        if (frameOptions != null && !frameOptions.isBlank()) {
            map.put("X-Frame-Options", frameOptions);
        }
        if (strictTransportSecurity != null && !strictTransportSecurity.isBlank()) {
            map.put("Strict-Transport-Security", strictTransportSecurity);
        }
        if (referrerPolicy != null && !referrerPolicy.isBlank()) {
            map.put("Referrer-Policy", referrerPolicy);
        }
        if (additionalHeaders != null) {
            map.putAll(additionalHeaders);
        }
        return Collections.unmodifiableMap(map);
    }
}
