// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable context describing an outgoing HTTP request in the {@link RestClientInterceptor}
 * pipeline.
 *
 * <p>Instances are immutable; interceptors produce modified copies via the copy-on-write
 * {@code with*} methods rather than mutating state in place. The {@link #clientName()} and
 * {@link #methodName()} fields identify the originating client and method, enabling interceptors to
 * apply client- or method-specific logic without coupling to internal metadata types.
 *
 * <p>The {@link #attributes} map is a caller-controlled bag; applications and user-defined
 * interceptors use {@link #withAttribute}/{@link #withAttributes} to pass data through the chain.
 *
 * <p>Example usage in a {@code beforeRequest} interceptor:
 *
 * <pre>{@code
 * public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
 *     return Future.succeededFuture(ctx.withHeader("Authorization", "Bearer " + token));
 * }
 * }</pre>
 *
 * @param httpMethod the HTTP verb (e.g. {@code "GET"})
 * @param requestUri the fully qualified request URI including query parameters
 * @param headers    the request headers; defensively copied on construction
 * @param body       the request body buffer, or {@code null} for bodyless requests
 * @param clientName the logical name of the REST client (from {@code @RestClient(name=...)} or
 *                   the interface simple name)
 * @param methodName the Java method name that triggered this request
 * @param attributes per-request attribute bag for passing data between interceptors; defensively
 *                   copied on construction
 */
public record RestClientRequestContext(
        String httpMethod,
        String requestUri,
        MultiMap headers,
        @Nullable Buffer body,
        String clientName,
        String methodName,
        Map<String, Object> attributes) {

    /**
     * Compact constructor that defensively copies headers and attributes to enforce immutability.
     * Callers that hold a reference to the original {@link MultiMap} or {@link Map} cannot mutate
     * the record's internals after construction.
     *
     * @param httpMethod the HTTP verb
     * @param requestUri the fully qualified URI
     * @param headers    the request headers; copied into a new case-insensitive MultiMap
     * @param body       the body buffer, or {@code null}
     * @param clientName the client name
     * @param methodName the method name
     * @param attributes the attribute map; copied into an unmodifiable {@link Map}
     */
    public RestClientRequestContext {
        headers = MultiMap.caseInsensitiveMultiMap().addAll(headers);
        attributes = Map.copyOf(attributes);
    }

    /**
     * Convenience constructor for creating a context without pre-populated headers or attributes.
     * Empty maps and headers are initialised automatically.
     *
     * @param httpMethod the HTTP verb (e.g. {@code "GET"})
     * @param requestUri the fully qualified request URI including query parameters
     * @param body       the request body buffer, or {@code null} for bodyless requests
     * @param clientName the logical client name
     * @param methodName the Java method name that triggered this request
     */
    public RestClientRequestContext(
            String httpMethod, String requestUri, @Nullable Buffer body, String clientName, String methodName) {
        this(httpMethod, requestUri, MultiMap.caseInsensitiveMultiMap(), body, clientName, methodName, Map.of());
    }

    // --- Copy-on-write mutators ---

    /**
     * Returns a copy of this context with the request URI replaced.
     *
     * @param uri the new request URI
     * @return a new context with the updated URI
     */
    public RestClientRequestContext withRequestUri(String uri) {
        return new RestClientRequestContext(httpMethod, uri, headers, body, clientName, methodName, attributes);
    }

    /**
     * Returns a copy of this context with the body replaced.
     *
     * @param newBody the new request body, or {@code null} to remove the body
     * @return a new context with the updated body
     */
    public RestClientRequestContext withBody(@Nullable Buffer newBody) {
        return new RestClientRequestContext(
                httpMethod, requestUri, headers, newBody, clientName, methodName, attributes);
    }

    /**
     * Returns a copy of this context with a single header added or replaced.
     *
     * @param key   the header name
     * @param value the header value
     * @return a new context with the updated header
     */
    public RestClientRequestContext withHeader(String key, String value) {
        MultiMap copy = MultiMap.caseInsensitiveMultiMap().addAll(headers);
        copy.set(key, value);
        return new RestClientRequestContext(httpMethod, requestUri, copy, body, clientName, methodName, attributes);
    }

    /**
     * Returns a copy of this context with all headers replaced by the provided map.
     *
     * @param newHeaders the new request headers; defensively copied
     * @return a new context with the updated headers
     */
    public RestClientRequestContext withHeaders(MultiMap newHeaders) {
        return new RestClientRequestContext(
                httpMethod, requestUri, newHeaders, body, clientName, methodName, attributes);
    }

    /**
     * Returns a copy of this context with an attribute added or replaced.
     *
     * @param key   the attribute key
     * @param value the attribute value
     * @return a new context with the updated attribute
     */
    public RestClientRequestContext withAttribute(String key, Object value) {
        Map<String, Object> copy = new HashMap<>(attributes);
        copy.put(key, value);
        return new RestClientRequestContext(httpMethod, requestUri, headers, body, clientName, methodName, copy);
    }

    /**
     * Returns a copy of this context with all attributes replaced by the provided map.
     *
     * @param newAttributes the new attribute map; defensively copied
     * @return a new context with the updated attributes
     */
    public RestClientRequestContext withAttributes(Map<String, Object> newAttributes) {
        return new RestClientRequestContext(
                httpMethod, requestUri, headers, body, clientName, methodName, newAttributes);
    }

    // --- Attribute lookup ---

    /**
     * Returns the value associated with the given attribute key, or {@code null} if absent.
     *
     * @param key the attribute key
     * @return the stored value, or {@code null}
     */
    @Nullable
    public Object attribute(String key) {
        return attributes.get(key);
    }
}
