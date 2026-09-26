// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.capture.HttpOperationMeta;
import dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.interceptor.OperationContext;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.request.DefaultBoundRequest;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.runtime.GeneratedJaxRsSupport;
import dev.vertique.rest.jaxrs.runtime.ResourceExecutionPlan;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x Handler that bridges a RoutingContext to a JAX-RS resource method invocation.
 * Handles parameter extraction, method invocation, Future resolution, and response dispatch.
 *
 * <p>Integrates operation interceptors, failure mapping, and type-based response handling:
 * <ol>
 *   <li>{@link OperationInterceptor#beforeOperation} chain (with {@link OperationContext})</li>
 *   <li>Sync {@link OperationInterceptor#onOperation} observers</li>
 *   <li>Method invocation + parameter extraction</li>
 *   <li>On success: {@link OperationInterceptor#afterOperation} chain, then sync
 *       {@link OperationInterceptor#onSuccess} observers, then {@link ResponsePipeline#produce}</li>
 *   <li>On failure: sync {@link OperationInterceptor#onError} observers, then
 *       {@link OperationInterceptor#recoverOperation} chain (recovery attempt),
 *       then {@link ErrorPipeline#mapToResponse}</li>
 *   <li>All paths converge at {@link ResponsePipeline#sendResponse} so that
 *       {@link dev.vertique.rest.core.interceptor.RequestInterceptor#transformResponse} and
 *       {@link dev.vertique.rest.core.interceptor.RequestInterceptor#afterResponse} fire for every response</li>
 * </ol>
 */
@Slf4j
public class ResourceMethodInvoker implements Handler<RoutingContext> {

    /** Context data key for the {@code @Produces} media type list set by the invoker. */
    static final String CTX_KEY_PRODUCES = "dev.vertique.produces";

    private final ResourceMethodMeta meta;
    private final ErrorPipeline errorPipeline;
    private final ResponsePipeline responsePipeline;
    private final @Nullable BeanValidator beanValidator;
    private final OperationInterceptorChain interceptorChain;
    private final ParameterExtractor parameterExtractor;
    private final @Nullable GeneratedJaxRsSupport generatedSupport;
    private final List<RestServerRequestEvidenceCapturer> evidenceCapturers;

    /** The descriptor handed to every capturer; built once, by {@link #operationMetaFor}. */
    private final HttpOperationMeta operationMeta;

    /**
     * The framework conversion resolver threaded into the {@link ParameterExtractor} and into every
     * {@link DefaultBoundRequest} this invoker constructs, so application converter bindings and JAX-RS
     * providers participate in inbound binding (the 3-site propagation contract).
     */
    private final ParamConversionResolver paramConversionResolver;

    /**
     * Effective request-body {@link ObjectMapper} resolved once at router-build time for this method
     * (FR-JSON-020), or {@code null} when the effective JSON profile is {@code vertx} (the default).
     * When non-null it is stashed on the {@link RoutingContext} under
     * {@link BoundRequest#KEY_RESOLVED_BODY_MAPPER} so the binding/materialization layers can use the
     * selected profile mapper; when {@code null} the existing default body path runs unchanged.
     */
    private final @Nullable ObjectMapper resolvedBodyMapper;

    /**
     * Operation descriptor derived once from {@link #meta} via
     * {@link ResourceMethodMetaToDescriptorAdapter}. Drives the {@link DefaultBoundRequest} binding
     * model on the reflective dispatch path (FR-024) so multiplicity and scalar coercion follow the
     * route's declared parameters.
     */
    private final JaxRsOperationDescriptor descriptor;

    /**
     * Creates a new invoker for the given resource method.
     *
     * @param meta                  metadata describing the JAX-RS resource method
     * @param interceptors          sorted list of operation interceptors
     * @param errorPipeline         shared error mapping pipeline
     * @param responsePipeline      unified response pipeline for producing and sending responses
     * @param restContextResolution coordinator for the {@link RestContextResolution} resolver chain
     * @param decoders              priority-sorted list of request body decoders
     * @param beanValidator         optional Bean Validation implementation; {@code null} when
     *                              {@code ValidationModule} is not included — validation is skipped
     * @param objectProcessor       optional input object processor for canonicalization and sanitization;
     *                              {@code null} when {@code SanitizationModule} is not included —
     *                              input processing is skipped
     * @param evidenceCapturers     pre-sorted list of {@link RestServerRequestEvidenceCapturer}
     *                              instances to invoke once per request after the body is available;
     *                              empty list is the no-op default
     * @param resolvedBodyMapper    effective request-body {@link ObjectMapper} resolved once at
     *                              router-build time for this method (FR-JSON-020); {@code null} when
     *                              the effective JSON profile is {@code vertx} (the default), in which
     *                              case the existing default body path runs unchanged
     * @param paramConversionResolver the framework parameter-conversion resolver threaded into the
     *                              {@link ParameterExtractor} and the per-request
     *                              {@link DefaultBoundRequest}; must not be {@code null}
     */
    public ResourceMethodInvoker(
            ResourceMethodMeta meta,
            List<OperationInterceptor> interceptors,
            ErrorPipeline errorPipeline,
            ResponsePipeline responsePipeline,
            RestContextResolution restContextResolution,
            List<RequestBodyDecoder> decoders,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            List<RestServerRequestEvidenceCapturer> evidenceCapturers,
            @Nullable ObjectMapper resolvedBodyMapper,
            ParamConversionResolver paramConversionResolver) {
        this(
                meta,
                interceptors,
                errorPipeline,
                responsePipeline,
                restContextResolution,
                decoders,
                beanValidator,
                objectProcessor,
                evidenceCapturers,
                resolvedBodyMapper,
                paramConversionResolver,
                JacksonFieldNameResolver.forRoute(resolvedBodyMapper));
    }

    /**
     * Creates a new invoker with an explicit body-name projection, so a router build can share one
     * projection cache across every route that materializes its body with the same
     * {@link ObjectMapper} instead of introspecting each body type once per route.
     *
     * @param meta                  metadata describing the JAX-RS resource method
     * @param interceptors          sorted list of operation interceptors
     * @param errorPipeline         shared error mapping pipeline
     * @param responsePipeline      unified response pipeline for producing and sending responses
     * @param restContextResolution coordinator for the {@link RestContextResolution} resolver chain
     * @param decoders              priority-sorted list of request body decoders
     * @param beanValidator         optional Bean Validation implementation; {@code null} skips validation
     * @param objectProcessor       optional input object processor; {@code null} skips input processing
     * @param evidenceCapturers     pre-sorted request-evidence capturers; empty list is the no-op default
     * @param resolvedBodyMapper    effective request-body mapper (FR-JSON-020), or {@code null} when the
     *                              route's profile resolved to the process codec's own mapper
     * @param paramConversionResolver the framework parameter-conversion resolver; must not be {@code null}
     * @param bodyNameResolver      the wire &rarr; Java property-name projection for this route's OBJECT
     *                              bodies; must be built from {@code resolvedBodyMapper} (or
     *                              the process codec's mapper when it is {@code null}), because that
     *                              is the mapper whose naming decides which declared policies apply
     */
    public ResourceMethodInvoker(
            ResourceMethodMeta meta,
            List<OperationInterceptor> interceptors,
            ErrorPipeline errorPipeline,
            ResponsePipeline responsePipeline,
            RestContextResolution restContextResolution,
            List<RequestBodyDecoder> decoders,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            List<RestServerRequestEvidenceCapturer> evidenceCapturers,
            @Nullable ObjectMapper resolvedBodyMapper,
            ParamConversionResolver paramConversionResolver,
            InputFieldNameResolver bodyNameResolver) {
        this.meta = meta;
        this.errorPipeline = errorPipeline;
        this.responsePipeline = responsePipeline;
        this.beanValidator = beanValidator;
        this.resolvedBodyMapper = resolvedBodyMapper;
        this.paramConversionResolver = paramConversionResolver;
        this.interceptorChain = new OperationInterceptorChain(interceptors != null ? interceptors : List.of());
        // The body-name projection comes from the mapper that actually materializes this route's body —
        // the resolved profile mapper, or the process codec's own mapper when the route resolved to it.
        // Without it the engine would look up a renamed field's metadata by its wire key and silently
        // skip its declared @Canonicalize/@Sanitize.
        this.parameterExtractor = new ParameterExtractor(
                meta,
                decoders != null ? decoders : List.of(),
                restContextResolution,
                objectProcessor,
                paramConversionResolver,
                bodyNameResolver);
        // Slice 2 adapter: wraps the per-route ParameterExtractor and exposes its policy-accepting
        // helpers to any generated ResourceExecutionPlan. Always wired; whether it actually runs
        // depends on whether meta.executionPlan() is non-null (which only happens when CG-010's
        // ExecutionPlanEmitter has produced a plan for this method).
        this.generatedSupport = new ParameterExtractorBackedSupport(this.parameterExtractor);
        this.evidenceCapturers = evidenceCapturers != null ? evidenceCapturers : List.of();
        // FR-024: build the operation descriptor once so the reflective path can construct a
        // DefaultBoundRequest per request without re-deriving the parameter model.
        this.descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);
        this.operationMeta = operationMetaFor(meta, descriptor);
    }

    /**
     * Builds the {@link HttpOperationMeta} request-evidence capturers receive for a route. The
     * registrar validates the route with the result of this method and the invoker hands every
     * request the result of this method, so the two can never disagree.
     *
     * @param meta       the route's method metadata
     * @param descriptor the route's operation descriptor
     * @return the operation descriptor for capturers; never {@code null}
     */
    static HttpOperationMeta operationMetaFor(ResourceMethodMeta meta, JaxRsOperationDescriptor descriptor) {
        return new HttpOperationMeta(
                meta.method(), meta.resourceInstance().getClass(), meta.operationId(), descriptor.routeTemplate());
    }

    /**
     * Backward-compatible constructor that omits evidence capturers (defaults to empty list) and the
     * resolved request-body mapper (defaults to {@code null}, i.e. the {@code vertx} default body
     * path). Existing call sites that have not yet been migrated to pass these continue to compile.
     *
     * @param meta                  metadata describing the JAX-RS resource method
     * @param interceptors          sorted list of operation interceptors
     * @param errorPipeline         shared error mapping pipeline
     * @param responsePipeline      unified response pipeline for producing and sending responses
     * @param restContextResolution coordinator for the {@link RestContextResolution} resolver chain
     * @param decoders              priority-sorted list of request body decoders
     * @param beanValidator         optional Bean Validation implementation; {@code null} when
     *                              {@code ValidationModule} is not included — validation is skipped
     * @param objectProcessor       optional input object processor for canonicalization and sanitization;
     *                              {@code null} when {@code SanitizationModule} is not included —
     *                              input processing is skipped
     */
    public ResourceMethodInvoker(
            ResourceMethodMeta meta,
            List<OperationInterceptor> interceptors,
            ErrorPipeline errorPipeline,
            ResponsePipeline responsePipeline,
            RestContextResolution restContextResolution,
            List<RequestBodyDecoder> decoders,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor) {
        this(
                meta,
                interceptors,
                errorPipeline,
                responsePipeline,
                restContextResolution,
                decoders,
                beanValidator,
                objectProcessor,
                List.of(),
                null,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver());
    }

    /**
     * Backward-compatible constructor that omits the parameter-conversion resolver (defaults to the
     * framework built-ins-only resolver). Existing call sites that pass evidence capturers and a
     * resolved body mapper but predate the conversion-resolver wiring continue to compile.
     *
     * @param meta                  metadata describing the JAX-RS resource method
     * @param interceptors          sorted list of operation interceptors
     * @param errorPipeline         shared error mapping pipeline
     * @param responsePipeline      unified response pipeline for producing and sending responses
     * @param restContextResolution coordinator for the {@link RestContextResolution} resolver chain
     * @param decoders              priority-sorted list of request body decoders
     * @param beanValidator         optional Bean Validation implementation; {@code null} skips validation
     * @param objectProcessor       optional input object processor; {@code null} skips input processing
     * @param evidenceCapturers     pre-sorted request-evidence capturers; empty list is the no-op default
     * @param resolvedBodyMapper    effective request-body mapper (FR-JSON-020), or {@code null} for the
     *                              {@code vertx} default
     */
    public ResourceMethodInvoker(
            ResourceMethodMeta meta,
            List<OperationInterceptor> interceptors,
            ErrorPipeline errorPipeline,
            ResponsePipeline responsePipeline,
            RestContextResolution restContextResolution,
            List<RequestBodyDecoder> decoders,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            List<RestServerRequestEvidenceCapturer> evidenceCapturers,
            @Nullable ObjectMapper resolvedBodyMapper) {
        this(
                meta,
                interceptors,
                errorPipeline,
                responsePipeline,
                restContextResolution,
                decoders,
                beanValidator,
                objectProcessor,
                evidenceCapturers,
                resolvedBodyMapper,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver());
    }

    /**
     * Handles the routing context by running the full request lifecycle pipeline.
     *
     * @param ctx the current routing context
     */
    @Override
    public void handle(RoutingContext ctx) {
        OperationContext opCtx = new OperationContext(
                meta.operationId(), ctx, meta.methodAnnotations(), meta.classAnnotations(), Map.of());
        interceptorChain
                .chainBeforeOperationInterceptors(opCtx, 0)
                .compose(finalOpCtx -> {
                    interceptorChain.fireOnOperation(finalOpCtx);
                    return invokeMethod(ctx)
                            .compose(result -> interceptorChain.chainAfterOperationInterceptors(finalOpCtx, result, 0))
                            .map(result -> {
                                interceptorChain.fireOnSuccess(finalOpCtx, result);
                                return responsePipeline.produce(ctx, meta.returnsVoid() ? null : result);
                            })
                            .recover(cause -> {
                                interceptorChain.fireOnError(finalOpCtx, cause);
                                return interceptorChain
                                        .chainRecoverOperationInterceptors(finalOpCtx, cause, 0)
                                        .map(recovered ->
                                                responsePipeline.produce(ctx, meta.returnsVoid() ? null : recovered))
                                        .recover(finalCause -> errorPipeline.mapToResponse(ctx, finalCause));
                            });
                })
                .recover(cause -> errorPipeline.mapToResponse(ctx, cause))
                .onSuccess(response -> responsePipeline.sendResponse(ctx, response))
                .onFailure(cause -> responsePipeline.sendFallback500(ctx, cause));
    }

    /**
     * Invokes the JAX-RS resource method and returns the result as a Future.
     * Unwraps {@code Future<T>} return values automatically.
     *
     * @param ctx the current routing context
     * @return a Future that completes with the method return value
     */
    private Future<Object> invokeMethod(RoutingContext ctx) {
        try {
            if (!meta.mediaTypes().produces().isEmpty()) {
                ctx.data().put(CTX_KEY_PRODUCES, meta.mediaTypes().produces());
            }

            // AUD-003 slice 2a: invoke request-evidence capturers after body materialisation.
            // Each capturer is called in OrderedExtension order; a throwing capturer is logged at
            // WARN and must never break request handling. When the set is empty this loop is a no-op.
            if (!evidenceCapturers.isEmpty()) {
                for (RestServerRequestEvidenceCapturer capturer : evidenceCapturers) {
                    try {
                        capturer.captureRequest(ctx, operationMeta);
                    } catch (Exception e) {
                        log.warn(
                                "RestServerRequestEvidenceCapturer [{}] threw during captureRequest — ignoring: {}",
                                capturer.getClass().getName(),
                                e.toString(),
                                e);
                    }
                }
            }

            // CG-010 slice 2: plan-or-reflective dispatch. When meta carries a generated execution
            // plan AND a runtime support adapter is wired, the plan owns argument extraction and
            // direct invocation. Otherwise the reflective path runs (ParameterExtractor +
            // Method.invoke), unchanged. Bean Validation, interceptors, and the response/error
            // pipeline run around both paths identically.
            final ResourceExecutionPlan plan = meta.executionPlan();
            final GeneratedJaxRsSupport support = generatedSupport;

            // FR-JSON-020/024: the resolved request-body profile mapper (when a profile other than the process codec's
            // applies
            // to this method) is stashed on the RoutingContext by a per-route handler installed AHEAD of
            // the validation gate in JaxRsRouteRegistrar — not here — so the gate's own body bind (which
            // runs before this invoker under the default web-validation strategy) reads it too and the
            // profile mapper owns the FIRST PARSE on every body path. As a defensive backstop for any
            // dispatch path that reaches the invoker without that pre-gate handler having run, ensure the
            // mapper is present before the bound request is built or read below. Absent key == vertx
            // default.
            if (resolvedBodyMapper != null && ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER) == null) {
                ctx.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, resolvedBodyMapper);
            }

            // FR-024: bind the request once and stash it so the generated path's deserializeBody can
            // reuse the same bound body the reflective path reads — both paths run on BoundRequest.
            // Read-if-present: a pre-dispatch validation gate may have already built and stashed the
            // bound request, in which case the gate and the invoker must share that one instance (one
            // bind, one body read). Only build a fresh one when none was stashed.
            BoundRequest boundRequest = ctx.get(BoundRequest.KEY_META_DATA_BOUND_REQUEST);
            if (boundRequest == null) {
                boundRequest = new DefaultBoundRequest(ctx, descriptor, paramConversionResolver);
                ctx.put(BoundRequest.KEY_META_DATA_BOUND_REQUEST, boundRequest);
            }

            Object[] args;
            Object result;
            if (plan != null && support != null) {
                args = plan.extractArguments(ctx, boundRequest, support);
                if (beanValidator != null) {
                    validateArguments(args);
                }
                result = plan.invoke(meta.resourceInstance(), args);
            } else {
                args = parameterExtractor.extractArguments(ctx, boundRequest);
                if (beanValidator != null) {
                    validateArguments(args);
                }
                result = meta.method().invoke(meta.resourceInstance(), args);
            }

            if (meta.returnsFuture()) {
                @SuppressWarnings("unchecked")
                Future<Object> future = (Future<Object>) result;
                return future;
            } else {
                return Future.succeededFuture(result);
            }
        } catch (InvocationTargetException e) {
            return Future.failedFuture(e.getCause());
        } catch (Throwable t) {
            return Future.failedFuture(t);
        }
    }

    /**
     * Validates method arguments using Bean Validation when a validator is available.
     * Uses {@link ConstraintViolationMapper} to map violations to REST-specific error details.
     *
     * @param args the extracted method arguments
     * @throws RestValidationException if any constraints are violated
     */
    private void validateArguments(Object[] args) {
        List<ParameterViolation> violations = (meta.validationGroups() != null)
                ? beanValidator.checkParameters(meta.resourceInstance(), meta.method(), args, meta.validationGroups())
                : beanValidator.checkParameters(meta.resourceInstance(), meta.method(), args);
        if (!violations.isEmpty()) {
            throw ConstraintViolationMapper.toRestValidationException(meta, violations);
        }
    }
}
