// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;

/**
 * Immutable context describing an HTTP response received by the REST client.
 *
 * <p>Passed to {@link RestClientInterceptor#afterResponse} and
 * {@link RestClientInterceptor#onResponse} for each received HTTP response. Interceptors may
 * inspect the response status, headers, and body for logging, metrics, or error enrichment.
 *
 * <p>Use {@link #from(HttpResponse)} to construct from a Vert.x HTTP response, or the 4-argument
 * canonical constructor to build a context directly (e.g. in tests).
 *
 * @param statusCode    the HTTP status code (e.g. 200, 404)
 * @param statusMessage the HTTP status message (e.g. "OK", "Not Found")
 * @param body          the response body buffer; never {@code null} (empty buffer if no body)
 * @param headers       the response headers
 */
public record RestClientResponseContext(int statusCode, String statusMessage, Buffer body, MultiMap headers) {

    /**
     * Compact constructor that normalises a {@code null} body to an empty buffer, ensuring
     * {@link #body()} never returns {@code null}.
     *
     * @param statusCode    the HTTP status code
     * @param statusMessage the HTTP status message
     * @param body          the response body; may be {@code null}
     * @param headers       the response headers
     */
    public RestClientResponseContext {
        if (body == null) {
            body = Buffer.buffer();
        }
    }

    /**
     * Creates a response context from a Vert.x HTTP response.
     *
     * @param response the Vert.x response to wrap; must not be {@code null}
     * @return a new {@link RestClientResponseContext} populated from the response
     */
    public static RestClientResponseContext from(HttpResponse<Buffer> response) {
        return new RestClientResponseContext(
                response.statusCode(), response.statusMessage(), response.body(), response.headers());
    }
}
