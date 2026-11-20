// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.interceptor;

import io.vertx.core.MultiMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The request an {@link HttpClientInterceptor} prepares and the snapshot it is shown on completion.
 *
 * <p>Instances are immutable: the headers and attributes are copied on construction and the
 * {@code with...} methods return modified copies. The context {@code beforeRequest} receives never holds
 * a credential, because credentials are applied after interceptors run; the snapshot {@code onAttemptCompleted}
 * receives holds the credential headers set on a hop to the original origin, unredacted. {@link #toString()} prints the method, client name
 * and call id only, never a header value, a path, a query or an attribute.
 *
 * @param method the HTTP method, for example {@code "GET"}
 * @param uri the request URI, absolute when the client knows the target, including the query
 * @param headers the request headers; copied on construction
 * @param clientName the name of the client, set by the framework
 * @param callId the id of the call, set by the framework
 * @param attributes a bag for passing data between interceptors; copied on construction
 */
public record HttpClientRequestContext(
        String method, String uri, MultiMap headers, String clientName, String callId, Map<String, Object> attributes) {

    /**
     * Validates and copies the components.
     *
     * @throws NullPointerException if a component other than {@code attributes} is null
     */
    public HttpClientRequestContext {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(callId, "callId");
        headers = MultiMap.caseInsensitiveMultiMap().addAll(headers);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * Returns the headers.
     *
     * @return a copy; changing it changes neither this record nor the request
     */
    @Override
    public MultiMap headers() {
        return MultiMap.caseInsensitiveMultiMap().addAll(headers);
    }

    /**
     * Returns a copy with one header set, replacing any value of that name.
     *
     * @param name the header name
     * @param value the header value
     * @return the changed copy
     */
    public HttpClientRequestContext withHeader(String name, String value) {
        MultiMap copy = MultiMap.caseInsensitiveMultiMap().addAll(headers);
        copy.set(name, value);
        return new HttpClientRequestContext(method, uri, copy, clientName, callId, attributes);
    }

    /**
     * Returns a copy with all headers replaced.
     *
     * @param newHeaders the headers of the copy; copied
     * @return the changed copy
     */
    public HttpClientRequestContext withHeaders(MultiMap newHeaders) {
        return new HttpClientRequestContext(method, uri, newHeaders, clientName, callId, attributes);
    }

    /**
     * Returns a copy with one attribute set.
     *
     * @param key the attribute key
     * @param value the attribute value
     * @return the changed copy
     */
    public HttpClientRequestContext withAttribute(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(attributes);
        copy.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return new HttpClientRequestContext(method, uri, headers, clientName, callId, copy);
    }

    @Override
    public String toString() {
        return "HttpClientRequestContext[method=" + method + ", clientName=" + clientName + ", callId=" + callId + "]";
    }
}
