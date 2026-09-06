// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.util.Strings;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.lifecycle.RouterLifecycleHook;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategySelector;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link RouterMount} implementation that wires JAX-RS annotated resource classes into a plain Vert.x
 * sub-router built entirely from the JAX-RS annotation model.
 *
 * <p>Instances are created exclusively via the inner {@link Factory} class, which holds all
 * shared framework services. Per-mount configuration ({@code mountPath}, {@code openapiPath},
 * {@code resources}, and {@code priority}) is supplied at creation time. The {@code openapiPath} is
 * retained only as documentation metadata ({@link #meta()}); routing is always built from JAX-RS
 * metadata, never from the spec, and the default {@code web-validation} strategy loads no spec at
 * runtime either (PRD-REST-017 FR-001 — {@code openapi.json} is docs-only on that path, and binding is
 * decoupled from validation). The opt-in {@code openapi-contract} strategy ({@code
 * vertique-rest-openapi-validation}) does load {@code openapi.json} at runtime and validates requests
 * against it.
 *
 * <p>The {@link #createRouter(Vertx)} method encapsulates the full JAX-RS router construction pipeline:
 * selecting the request-validation strategy by id, running router lifecycle hooks, collecting security
 * scheme handlers, scanning and registering JAX-RS resource methods on a plain {@link Router},
 * installing middleware, and attaching the error failure handler.
 *
 * <p>Example factory usage:
 * <pre>
 * {@literal @}Provides {@literal @}IntoSet
 * RouterMount jaxrsMount(JaxRsRouterMount.Factory factory,
 *                        {@literal @}JaxRsResources Set&lt;Object&gt; resources,
 *                        JaxRsConfig jaxRsConfig) {
 *     return factory.create(jaxRsConfig.basePath(), jaxRsConfig.openapiPath(), resources);
 * }
 * </pre>
 */
@Slf4j
public class JaxRsRouterMount implements RouterMount {

    // --- Instance fields ---

    private final String mountPath;
    private final String openapiPath;
    private final Set<Object> resources;
    private final int priority;
    private final Factory factory;

    /**
     * Creates a new mount. Only {@link Factory} should call this constructor.
     *
     * @param mountPath    the path prefix where the sub-router is mounted
     * @param openapiPath  classpath location of the OpenAPI spec
     * @param resources    JAX-RS annotated resource instances
     * @param priority     mount priority (lower values are mounted first)
     * @param factory      shared services factory
     */
    private JaxRsRouterMount(
            String mountPath, String openapiPath, Set<Object> resources, int priority, Factory factory) {
        this.mountPath = mountPath;
        this.openapiPath = openapiPath;
        this.resources = resources;
        this.priority = priority;
        this.factory = factory;
    }

    /** {@inheritDoc} */
    @Override
    public String mountPath() {
        return mountPath;
    }

    /** {@inheritDoc} */
    @Override
    public int priority() {
        return priority;
    }

    /**
     * Returns metadata for this mount. The {@code mountId} is {@code "jaxrs:"} followed by
     * the mount path; the {@code resourceTypes} set contains the classes of all registered
     * resources.
     *
     * @return mount metadata with a stable {@code "jaxrs:<mountPath>"} identifier
     */
    @Override
    public MountMeta meta() {
        Set<Class<?>> resourceTypes = resources.stream().map(Object::getClass).collect(Collectors.toUnmodifiableSet());
        return new MountMeta("jaxrs:" + mountPath, mountPath, openapiPath, resourceTypes);
    }

    /**
     * Creates the JAX-RS sub-router for this mount as a plain Vert.x {@link Router}.
     *
     * <p>If no resources are registered, an empty router is returned immediately. Otherwise the
     * pipeline is:
     * <ol>
     *   <li>Create a plain {@link Router} via {@link Router#router(Vertx)}.</li>
     *   <li>Select the request-validation strategy by id via
     *       {@link RequestValidationStrategySelector} (fail-fast on an unknown id).</li>
     *   <li>Install a root-order {@link BodyHandler} so the body is materialised before any operation
     *       handler reads it.</li>
     *   <li>Register reset-safe multipart upload cleanup after the body handler.</li>
     *   <li>Install request interceptors (after upload cleanup registration).</li>
     *   <li>Run {@link RouterLifecycleHook#beforeAuthSetup} hooks.</li>
     *   <li>Configure {@link dev.vertique.rest.core.security.SecuritySchemeHandler} instances, recording
     *       each scheme's authentication handler into a {@link SecuritySchemeHandlerCollector}.</li>
     *   <li>Run {@link RouterLifecycleHook#afterAuthSetup} hooks.</li>
     *   <li>Scan and register all JAX-RS resource methods via {@link JaxRsRouteRegistrar}, installing per
     *       operation: the collected auth handler(s) → the validation gate → the sorted contributors →
     *       the {@link ResourceMethodInvoker}.</li>
     *   <li>Run {@link RouterLifecycleHook#afterRouterCreated} hooks.</li>
     *   <li>Mount {@link MiddlewareScope#API} middlewares on the API router.</li>
     *   <li>Attach the router-level failure handler.</li>
     * </ol>
     *
     * <p>No OpenAPI contract is loaded: the router is built entirely from the JAX-RS annotation model,
     * so an {@code openapi.json} (when present) is documentation only (PRD-REST-017 FR-001).
     *
     * @param vertx the Vert.x instance
     * @return a future resolving to the configured API router
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        if (resources.isEmpty()) {
            return Future.succeededFuture(Router.router(vertx));
        }

        List<RouterLifecycleHook> sortedRouterHooks = factory.routerLifecycleHooks.stream()
                .sorted(OrderedExtension.comparator())
                .toList();
        List<OperationInterceptor> sortedOperationInterceptors = factory.operationInterceptors.stream()
                .sorted(OrderedExtension.comparator())
                .toList();
        List<ErrorInterceptor> sortedErrorInterceptors = factory.errorInterceptors.stream()
                .sorted(OrderedExtension.comparator())
                .toList();
        List<RequestInterceptor> sortedRequestInterceptors = factory.requestInterceptors.stream()
                .sorted(OrderedExtension.comparator())
                .toList();
        List<OperationHandlerContributor> sortedContributors = factory.operationHandlerContributors.stream()
                .sorted(OrderedExtension.comparator())
                .toList();
        List<RestServerRequestEvidenceCapturer> sortedCapturers = factory.evidenceCapturers.stream()
                .sorted(OrderedExtension.comparator())
                .toList();

        ResponsePipeline responsePipeline = new ResponsePipeline(
                factory.responseProducerBindings, sortedRequestInterceptors, factory.responseSerializer);

        ErrorPipeline errorPipeline = new ErrorPipeline(
                sortedErrorInterceptors,
                sortedRequestInterceptors,
                factory.restExceptionMapper,
                factory.exceptionMapperRegistry);

        // Fail-fast strategy selection: resolve the configured id against the registered strategies.
        RequestValidationStrategy strategy = RequestValidationStrategySelector.select(
                factory.jaxRsConfig.validationStrategy(), factory.validationStrategies);
        log.info("Using request-validation strategy '{}'", strategy.id());

        if (!factory.fileContentVerifiers.isEmpty() && !strategy.runsFileVerifiers()) {
            log.warn(
                    "Mount {}: {} FileContentVerifier(s) bound but validation strategy '{}' does not run file verifiers — file content verification is INACTIVE for this mount",
                    mountPath,
                    factory.fileContentVerifiers.size(),
                    strategy.id());
        }

        // Bind the selected strategy to THIS mount's metadata before any gate is produced.
        // A strategy whose validation is driven by a per-mount contract (the opt-in openapi-contract
        // strategy) fails fast here when mounts declare divergent contract paths, so no operation is ever
        // silently validated against a different mount's contract. Contract-path-agnostic strategies
        // (web-validation, none) ignore this via the default no-op.
        strategy.bindToMount(meta());

        Router apiRouter = Router.router(vertx);

        // Install BodyHandler with lowest order so the body is materialised before any operation
        // handler (gate, contributors, invoker) reads routingContext.body().
        apiRouter
                .route()
                .order(Integer.MIN_VALUE)
                .handler(BodyHandler.create()
                        .setBodyLimit(factory.httpConfig.maxBodySize())
                        .setUploadsDirectory(factory.httpConfig.uploadsDirectory()));

        // Register always-on cleanup after BodyHandler has materialised multipart uploads. Routing
        // context end handlers cover normal completion, failures, and connection/stream resets.
        apiRouter.route().order(Integer.MIN_VALUE + 1).handler(ctx -> {
            ctx.addEndHandler(v -> ctx.cancelAndCleanupFileUploads());
            ctx.next();
        });

        // Install request interceptors at router level (after upload cleanup registration).
        // Sync onRequest observers fire first, then the async beforeRequest chain.
        if (!sortedRequestInterceptors.isEmpty()) {
            apiRouter.route().order(Integer.MIN_VALUE + 2).handler(ctx -> {
                for (RequestInterceptor interceptor : sortedRequestInterceptors) {
                    try {
                        interceptor.onRequest(ctx);
                    } catch (Exception e) {
                        // swallow — sync observers must not affect outcome
                    }
                }
                chainBeforeRequest(ctx, sortedRequestInterceptors)
                        .onSuccess(v -> ctx.next())
                        .onFailure(cause -> dispatchError(ctx, cause, errorPipeline, responsePipeline));
            });
        }

        // Lifecycle hooks observe the real router (available upfront on a plain Router).
        PlainRouterSetup routerSetup = new PlainRouterSetup(apiRouter);
        for (RouterLifecycleHook hook : sortedRouterHooks) {
            hook.beforeAuthSetup(routerSetup);
        }

        // Configure security scheme handlers, collecting each scheme's auth handler. No contract gating
        // (FR-014): every handler is configured once; the registrar applies the collected handler per
        // each operation's effective security requirements.
        SecuritySchemeHandlerCollector securityHandlers = new SecuritySchemeHandlerCollector();
        for (SecuritySchemeHandler handler : factory.securitySchemeHandlers) {
            log.info("Configuring security scheme: {}", handler.schemeName());
            handler.configure(new CollectingSecuritySchemeRegistry(handler, securityHandlers));
        }

        for (RouterLifecycleHook hook : sortedRouterHooks) {
            hook.afterAuthSetup(routerSetup);
        }

        JaxRsRouteRegistrar registrar = new JaxRsRouteRegistrar();
        registrar.registerAll(
                resources,
                apiRouter,
                strategy,
                factory.operationSchemaSource,
                securityHandlers,
                sortedOperationInterceptors,
                sortedContributors,
                errorPipeline,
                responsePipeline,
                factory.restContextResolution,
                factory.paramConversionResolver,
                factory.securityPolicyValidator,
                factory.authEnabled,
                factory.sortedDecoders,
                factory.sortedEncoders,
                factory.jaxRsConfig.mediaTypeValidation(),
                factory.beanValidator,
                factory.objectProcessor,
                sortedCapturers,
                factory.actionRegistry,
                factory.authorizerAvailable,
                factory.jaxRsConfig,
                factory.jsonMapperProfileRegistry,
                factory.jsonConfig);

        for (RouterLifecycleHook hook : sortedRouterHooks) {
            hook.afterRouterCreated(apiRouter);
        }

        // Mount API-scoped middlewares with explicit order so they execute before the per-operation
        // handlers (which get auto-orders starting from 0+).
        // BodyHandler: MIN_VALUE, request interceptors: MIN_VALUE + 1, middlewares:
        // MIN_VALUE + 100 + <position in the OrderedExtension-sorted list>. Using the sorted index
        // (not priority()) makes the Vert.x route order honor the full OrderedExtension contract
        // (phase -> priority -> orderKey), not priority alone.
        List<Middleware> apiMiddlewares = factory.middlewares.stream()
                .filter(m -> m.scope() == MiddlewareScope.API)
                .sorted(OrderedExtension.comparator())
                .toList();
        for (int i = 0; i < apiMiddlewares.size(); i++) {
            Middleware m = apiMiddlewares.get(i);
            // Clamp to prevent overflow; index is small so this only guards against extremes.
            int routeOrder = (int) Math.min(-1L, (long) Integer.MIN_VALUE + 100 + i);
            apiRouter.route(m.path()).order(routeOrder).handler(m);
        }

        // FR-JSON-058: resolve the no-matched-method error-body default mapper once, at router-build
        // time. The no-method failure path (pre-routing 404s, request-interceptor rejections) reaches
        // the failure handler with NO per-method KEY_RESOLVED_BODY_MAPPER stash, so the boundary+global
        // default (jaxrs.jsonProfile -> json.jsonProfile -> vertx) is folded in here and stashed before
        // the error body is serialized. A blank/vertx default resolves to null => no stash => Json.encode
        // (the byte-for-byte vertx path, unchanged). An unknown configured id fails fast here at startup
        // (registry.mapper throws), matching the request-resolution fail-fast.
        ObjectMapper noMethodDefaultMapper = resolveNoMethodDefaultMapper(
                factory.jaxRsConfig, factory.jsonConfig, factory.jsonMapperProfileRegistry);

        apiRouter
                .route()
                .failureHandler(ctx -> handleFailure(ctx, errorPipeline, responsePipeline, noMethodDefaultMapper));

        return Future.succeededFuture(apiRouter);
    }

    /**
     * Resolves the boundary+global default error-body mapper for the no-matched-method failure path
     * (FR-JSON-058). The effective id is {@code firstNonBlank(jaxrs.jsonProfile, json.jsonProfile)}; a
     * blank result, or the reserved {@code vertx} id, resolves to {@code null} (the byte-for-byte vertx
     * path). Any other id is resolved against the registry, which fails fast at this router-build time
     * when the id is unknown.
     *
     * @param jaxRsConfig the JAX-RS routing config supplying the {@code jaxrs.jsonProfile} default
     * @param jsonConfig the global JSON config supplying the {@code json.jsonProfile} default
     * @param registry the profile registry used to resolve a non-{@code system} id to its mapper
     * @return the resolved default mapper, or {@code null} when the effective default is {@code system}
     * @throws dev.vertique.core.json.JsonProfileConfigurationException if the effective non-{@code system}
     *     id is not registered
     */
    private static @Nullable ObjectMapper resolveNoMethodDefaultMapper(
            JaxRsConfig jaxRsConfig, JsonConfig jsonConfig, JsonMapperProfileRegistry registry) {
        String configured = Strings.firstNonBlank(jaxRsConfig.jsonProfile(), jsonConfig.jsonProfile());
        if (configured == null || JsonProfileId.SYSTEM.value().equals(configured)) {
            return null;
        }
        return registry.mapper(JsonProfileId.of(configured));
    }

    /**
     * Chains {@link RequestInterceptor#beforeRequest} async handlers sequentially.
     * Each interceptor must complete before the next is invoked.
     *
     * @param ctx          the current routing context
     * @param interceptors the sorted list of request interceptors to chain
     * @return a {@link Future} that completes when all interceptors have been invoked
     */
    private static Future<Void> chainBeforeRequest(RoutingContext ctx, List<RequestInterceptor> interceptors) {
        Future<Void> chain = Future.succeededFuture();
        for (RequestInterceptor interceptor : interceptors) {
            chain = chain.flatMap(v -> interceptor.beforeRequest(ctx));
        }
        return chain;
    }

    /**
     * Handles a Vert.x failure by translating the cause into a framework-compatible exception
     * and dispatching it through the error pipeline.
     *
     * <p>A bare failure (no {@link Throwable}) is turned into a {@link jakarta.ws.rs.WebApplicationException}
     * carrying the routing context's status code — this is the shape a body-limit 413 from
     * {@link BodyHandler} arrives in, since it fails the context with a status and no throwable. A Vert.x
     * {@code HttpException} is unwrapped so custom {@code ExceptionMapper<T>} implementations see the
     * original cause; its status code is stored in {@link RoutingContext#data()} for fallback use when no
     * specific {@code ExceptionMapper} matches — but only when it is an error status (4xx or 5xx), since
     * {@code HttpException} accepts any {@code int} and a sub-400 status would otherwise dictate both the
     * response status and the problem body derived from it.
     * Request-validation failures from the web-validation gate are raised directly as
     * {@code RestValidationException} (not via {@code HttpException}), so they flow through the pipeline
     * unchanged.
     *
     * <p>A failure that carries <em>both</em> an explicit 4xx status and a cause that is not an
     * {@code HttpException} — {@code ctx.fail(400, decoderFailure)} from Vert.x's body handler,
     * {@code ctx.fail(401, e)} from a JWT claims validator — stores that status the same way, but keeps the
     * raw cause unwrapped so an application {@code ExceptionMapper} registered for the cause's own type
     * still matches and still outranks it. The range is deliberately strict: a 5xx is not carried, because
     * {@code RoutingContext.fail(Throwable)} synthesises a 500 that is indistinguishable from a deliberate
     * {@code fail(500, cause)}, and a sub-400 status is not a client-error decision at all.
     *
     * <p>Before dispatching, the resolved no-matched-method error-body default mapper (FR-JSON-058) — when
     * non-{@code null} and not already stashed by an upstream per-method handler — is placed under
     * {@link dev.vertique.rest.jaxrs.request.BoundRequest#KEY_RESOLVED_BODY_MAPPER} so the JSON body
     * encoder serializes the error body through the boundary+global default profile. When the default
     * is {@code null} (the {@code vertx} path) nothing is stashed and the encoder falls back to
     * {@code Json.encode}. The boundary default is applied <em>only</em> when no matched operation route
     * already decided the error-body mapper: a matched route's per-route failure handler
     * ({@code JaxRsRouteRegistrar} step (e)) runs first and sets
     * {@link dev.vertique.rest.jaxrs.request.BoundRequest#KEY_ERROR_BODY_MAPPER_DECIDED} (FR-JSON-058A),
     * so a route's own decision — including an explicit {@code vertx} that must serialize via
     * {@code Json.encode} — is preserved over the boundary default. This closes the error-path profiling
     * asymmetry for auth/415 rejections and for explicit-{@code vertx} routes under a non-{@code vertx}
     * global default.
     *
     * @param ctx                   the current routing context
     * @param errorPipeline         the error mapping pipeline
     * @param responsePipeline      the response sending pipeline
     * @param noMethodDefaultMapper the boundary+global default error-body mapper, or {@code null} for vertx
     */
    private static void handleFailure(
            RoutingContext ctx,
            ErrorPipeline errorPipeline,
            ResponsePipeline responsePipeline,
            @Nullable ObjectMapper noMethodDefaultMapper) {
        Throwable cause = ctx.failure();
        if (ctx.response().ended() || ctx.response().headWritten()) {
            return;
        }
        // FR-JSON-058/058A: stash the no-method boundary+global default mapper before the error body is
        // serialized, but ONLY when no matched operation route already decided the error-body mapper.
        // A matched route's per-route failure handler (JaxRsRouteRegistrar step (e)) runs first and sets
        // KEY_ERROR_BODY_MAPPER_DECIDED: it either stashed its own profile mapper under
        // KEY_RESOLVED_BODY_MAPPER (non-vertx route) or stashed nothing (explicit-vertx route — must
        // serialize via Json.encode, NOT the boundary default). The marker therefore distinguishes "a
        // matched route decided (honor it, including an explicit vertx)" from "no operation route matched
        // — a pre-routing 404 / request-interceptor rejection" (apply the boundary+global default). This
        // closes the error-path asymmetry where an auth/415 rejection or an explicit-vertx route under a
        // non-vertx global default was serialized with the global default mapper. A null default also
        // leaves the stash absent => Json.encode (vertx).
        boolean matchedRouteDecided = Boolean.TRUE.equals(ctx.get(BoundRequest.KEY_ERROR_BODY_MAPPER_DECIDED));
        if (!matchedRouteDecided
                && noMethodDefaultMapper != null
                && ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER) == null) {
            ctx.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, noMethodDefaultMapper);
        }
        if (cause == null) {
            cause = new jakarta.ws.rs.WebApplicationException(errorStatusOrServerError(ctx.statusCode()));
        } else if (cause instanceof io.vertx.ext.web.handler.HttpException he) {
            // HttpException accepts any int, so a middleware or SecuritySchemeHandler can fail the context
            // with a non-error status. Carry it as the authoritative failure status only when it actually
            // denotes an error — the fallback re-derives the whole problem body from this status, so a
            // stashed 200 would answer a failure with "200 OK" plus a problem document. Unlike the
            // fail(4xx, cause) branch below, 5xx is legitimate here: HttpException(503, …) is a deliberate
            // status, not a fail(Throwable) synthesis.
            int status = he.getStatusCode();
            if (status >= 400 && status < 600) {
                ctx.data().put(VertxFailureStatus.KEY, status);
            }
            if (he.getCause() != null) {
                cause = he.getCause();
            } else {
                // No cause to map: the status this exception carries becomes the response's own, so it must
                // be normalized here too. Withholding the hint alone would not save the request — the
                // default WebApplicationException mapping answers with whatever status the synthesised
                // exception carries, reintroducing exactly the "200 OK plus a problem document"
                // contradiction the hint guard above refuses.
                cause = new jakarta.ws.rs.WebApplicationException(
                        he.getPayload(), errorStatusOrServerError(he.getStatusCode()));
            }
        } else if (ctx.statusCode() >= 400 && ctx.statusCode() < 500) {
            // ctx.fail(4xx, cause): the Vert.x layer made a deliberate client-error decision *and* handed
            // over a cause. Carry the status as the authoritative failure status, but leave the cause raw —
            // wrapping it would hide the original type from an application ExceptionMapper, which outranks
            // this status. 5xx is excluded because fail(Throwable) synthesises an indistinguishable 500.
            ctx.data().put(VertxFailureStatus.KEY, ctx.statusCode());
        }
        dispatchError(ctx, cause, errorPipeline, responsePipeline);
    }

    /**
     * Normalizes a failure status into one that can actually answer a failure.
     *
     * <p>Both the Vert.x routing context and {@code HttpException} accept any {@code int}, and neither
     * this module's {@code SimpleResponseBuilder} nor {@link jakarta.ws.rs.WebApplicationException}
     * range-checks what it is handed. A failure answered 1xx/2xx/3xx — or with a status outside the
     * HTTP range entirely — is a contradiction: the framework would ship a problem document under a
     * status that claims nothing went wrong. Anything that is not a genuine 4xx/5xx therefore becomes
     * 500, the same status a bare {@code fail(Throwable)} produces.
     *
     * @param statusCode the status the Vert.x layer carried on the failure
     * @return {@code statusCode} when it is a 4xx or 5xx, otherwise 500
     */
    private static int errorStatusOrServerError(int statusCode) {
        return statusCode >= 400 && statusCode < 600 ? statusCode : 500;
    }

    /**
     * Routes a failure through the error pipeline and unified response pipeline.
     * Falls back to a bare-metal 500 if the error pipeline itself fails.
     *
     * @param ctx              the current routing context
     * @param cause            the failure to map and send
     * @param errorPipeline    the error mapping pipeline
     * @param responsePipeline the response sending pipeline
     */
    private static void dispatchError(
            RoutingContext ctx, Throwable cause, ErrorPipeline errorPipeline, ResponsePipeline responsePipeline) {
        errorPipeline
                .mapToResponse(ctx, cause)
                .onSuccess(r -> responsePipeline.sendResponse(ctx, r))
                .onFailure(f -> responsePipeline.sendFallback500(ctx, f));
    }

    // --- Factory ---

    /**
     * Factory for creating {@link JaxRsRouterMount} instances. Holds all shared framework
     * services injected once by Dagger and reused across multiple mount instances.
     *
     * <p>Inject this factory into application modules to create one or more JAX-RS mounts
     * without having to declare each individual dependency.
     */
    public static class Factory {

        // --- Shared services (package-private for direct access by JaxRsRouterMount) ---

        final Set<RouterLifecycleHook> routerLifecycleHooks;
        final Set<OperationInterceptor> operationInterceptors;
        final Set<ErrorInterceptor> errorInterceptors;
        final Set<Middleware> middlewares;
        final Set<OperationHandlerContributor> operationHandlerContributors;
        final Set<SecuritySchemeHandler> securitySchemeHandlers;
        final Set<RequestInterceptor> requestInterceptors;
        final RestExceptionMapper restExceptionMapper;
        final ExceptionMapperRegistry exceptionMapperRegistry;
        final Set<dev.vertique.rest.core.response.ResponseProducerBinding<?>> responseProducerBindings;
        final dev.vertique.rest.core.response.ResponseSerializer responseSerializer;
        final RestContextResolution restContextResolution;
        final ParamConversionResolver paramConversionResolver;
        final SecurityPolicyValidator securityPolicyValidator;
        final List<RequestBodyDecoder> sortedDecoders;
        final List<ResponseBodyEncoder> sortedEncoders;
        final HttpConfig httpConfig;
        final JaxRsConfig jaxRsConfig;
        final JsonMapperProfileRegistry jsonMapperProfileRegistry;
        final JsonConfig jsonConfig;
        final boolean authEnabled;
        final @Nullable BeanValidator beanValidator;
        final @Nullable InputObjectProcessor objectProcessor;
        final Set<RestServerRequestEvidenceCapturer> evidenceCapturers;
        final @Nullable ActionRegistry actionRegistry;

        /**
         * File-content verifiers bound in the application graph. The selected validation strategy
         * determines whether these extensions are active for a mount; the factory retains the set so
         * mount creation can warn when bindings would otherwise be silently inactive.
         */
        final Set<FileContentVerifier> fileContentVerifiers;

        /**
         * Registered request-validation strategies (the {@code Set<RequestValidationStrategy>}
         * multibinding). Always contains at least the {@code none} strategy; {@code web-validation} is
         * present when {@code vertique-rest-validation} is on the classpath. The configured strategy
         * (see {@link JaxRsConfig#validationStrategy()}) is resolved against this set by
         * {@link dev.vertique.rest.jaxrs.validation.RequestValidationStrategySelector} when the router is
         * built (slice 9c); selection fails fast when the configured id is not registered.
         */
        final Set<RequestValidationStrategy> validationStrategies;

        /**
         * Optional source of per-operation validation schemas, present when a module providing an
         * {@link OperationSchemaSource} (e.g. {@code vertique-rest-validation}) is included. The
         * {@code web-validation} gate consumes it; under the {@code none} strategy it may be absent.
         */
        final Optional<OperationSchemaSource> operationSchemaSource;

        /**
         * Whether the core action {@link Authorizer} is installed. The {@code Authorizer} is the
         * function the enforcement layer calls to decide the {@code @RequiresAction} gate. Because the
         * {@link ActionRegistry} and the {@code Authorizer} are bound through separate optional seams, a
         * non-default graph can have the registry present while the {@code Authorizer} is absent; the
         * registrar then fails startup for any {@code @RequiresAction} route rather than failing closed
         * per-request (finding W2).
         */
        final boolean authorizerAvailable;

        /**
         * Creates the factory with all shared framework services.
         *
         * @param routerLifecycleHooks         hooks for router creation phases
         * @param operationInterceptors        interceptors for per-operation invocation lifecycle
         * @param errorInterceptors            interceptors for the error mapping pipeline
         * @param middlewares                  auto-registered scoped request handlers
         * @param operationHandlerContributors contributors for per-operation handler chains
         * @param securitySchemeHandlers       OpenAPI security scheme handlers to configure on the router
         * @param requestInterceptors          HTTP-level request/response interceptors (router-wide)
         * @param restExceptionMapper          REST-layer exception mapper for pre-translation
         * @param exceptionMapperRegistry      JAX-RS exception-to-Response mapper
         * @param responseProducerBindings     user-contributed response producer bindings
         * @param responseSerializer           response body serializer
         * @param restContextResolution        coordinator for the {@link RestContextResolution} resolver chain
         * @param paramConversionResolver      the framework parameter-conversion resolver (native registry +
         *                                     JAX-RS provider bridge); threaded into the binding path and used
         *                                     for fail-fast startup validation of declared parameter types
         * @param securityPolicyValidator      optional policy validator; {@code null} when auth module is absent
         * @param authEnforcementCapability    present when the auth enforcement runtime is installed; its
         *                                     presence is the typed signal that restrictive security
         *                                     annotations have proper runtime support
         * @param sortedDecoders               priority-sorted request body decoders
         * @param sortedEncoders               priority-sorted response body encoders
         * @param httpConfig                   HTTP server configuration, used to apply {@code maxBodySize}
         *                                     and {@code uploadsDirectory} to the body handler
         * @param jaxRsConfig                  JAX-RS routing configuration (operationId strictness, media type validation mode)
         * @param jsonMapperProfileRegistry    registry of named JSON mapper profiles, used to resolve the
         *                                     effective request-body {@code ObjectMapper} per resource method
         *                                     ({@code @JsonProfile} method/class &rarr; {@code jaxrs.jsonProfile}
         *                                     &rarr; {@code json.jsonProfile} &rarr; {@code vertx}); always present via
         *                                     {@link dev.vertique.json.JsonRuntimeModule} (FR-JSON-007B)
         * @param jsonConfig                  global JSON configuration; supplies the {@code json.jsonProfile}
         *                                     default used when a resource method and {@code jaxrs.jsonProfile}
         *                                     both select no profile; always present via
         *                                     {@link dev.vertique.json.JsonRuntimeModule}
         * @param beanValidator                optional Bean Validation implementation; present when {@code ValidationModule} is included
         * @param objectProcessor              optional input object processor for canonicalization and sanitization;
         *                                     present when a module providing {@code InputObjectProcessor} is included
         * @param evidenceCapturers            set of {@link RestServerRequestEvidenceCapturer} instances
         *                                     contributed via Dagger multibinding; empty when no audit
         *                                     adapter is installed — the capturer loop is a pure no-op
         * @param actionRegistry               optional framework {@link ActionRegistry}; present when the
         *                                     authorization engine is installed. Used to validate
         *                                     {@code @RequiresAction} values at startup; when empty, any
         *                                     operation declaring {@code @RequiresAction} fails startup
         * @param authorizer                   optional core action {@link Authorizer}; present when the
         *                                     authorization engine is installed. Its presence is the
         *                                     signal that the {@code @RequiresAction} gate can actually be
         *                                     enforced. Because the {@link ActionRegistry} and the
         *                                     {@code Authorizer} are bound through separate optional seams,
         *                                     a non-default graph can have the registry present while the
         *                                     {@code Authorizer} is absent; when empty, any operation
         *                                     declaring {@code @RequiresAction} fails startup (fail-closed,
         *                                     finding W2)
         * @param fileContentVerifiers         file-content verifier extensions bound in the application
         *                                     graph; used to warn when the selected validation strategy
         *                                     does not run file verifiers
         * @param validationStrategies         the registered request-validation strategies (the
         *                                     {@code Set<RequestValidationStrategy>} multibinding); always
         *                                     contains at least the {@code none} strategy. The configured
         *                                     strategy is resolved against this set by id when the router
         *                                     is built (slice 9c)
         * @param operationSchemaSource        optional source of per-operation validation schemas, present
         *                                     when a module providing an {@link OperationSchemaSource}
         *                                     (e.g. {@code vertique-rest-validation}) is included
         */
        @Inject
        public Factory(
                Set<RouterLifecycleHook> routerLifecycleHooks,
                Set<OperationInterceptor> operationInterceptors,
                Set<ErrorInterceptor> errorInterceptors,
                Set<Middleware> middlewares,
                Set<OperationHandlerContributor> operationHandlerContributors,
                Set<SecuritySchemeHandler> securitySchemeHandlers,
                Set<RequestInterceptor> requestInterceptors,
                RestExceptionMapper restExceptionMapper,
                ExceptionMapperRegistry exceptionMapperRegistry,
                Set<dev.vertique.rest.core.response.ResponseProducerBinding<?>> responseProducerBindings,
                dev.vertique.rest.core.response.ResponseSerializer responseSerializer,
                RestContextResolution restContextResolution,
                ParamConversionResolver paramConversionResolver,
                @Nullable SecurityPolicyValidator securityPolicyValidator,
                Optional<AuthEnforcementCapability> authEnforcementCapability,
                List<RequestBodyDecoder> sortedDecoders,
                List<ResponseBodyEncoder> sortedEncoders,
                HttpConfig httpConfig,
                JaxRsConfig jaxRsConfig,
                JsonMapperProfileRegistry jsonMapperProfileRegistry,
                JsonConfig jsonConfig,
                Optional<BeanValidator> beanValidator,
                Optional<InputObjectProcessor> objectProcessor,
                Set<RestServerRequestEvidenceCapturer> evidenceCapturers,
                Optional<ActionRegistry> actionRegistry,
                Optional<Authorizer> authorizer,
                Set<FileContentVerifier> fileContentVerifiers,
                Set<RequestValidationStrategy> validationStrategies,
                Optional<OperationSchemaSource> operationSchemaSource) {
            this.routerLifecycleHooks = routerLifecycleHooks;
            this.operationInterceptors = operationInterceptors;
            this.errorInterceptors = errorInterceptors;
            this.middlewares = middlewares;
            this.operationHandlerContributors = operationHandlerContributors;
            this.securitySchemeHandlers = securitySchemeHandlers;
            this.requestInterceptors = requestInterceptors;
            this.restExceptionMapper = restExceptionMapper;
            this.exceptionMapperRegistry = exceptionMapperRegistry;
            this.responseProducerBindings = responseProducerBindings;
            this.responseSerializer = responseSerializer;
            this.restContextResolution = restContextResolution;
            this.paramConversionResolver = paramConversionResolver;
            this.securityPolicyValidator = securityPolicyValidator;
            this.sortedDecoders = sortedDecoders;
            this.sortedEncoders = sortedEncoders;
            this.httpConfig = httpConfig;
            this.jaxRsConfig = jaxRsConfig;
            this.jsonMapperProfileRegistry = jsonMapperProfileRegistry;
            this.jsonConfig = jsonConfig;
            this.authEnabled = authEnforcementCapability.isPresent();
            this.beanValidator = beanValidator.orElse(null);
            this.objectProcessor = objectProcessor.orElse(null);
            this.evidenceCapturers = evidenceCapturers;
            this.actionRegistry = actionRegistry.orElse(null);
            this.authorizerAvailable = authorizer.isPresent();
            this.fileContentVerifiers = fileContentVerifiers;
            this.validationStrategies = validationStrategies;
            this.operationSchemaSource = operationSchemaSource;
        }

        /**
         * Creates a new {@link JaxRsRouterMount} with the default priority of {@code 1000}.
         *
         * @param mountPath   the path prefix where the sub-router is mounted (e.g. {@code "/*"})
         * @param openapiPath classpath location of the OpenAPI spec (e.g. {@code "openapi.json"})
         * @param resources   JAX-RS annotated resource instances
         * @return a configured mount instance
         */
        public JaxRsRouterMount create(String mountPath, String openapiPath, Set<Object> resources) {
            return new JaxRsRouterMount(mountPath, openapiPath, resources, 1000, this);
        }

        /**
         * Creates a new {@link JaxRsRouterMount} with an explicit priority.
         *
         * @param mountPath   the path prefix where the sub-router is mounted (e.g. {@code "/*"})
         * @param openapiPath classpath location of the OpenAPI spec (e.g. {@code "openapi.json"})
         * @param resources   JAX-RS annotated resource instances
         * @param priority    mount priority (lower values are mounted first by {@link HttpVerticle})
         * @return a configured mount instance
         */
        public JaxRsRouterMount create(String mountPath, String openapiPath, Set<Object> resources, int priority) {
            return new JaxRsRouterMount(mountPath, openapiPath, resources, priority, this);
        }
    }
}
