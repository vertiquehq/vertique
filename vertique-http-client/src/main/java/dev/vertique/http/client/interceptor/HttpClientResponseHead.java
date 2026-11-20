// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import io.vertx.core.MultiMap;
import java.util.Objects;

/**
 * The head of a response, built by the client for {@link HttpClientInterceptor#onAttemptCompleted}.
 * The headers are a copy of every response header, {@code Set-Cookie} included, unredacted; redacting what an
 * interceptor stores or logs is its own job. {@link #toString()} prints the status code only.
 *
 * @param statusCode the status code
 * @param statusMessage the status message, empty when the response had none
 * @param headers the response headers; copied on construction
 */
public record HttpClientResponseHead(int statusCode, String statusMessage, MultiMap headers) {

    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if {@code headers} is null
     */
    public HttpClientResponseHead {
        Objects.requireNonNull(headers, "headers");
        statusMessage = statusMessage == null ? "" : statusMessage;
        headers = MultiMap.caseInsensitiveMultiMap().addAll(headers);
    }

    /**
     * Returns the headers.
     *
     * @return a copy; changing it does not change this record
     */
    @Override
    public MultiMap headers() {
        return MultiMap.caseInsensitiveMultiMap().addAll(headers);
    }

    @Override
    public String toString() {
        return "HttpClientResponseHead[statusCode=" + statusCode + "]";
    }
}
