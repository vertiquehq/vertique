// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.exception;

import dev.vertique.resilience.RetryDelayHint;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thrown when a status check refused a response, or a stream head was refused. The message is constant
 * and never contains a URI path, query, header value or body text. The failure carries the status, the
 * response headers without {@code Set-Cookie}, the parsed {@code Retry-After} and a bounded excerpt of
 * the body, and tells a retry policy how long to wait through {@link RetryDelayHint}.
 */
public final class HttpStatusRejectedException extends HttpClientTechnicalException implements RetryDelayHint {

    /** The most body bytes {@link #bodyExcerpt()} holds. */
    private static final int MAX_EXCERPT_BYTES = 2048;

    private static final String MESSAGE = "The HTTP response status was refused";
    private static final String SET_COOKIE = "Set-Cookie";

    private final int statusCode;
    /** Flat list of header name and value pairs, so the failure stays serializable. */
    private final List<String> headerPairs;

    @Nullable
    private final Duration retryAfter;

    @Nullable
    private final byte[] excerpt;

    /**
     * Creates a status rejection.
     *
     * @param statusCode the refused status code
     * @param headers the response headers; copied, without {@code Set-Cookie}
     * @param retryAfter the parsed {@code Retry-After} delay, or null when absent or unparseable
     * @param bodyExcerpt the response body, or null when it was not buffered; only the first 2048 bytes
     *     are kept
     * @throws NullPointerException if {@code headers} is null
     */
    public HttpStatusRejectedException(
            int statusCode, MultiMap headers, @Nullable Duration retryAfter, @Nullable Buffer bodyExcerpt) {
        super(MESSAGE);
        Objects.requireNonNull(headers, "headers");
        this.statusCode = statusCode;
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers.entries()) {
            if (!SET_COOKIE.equalsIgnoreCase(entry.getKey())) {
                pairs.add(entry.getKey());
                pairs.add(entry.getValue());
            }
        }
        this.headerPairs = List.copyOf(pairs);
        this.retryAfter = retryAfter;
        this.excerpt =
                bodyExcerpt == null ? null : bodyExcerpt.getBytes(0, Math.min(bodyExcerpt.length(), MAX_EXCERPT_BYTES));
    }

    /**
     * Returns the refused status code.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns a fresh, mutable copy of the response headers, without {@code Set-Cookie}; changing it never changes this exception.
     *
     * @return the headers
     */
    public MultiMap headers() {
        MultiMap copy = MultiMap.caseInsensitiveMultiMap();
        for (int i = 0; i < headerPairs.size(); i += 2) {
            copy.add(headerPairs.get(i), headerPairs.get(i + 1));
        }
        return copy;
    }

    /**
     * Returns how long the server asked the caller to wait, from its {@code Retry-After} header.
     *
     * @return the delay, capped at 24 hours, or empty when the header is absent or unparseable
     */
    @Override
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Returns the first bytes of the response body.
     *
     * @return a fresh copy of at most 2048 bytes, so changing it never changes this exception, or empty
     *     when the checked value was not a buffered body
     */
    public Optional<Buffer> bodyExcerpt() {
        if (excerpt == null) {
            return Optional.empty();
        }
        return Optional.of(Buffer.buffer(excerpt.clone()));
    }
}
