// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * Concrete {@link GeneratedJaxRsSupport} adapter that wraps the request's
 * {@link ParameterExtractor} and exposes its package-private policy-accepting helpers to
 * generated {@link dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan} instances.
 *
 * <p>The adapter is constructed once per {@link ResourceMethodInvoker} (which holds the matching
 * {@code ParameterExtractor}) and is therefore safe to share across requests for the same route —
 * the {@code ParameterExtractor} itself is stateless beyond its constructor-injected
 * collaborators.
 *
 * <p>Helpers that delegate to {@code ParameterExtractor}'s {@code EffectiveInputPolicies}-accepting
 * overloads bypass per-request annotation re-resolution; this is the load-bearing optimization on
 * the request hot path that justifies CG-010 slice 2.
 */
final class ParameterExtractorBackedSupport implements GeneratedJaxRsSupport {

    private final ParameterExtractor parameterExtractor;

    ParameterExtractorBackedSupport(ParameterExtractor parameterExtractor) {
        this.parameterExtractor = parameterExtractor;
    }

    @Override
    @Nullable
    public Object extractScalarParam(
            ResourceMethodMeta.ParamMeta meta, EffectiveInputPolicies policies, BoundRequest request) {
        return parameterExtractor.extractScalarParam(meta, policies, request);
    }

    @Override
    @Nullable
    public Object extractFormParam(
            ResourceMethodMeta.ParamMeta meta, EffectiveInputPolicies policies, RoutingContext ctx) {
        return parameterExtractor.extractFormParam(meta, policies, ctx);
    }

    @Override
    public Object extractFileUploads(RoutingContext ctx) {
        return List.copyOf(ctx.fileUploads());
    }

    @Override
    public Object extractEntityParts(ResourceMethodMeta.ParamMeta meta, RoutingContext ctx) {
        return parameterExtractor.extractAllEntityParts(ctx);
    }

    @Override
    @Nullable
    public Object deserializeBody(
            ResourceMethodMeta.ParamMeta meta, EffectiveInputPolicies policies, RoutingContext ctx) {
        // Read the already-bound request stashed by ResourceMethodInvoker before dispatch. The
        // generated path reuses the same BoundRequest body the reflective path binds, so the two
        // paths share one body representation (FR-024). When absent (defensive), pass a null body.
        BoundRequest br = ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST);
        return parameterExtractor.deserializeBody(
                br != null ? br.body() : RequestValue.of(null), meta.type(), meta.genericType(), ctx, policies);
    }

    @Override
    public Object materializeBean(
            BeanParamFieldMeta[] fields,
            EffectiveInputPolicies routePolicies,
            BoundRequest request,
            RoutingContext ctx,
            Class<?> beanType) {
        return parameterExtractor.materializeBean(fields, routePolicies, request, ctx, beanType);
    }

    @Override
    public Object resolveContext(Class<?> declaredType, RoutingContext ctx, String resourceClass, String methodName) {
        return parameterExtractor.resolveContext(declaredType, ctx, resourceClass, methodName);
    }
}
