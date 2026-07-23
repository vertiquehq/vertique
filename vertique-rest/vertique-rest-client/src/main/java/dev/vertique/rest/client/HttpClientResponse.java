// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.client.exception.RestClientException;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.client.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Immutable wrapper around a Vert.x {@link HttpResponse} that provides typed body deserialization.
 *
 * <p>Use this as the return type generic ({@code Future<HttpClientResponse>}) when you need raw
 * access to status, headers, and body bytes rather than automatic JSON deserialization.
 *
 * <pre>{@code
 * @GET
 * @Path("/{id}")
 * Future<HttpClientResponse> getRaw(@PathParam("id") String id);
 * }</pre>
 */
public final class HttpClientResponse {

    private final int statusCode;
    private final String statusMessage;
    private final MultiMap headers;
    private final Buffer body;

    /**
     * Creates a new wrapper by copying values from the Vert.x {@link HttpResponse}.
     *
     * @param response the Vert.x response to wrap; must not be {@code null}
     */
    public HttpClientResponse(HttpResponse<Buffer> response) {
        this.statusCode = response.statusCode();
        this.statusMessage = response.statusMessage();
        this.headers = response.headers();
        this.body = response.body() != null ? response.body() : Buffer.buffer();
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code (e.g. 200, 404)
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Returns the HTTP status message.
     *
     * @return the status message (e.g. "OK", "Not Found")
     */
    public String statusMessage() {
        return statusMessage;
    }

    /**
     * Returns the response headers.
     *
     * @return the response headers, never {@code null}
     */
    public MultiMap headers() {
        return headers;
    }

    /**
     * Returns the raw response body buffer.
     *
     * @return the response body, never {@code null}
     */
    public Buffer body() {
        return body;
    }

    /**
     * Deserializes the response body as the given class using the provided {@link ObjectMapper}.
     *
     * @param <T> the target type
     * @param type the target class
     * @param mapper the Jackson ObjectMapper to use for deserialization
     * @return the deserialized body
     * @throws RestClientException if deserialization fails
     */
    public <T> T bodyAs(Class<T> type, ObjectMapper mapper) {
        try {
            return mapper.readValue(body.getBytes(), type);
        } catch (IOException e) {
            throw new RestClientException("Failed to deserialize response body as " + type.getSimpleName(), e);
        }
    }

    /**
     * Deserializes the response body using the provided {@link TypeReference}, supporting generic
     * types such as {@code List<MyPojo>}.
     *
     * @param <T> the target type
     * @param typeRef the Jackson type reference for generic deserialization
     * @param mapper the Jackson ObjectMapper to use for deserialization
     * @return the deserialized body
     * @throws RestClientException if deserialization fails
     */
    public <T> T bodyAs(TypeReference<T> typeRef, ObjectMapper mapper) {
        try {
            return mapper.readValue(body.getBytes(), typeRef);
        } catch (IOException e) {
            throw new RestClientException("Failed to deserialize response body", e);
        }
    }

    /**
     * Returns the response body decoded as a UTF-8 string.
     *
     * @return the body as a string
     */
    public String bodyAsString() {
        return body.toString(StandardCharsets.UTF_8);
    }
}
