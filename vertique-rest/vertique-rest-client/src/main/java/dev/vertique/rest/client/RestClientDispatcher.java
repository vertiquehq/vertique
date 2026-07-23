// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.meta.ClientMethodMeta;
import io.vertx.core.Future;
import jakarta.annotation.Nullable;
import java.net.URI;

/**
 * Fluent facade that runs the full HTTP dispatch pipeline for a single REST client method
 * invocation.
 *
 * <p>The dispatcher abstracts the pipeline that was previously embedded in
 * {@link RestClientProxy}. Both the JDK proxy ({@link RestClientProxy}) and generated static
 * proxies (Pass B) delegate to the same dispatcher implementation so that the pipeline logic
 * is shared and not duplicated.
 *
 * <p>Usage pattern (reflective proxy):
 * <pre>{@code
 * RestRequestBuilder req = dispatcher.newRequest(meta)
 *     .path("id", idValue)
 *     .query("page", pageValue)
 *     .header("X-Token", token);
 * return dispatcher.send(req, meta);
 * }</pre>
 *
 * <p>Usage pattern (generated proxy, Pass B):
 * <pre>{@code
 * RestRequestBuilder req = dispatcher.newRequest(metaGetUser)
 *     .path("id", id)
 *     .query("page", pageAccessor.extract(paging, "page"))
 *     .query("size", pageAccessor.extract(paging, "size"));
 * return dispatcher.send(req, metaGetUser);
 * }</pre>
 *
 * <p>The dispatcher uses {@link ClientMethodMeta#responseType()} (a full
 * {@link java.lang.reflect.Type}) to drive deserialization, including {@code Optional},
 * {@code List}, parameterised generics, and primitive/void cases — the same as the prior
 * {@link RestClientProxy} path.
 *
 * <p>The only implementation is {@link DefaultRestClientDispatcher}. This interface is not sealed
 * so that tests can supply stub implementations if needed.
 */
public interface RestClientDispatcher {

    /**
     * Creates a new {@link RestRequestBuilder} for the given method invocation. The builder
     * is pre-seeded with base URL, default headers, and Accept/Content-Type from the method
     * metadata. Callers populate path params, query params, explicit headers, cookies, and the
     * body by chaining fluent setter calls.
     *
     * @param meta the method metadata for the invocation
     * @return a new mutable request builder; never {@code null}
     */
    RestRequestBuilder newRequest(ClientMethodMeta meta);

    /**
     * Sends the request described by the builder and returns a deserialized response.
     *
     * <p>The full 14-step pipeline is executed:
     * <ol>
     *   <li>Build final URI from base URL, path params, and query string.</li>
     *   <li>Assemble headers (default + param headers + cookie header).</li>
     *   <li>Create {@link dev.vertique.rest.client.interceptor.RestClientRequestContext}.</li>
     *   <li>Fire sync {@code onRequest} observers.</li>
     *   <li>Run async {@code beforeRequest} interceptors (may mutate headers/URI).</li>
     *   <li>Build Vert.x {@link io.vertx.ext.web.client.HttpRequest} from final URI.</li>
     *   <li>Apply per-method timeout.</li>
     *   <li>Send inside circuit breaker if configured.</li>
     *   <li>Apply {@link io.vertx.core.Expectation}.</li>
     *   <li>Capture response context; fire sync {@code onResponse} observers.</li>
     *   <li>Run async {@code afterResponse} interceptors.</li>
     *   <li>Handle {@code Optional<T>}: 404 → {@code Optional.empty()}.</li>
     *   <li>Deserialize body using {@link ClientMethodMeta#responseType()}.</li>
     *   <li>Validate with bean validator if configured.</li>
     * </ol>
     *
     * <p>On failure, the error pipeline runs: sync {@code onError} observers, async
     * {@code transformError} interceptors, then
     * {@link RestClientExceptionMapper#translate(Throwable)}.
     *
     * <p>The returned {@code Future} carries the deserialized value cast to {@code T}. The
     * caller (JDK proxy or generated proxy) already knows the concrete type via its method
     * signature, so the cast is safe.
     *
     * @param <T> the expected return type (unwrapped from {@code Future<T>})
     * @param request the assembled request builder
     * @param meta the method metadata driving deserialization and resilience
     * @return a {@link Future} that completes with the deserialized response value, or fails
     *     with an exception from the error pipeline
     */
    <T> Future<T> send(RestRequestBuilder request, ClientMethodMeta meta);

    /**
     * Serializes and applies a path parameter to the request builder using the effective
     * {@link dev.vertique.rest.core.convert.ParamConversionResolver}.
     *
     * <p>If {@code value} is {@code null} and {@code defaultValue} is non-null, the raw default
     * string is applied. If both are {@code null}, the builder is returned unchanged (caller is
     * responsible for null-guarding path params before calling).
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to locate the {@link dev.vertique.rest.client.meta.ClientParamMeta}
     *     for conversion-context construction
     * @param paramName the JAX-RS wire name of the path parameter
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback string, or {@code null}
     * @return the updated request builder
     */
    RestRequestBuilder applyPathParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue);

    /**
     * Serializes and applies a query parameter to the request builder using the effective
     * {@link dev.vertique.rest.core.convert.ParamConversionResolver}.
     *
     * <p>Collection-valued params (when the {@link dev.vertique.rest.client.meta.ClientParamMeta#componentType()}
     * is non-null and {@code value} is {@link Iterable}) are expanded element-by-element, matching
     * the JDK-proxy path's multi-value query-string behaviour.
     *
     * <p>If {@code value} is {@code null} and {@code defaultValue} is non-null, the raw default
     * string is applied as a single scalar. If both are {@code null}, the param is omitted.
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to locate the {@link dev.vertique.rest.client.meta.ClientParamMeta}
     * @param paramName the JAX-RS wire name of the query parameter
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback string, or {@code null}
     * @return the updated request builder
     */
    RestRequestBuilder applyQueryParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue);

    /**
     * Serializes and applies a header parameter to the request builder using the effective
     * {@link dev.vertique.rest.core.convert.ParamConversionResolver}.
     *
     * <p>If {@code value} is {@code null} and {@code defaultValue} is non-null, the raw default
     * string is applied. If both are {@code null}, the header is omitted.
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to locate the {@link dev.vertique.rest.client.meta.ClientParamMeta}
     * @param paramName the HTTP header name
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback string, or {@code null}
     * @return the updated request builder
     */
    RestRequestBuilder applyHeaderParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue);

    /**
     * Serializes and applies a cookie parameter to the request builder using the effective
     * {@link dev.vertique.rest.core.convert.ParamConversionResolver}.
     *
     * <p>If {@code value} is {@code null} and {@code defaultValue} is non-null, the raw default
     * string is applied. If both are {@code null}, the cookie is omitted.
     *
     * @param req the request builder to populate
     * @param meta the method metadata used to locate the {@link dev.vertique.rest.client.meta.ClientParamMeta}
     * @param paramName the cookie name
     * @param value the raw argument value; may be {@code null}
     * @param defaultValue the {@code @DefaultValue} fallback string, or {@code null}
     * @return the updated request builder
     */
    RestRequestBuilder applyCookieParam(
            RestRequestBuilder req,
            ClientMethodMeta meta,
            String paramName,
            @Nullable Object value,
            @Nullable String defaultValue);

    /**
     * Serializes and applies the request body to the request builder, respecting the method's
     * {@link ClientMethodMeta#consumesMediaType()} exactly as the JDK reflective proxy path does
     * (via {@code RestClientRequestFactory.buildBody}): {@code text/plain} serializes the raw
     * UTF-8 bytes of {@code value.toString()}; {@code application/octet-stream} passes a
     * {@code byte[]} or {@link io.vertx.core.buffer.Buffer} value through unchanged; every other
     * media type JSON-serializes the value.
     *
     * @param req the request builder to populate
     * @param meta the method metadata carrying the {@code @Consumes} media type
     * @param value the raw body value; {@code null} means no body is applied
     * @return the updated request builder
     */
    RestRequestBuilder applyBody(RestRequestBuilder req, ClientMethodMeta meta, @Nullable Object value);

    /**
     * Validates and applies a {@code @Url} parameter to the request builder as the pre-resolved
     * absolute URI, using the same validation rules as the JDK reflective proxy path (via
     * {@code RestClientRequestFactory.extractUrlParam}): the URI must be absolute, have an
     * authority and a non-blank host, use the {@code http} or {@code https} scheme (case
     * insensitive), and must not contain a fragment.
     *
     * <p>A {@code null} value is passed through unchanged to {@link RestRequestBuilder#absoluteUri}
     * rather than throwing here — the dispatcher's URI-assembly step is responsible for raising
     * {@link dev.vertique.rest.client.exception.RestClientException} with a clear message when the
     * {@code @Url} argument was {@code null}, matching the generated proxy's existing
     * null-deferral contract.
     *
     * @param req the request builder to populate
     * @param meta the method metadata for the invocation
     * @param value the raw {@code @Url} argument value; may be {@code null}
     * @return the updated request builder
     */
    RestRequestBuilder applyUrlParam(RestRequestBuilder req, ClientMethodMeta meta, @Nullable URI value);
}
