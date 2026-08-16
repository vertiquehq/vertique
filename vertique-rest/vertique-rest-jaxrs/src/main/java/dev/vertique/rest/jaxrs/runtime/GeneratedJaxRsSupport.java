// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.rest.core.context.RestContextUnavailableException;
import dev.vertique.rest.jaxrs.ResourceMethodMeta;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;

/**
 * Per-request helper bag exposed to generated {@link ResourceExecutionPlan} implementations.
 *
 * <p>Implementations are constructed once per {@code ResourceMethodInvoker} and act as a thin
 * facade over the request runtime ({@code ParameterExtractor}, {@code RequestBodyDecoder} chain,
 * optional {@code InputObjectProcessor}). Each helper accepts a precomputed
 * {@link EffectiveInputPolicies} so generated plans pass policies derived at compile time and
 * never need to re-resolve them from {@link ResourceMethodMeta.ParamMeta#annotations()} on the
 * request hot path.
 *
 * <p>Security and other {@code @Context}-injectable types are resolved uniformly through the
 * {@link dev.vertique.rest.core.context.RestContextResolver} chain via
 * {@link #resolveContext(Class, RoutingContext, String, String)}.
 *
 * <p>The interface is open (not sealed) so future extensions can add helpers without breaking
 * existing generated plans. Implementations MUST be safe for concurrent use across event-loop
 * threads — same threading model as {@code ParameterExtractor}.
 */
public interface GeneratedJaxRsSupport {

    /**
     * Extracts a path / query / header / cookie scalar parameter using the precomputed policies.
     *
     * @param meta     the parameter metadata
     * @param policies precomputed input policies for this parameter
     * @param request  the neutral bound request
     * @return the extracted value, or {@code null} if absent
     */
    @Nullable
    Object extractScalarParam(ResourceMethodMeta.ParamMeta meta, EffectiveInputPolicies policies, BoundRequest request);

    /**
     * Extracts a {@code @FormParam} value using the precomputed policies.
     *
     * @param meta     the parameter metadata
     * @param policies precomputed input policies for this parameter
     * @param ctx      the routing context
     * @return the extracted value, or {@code null} if absent
     */
    @Nullable
    Object extractFormParam(ResourceMethodMeta.ParamMeta meta, EffectiveInputPolicies policies, RoutingContext ctx);

    /**
     * Returns the request's file uploads as a {@code List<FileUpload>}; no policies needed.
     *
     * @param ctx the routing context
     * @return immutable list of uploads, possibly empty
     */
    Object extractFileUploads(RoutingContext ctx);

    /**
     * Returns the request's entity parts as a {@code List<EntityPart>}.
     *
     * @param meta the parameter metadata (used for diagnostic context)
     * @param ctx  the routing context
     * @return immutable list of entity parts, possibly empty
     */
    Object extractEntityParts(ResourceMethodMeta.ParamMeta meta, RoutingContext ctx);

    /**
     * Deserializes the request body, applying the precomputed input policies during the two-phase
     * {@code InputObjectProcessor} pathway when active.
     *
     * @param meta     the parameter metadata (carries body type and generic type)
     * @param policies precomputed input policies for this body parameter
     * @param ctx      the routing context
     * @return the deserialized body, or {@code null} if absent
     */
    @Nullable
    Object deserializeBody(ResourceMethodMeta.ParamMeta meta, EffectiveInputPolicies policies, RoutingContext ctx);

    /**
     * Materializes a {@code @BeanParam} composite using the route-level policies. Per-field
     * policies are derived internally from each {@link BeanParamFieldMeta#meta()}'s
     * {@link dev.vertique.rest.jaxrs.ResourceMethodMeta.ParamMeta#annotations()} array, which
     * carries the field's declared input-policy annotations ({@code @Canonicalize},
     * {@code @Sanitize}, {@code @SkipCanonicalization}, {@code @SkipSanitization}).
     * The {@code routePolicies} apply as the route-level baseline for each field and to the
     * resulting bean during structured-body input processing.
     *
     * @param fields        the bean field entries (one per source field/component); each
     *                      {@code meta().annotations()} must carry the field's declared annotations
     *                      so per-field policies can be derived at materialisation time
     * @param routePolicies the route-level policies to use as baseline and for final processing
     * @param request       the neutral bound request
     * @param ctx           the routing context
     * @param beanType      the bean class to materialize
     * @return the populated bean instance
     */
    Object materializeBean(
            BeanParamFieldMeta[] fields,
            EffectiveInputPolicies routePolicies,
            BoundRequest request,
            RoutingContext ctx,
            Class<?> beanType);

    /**
     * Resolves a {@code @Context} / auto-injected context parameter through the
     * {@link dev.vertique.rest.core.context.RestContextResolver} chain.
     *
     * <p>Delegates to {@link dev.vertique.rest.core.context.RestContextResolution#require} which
     * walks the sorted resolver chain and returns the first non-empty result. When no resolver can
     * supply a value, {@link RestContextUnavailableException} is thrown (FR-REST-185).
     *
     * @param declaredType  the declared Java type of the {@code @Context} parameter; must not be
     *                      {@code null}
     * @param ctx           the current Vert.x routing context; must not be {@code null}
     * @param resourceClass the simple or qualified name of the JAX-RS resource class declaring the
     *                      parameter; used in the exception message; must not be {@code null}
     * @param methodName    the name of the resource method declaring the parameter; used in the
     *                      exception message; must not be {@code null}
     * @return the resolved context value; never {@code null}
     * @throws RestContextUnavailableException when required and unbound (FR-REST-185)
     */
    Object resolveContext(Class<?> declaredType, RoutingContext ctx, String resourceClass, String methodName);
}
