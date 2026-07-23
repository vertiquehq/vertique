// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.ext.web.RoutingContext;

/**
 * SPI contract for generated per-method execution plans.
 *
 * <p>A generated implementation replaces the two reflective operations that happen on every
 * request in {@link dev.vertique.rest.jaxrs.ResourceMethodInvoker}:
 * <ol>
 *   <li>{@link #extractArguments(RoutingContext, BoundRequest, GeneratedJaxRsSupport)} —
 *       replaces {@code ParameterExtractor.extractArguments(ctx, req)}. The plan calls the
 *       strongly-typed helper overloads on {@code support} (passing precomputed
 *       {@link dev.vertique.rest.core.request.EffectiveInputPolicies}) instead of
 *       re-deriving them from {@link dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta#annotations()}
 *       on every request.</li>
 *   <li>{@link #invoke(Object, Object[])} — replaces
 *       {@code meta.method().invoke(resourceInstance, args)}.  The plan calls the resource
 *       method directly via a typed cast, eliminating the JVM overhead of reflective dispatch
 *       and removing {@link java.lang.reflect.InvocationTargetException} wrapping from the hot
 *       path.</li>
 * </ol>
 *
 * <p><strong>Runtime invariants.</strong> The following cross-cutting concerns happen
 * <em>outside</em> this interface, in {@code ResourceMethodInvoker}, and are therefore
 * invisible to the plan:
 * <ul>
 *   <li>Bean Validation — applied after {@code extractArguments} returns using
 *       {@code meta.method()} (the {@link java.lang.reflect.Method} object is always retained in
 *       {@link dev.vertique.rest.jaxrs.ResourceMethodMeta} per FR-CG010-008).</li>
 *   <li>Operation interceptors ({@code beforeOperation}, {@code afterOperation},
 *       {@code recoverOperation}).</li>
 *   <li>Response and error pipelines.</li>
 * </ul>
 *
 * <p><strong>Visibility.</strong> This interface is intentionally <em>not</em> sealed.
 * Generated plans live in arbitrary consumer packages (e.g.
 * {@code com.example.api.HelloResource_listItems_ExecutionPlan}) and must be able to implement
 * it without access to the {@code dev.vertique.rest.jaxrs.runtime} package internals.
 *
 * <p><strong>Thread safety.</strong> Implementations MUST be stateless and safe for concurrent
 * invocation from multiple event-loop threads. The {@code support} adapter passed to
 * {@code extractArguments} is already a per-request view and must not be retained across calls.
 */
public interface ResourceExecutionPlan {

    /**
     * Extracts the ordered argument array for the resource method from the current request.
     *
     * <p>Implementations call the typed helper overloads on {@code support} (such as
     * {@link GeneratedJaxRsSupport#extractScalarParam} or
     * {@link GeneratedJaxRsSupport#deserializeBody}) with precomputed
     * {@link dev.vertique.rest.core.request.EffectiveInputPolicies} instead of re-resolving
     * policies from raw annotations on every request.
     *
     * @param ctx     the current routing context; must not be {@code null}
     * @param request the neutral bound request exposing parameters and body as
     *                {@link dev.vertique.rest.core.request.RequestValue}s; must not be {@code null}
     * @param support the per-request parameter helper bag; must not be retained after this method
     *                returns
     * @return the ordered argument array, positionally matching the resource method's parameter
     *         list; never {@code null}
     * @throws Exception if any parameter cannot be extracted (e.g. missing required value,
     *                   deserialization failure, unsupported content type)
     */
    Object[] extractArguments(RoutingContext ctx, BoundRequest request, GeneratedJaxRsSupport support) throws Exception;

    /**
     * Invokes the resource method directly via a typed call, returning its result.
     *
     * <p>Unlike {@code Method.invoke(...)}, implementations cast {@code resource} to the
     * concrete resource type and call the method directly, eliminating reflective dispatch
     * overhead and {@link java.lang.reflect.InvocationTargetException} wrapping.
     *
     * <p>Any exception thrown by the resource method propagates directly as {@link Throwable}.
     * {@code ResourceMethodInvoker} wraps it appropriately before passing it to the error
     * pipeline.
     *
     * @param resource the resource instance to invoke the method on; the generated implementation
     *                 casts this to the concrete resource type
     * @param args     the argument array produced by
     *                 {@link #extractArguments(RoutingContext, BoundRequest, GeneratedJaxRsSupport)};
     *                 must not be {@code null}
     * @return the method return value; may be {@code null} for {@code void} methods or when the
     *         method returns {@code null}
     * @throws Throwable any exception thrown by the resource method
     */
    Object invoke(Object resource, Object[] args) throws Throwable;
}
