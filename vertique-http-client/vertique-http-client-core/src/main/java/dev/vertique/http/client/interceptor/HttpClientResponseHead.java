// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import io.vertx.core.MultiMap;
import java.util.Map;
import java.util.Objects;

/**
 * The head of a response, built by the client for {@link HttpClientInterceptor#onAttemptCompleted}.
 * The headers are a copy without {@code Set-Cookie}. {@link #toString()} prints the status code only.
 *
 * @param statusCode the status code
 * @param statusMessage the status message, empty when the response had none
 * @param headers the response headers without {@code Set-Cookie}; copied on construction
 */
public record HttpClientResponseHead(int statusCode, String statusMessage, MultiMap headers) {

    private static final String SET_COOKIE = "Set-Cookie";

    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if {@code headers} is null
     */
    public HttpClientResponseHead {
        Objects.requireNonNull(headers, "headers");
        statusMessage = statusMessage == null ? "" : statusMessage;
        MultiMap copy = MultiMap.caseInsensitiveMultiMap();
        for (Map.Entry<String, String> header : headers) {
            if (!SET_COOKIE.equalsIgnoreCase(header.getKey())) {
                copy.add(header.getKey(), header.getValue());
            }
        }
        headers = copy;
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
