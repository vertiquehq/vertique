// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.JsonMapperProfileRegistry;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.capture.RestServerRequestEvidenceCapturer;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.convert.ParamConversionResolver;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import dev.vertique.rest.core.request.MediaType;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.routing.SecurityRequirement;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.DeferredCredentialRejectionAuthHandler;
import dev.vertique.rest.core.security.RequiresActionResolver;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.core.sse.SseEvent;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.request.BoundRequest;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.runtime.BeanParamFieldMeta;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.ChainAuthHandler;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.EntityPart;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans JAX-RS annotated resource classes and registers handlers on a plain Vert.x {@link Router}.
 *
 * <p>Delegates resource scanning to {@link ResourceScanner}, route validation to {@link
 * RouteValidator}, security annotation interpretation to {@link SecurityPolicyBuilder}, and JAX-RS
 * path-template translation to {@link JaxRsPathTemplate}.
 *
 * <p>Routes are registered <strong>most-specific-first</strong> (see {@link RoutePathSpecificity}).
 * A plain Vert.x {@link Router} evaluates routes of equal {@code order} in <em>add order</em> and runs
 * the first match, so a path-parameter route added before a more-specific static route would shadow it
 * (e.g. {@code GET /hello/{name}} would match {@code /hello/secured}, bypassing the secured route's
 * authentication handler). Sorting by path specificity before the registration loop registers the
 * more-specific path first so it wins Vert.x match precedence — restoring the static-before-parameter
 * ordering the pre-rewrite OpenAPI router supplied automatically.
 *
 * <p>For each discovered operation the per-route handler chain is, in order:
 * <ol>
 *   <li>The collected authentication handler(s) for the schemes in the operation's
 *       {@link JaxRsOperationDescriptor#securityRequirementSets()}. These are Vert.x
 *       {@code AuthenticationHandler}s and must be added to the route <em>first</em>: Vert.x
 *       forbids adding an {@code AUTHENTICATION} handler to a route that already carries a
 *       {@code USER} handler, and the {@code @Consumes} check below is a {@code USER} handler.
 *       Multiple alternative requirements are an OpenAPI OR, composed into a single
 *       {@code ChainAuthHandler.any()} (a request satisfying any one is authenticated) rather than
 *       chained sequentially — see {@code applySecurity}.</li>
 *   <li>A {@code @Consumes} 415-check handler — installed only when the operation declares a
 *       non-empty {@code @Consumes} list; rejects requests whose {@code Content-Type} does not
 *       match any declared consume via wildcard-aware {@link MediaType#isCompatible} matching.</li>
 *   <li>The validation gate produced by the selected {@link RequestValidationStrategy} (when present).</li>
 *   <li>The sorted {@link OperationHandlerContributor}s (identity, authorization, etc.).</li>
 *   <li>The terminal {@link ResourceMethodInvoker}.</li>
 * </ol>
 *
 * <p>This add-order also matches the historical runtime order: under the pre-rewrite OpenAPI router,
 * authentication ran ahead of content-type and request-body validation.
 */
@Slf4j
public class JaxRsRouteRegistrar {

    /**
     * Scans all resource instances and registers handlers on a plain {@link Router}.
     *
     * <p>If a {@code securityPolicyValidator} is provided, it is run for every discovered operation.
     * Any violations found cause startup to fail immediately with a {@link
     * SecurityPolicyViolationException}.
     *
     * <p>Route registration violations (duplicate operationIds, multiple body parameters, invalid
     * {@code @RequiresAction}) cause startup to fail with a {@link RouteRegistrationException}.
     *
     * <p>After scanning all resources, {@link MediaTypeValidator} is invoked to check that every
     * declared {@code @Consumes}/{@code @Produces} media type has a matching decoder or encoder. The
     * {@code mediaTypeValidation} mode controls whether mismatches produce a warning, a startup
     * failure, or are skipped entirely.
     *
     * @param resources               JAX-RS annotated resource instances
     * @param apiRouter               the plain Vert.x web router to register routes on
     * @param strategy                the selected request-validation strategy producing the per-operation
     *                                validation gate
     * @param schemaSource            optional source of per-operation validation schemas; when empty an
     *                                {@link OperationSchemas#empty()} collection is passed to the strategy
     * @param securityHandlers        the collected authentication handlers keyed by scheme name, applied
     *                                per the operation's security requirements
     * @param operationInterceptors   sorted list of operation interceptors
     * @param contributors            sorted list of operation handler contributors
     * @param errorPipeline           shared error mapping pipeline
     * @param responsePipeline        unified response pipeline for producing and sending responses
     * @param restContextResolution   coordinator for the {@link RestContextResolution} resolver chain
     * @param paramConversionResolver the framework parameter-conversion resolver; threaded into each
     *                                {@link ResourceMethodInvoker} and used to fail-fast validate that a
     *                                converter exists for every declared conversion-applicable parameter
     * @param securityPolicyValidator optional security policy validator; {@code null} when auth
     *                                module is absent
     * @param authEnabled             whether the auth module is installed
     * @param decoders                priority-sorted list of request body decoders
     * @param encoders                priority-sorted list of response body encoders
     * @param mediaTypeValidation     media type validation mode: {@code "WARN"}, {@code "STRICT"},
     *                                or {@code "OFF"}
     * @param beanValidator           optional Bean Validation implementation; {@code null} when
     *                                {@code ValidationModule} is not included — validation is skipped
     * @param objectProcessor         optional input object processor for canonicalization and
     *                                sanitization; {@code null} when input processing is not configured
     * @param evidenceCapturers       pre-sorted list of request-evidence capturers to invoke once
     *                                per request after body materialisation; empty list is the no-op default
     * @param actionRegistry          the framework {@link ActionRegistry} used to validate
     *                                {@code @RequiresAction} values at startup; {@code null} when the
     *                                authz engine is not installed — in which case any operation that
     *                                declares {@code @RequiresAction} fails startup (fail-closed),
     *                                since the action gate could not be enforced
     * @param authorizerAvailable     whether the core action {@link dev.vertique.security.authz.Authorizer}
     *                                is installed; the {@code Authorizer} is the function the enforcement
     *                                layer calls to decide the action gate. Because the
     *                                {@link ActionRegistry} and the {@code Authorizer} are bound through
     *                                separate optional seams, a non-default graph can have the registry
     *                                present while the {@code Authorizer} is absent; an operation that
     *                                declares {@code @RequiresAction} then fails startup (fail-closed)
     *                                rather than failing closed per-request when the gate evaluates
     *                                (finding W2)
     * @param jaxRsConfig             JAX-RS routing configuration; supplies the default request JSON
     *                                profile ({@link JaxRsConfig#jsonProfile()}, config key
     *                                {@code jaxrs.jsonProfile}) used when a resource method selects no
     *                                profile of its own
     * @param jsonMapperProfileRegistry registry of named JSON mapper profiles; used to resolve the
     *                                effective request-body {@code ObjectMapper} per resource method
     *                                ({@code @JsonProfile} method/class &rarr; config &rarr; {@code vertx}).
     *                                Resolving an unknown profile id fails startup (fail-fast, FR-JSON-008)
     * @param jsonConfig             global JSON configuration; supplies the {@code json.jsonProfile} default
     *                                applied when a resource method and {@code jaxrs.jsonProfile} both select
     *                                no profile of their own
     */
    public void registerAll(
            Set<Object> resources,
            Router apiRouter,
            RequestValidationStrategy strategy,
            Optional<OperationSchemaSource> schemaSource,
            SecuritySchemeHandlerCollector securityHandlers,
            List<OperationInterceptor> operationInterceptors,
            List<OperationHandlerContributor> contributors,
            ErrorPipeline errorPipeline,
            ResponsePipeline responsePipeline,
            RestContextResolution restContextResolution,
            ParamConversionResolver paramConversionResolver,
            SecurityPolicyValidator securityPolicyValidator,
            boolean authEnabled,
            List<RequestBodyDecoder> decoders,
            List<ResponseBodyEncoder> encoders,
            String mediaTypeValidation,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            List<RestServerRequestEvidenceCapturer> evidenceCapturers,
            @Nullable ActionRegistry actionRegistry,
            boolean authorizerAvailable,
            JaxRsConfig jaxRsConfig,
            JsonMapperProfileRegistry jsonMapperProfileRegistry,
            JsonConfig jsonConfig) {
        List<OperationInterceptor> sortedInterceptors =
                operationInterceptors != null ? Collections.unmodifiableList(operationInterceptors) : List.of();
        List<OperationHandlerContributor> sortedContributors =
                contributors != null ? Collections.unmodifiableList(contributors) : List.of();
        List<SecurityPolicyViolation> securityViolations = new ArrayList<>();
        List<RouteRegistrationViolation> routeViolations = new ArrayList<>();
        Map<String, ResourceMethodMeta> registered = new HashMap<>();
        // Per-operation EFFECTIVE security policy (raw policy with any single-scheme
        // @SecurityRequirement scopes folded in). Captured here so the auth-absent check below can
        // evaluate the effective policy — a scoped @SecurityRequirement is restrictive in the
        // effective policy while the raw policy is not (finding C2 / SH-4).
        Map<String, SecurityPolicy> effectivePolicies = new HashMap<>();
        List<ResourceMethodMeta> allMethods = new ArrayList<>();
        // One wire → Java name projection per body mapper for this router build, so routes sharing a
        // mapper (every route on the reserved vertx profile, typically the whole application) share one
        // per-type projection cache instead of introspecting each body type once per route. Scoped to
        // this build and discarded with it: a static mapper-keyed cache would outlive the router.
        Map<ObjectMapper, JacksonFieldNameResolver> bodyNameResolvers = new IdentityHashMap<>();

        ResourceScanner scanner = new ResourceScanner(new SecurityPolicyBuilder());
        RequiresActionResolver requiresActionResolver = new RequiresActionResolver();

        // Phase 1 — scan every resource, accumulating all discovered methods (and any security-policy
        // scan violations) before any route is registered. Scanning is decoupled from registration so
        // the registration order can be chosen by path specificity in phase 2.
        for (Object resource : resources) {
            allMethods.addAll(scanner.scanResource(resource, securityViolations));
        }

        // Phase 2 — register routes MOST-SPECIFIC-FIRST. Vert.x evaluates routes of equal order in add
        // order and runs the first match, so a path-parameter route added before a more-specific static
        // route would SHADOW it (e.g. GET /hello/{name} would match /hello/secured, bypassing the
        // secured route's authentication handler). Sorting by RoutePathSpecificity here registers the
        // more-specific path first so it wins Vert.x match precedence — restoring the static-before-
        // parameter ordering the pre-rewrite OpenAPI router supplied automatically. The sort is a stable
        // total order (see RoutePathSpecificity) and is independent of HTTP method: method-disjoint
        // routes do not shadow one another, but a single global specificity sort is sufficient and
        // simplest. Everything inside the loop — duplicate-operationId detection, param validation,
        // security-policy validation, @RequiresAction resolution, and the handler add-order — is
        // unchanged from the per-resource form.
        List<ResourceMethodMeta> registrationOrder = new ArrayList<>(allMethods);
        registrationOrder.sort(
                Comparator.comparing(ResourceMethodMeta::path, RoutePathSpecificity.MOST_SPECIFIC_FIRST));

        for (ResourceMethodMeta meta : registrationOrder) {
            // Validate: at most one body parameter; form params and body params are mutually exclusive
            List<RouteRegistrationViolation> paramViolations = RouteValidator.validateMethodParams(meta);
            if (!paramViolations.isEmpty()) {
                routeViolations.addAll(paramViolations);
                continue;
            }

            // Validate: no duplicate operationIds
            Optional<RouteRegistrationViolation> dupViolation =
                    RouteValidator.checkDuplicateOperationId(meta, registered);
            if (dupViolation.isPresent()) {
                routeViolations.add(dupViolation.get());
                continue;
            }

            registered.put(meta.operationId(), meta);

            log.info(
                    "Registering handler for operationId={} [{} {}]",
                    meta.operationId(),
                    meta.httpMethod(),
                    meta.path());

            // Build the neutral descriptor once; the security application, security-policy
            // validation, schema synthesis, and the operation-handler contributors all consume the
            // same instance. Its securityRequirementSets() are the annotation-sourced effective
            // requirements (an OR of single-scheme sets from annotations).
            JaxRsOperationDescriptor descriptor = ResourceMethodMetaToDescriptorAdapter.adapt(meta);

            // PRD-REST-018: fail-fast startup validation. Every declared parameter whose runtime
            // extraction uses string conversion must be resolvable by the FULL conversion chain (native
            // registry + JAX-RS providers). Native multipart form targets are materialized directly by
            // ParameterExtractor and therefore do not require a converter. A conversion-backed type no
            // converter can satisfy would only fail opaquely at request time, so reject it here naming
            // the param and type.
            for (ParamDescriptor param : descriptor.parameters()) {
                if (isNativeMultipartFormParam(param)) {
                    continue;
                }
                var conversionContext = ConversionContexts.forDescriptorConvertibleType(param);
                if (!paramConversionResolver.canResolve(conversionContext)) {
                    routeViolations.add(new RouteRegistrationViolation(
                            meta.operationId(),
                            RouteRegistrationViolation.ViolationType.UNRESOLVABLE_PARAM_CONVERTER,
                            "Parameter '" + param.name() + "' (" + param.location() + ") of type "
                                    + conversionContext.rawType().getName()
                                    + " has no registered ParamConverter, ParamConverterBinding, or "
                                    + "ParamConverterProvider; register one or change the declared type."));
                }
            }

            // PRD-REST-018 (fix H): descriptor.parameters() exposes only TOP-LEVEL path/query/header/
            // cookie/form params — a @BeanParam's own FIELDS are not members of that SPI, so the loop
            // above never sees them. But bean-param fields DO go through this same resolver at request
            // time (ParameterExtractor.materializeBean -> extractScalarParam -> resolver.fromString), so
            // a bean field of an unresolvable type would otherwise pass startup and only fail (opaquely)
            // on the first request. Walk each @BeanParam's convertible fields here too.
            validateBeanParamFields(meta, paramConversionResolver, routeViolations);

            // ALWAYS-ON fail-closed gate (ADR-0124). Compute the operation's effective security policy
            // up front, unconditionally — accessing it runs EffectiveSecurityPolicy.enforceSupportedShape
            // (via the descriptor default), which throws RestConfigurationException for a multi-scheme
            // set, a scoped-OR, or the both-scopes shape. This is independent of the optional
            // DefaultSecurityPolicyValidator: rest-jaxrs does not depend on rest-security, so an app that
            // wires rest-jaxrs WITHOUT rest-security has securityPolicyValidator == null and this gate is
            // the ONLY defense. The single effective policy computed here is reused for the contributors
            // below so the validator and the contributors read one consistent policy. It is also
            // recorded so the post-loop auth-absent check evaluates the EFFECTIVE policy — a scoped
            // @SecurityRequirement folds into a restrictive effective policy while its raw policy is
            // not (finding C2 / SH-4), so checking the raw policy there would let it mount with its
            // scopes silently unenforced.
            SecurityPolicy effectivePolicy = descriptor.effectiveSecurityPolicy();
            effectivePolicies.put(meta.operationId(), effectivePolicy);

            // Create the Vert.x route from the translated JAX-RS path template.
            Route route = createRoute(apiRouter, meta);

            // Run security policy validation
            if (securityPolicyValidator != null) {
                securityViolations.addAll(securityPolicyValidator.validate(descriptor, meta.securityPolicy()));
            }

            // Resolve and startup-validate @RequiresAction (fail-closed on any problem)
            Optional<ActionRef> requiredAction = resolveRequiredAction(
                    meta, requiresActionResolver, actionRegistry, authEnabled, authorizerAvailable, routeViolations);

            // (a) Authentication: install the collected auth handler(s) for the operation's required
            // schemes FIRST. These are Vert.x AuthenticationHandlers; Vert.x rejects adding an
            // AUTHENTICATION handler to a route that already carries a USER handler (the @Consumes check
            // below is a USER handler), so auth must be added ahead of it. This also matches the
            // historical runtime order: authentication ran before content-type/validation under the
            // OpenAPI router. Multiple alternative @SecurityRequirements are an OPENAPI OR (a request
            // satisfying ANY one is authenticated), so they are composed into a ChainAuthHandler.any()
            // rather than chained sequentially (which Vert.x would run as an AND). An operation that
            // declares a security requirement whose scheme has no collected handler fails startup here
            // (fail-closed) rather than mounting with no authentication.
            applySecurity(meta.operationId(), route, descriptor.securityRequirementSets(), securityHandlers);

            // (a-1) Per-route @Consumes 415 check: installed only when the operation declares a
            // non-empty @Consumes list. Added after authentication (Vert.x USER-handler ordering
            // rule) and so runs after auth at request time. Operations with empty consumes() skip
            // this check — the broad API-scoped ContentTypeValidationMiddleware remains the safety
            // net for those routes.
            List<String> consumes = descriptor.consumes();
            if (!consumes.isEmpty()) {
                route.handler(buildConsumesCheckHandler(consumes));
            }

            // (a-2) Resolved request-body JSON profile mapper. Resolve the effective profile mapper for
            // this method ONCE at router-build time (method @JsonProfile -> class @JsonProfile ->
            // jaxrs.jsonProfile -> json.jsonProfile -> vertx). A vertx-effective profile resolves to null, leaving
            // today's default body path unchanged; an unknown configured/annotated id fails startup here
            // (fail-fast, FR-JSON-008). When a non-vertx mapper applies, install a tiny per-route handler
            // that stashes it on the RoutingContext BEFORE the validation gate (b) and the invoker (d):
            // under the default web-validation strategy the gate's validateBody binds (and FIRST-PARSES)
            // the body BEFORE the invoker runs, so stashing the mapper only at the invoker would let the
            // gate first-parse with the lenient Vert.x path and silently bypass the profile's strict
            // parse (FR-JSON-024). Placing the stash ahead of the gate guarantees the profile mapper owns
            // the first parse on every body path (gated or not).
            // JsonConfig is threaded as a method parameter to keep the resolver stateless/static; it is the
            // real injected global JsonConfig wired through the Factory, so the json.jsonProfile tier applies.
            ObjectMapper resolvedBodyMapper = RequestBodyProfileResolver.resolveRequestBodyMapper(
                    meta, jaxRsConfig, jsonConfig, jsonMapperProfileRegistry);
            if (resolvedBodyMapper != null) {
                route.handler(ctx -> {
                    ctx.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, resolvedBodyMapper);
                    ctx.next();
                });
            }

            // (b) Validation gate: produced by the selected strategy from the operation's schemas.
            OperationSchemas schemas =
                    schemaSource.map(source -> source.schemasFor(descriptor)).orElseGet(OperationSchemas::empty);
            Optional<Handler<RoutingContext>> gate = strategy.gateFor(descriptor, schemas);
            gate.ifPresent(route::handler);

            // (c) Operation handler contributors (authorization, security context, etc.) in sorted
            // order, via the plain-Router RouteRegistration over the Vert.x Route. The contributors
            // receive the operation's EFFECTIVE security policy computed above: a single-scheme
            // @SecurityRequirement's scopes are folded into a scope-enforcing Constrained policy
            // (EffectiveSecurityPolicy.fold) so the existing authorization decision point enforces them
            // (finding C2 / SH-4). A scopeless set leaves the annotation-derived policy unchanged. The
            // shapes the fold cannot handle (multi-scheme, scoped-OR, both-scopes) were already rejected
            // by the always-on gate above, so the fold only ever sees the supported single scoped set.
            if (!sortedContributors.isEmpty()) {
                RouteRegistration routeReg = new PlainRouteRegistration(route, descriptor);
                OperationRegistrationContext ctx = new OperationRegistrationContext(
                        meta.operationId(), effectivePolicy, requiredAction, descriptor, routeReg);
                for (OperationHandlerContributor contributor : sortedContributors) {
                    contributor.contribute(ctx);
                }
            }

            // (c-1) Wire → Java name projection for this route's object bodies, composed HERE rather
            // than on the request path. InputFieldNameResolver publishes that an implementation never
            // throws and serves every call from a precomputed projection; composing one runs a full
            // Jackson bean introspection that can also fail on a name collision. Left to the first
            // request, that work would run on an event-loop thread, a collision would surface as a 500
            // instead of the documented startup failure, and — because a ClassValue does not memoise a
            // computeValue that threw — every following request would re-introspect before failing
            // again. Warming only matters when the engine is bound; without it no projection is ever
            // consulted, and any declared policy already failed the composition gate below.
            JacksonFieldNameResolver bodyNameResolver = bodyNameResolvers.computeIfAbsent(
                    resolvedBodyMapper != null ? resolvedBodyMapper : DatabindCodec.mapper(),
                    JacksonFieldNameResolver::forMapper);
            if (objectProcessor != null) {
                warmBodyNameProjection(meta, objectProcessor, bodyNameResolver);
            }

            // (d) Terminal operation invoker. The effective request-body profile mapper resolved at
            // step (a-2) is also handed to the invoker, which reads it for any dispatch path that needs
            // the resolved mapper directly; the per-route handler at (a-2) is what places it on the
            // RoutingContext ahead of the gate.
            route.handler(new ResourceMethodInvoker(
                    meta,
                    sortedInterceptors,
                    errorPipeline,
                    responsePipeline,
                    restContextResolution,
                    decoders,
                    beanValidator,
                    objectProcessor,
                    evidenceCapturers != null ? evidenceCapturers : List.of(),
                    resolvedBodyMapper,
                    paramConversionResolver,
                    bodyNameResolver));

            // (e) Per-route ERROR-body profile decision (FR-JSON-058/058A). This closes the error-path
            // profiling asymmetry: a failure that fires BEFORE the request-path stash at (a-2) runs
            // (auth rejection, @Consumes 415) — or a route whose effective profile is the reserved
            // vertx floor under a NON-vertx global default — would otherwise reach the router-level
            // failure handler with no KEY_RESOLVED_BODY_MAPPER stashed, so the boundary+global default
            // would be applied to the error body instead of the matched route's own decision.
            //
            // A PER-ROUTE failure handler is the only mechanism that reliably identifies the matched
            // route inside a failure: a router-level catch-all failure handler observes
            // ctx.currentRoute() == null for matched-route failures in Vert.x 5.1.2, whereas Vert.x
            // dispatches a route's failure to that SAME route's per-route failure handler (verified by
            // FailureHandlerRouteIdentityCharacterizationIT). This handler runs first, applies the
            // route's build-time decision, marks the decision as taken, and ctx.next()s to the existing
            // router-level handleFailure, which then serializes the error body (chaining verified by
            // FailureHandlerChainProbeIT). The decision is exactly resolvedBodyMapper resolved at (a-2):
            // a non-null profile mapper => stash it (the encoder writes via the profile); a null vertx
            // decision => stash nothing (the encoder falls back to Json.encode). Either way the
            // KEY_ERROR_BODY_MAPPER_DECIDED marker tells handleFailure a matched route already decided,
            // so it must NOT overlay the boundary+global default — preserving an explicit vertx choice.
            final ObjectMapper errorBodyDecision = resolvedBodyMapper;
            route.failureHandler(ctx -> {
                // FIRST-DECISION-WINS idempotency guard. When two operation routes pattern-match the
                // same request path (a static route plus an overlapping {param} route, e.g.
                // /users/me + /users/{id}), Vert.x dispatches the failure through EVERY matching
                // route's failure handler (the ctx.next() chaining proven by
                // FailureHandlerChainProbeIT). Routes are registered MOST-SPECIFIC-FIRST, so the first
                // handler to run belongs to the matched route; once it sets the marker, subsequent
                // overlapping routes' handlers must pass through untouched. Without this short-circuit a
                // less-specific PROFILED route's handler would observe KEY_RESOLVED_BODY_MAPPER == null
                // (left by a more-specific EXPLICIT-vertx route, which stashes nothing) and stash ITS
                // profile mapper, serializing the error body via the wrong route's profile and breaking
                // the explicit-vertx invariant.
                if (Boolean.TRUE.equals(ctx.get(BoundRequest.KEY_ERROR_BODY_MAPPER_DECIDED))) {
                    ctx.next();
                    return;
                }
                if (errorBodyDecision != null && ctx.get(BoundRequest.KEY_RESOLVED_BODY_MAPPER) == null) {
                    ctx.put(BoundRequest.KEY_RESOLVED_BODY_MAPPER, errorBodyDecision);
                }
                ctx.put(BoundRequest.KEY_ERROR_BODY_MAPPER_DECIDED, Boolean.TRUE);
                ctx.next();
            });
        }

        // Auth-absent check: security annotations (or a scoped @SecurityRequirement) without auth
        // module. Evaluates the EFFECTIVE policy captured per operation above, so a scoped
        // @SecurityRequirement — whose raw policy is non-restrictive but whose effective policy is a
        // scope-enforcing Constrained — is rejected when the auth-enforcement capability is absent
        // (finding C2 / SH-4). Action-only routes keep an effective SecurityPolicy.None and are handled
        // by resolveRequiredAction, so they are not double-reported here.
        routeViolations.addAll(RouteValidator.checkSecurityWithoutAuth(effectivePolicies, authEnabled));

        // Validate declared media types against registered decoders/encoders
        MediaTypeValidator.validate(allMethods, decoders, encoders, mediaTypeValidation);

        // Validate SSE endpoints return ReadStream<SseEvent>
        validateSseReturnTypes(allMethods);

        // After all operations: fail fast on any violations
        if (!securityViolations.isEmpty()) {
            throw new SecurityPolicyViolationException(securityViolations);
        }
        if (!routeViolations.isEmpty()) {
            throw new RouteRegistrationException(routeViolations);
        }

        // Composition gate: declared input processing with no engine bound is a configuration error,
        // never a silent no-op.
        checkInputProcessingComposition(allMethods, objectProcessor);

        // Shape gate: a chain declared on a raw binary body cannot run under any graph, so it is
        // rejected whether or not the engine is bound.
        checkBinaryBodyPolicies(allMethods);
    }

    /**
     * Composes the wire &rarr; Java name projection for every body type this route can hand to the
     * input-processing engine, so the request path is served entirely from the precomputed projection.
     *
     * <p>The body parameter is the only source whose values reach the engine keyed by wire names — a
     * {@code @BeanParam}'s intermediate is keyed by the framework's own field names and is processed
     * with {@link InputFieldNameResolver#IDENTITY}, as is every bare-{@code String} parameter — so it
     * is the only source warmed here. Warming is idempotent and shared: routes that materialize their
     * bodies with the same {@link ObjectMapper} share one resolver, so a body type used by many routes
     * is introspected once per router build.
     *
     * <p>The engine owns the walk: {@link InputObjectProcessor#precomputeFieldNameResolution} hands the
     * resolver every owner type the engine's <em>own</em> descent may pass to
     * {@link InputFieldNameResolver#logicalName} for that body type, and the registrar contributes only
     * the declared type. That is what makes the postcondition true rather than approximated — on
     * return, every statically knowable owner reachable from the body type has had its projection
     * composed, including the ones no property-based walk can see (a field with no accessor) and the
     * raw declared classes a shape-mismatched fragment is dispatched against. A boundary that re-derived
     * the descent rules diverged from them in both directions.
     *
     * <p>Two failures therefore move from the request path to registration, which is the point: a
     * projection that cannot be composed, and a reachable type whose policy annotations conflict.
     *
     * @param meta             the resource method whose body types to compose projections for
     * @param objectProcessor  the bound engine, whose descent defines the owner set; must not be
     *                         {@code null}
     * @param bodyNameResolver this route's projection, built from its resolved body mapper
     * @throws dev.vertique.core.exception.ConfigurationException if a reachable owner's projection
     *                                                            cannot be composed
     * @throws IllegalStateException if a reachable type declares conflicting policy annotations
     */
    private static void warmBodyNameProjection(
            ResourceMethodMeta meta, InputObjectProcessor objectProcessor, JacksonFieldNameResolver bodyNameResolver) {
        for (ResourceMethodMeta.ParamMeta param : meta.params()) {
            if (param.source() != ResourceMethodMeta.ParamSource.BODY) {
                continue;
            }
            objectProcessor.precomputeFieldNameResolution(
                    param.genericType() != null ? param.genericType() : param.type(), bodyNameResolver);
        }
    }

    /**
     * Fails startup when a route declares canonicalization or sanitization while no
     * {@link InputObjectProcessor} is bound.
     *
     * <p>The engine binding is optional ({@code RestModule} declares {@code @BindsOptionalOf}), and
     * every consumer null-guards it. Without this gate an application whose routes or DTOs declare
     * {@code @Canonicalize}/{@code @Sanitize} boots and serves requests with none of that processing
     * running — a silent security failure. There is deliberately no opt-out flag: "declared but not
     * running" is not a second legitimate mode, and a warning is not an enforcement mechanism.
     *
     * <p>Both shapes a declaration can take are covered: an invocation-level chain (from the route's
     * own or its parameters' annotations, derived by the same {@link ParameterExtractor} computation
     * the request path uses) and a policy declared inside a parameter's type graph (answered by
     * {@link InputObjectProcessor#declaresPolicies}). Both halves are scoped by the same
     * {@link ParameterExtractor#isProcessedParamSource} filter, so neither can report a policy on a
     * parameter source the engine would never see — telling an operator to install a module that
     * would not make that policy run is worse than saying nothing. Every offending route is collected
     * before throwing, so one startup failure reports the whole surface rather than one route per
     * rebuild.
     *
     * @param methods         every scanned resource method
     * @param objectProcessor the optional input-processing engine; {@code null} when unbound
     * @throws ConfigurationException if any route declares processing that cannot run
     */
    private static void checkInputProcessingComposition(
            List<ResourceMethodMeta> methods, @Nullable InputObjectProcessor objectProcessor) {
        if (objectProcessor != null) {
            return;
        }
        List<String> offendingRoutes = new ArrayList<>();
        for (ResourceMethodMeta meta : methods) {
            String reason = unboundPolicyReason(meta);
            if (reason != null) {
                offendingRoutes.add("  - " + meta.httpMethod() + " " + meta.path() + " (operationId="
                        + meta.operationId() + "): " + reason);
            }
        }
        if (offendingRoutes.isEmpty()) {
            return;
        }
        throw new ConfigurationException(offendingRoutes.size()
                + " route(s) declare input canonicalization or sanitization, but no InputObjectProcessor is bound, "
                + "so none of it would run:\n" + String.join("\n", offendingRoutes)
                + "\nInstall a module providing an InputObjectProcessor (SanitizationModule) in the Dagger "
                + "component, or remove the declared policies.");
    }

    /**
     * Returns why the given route's declared input processing cannot run, or {@code null} when it
     * declares none.
     *
     * @param meta the resource method metadata
     * @return a human-readable reason naming the declaration, or {@code null}
     */
    private static @Nullable String unboundPolicyReason(ResourceMethodMeta meta) {
        if (ParameterExtractor.declaresInvocationPolicies(meta)) {
            return "the route or one of its parameters declares a canonicalizer or sanitizer chain";
        }
        for (ResourceMethodMeta.ParamMeta param : meta.params()) {
            if (!ParameterExtractor.isProcessedParamSource(param.source())) {
                continue;
            }
            Type declaredType = param.genericType() != null ? param.genericType() : param.type();
            if (InputObjectProcessor.declaresPolicies(declaredType)) {
                return "parameter '" + param.name() + "' of type " + declaredType.getTypeName()
                        + " declares input policies on its own fields";
            }
        }
        return null;
    }

    /**
     * Fails startup when a route declares canonicalization or sanitization on a raw binary body.
     *
     * <p>A {@code byte[]} or {@link io.vertx.core.buffer.Buffer} body is opaque bytes; both engine
     * phases operate on string values, of which such a body has none. The declaration therefore
     * cannot run under <em>any</em> Dagger graph — which is why this check is independent of whether
     * the engine is bound, unlike {@link #checkInputProcessingComposition}. Silently skipping it is
     * the failure mode this whole gate exists to remove.
     *
     * <p>The failure names {@code FileContentVerifier} as the control that does apply to binary
     * content, while stating plainly what its contract actually covers, so the operator gets an
     * honest pointer rather than an implied migration that does not exist.
     *
     * @param methods every scanned resource method
     * @throws ConfigurationException if any route declares a chain on a binary body parameter
     */
    private static void checkBinaryBodyPolicies(List<ResourceMethodMeta> methods) {
        List<String> offendingRoutes = new ArrayList<>();
        for (ResourceMethodMeta meta : methods) {
            List<ResourceMethodMeta.ParamMeta> params = meta.params();
            EffectiveInputPolicies[] policies = ParameterExtractor.invocationPolicies(meta);
            for (int i = 0; i < params.size(); i++) {
                ResourceMethodMeta.ParamMeta param = params.get(i);
                if (param.source() != ResourceMethodMeta.ParamSource.BODY
                        || !ParameterExtractor.isBinaryBodyTarget(param.type())
                        || policies[i].isEmpty()) {
                    continue;
                }
                offendingRoutes.add("  - " + meta.httpMethod() + " " + meta.path() + " (operationId="
                        + meta.operationId() + "): parameter '" + param.name() + "' of type "
                        + param.type().getSimpleName() + " is a raw binary body");
            }
        }
        if (offendingRoutes.isEmpty()) {
            return;
        }
        throw new ConfigurationException(offendingRoutes.size()
                + " route(s) declare input canonicalization or sanitization on a raw binary body, which carries no "
                + "string values for a chain to act on, so the declaration could never run:\n"
                + String.join("\n", offendingRoutes)
                + "\nRemove the declared policies from these parameters, or declare @SkipCanonicalization and "
                + "@SkipSanitization on them. The control that does apply to binary content is FileContentVerifier "
                + "— but note its contract covers multipart FileUpload parts "
                + "(Future<FileVerificationResult> verify(FileUpload)), not a raw binary body parameter, so it is a "
                + "pointer rather than a drop-in replacement for what was declared here.");
    }

    /**
     * Fail-fast startup validation (PRD-REST-018, fix H) for {@code @BeanParam} FIELDS.
     *
     * <p>{@link ResourceMethodMetaToDescriptorAdapter#adapt} projects only the resource method's
     * TOP-LEVEL parameters into {@link ParamDescriptor}s — a {@code @BeanParam}'s composite object is
     * itself skipped by {@code isBindableParam} (its source is {@code BEAN_PARAM}, not one of
     * path/query/header/cookie/form), so the caller's loop over {@code descriptor.parameters()} never
     * validates the bean's own fields. At request time those fields DO flow through the same
     * {@link ParamConversionResolver} — {@link ParameterExtractor#materializeBean} calls
     * {@code extractScalarParam} for path/query/header/cookie fields and {@code extractFormParam} for
     * {@code @FormParam} fields. Text/coercion-backed values ultimately route through {@code
     * coerceString} → the resolver, while native multipart form targets are materialized directly. An
     * unresolvable conversion-backed field would otherwise pass startup and only fail (opaquely, as a
     * 400/500) on the first request.
     *
     * <p>Walks every {@code BEAN_PARAM}-sourced top-level parameter in {@code meta.params()}, resolves
     * its bean type's fields via {@link ParameterExtractor#beanParamFields}, and — for each field whose
     * source is conversion-applicable (path/query/header/cookie/form; no other source appears on a bean
     * field) and is not a native multipart form target — probes the resolver exactly as the top-level
     * loop does, naming the bean field (not just the top-level bean parameter) in the violation message.
     *
     * @param meta                    the resource method metadata, supplying the top-level parameter list
     * @param paramConversionResolver the framework parameter-conversion resolver
     * @param routeViolations         the accumulator to which any startup violation is added
     */
    private static void validateBeanParamFields(
            ResourceMethodMeta meta,
            ParamConversionResolver paramConversionResolver,
            List<RouteRegistrationViolation> routeViolations) {
        for (ResourceMethodMeta.ParamMeta pm : meta.params()) {
            if (pm.source() != ResourceMethodMeta.ParamSource.BEAN_PARAM) {
                continue;
            }
            for (ResourceMethodMeta.ParamMeta fieldMeta :
                    fieldParamMetas(ParameterExtractor.beanParamFields(pm.type()))) {
                if (!isConversionApplicableBeanField(fieldMeta.source())) {
                    continue;
                }
                if (isNativeMultipartFormParam(fieldMeta)) {
                    continue;
                }
                var conversionContext = ConversionContexts.forParamMeta(fieldMeta);
                if (!paramConversionResolver.canResolve(conversionContext)) {
                    routeViolations.add(new RouteRegistrationViolation(
                            meta.operationId(),
                            RouteRegistrationViolation.ViolationType.UNRESOLVABLE_PARAM_CONVERTER,
                            "Bean param field '" + fieldMeta.name() + "' (" + fieldMeta.source()
                                    + ") of @BeanParam type " + pm.type().getName() + " has type "
                                    + conversionContext.rawType().getName()
                                    + " with no registered ParamConverter, ParamConverterBinding, or "
                                    + "ParamConverterProvider; register one or change the declared type."));
                }
            }
        }
    }

    /**
     * Projects a list of {@link BeanParamFieldMeta} onto its {@link ResourceMethodMeta.ParamMeta}
     * components.
     *
     * @param fields the bean-param field metadata
     * @return the ordered list of the fields' {@link ResourceMethodMeta.ParamMeta}
     */
    private static List<ResourceMethodMeta.ParamMeta> fieldParamMetas(List<BeanParamFieldMeta> fields) {
        List<ResourceMethodMeta.ParamMeta> result = new ArrayList<>(fields.size());
        for (BeanParamFieldMeta field : fields) {
            result.add(field.meta());
        }
        return result;
    }

    /**
     * Returns whether a {@code @BeanParam} field's source is conversion-applicable — path, query,
     * header, cookie, or form — mirroring the top-level {@code isBindableParam} filter exactly. Native
     * multipart form targets are filtered separately; every remaining form field uses {@link
     * ParameterExtractor#extractFormParam}'s text branch and therefore the same {@link
     * ParamConversionResolver}-backed path as path/query/header/cookie. Only the sources {@code
     * descriptor.parameters()} itself never binds (context/body/bean/uploads) are skipped, matching
     * the top-level loop.
     *
     * @param source the bean field's parameter source
     * @return {@code true} for PATH, QUERY, HEADER, COOKIE, or FORM
     */
    private static boolean isConversionApplicableBeanField(ResourceMethodMeta.ParamSource source) {
        return switch (source) {
            case PATH, QUERY, HEADER, COOKIE, FORM -> true;
            default -> false;
        };
    }

    /**
     * Returns whether a top-level form parameter is materialized natively by
     * {@link ParameterExtractor#extractFormParam} and therefore does not require string conversion.
     *
     * @param param the top-level parameter descriptor
     * @return {@code true} for scalar or collection-element {@link FileUpload}/{@link EntityPart}
     *     form parameters
     */
    private static boolean isNativeMultipartFormParam(ParamDescriptor param) {
        return param.location() == ParamLocation.FORM && isNativeMultipartType(param.type(), param.componentType());
    }

    /**
     * Returns whether a bean-param field is materialized natively by
     * {@link ParameterExtractor#extractFormParam} and therefore does not require string conversion.
     *
     * @param param the bean-field parameter metadata
     * @return {@code true} for scalar or collection-element {@link FileUpload}/{@link EntityPart}
     *     form parameters
     */
    private static boolean isNativeMultipartFormParam(ResourceMethodMeta.ParamMeta param) {
        return param.source() == ResourceMethodMeta.ParamSource.FORM
                && isNativeMultipartType(param.type(), param.componentType());
    }

    private static boolean isNativeMultipartType(Class<?> type, @Nullable Class<?> componentType) {
        return type == FileUpload.class
                || type == EntityPart.class
                || componentType == FileUpload.class
                || componentType == EntityPart.class;
    }

    /**
     * Creates the Vert.x {@link Route} for a resource method, translating its JAX-RS {@code @Path}
     * template via {@link JaxRsPathTemplate}. A plain template registers with
     * {@link Router#route(HttpMethod, String)} (colon form, e.g. {@code :id}); a regex-constrained
     * template registers with {@link Router#routeWithRegex(HttpMethod, String)} (anchored regex with
     * named capture groups, so {@link RoutingContext#pathParams()} still binds by name).
     *
     * @param apiRouter the plain router to register on
     * @param meta      the resource-method metadata (HTTP verb and JAX-RS path)
     * @return the created route
     */
    private static Route createRoute(Router apiRouter, ResourceMethodMeta meta) {
        HttpMethod httpMethod = HttpMethod.valueOf(meta.httpMethod());
        JaxRsPathTemplate template = JaxRsPathTemplate.translate(meta.path());
        return template.isRegex()
                ? apiRouter.routeWithRegex(httpMethod, template.vertxValue())
                : apiRouter.route(httpMethod, template.vertxValue());
    }

    /**
     * Installs the operation's authentication handler(s) on the route, composing alternative security
     * requirements as an OR.
     *
     * <p>Per the OpenAPI specification an operation's {@code security} array is an <strong>OR</strong>:
     * a request satisfying <em>any one</em> entry is authenticated. Swagger-core maps each
     * {@code @SecurityRequirement} annotation to a separate single-scheme entry, so the requirements
     * passed here are an OR of single schemes. Each required scheme's {@link AuthenticationHandler} is
     * collected; then:
     * <ul>
     *   <li>no requirements declared (a public operation) → nothing is installed;</li>
     *   <li>exactly one → it is installed directly on the route;</li>
     *   <li>more than one → they are composed into a {@link ChainAuthHandler#any()} (an OR chain — the
     *       first handler that authenticates wins) and the chain is installed as the single
     *       authentication handler.</li>
     * </ul>
     *
     * <p>Chaining each handler sequentially via {@code route.handler(...)} would instead enforce an
     * <strong>AND</strong> (every handler must pass), rejecting a request that satisfies only one
     * alternative — the defect this method fixes. The installed handler is an
     * {@link AuthenticationHandler}, so it is added <em>before</em> any USER handler (the
     * {@code @Consumes} 415-check), preserving the AUTHENTICATION-before-USER ordering Vert.x requires.
     *
     * <p><strong>Fail-closed gate.</strong> When the operation declares one or more security
     * requirements, <em>every</em> required scheme must resolve to a collected
     * {@link AuthenticationHandler}; if any does not, this method throws a
     * {@link RestConfigurationException} naming the operationId and scheme, failing startup rather than
     * mounting a route that declares a security requirement with no authentication handler installed
     * (a fail-OPEN route). This guard is the authoritative gate and is independent of
     * {@code DefaultSecurityPolicyValidator}: that validator only checks a scheme handler with the
     * scheme name exists in the injected set (not that its {@code configure()} actually registered an
     * {@link AuthenticationHandler}) and is gated by a non-null validator, so it cannot guarantee
     * fail-closed on its own. An operation with no requirements is public and is mounted with no
     * authentication handler.
     *
     * <p><strong>Defensive single-scheme assert.</strong> Each set is treated as single-scheme — its
     * sole scheme's handler is resolved and installed via {@code schemes().get(0)}. The
     * <em>authoritative</em> rejection of multi-scheme (and scoped-OR, and both-scopes) sets is the
     * always-on {@link dev.vertique.rest.core.security.EffectiveSecurityPolicy#enforceSupportedShape}
     * gate the registration loop runs before this method (via {@code descriptor.effectiveSecurityPolicy()}),
     * so a multi-scheme set never reaches {@code applySecurity} on the normal path. This method keeps a
     * minimal multi-scheme assert anyway so it is safe in isolation (it is {@code static} and unit-tested
     * directly): a non-single-scheme set fails with a {@link RestConfigurationException} rather than
     * silently mounting with only its first scheme enforced.
     *
     * <p><strong>OR rejection deferral.</strong> A multi-alternative (OR) operation is mounted as a
     * {@link ChainAuthHandler#any()} wrapped in a
     * {@link dev.vertique.rest.core.security.DeferredCredentialRejectionAuthHandler}. The wrapper arms
     * per-request deferral so an earlier alternative's {@code CredentialRejected} is buffered and
     * emitted only if the whole chain ultimately fails — a request that authenticates via a later
     * alternative records no spurious rejection. Single-scheme routes are mounted without the wrapper
     * and continue to emit rejections immediately.
     *
     * @param operationId      the operation identifier, used in the fail-closed error message
     * @param route            the Vert.x route to install the authentication handler(s) on
     * @param requirementSets  the operation's effective security requirement sets (the OR set of
     *                         alternatives, each a single-scheme set from annotations)
     * @param securityHandlers the collected authentication handlers keyed by scheme name
     * @throws RestConfigurationException if the operation declares a multi-scheme (AND) set, or a
     *     security requirement whose scheme has no collected authentication handler
     */
    static void applySecurity(
            String operationId,
            Route route,
            List<SecurityRequirementSet> requirementSets,
            SecuritySchemeHandlerCollector securityHandlers) {
        if (requirementSets.isEmpty()) {
            // Public operation — no security requirement declared, so no authentication handler.
            return;
        }

        // Fail closed: resolve EACH required scheme's collected handler. A required scheme with no
        // collected handler means the route would mount with no authentication while still declaring a
        // security requirement (fail-OPEN). Reject at startup instead. Every set must be single-scheme:
        // this method extracts schemes().get(0), so a multi-scheme (AND) set would silently mount with
        // only its first scheme enforced. The authoritative multi-scheme rejection is the always-on
        // EffectiveSecurityPolicy.enforceSupportedShape gate the registration loop runs before this
        // method, so a multi-scheme set never reaches here on the normal path; the minimal assert below
        // keeps applySecurity safe in isolation (it is static and unit-tested directly).
        List<AuthenticationHandler> present = new ArrayList<>(requirementSets.size());
        for (SecurityRequirementSet requirementSet : requirementSets) {
            if (!requirementSet.isSingleScheme()) {
                throw new RestConfigurationException("Operation '" + operationId
                        + "' declares a multi-scheme @SecurityRequirement (combine() AND-group), "
                        + "which is not yet supported. Declare a single scheme per requirement.");
            }
            SecurityRequirement requirement = requirementSet.schemes().get(0);
            AuthenticationHandler handler = securityHandlers
                    .handlerFor(requirement.schemeName())
                    .orElseThrow(() ->
                            new RestConfigurationException("Operation '" + operationId + "' declares security scheme '"
                                    + requirement.schemeName()
                                    + "' but no authentication handler is configured for it; a declared "
                                    + "security requirement must always have an authentication handler "
                                    + "(fail-closed)."));
            present.add(handler);
        }

        if (present.size() == 1) {
            route.handler(present.get(0));
            return;
        }
        ChainAuthHandler orChain = ChainAuthHandler.any();
        present.forEach(orChain::add);
        // Wrap the OR chain so each failed alternative's CredentialRejected is buffered and emitted
        // only when the whole chain ultimately fails — a request authenticated by a LATER alternative
        // must not record a spurious rejection from an earlier one. Single-scheme routes (above) are
        // left unwrapped, so their rejections still emit immediately.
        route.handler(new DeferredCredentialRejectionAuthHandler(orChain));
    }

    /**
     * Builds a per-route {@link Handler} that enforces the operation's {@code @Consumes} list for
     * requests that carry a body (POST, PUT, PATCH with a non-zero body). On a mismatch the client
     * receives a clear {@code 415 Unsupported Media Type}. The check is added to the route after the
     * authentication handlers (Vert.x's {@code USER}-after-{@code AUTHENTICATION} ordering rule), so
     * authentication runs first at request time.
     *
     * <p>Matching uses wildcard-aware {@link MediaType#isCompatible}: {@code *}{@code /*} accepts
     * anything, {@code application/*} accepts any application subtype, and a concrete type (e.g.
     * {@code application/json}) performs an exact subtype match (case-insensitive, parameters
     * ignored).
     *
     * <p>A missing or unparseable {@code Content-Type} header is treated as absent — the request
     * does not match any declared consume and is rejected with 415. An absent body (as signalled
     * by {@code Content-Length: 0} and no {@code Transfer-Encoding}) is never checked, consistent
     * with the broad {@code ContentTypeValidationMiddleware} safety net.
     *
     * <p>On mismatch the handler delegates to {@code ctx.fail(415, NotSupportedException)} —
     * exactly mirroring the broad {@link dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware}
     * approach — so both paths produce the same canonical {@code application/problem+json} response
     * shape via the router-level failure handler and the REST error pipeline.
     *
     * @param consumes the non-empty list of declared {@code @Consumes} media types
     * @return the per-route 415-check handler
     */
    private static Handler<RoutingContext> buildConsumesCheckHandler(List<String> consumes) {
        // Pre-parse the declared consume types once at registration time for efficiency.
        List<MediaType> declaredTypes =
                consumes.stream().map(MediaType::parse).filter(mt -> mt != null).toList();

        return ctx -> {
            HttpMethod method = ctx.request().method();
            // Only check body-carrying methods.
            if (method != HttpMethod.POST && method != HttpMethod.PUT && method != HttpMethod.PATCH) {
                ctx.next();
                return;
            }

            // Skip the check when the request has no body (Content-Length: 0 and no chunked encoding).
            String contentLength = ctx.request().getHeader("Content-Length");
            String transferEncoding = ctx.request().getHeader("Transfer-Encoding");
            boolean hasBody = (contentLength != null && !"0".equals(contentLength)) || transferEncoding != null;
            if (!hasBody) {
                ctx.next();
                return;
            }

            // A missing or unparseable Content-Type is treated as absent (no match → 415).
            String rawContentType = ctx.request().getHeader("Content-Type");
            MediaType requestType = MediaType.parse(rawContentType);
            if (requestType != null) {
                for (MediaType declared : declaredTypes) {
                    if (declared.isCompatible(requestType)) {
                        ctx.next();
                        return;
                    }
                }
            }

            // Delegate to ctx.fail() so the failure routes through the router-level failure handler
            // and the REST error pipeline — the same path as ContentTypeValidationMiddleware. This
            // produces a canonical application/problem+json 415 body via WebApplicationException
            // mapping in DefaultExceptionMapper, with no divergent direct-write path.
            String message = "Unsupported Content-Type: "
                    + (rawContentType != null ? rawContentType : "(none)")
                    + "; expected one of " + consumes;
            ctx.fail(415, new jakarta.ws.rs.NotSupportedException(message));
        };
    }

    /**
     * Resolves the {@code @RequiresAction} action gate for an operation and validates it at startup,
     * accumulating any problem into {@code routeViolations} (fail-closed: a problem never defers to
     * first request).
     *
     * <p>Validation rules, in order:
     * <ol>
     *   <li><strong>Policy conflict</strong> — {@code @RequiresAction} together with
     *       {@code @PermitAll} or {@code @DenyAll} (the resolved {@link SecurityPolicy} is a
     *       {@link SecurityPolicy.PermitAll} or {@link SecurityPolicy.DenyAll}) is a contradiction →
     *       {@link RouteRegistrationViolation.ViolationType#REQUIRES_ACTION_POLICY_CONFLICT}; the
     *       action is not surfaced to contributors.</li>
     *   <li><strong>Unparseable value</strong> — a {@code @RequiresAction} value that does not parse
     *       as a canonical {@link ActionRef} →
     *       {@link RouteRegistrationViolation.ViolationType#REQUIRES_ACTION_INVALID}.</li>
     *   <li><strong>No registry</strong> — {@code @RequiresAction} is present but the authz engine
     *       (and thus the {@link ActionRegistry}) is not installed, so the gate cannot be enforced →
     *       {@link RouteRegistrationViolation.ViolationType#REQUIRES_ACTION_INVALID}.</li>
     *   <li><strong>No enforcement pipeline</strong> — {@code @RequiresAction} is present and the
     *       authz engine is installed, but the REST auth-enforcement capability is absent
     *       ({@code authEnabled == false}, i.e. no {@code AuthorizationContributor} /
     *       {@code IdentityResolutionMiddleware}). The action gate handler would never be installed on
     *       the route, so the gate could not be enforced (a silent bypass) →
     *       {@link RouteRegistrationViolation.ViolationType#REQUIRES_ACTION_INVALID}. This complements
     *       {@link RouteValidator#checkSecurityWithoutAuth}, which does not catch action-only routes
     *       because {@link SecurityPolicy.None#isRestrictive()} is {@code false}.</li>
     *   <li><strong>No Authorizer</strong> — {@code @RequiresAction} is present, the engine and the
     *       enforcement pipeline are both installed, but the core action
     *       {@link dev.vertique.security.authz.Authorizer} (the function the enforcement layer calls to
     *       decide the action gate) is absent. The {@link ActionRegistry} and the {@code Authorizer}
     *       are bound through separate optional seams, so a non-default graph can have the registry
     *       present while the {@code Authorizer} is absent; the gate would then fail closed per-request
     *       (the enforcer NPEs on the missing authorizer and denies) instead of the graph being
     *       rejected at boot →
     *       {@link RouteRegistrationViolation.ViolationType#REQUIRES_ACTION_INVALID} (finding W2).</li>
     *   <li><strong>Unregistered action</strong> — a parseable action absent from the
     *       {@link ActionRegistry} →
     *       {@link RouteRegistrationViolation.ViolationType#REQUIRES_ACTION_INVALID}.</li>
     * </ol>
     *
     * @param meta                the discovered resource method metadata
     * @param resolver            the {@code @RequiresAction} resolver
     * @param actionRegistry      the framework action registry, or {@code null} when authz is absent
     * @param authEnabled         whether the REST auth-enforcement capability is installed; when
     *                            {@code false} a present {@code @RequiresAction} fails startup because the
     *                            action gate handler is never installed (fail-closed)
     * @param authorizerAvailable whether the core action {@link dev.vertique.security.authz.Authorizer}
     *                            is installed; when {@code false} a present {@code @RequiresAction} fails
     *                            startup because no {@code Authorizer} would be available to enforce the
     *                            gate (fail-closed, finding W2)
     * @param routeViolations     the accumulator to which any startup violation is added
     * @return the resolved, registered {@link ActionRef}, or {@link Optional#empty()} when the
     *     operation declares no enforceable action gate (including when a violation was recorded)
     */
    private static Optional<ActionRef> resolveRequiredAction(
            ResourceMethodMeta meta,
            RequiresActionResolver resolver,
            @Nullable ActionRegistry actionRegistry,
            boolean authEnabled,
            boolean authorizerAvailable,
            List<RouteRegistrationViolation> routeViolations) {
        List<Annotation> methodAnnotations = meta.methodAnnotations();
        List<Annotation> classAnnotations = meta.classAnnotations();

        // Parse first so an unparseable value is reported as REQUIRES_ACTION_INVALID rather than
        // being masked by the conflict check; absence short-circuits with no work.
        Optional<ActionRef> resolved;
        try {
            resolved = resolver.resolve(methodAnnotations, classAnnotations);
        } catch (IllegalArgumentException e) {
            routeViolations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                    "@RequiresAction on operation '" + meta.operationId() + "' is not a canonical action: "
                            + e.getMessage()));
            return Optional.empty();
        }

        if (resolved.isEmpty()) {
            return Optional.empty();
        }

        // @RequiresAction AND-composes only with @RolesAllowed/@Authorized; pairing it with a
        // blanket @PermitAll/@DenyAll is a conflict (mirrors the compile-time codegen check).
        SecurityPolicy policy = meta.securityPolicy();
        if (policy instanceof SecurityPolicy.PermitAll || policy instanceof SecurityPolicy.DenyAll) {
            routeViolations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_POLICY_CONFLICT,
                    "@RequiresAction on operation '" + meta.operationId() + "' conflicts with "
                            + (policy instanceof SecurityPolicy.PermitAll ? "@PermitAll" : "@DenyAll")
                            + "; @RequiresAction composes only with @RolesAllowed/@Authorized"));
            return Optional.empty();
        }

        ActionRef action = resolved.get();
        if (actionRegistry == null) {
            routeViolations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                    "@RequiresAction('" + action.value() + "') on operation '" + meta.operationId()
                            + "' cannot be enforced: the authorization engine is not installed"));
            return Optional.empty();
        }
        if (!authEnabled) {
            // Engine present but the REST enforcement pipeline (AuthorizationContributor +
            // IdentityResolutionMiddleware) is absent, so the action gate handler is never installed
            // on the route. Fail closed rather than accept an unenforceable gate (silent bypass).
            // checkSecurityWithoutAuth does not catch this case: SecurityPolicy.None.isRestrictive()
            // is false, so an action-only route is invisible to it.
            routeViolations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                    "@RequiresAction('" + action.value() + "') on operation '" + meta.operationId()
                            + "' cannot be enforced: the auth enforcement runtime is not installed. "
                            + "Include AuthModule in your Dagger component to enable security features."));
            return Optional.empty();
        }
        if (!authorizerAvailable) {
            // Engine and enforcement pipeline present, but no core Authorizer is installed to decide the
            // action gate. ActionRegistry and Authorizer are bound through separate optional seams, so
            // this incomplete graph would otherwise pass startup and only fail closed per-request when
            // the enforcer evaluates the gate (NPE on the missing authorizer). Fail closed at boot
            // instead (finding W2).
            routeViolations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                    "@RequiresAction('" + action.value() + "') on operation '" + meta.operationId()
                            + "' requires action '" + action.value()
                            + "' but no Authorizer is installed to enforce it. Include SecurityAuthzModule "
                            + "in your Dagger component to enable the authorization engine."));
            return Optional.empty();
        }
        if (!actionRegistry.contains(action)) {
            routeViolations.add(new RouteRegistrationViolation(
                    meta.operationId(),
                    RouteRegistrationViolation.ViolationType.REQUIRES_ACTION_INVALID,
                    "@RequiresAction('" + action.value() + "') on operation '" + meta.operationId()
                            + "' is not registered in the ActionRegistry"));
            return Optional.empty();
        }
        return resolved;
    }

    /**
     * Validates that all SSE endpoints (those declaring {@code @Produces("text/event-stream")})
     * return {@code ReadStream<SseEvent>} or {@code Future<ReadStream<SseEvent>>}. This prevents
     * endpoints from passing startup media-type validation but failing at runtime when the
     * {@link SseBodyEncoder} encounters a non-stream entity.
     *
     * @param methods the list of discovered resource method metadata to validate
     * @throws RestConfigurationException if any SSE endpoint has an invalid return type
     */
    static void validateSseReturnTypes(List<ResourceMethodMeta> methods) {
        List<String> violations = new ArrayList<>();
        for (ResourceMethodMeta meta : methods) {
            if (meta.mediaTypes() == null) continue;
            boolean isSse = meta.mediaTypes().produces().stream().anyMatch(t -> t.contains("text/event-stream"));
            if (!isSse) continue;

            Type returnType = meta.method().getGenericReturnType();

            // Unwrap Future<T> if present
            Type innerType = returnType;
            if (returnType instanceof ParameterizedType pt
                    && io.vertx.core.Future.class.isAssignableFrom((Class<?>) pt.getRawType())) {
                innerType = pt.getActualTypeArguments()[0];
            }

            // Check: must be ReadStream<SseEvent>
            boolean valid = false;
            if (innerType instanceof ParameterizedType pt) {
                Type rawType = pt.getRawType();
                if (rawType instanceof Class<?> rawClass && ReadStream.class.isAssignableFrom(rawClass)) {
                    Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs.length == 1 && typeArgs[0] == SseEvent.class) {
                        valid = true;
                    }
                }
            }

            if (!valid) {
                violations.add("Operation '" + meta.operationId()
                        + "' declares @Produces(\"text/event-stream\") but returns "
                        + meta.method().getGenericReturnType().getTypeName()
                        + "; SSE endpoints must return ReadStream<SseEvent> or Future<ReadStream<SseEvent>>");
            }
        }
        if (!violations.isEmpty()) {
            throw new RestConfigurationException(
                    "SSE return type validation failed:\n  " + String.join("\n  ", violations));
        }
    }

    /**
     * Scans a single JAX-RS resource instance and returns metadata for all discovered methods.
     * Methods without an HTTP verb annotation are ignored.
     *
     * <p>If any methods have conflicting security annotations, a {@link
     * SecurityPolicyViolationException} is thrown immediately.
     *
     * @param resource the JAX-RS annotated resource instance
     * @return list of method metadata, one entry per discoverable endpoint
     * @throws SecurityPolicyViolationException if any method has conflicting security annotations
     */
    public List<ResourceMethodMeta> scanResource(Object resource) {
        return new ResourceScanner(new SecurityPolicyBuilder()).scanResource(resource);
    }
}
