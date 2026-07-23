// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable per-request builder that accumulates path parameters, query parameters, headers,
 * cookies, and a body for a single REST client method invocation.
 *
 * <p>Instances are constructed by {@link RestClientDispatcher#newRequest(dev.vertique.rest.client.meta.ClientMethodMeta)}
 * and consumed by {@link RestClientDispatcher#send(RestRequestBuilder, dev.vertique.rest.client.meta.ClientMethodMeta)}.
 * The builder is used by both {@link RestClientProxy} (reflective JDK proxy path) and by
 * generated static proxies (Pass B).
 *
 * <p>Fluent setters return {@code this} for method chaining. The builder is NOT thread-safe;
 * it is always used within a single method invocation on the Vert.x event loop.
 */
public final class RestRequestBuilder {

    // --- Accumulated state ---

    private final List<Map.Entry<String, Object>> pathParams = new ArrayList<>();
    private final List<Map.Entry<String, Object>> queryParams = new ArrayList<>();
    private final List<Map.Entry<String, String>> headers = new ArrayList<>();
    private final List<Map.Entry<String, String>> cookies = new ArrayList<>();

    @Nullable
    private Buffer body;

    /**
     * Raw (un-serialized) body object set by generated static proxies. Mutually exclusive with
     * {@link #body} — when this field is non-null the dispatcher serializes it with its
     * configured {@link com.fasterxml.jackson.databind.ObjectMapper} and uses the result as the
     * request body. When {@link #body} is set directly (JDK proxy path), this field remains
     * {@code null}.
     */
    @Nullable
    private Object bodyObject;

    /**
     * Pre-resolved absolute URI used for {@code @Url} methods. When non-null, this overrides
     * the base URL + path template assembly performed by the dispatcher. Query params from
     * {@link #queryParams} are still merged with the existing query string in this URI.
     */
    @Nullable
    private String absoluteUri;

    /**
     * Creates a new empty request builder.
     */
    public RestRequestBuilder() {}

    // --- Fluent setters ---

    /**
     * Adds a path parameter. The {@code name} must match a {@code {name}} placeholder in the
     * method's path template. Multiple calls with the same name overwrite the last value.
     *
     * @param name the path parameter name (without braces)
     * @param value the parameter value; must not be {@code null}
     * @return this builder
     */
    public RestRequestBuilder path(String name, Object value) {
        pathParams.add(Map.entry(name, value));
        return this;
    }

    /**
     * Adds a query parameter. If the value is {@code null} it is silently ignored, matching
     * the behaviour of the reflective path in {@link RestClientRequestFactory#collectParamsWithMeta}.
     *
     * @param name the query parameter name
     * @param value the parameter value; {@code null} values are ignored
     * @return this builder
     */
    public RestRequestBuilder query(String name, @Nullable Object value) {
        if (value != null) {
            queryParams.add(Map.entry(name, value));
        }
        return this;
    }

    /**
     * Adds a request header. Multiple calls with the same name overwrite the last value.
     *
     * @param name the header name
     * @param value the header value; must not be {@code null}
     * @return this builder
     */
    public RestRequestBuilder header(String name, String value) {
        headers.add(Map.entry(name, value));
        return this;
    }

    /**
     * Adds a cookie parameter. Cookies are assembled into a single {@code Cookie} header by
     * the dispatcher.
     *
     * @param name the cookie name
     * @param value the cookie value; must not be {@code null}
     * @return this builder
     */
    public RestRequestBuilder cookie(String name, String value) {
        cookies.add(Map.entry(name, value));
        return this;
    }

    /**
     * Sets the pre-serialized request body buffer. A {@code null} value clears any previously
     * set body.
     *
     * <p>Mutually exclusive with {@link #bodyObject(Object)}: calling this method when a raw
     * body object has already been set (or vice versa) throws {@link IllegalStateException}. Set
     * the conflicting field to {@code null} first if you need to switch modes.
     *
     * @param body the serialized body buffer, or {@code null} for no body
     * @return this builder
     * @throws IllegalStateException if {@link #bodyObject(Object)} was already called with a
     *     non-null value
     */
    public RestRequestBuilder body(@Nullable Buffer body) {
        if (body != null && bodyObject != null) {
            throw new IllegalStateException(
                    "Cannot set body(Buffer) when bodyObject is already set; clear bodyObject first");
        }
        this.body = body;
        return this;
    }

    /**
     * Sets a raw (un-serialized) body object. Used by generated static proxies to hand off
     * the body to the dispatcher for serialization with the client's configured
     * {@link com.fasterxml.jackson.databind.ObjectMapper}.
     *
     * <p>Application code and the JDK proxy path should prefer {@link #body(Buffer)} with a
     * pre-serialized buffer; this method exists specifically for the generated-proxy path where
     * the raw object is passed through to the dispatcher for serialization.
     *
     * <p>Mutually exclusive with {@link #body(Buffer)}: calling this method when a serialized
     * buffer has already been set throws {@link IllegalStateException}.
     *
     * @param bodyObject the raw body object to serialize; {@code null} clears any previously set body object
     * @return this builder
     * @throws IllegalStateException if {@link #body(Buffer)} was already called with a non-null
     *     value
     */
    public RestRequestBuilder bodyObject(@Nullable Object bodyObject) {
        if (bodyObject != null && body != null) {
            throw new IllegalStateException("Cannot set bodyObject when body(Buffer) is already set; clear body first");
        }
        this.bodyObject = bodyObject;
        return this;
    }

    /**
     * Sets a pre-resolved absolute URI to use instead of base URL + path template assembly.
     * Used by {@link RestClientProxy} and by static generated proxies for {@code @Url}-annotated
     * method parameters. Query params in {@link #queryParams()} are still merged with this URI's
     * existing query.
     *
     * <p>Public so generated proxies emitted in the bean's package can call this from foreign
     * packages. Null-safe — when the caller's argument is {@code null} the URI is cleared and the
     * dispatcher falls back to base URL + path template assembly.
     *
     * @param uri the pre-resolved absolute URI string; may be {@code null}
     * @return this builder
     */
    public RestRequestBuilder absoluteUri(String uri) {
        this.absoluteUri = uri;
        return this;
    }

    // --- Accessors used by DefaultRestClientDispatcher ---

    /**
     * Returns the accumulated path parameters in addition order.
     *
     * @return an unmodifiable view of the path parameters; may be empty
     */
    List<Map.Entry<String, Object>> pathParams() {
        return pathParams;
    }

    /**
     * Returns the accumulated query parameters in addition order.
     *
     * @return an unmodifiable view of the query parameters; may be empty
     */
    List<Map.Entry<String, Object>> queryParams() {
        return queryParams;
    }

    /**
     * Returns the accumulated explicit headers in addition order. These are in addition to
     * the default headers managed by the dispatcher.
     *
     * @return an unmodifiable view of the explicit headers; may be empty
     */
    List<Map.Entry<String, String>> headers() {
        return headers;
    }

    /**
     * Returns the accumulated cookie parameters in addition order.
     *
     * @return an unmodifiable view of the cookie parameters; may be empty
     */
    List<Map.Entry<String, String>> cookies() {
        return cookies;
    }

    /**
     * Returns the serialized body buffer, or {@code null} if no body was set.
     *
     * @return the body buffer, or {@code null}
     */
    @Nullable
    Buffer body() {
        return body;
    }

    /**
     * Returns the raw (un-serialized) body object set via {@link #bodyObject(Object)}, or
     * {@code null} if the body was set directly as a {@link Buffer} or not set at all.
     *
     * <p>The dispatcher serializes this object with its configured ObjectMapper when non-null.
     *
     * @return the raw body object, or {@code null}
     */
    @Nullable
    public Object bodyObject() {
        return bodyObject;
    }

    /**
     * Returns the pre-resolved absolute URI, or {@code null} when the dispatcher should
     * assemble the URI from base URL + path template + path params.
     *
     * @return the absolute URI string, or {@code null}
     */
    @Nullable
    String absoluteUri() {
        return absoluteUri;
    }
}
