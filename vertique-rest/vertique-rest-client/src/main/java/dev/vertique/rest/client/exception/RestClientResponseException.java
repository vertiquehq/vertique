// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.io.IOException;
import java.util.Optional;

/**
 * Exception thrown when a REST client receives an HTTP error response (4xx or 5xx) or when a
 * configured {@link dev.vertique.rest.client.ExpectedStatus} expectation is not met.
 *
 * <p>Carries the full request and response contexts so that interceptors, failure translators, and
 * callers can inspect or deserialize the error body. The request context provides method name and
 * client name for structured logging.
 *
 * <p>Convenience delegation methods ({@link #statusCode()}, {@link #statusMessage()},
 * {@link #responseBody()}, {@link #headers()}) are provided for backward compatibility and ergonomic
 * access without requiring callers to reach into the response context directly.
 */
public class RestClientResponseException extends RestClientException {

    private final RestClientRequestContext requestContext;
    private final RestClientResponseContext responseContext;

    /**
     * Creates a new response exception wrapping the given request and response contexts.
     *
     * @param requestContext the request context describing the failed request
     * @param responseContext the response context containing the HTTP status, headers, and body
     */
    public RestClientResponseException(
            RestClientRequestContext requestContext, RestClientResponseContext responseContext) {
        super(String.format("HTTP %d %s", responseContext.statusCode(), responseContext.statusMessage()));
        this.requestContext = requestContext;
        this.responseContext = responseContext;
    }

    /**
     * Creates a new response exception with individual response fields (backward-compatible
     * constructor for callers that do not have full context objects).
     *
     * @param statusCode the HTTP status code (e.g. 404, 500)
     * @param statusMessage the HTTP status message (e.g. "Not Found")
     * @param responseBody the raw response body buffer; may be empty but not {@code null}
     * @param headers the response headers
     * @deprecated Prefer {@link #RestClientResponseException(RestClientRequestContext,
     *     RestClientResponseContext)}; this constructor creates placeholder contexts with no method
     *     or client name.
     */
    @Deprecated
    public RestClientResponseException(int statusCode, String statusMessage, Buffer responseBody, MultiMap headers) {
        super(String.format("HTTP %d %s", statusCode, statusMessage));
        this.requestContext = null;
        this.responseContext = new RestClientResponseContext(statusCode, statusMessage, responseBody, headers);
    }

    // --- Request context accessors ---

    /**
     * Returns the request context that describes the failed HTTP request.
     *
     * @return the request context, or {@code null} when constructed via the deprecated constructor
     */
    public RestClientRequestContext requestContext() {
        return requestContext;
    }

    /**
     * Returns the response context wrapping the received HTTP response.
     *
     * @return the response context, never {@code null}
     */
    public RestClientResponseContext responseContext() {
        return responseContext;
    }

    // --- Delegate accessors (backward compatibility) ---

    /**
     * Returns the HTTP status code.
     *
     * @return the status code (e.g. 404, 500)
     */
    public int statusCode() {
        return responseContext.statusCode();
    }

    /**
     * Returns the HTTP status message.
     *
     * @return the status message (e.g. "Not Found")
     */
    public String statusMessage() {
        return responseContext.statusMessage();
    }

    /**
     * Returns the raw response body buffer.
     *
     * @return the response body, never {@code null}
     */
    public Buffer responseBody() {
        return responseContext.body();
    }

    /**
     * Returns the response headers.
     *
     * @return the response headers
     */
    public MultiMap headers() {
        return responseContext.headers();
    }

    // --- Status checks ---

    /**
     * Returns {@code true} if the status code is in the 4xx client error range.
     *
     * @return {@code true} for 400–499
     */
    public boolean isClientError() {
        return responseContext.statusCode() >= 400 && responseContext.statusCode() < 500;
    }

    /**
     * Returns {@code true} if the status code is in the 5xx server error range.
     *
     * @return {@code true} for 500–599
     */
    public boolean isServerError() {
        return responseContext.statusCode() >= 500 && responseContext.statusCode() < 600;
    }

    // --- Body deserialization ---

    /**
     * Deserializes the response body as the given type using the provided {@link ObjectMapper}.
     *
     * @param <T> the target type
     * @param type the target class
     * @param mapper the Jackson ObjectMapper to use for deserialization
     * @return the deserialized body
     * @throws RestClientException if deserialization fails
     */
    public <T> T bodyAs(Class<T> type, ObjectMapper mapper) {
        try {
            return mapper.readValue(responseContext.body().getBytes(), type);
        } catch (IOException e) {
            throw new RestClientException("Failed to deserialize error response body as " + type.getSimpleName(), e);
        }
    }

    /**
     * Attempts to deserialize the response body as the given type if the {@code Content-Type}
     * header is {@code application/problem+json}. Returns {@link Optional#empty()} if the
     * content type does not match or deserialization fails.
     *
     * <p>This is particularly useful for consuming RFC 9457 Problem Details error responses:
     * <pre>{@code
     * ex.problemDetail(ProblemDetail.class, objectMapper)
     *     .ifPresent(pd -> log.warn("Remote error: {} — {}", pd.title(), pd.detail()));
     * }</pre>
     *
     * @param <T> the target problem detail type
     * @param type the target class (e.g. {@code ProblemDetail.class} or a custom subclass)
     * @param mapper the Jackson ObjectMapper to use for deserialization
     * @return an {@link Optional} containing the deserialized body, or empty if not applicable
     */
    public <T> Optional<T> problemDetail(Class<T> type, ObjectMapper mapper) {
        String contentType = responseContext.headers().get("Content-Type");
        if (contentType == null || !contentType.contains("application/problem+json")) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(responseContext.body().getBytes(), type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
