// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.request;

import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.http.HttpServerRequest;
import java.util.Map;

/**
 * Typed binding facade over a Vert.x {@code RoutingContext.request()}, exposing the decoded request
 * parameters and body as transport-neutral {@link RequestValue} instances (FR-024).
 *
 * <p>The four parameter maps expose path, query, header, and cookie values. Header and cookie
 * lookups are case-insensitive. Multiplicity is type-driven by the operation's declared parameters:
 * a parameter whose declared type is a collection binds all values as a {@code JsonArray}, while a
 * scalar parameter binds only the first value (and is coerced to its declared scalar type).
 * Undeclared keys bind as their raw first-value string.
 *
 * <p>{@link #body()} never returns {@code null}; when the request has no body it returns a
 * {@link RequestValue} wrapping {@code null}. {@link #raw()} is the escape hatch for anything the
 * typed view omits.
 */
public interface BoundRequest {

    /**
     * Routing-context data key under which {@code ResourceMethodInvoker} stashes the per-request
     * {@link BoundRequest} before dispatch, so the generated-runtime SPI's
     * {@code GeneratedJaxRsSupport.deserializeBody} can read the already-bound body without
     * re-binding it. Replaces the retired Vert.x OpenAPI router validated-request key on the
     * rest-jaxrs request hot path (FR-024).
     */
    String KEY_META_DATA_BOUND_REQUEST = "vertique.rest.jaxrs.boundRequest";

    /**
     * Routing-context data key under which {@code ResourceMethodInvoker} stashes the per-request
     * resolved request-body {@code ObjectMapper} when a non-{@code vertx} JSON profile applies to the
     * dispatched resource method (FR-JSON-020). The key is absent when the effective profile is
     * {@code vertx} (the default), so a reader treats absence as "use the existing default body
     * path". Consumers in the binding/materialization layers read this to (de)serialize the request
     * body with the selected profile mapper.
     */
    String KEY_RESOLVED_BODY_MAPPER = "vertique.rest.jaxrs.resolvedBodyMapper";

    /**
     * Routing-context data key set by a matched operation route's per-route failure handler to mark
     * that the <em>error</em>-body mapper has already been decided for the matched route
     * (FR-JSON-058/058A). The marker is independent of {@link #KEY_RESOLVED_BODY_MAPPER}: a matched
     * route whose effective profile is a non-{@code vertx} profile stashes its mapper under
     * {@code KEY_RESOLVED_BODY_MAPPER} <em>and</em> sets this marker; a matched route whose effective
     * profile is {@code vertx} sets only this marker (leaving {@code KEY_RESOLVED_BODY_MAPPER} absent
     * so the encoder uses {@code Json.encode}).
     *
     * <p>The router-level failure handler reads this marker to distinguish "a matched route already
     * decided the error-body mapper (honor its decision — including an explicit {@code vertx})" from
     * "no operation route matched (apply the boundary+global default)". This closes the error-path
     * profiling asymmetry where an auth/415 rejection or an explicit-{@code vertx} route under a
     * non-{@code vertx} global default would otherwise be serialized with the global default mapper.
     *
     * <p>The value is the constant {@link Boolean#TRUE}; only its presence is significant.
     */
    String KEY_ERROR_BODY_MAPPER_DECIDED = "vertique.rest.jaxrs.errorBodyMapperDecided";

    /**
     * Returns the bound path parameters, keyed by declared parameter name.
     *
     * @return the non-null, possibly empty map of path parameter values
     */
    Map<String, RequestValue> pathParameters();

    /**
     * Returns the bound query parameters, keyed by query parameter name.
     *
     * @return the non-null, possibly empty map of query parameter values
     */
    Map<String, RequestValue> query();

    /**
     * Returns the bound request headers, keyed case-insensitively by header name.
     *
     * @return the non-null, possibly empty map of header values
     */
    Map<String, RequestValue> headers();

    /**
     * Returns the bound request cookies, keyed case-insensitively by cookie name.
     *
     * @return the non-null, possibly empty map of cookie values
     */
    Map<String, RequestValue> cookies();

    /**
     * Returns the bound request body, never {@code null}; a request without a body yields a
     * {@link RequestValue} wrapping {@code null}.
     *
     * @return the non-null request body value
     */
    RequestValue body();

    /**
     * Returns the underlying Vert.x request as an escape hatch for what the typed view omits.
     *
     * @return the raw {@link HttpServerRequest}
     */
    HttpServerRequest raw();
}
