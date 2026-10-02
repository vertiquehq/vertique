// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.events.RequestCompletionRecorder;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityPolicyViolation;
import dev.vertique.rest.core.security.SecurityPolicyViolationException;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperation;
import dev.vertique.rest.jaxrs.synthetic.SyntheticOperations;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.HttpException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Package-private {@link SyntheticOperations} implementation, bound by {@code @Binds} in
 * {@link RestModule}. Reuses {@link JaxRsRouterMount.Factory}'s shared services and the registrar's
 * shared helpers, so a synthetic route runs exactly the chain an equally annotated JAX-RS resource
 * method gets.
 *
 * <p>For each operation it builds the synthetic security annotations of the equally annotated
 * resource method ({@link SyntheticOperationDescriptor#securityAnnotations}), resolves the security
 * policy from them through the scanner's own {@link SecurityPolicyBuilder} and the requirement sets
 * through the scanner's own {@link SecuritySchemeAnnotationScanner}, and hands contributors a {@link
 * SyntheticOperationDescriptor} that reports those same annotations. Every check a resource route
 * would fail on, plus a duplicate operation id on the same router, runs before the router gains any
 * route, and fails with a {@link RestConfigurationException} whose message starts with the
 * operation's origin. A contributor or Vert.x failing while the route is built fails the same way,
 * with that failure as the cause, after the partial route has been removed from the router again and
 * the operation id released, so the router is left exactly as before and a corrected installation of
 * the same id can succeed.
 *
 * <p>The route then carries, in order: the completion recorder (a platform handler recording the
 * synthetic descriptor as the request's route identity, so a request rejected by authentication or
 * authorization still completes as a REST operation completion carrying that descriptor), the
 * scheme's authentication handler ({@link
 * JaxRsRouteRegistrar#applySecurity}), every registered contributor in {@link
 * JaxRsRouterMount.Factory#sortedOperationHandlerContributors() resource order} with the effective
 * policy and no required action ({@link JaxRsRouteRegistrar#contributeOperationHandlers}), the
 * caller's terminal handler, and a failure handler that ends every failure with a problem body and
 * {@code Cache-Control: no-store} and never continues. That body deliberately bypasses the
 * application's {@link ErrorPipeline}. Router lifecycle hooks, API-scoped middleware, request
 * interceptors, and mount customizers of JAX-RS mounts never run for a synthetic route.
 *
 * <p>Singleton state, one entry per router, held weakly so a discarded router is not retained: the
 * authentication handlers its scheme handlers collected, configured once on the router's first
 * installation through {@link JaxRsRouterMount#configureSecuritySchemes}, and the operation ids
 * installed on it.
 */
@Slf4j
@Singleton
final class SyntheticOperationInstaller implements SyntheticOperations {

    private final JaxRsRouterMount.Factory factory;
    private final SecurityPolicyBuilder policyBuilder = new SecurityPolicyBuilder();
    private final Map<Router, RouterState> routerStates = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Creates the installer over the shared framework services the factory holds.
     *
     * @param factory the JAX-RS router mount factory, whose scheme handlers, contributors, security
     *                policy validator, and authentication-enforcement flag this installer reuses
     */
    @Inject
    SyntheticOperationInstaller(JaxRsRouterMount.Factory factory) {
        this.factory = factory;
    }

    @Override
    public void install(
            Router router,
            String path,
            List<HttpMethod> methods,
            SyntheticOperation operation,
            Handler<RoutingContext> terminal) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(terminal, "terminal");
        if (methods.isEmpty()) {
            throw new IllegalArgumentException("methods must name at least one HTTP method");
        }
        for (HttpMethod method : methods) {
            Objects.requireNonNull(method, "methods must not contain a null method");
        }

        String origin = operation.origin();
        String operationId = operation.operationId();
        RouterState state = prefixed(origin, () -> stateFor(router));

        // The annotations an equally annotated resource method declares; the policy, the requirement
        // sets, and the descriptor's reported annotations all come from this one list.
        List<Annotation> annotations = SyntheticOperationDescriptor.securityAnnotations(operation);
        if (policyBuilder.hasEmptyRolesAllowed(List.of(), annotations)) {
            throw rejected(
                    origin,
                    new SecurityPolicyViolationException(List.of(new SecurityPolicyViolation(
                            operationId,
                            SecurityPolicyViolation.ViolationType.EMPTY_ROLES_ALLOWED,
                            "the operation allows an empty role list; name at least one role"))));
        }
        for (String role : operation.rolesAllowed().orElse(List.of())) {
            if (role.isBlank()) {
                throw rejected(origin, "operation '" + operationId + "' allows a blank role; every role must be named");
            }
        }
        SecurityPolicy policy = policyBuilder.buildSecurityPolicy(List.of(), annotations);
        List<SecurityRequirementSet> requirementSets =
                prefixed(origin, () -> SecuritySchemeAnnotationScanner.effectiveRequirements(annotations, List.of()));
        SyntheticOperationDescriptor descriptor = new SyntheticOperationDescriptor(
                operationId,
                methods.get(0).name(),
                path,
                annotations,
                policy,
                requirementSets,
                operation.applicationName());

        // The always-on policy-shape gate, exactly as for a resource route.
        SecurityPolicy effectivePolicy = prefixed(origin, descriptor::effectiveSecurityPolicy);
        if (factory.securityPolicyValidator != null) {
            List<SecurityPolicyViolation> violations =
                    factory.securityPolicyValidator.validate(descriptor, descriptor.securityPolicy());
            if (!violations.isEmpty()) {
                throw rejected(origin, new SecurityPolicyViolationException(violations));
            }
        }
        List<RouteRegistrationViolation> authViolations =
                RouteValidator.checkSecurityWithoutAuth(Map.of(operationId, effectivePolicy), factory.authEnabled);
        if (!authViolations.isEmpty()) {
            throw rejected(origin, new RouteRegistrationException(authViolations));
        }
        requireAuthenticationHandler(origin, operation.schemeName(), state.securityHandlers());
        if (!state.operationIds().add(operationId)) {
            throw rejected(
                    origin,
                    "operation id '" + operationId + "' is already installed on this router; "
                            + "each synthetic operation id may be installed once per router");
        }

        // Contributors and Vert.x's handler-ordering checks can only fail once the route exists. Such a
        // failure takes the partial route off the router again, so no half-built chain can serve a
        // request, releases the operation id so a corrected installation can succeed, and reports it
        // with the operation's origin like every check above.
        Route route = null;
        try {
            route = router.route(path);
            for (HttpMethod method : methods) {
                route.method(method);
            }
            // The route's first handler records this operation as the request's route identity, ahead
            // of authentication, so a request rejected by authentication or authorization still
            // completes as an operation completion carrying this descriptor. It is a platform handler,
            // the only handler type Vert.x lets precede the authentication handler(s).
            route.handler(RequestCompletionRecorder.operationRouteHandler(descriptor));
            JaxRsRouteRegistrar.applySecurity(operationId, route, requirementSets, state.securityHandlers());
            JaxRsRouteRegistrar.contributeOperationHandlers(
                    route,
                    operationId,
                    descriptor,
                    effectivePolicy,
                    Optional.empty(),
                    factory.sortedOperationHandlerContributors());
            route.handler(terminal);
            route.failureHandler(new ProblemFailureHandler(operationId));
        } catch (RuntimeException e) {
            if (route != null) {
                route.remove();
            }
            state.operationIds().remove(operationId);
            throw rejected(origin, e);
        }
    }

    /**
     * Returns the router's state, creating it on the router's first installation: every registered
     * scheme handler is configured then, and never again for that router.
     *
     * @param router the router being installed on
     * @return the router's state
     * @throws RestConfigurationException if two scheme handlers register the same scheme
     */
    private RouterState stateFor(Router router) {
        return routerStates.computeIfAbsent(
                router,
                ignored -> new RouterState(
                        JaxRsRouterMount.configureSecuritySchemes(factory.securitySchemeHandlers),
                        ConcurrentHashMap.newKeySet()));
    }

    /**
     * Fails unless the operation's scheme has a registered handler that collected an authentication
     * handler, naming the scheme either way.
     *
     * @param origin           the operation's origin, prefixed to the message
     * @param schemeName       the operation's security scheme
     * @param securityHandlers the authentication handlers the router's scheme handlers collected
     * @throws RestConfigurationException if the scheme has no collected authentication handler
     */
    private void requireAuthenticationHandler(
            String origin, String schemeName, SecuritySchemeHandlerCollector securityHandlers) {
        if (securityHandlers.handlerFor(schemeName).isPresent()) {
            return;
        }
        boolean handlerRegistered = factory.securitySchemeHandlers.stream()
                .map(SecuritySchemeHandler::schemeName)
                .anyMatch(schemeName::equals);
        throw rejected(
                origin,
                handlerRegistered
                        ? "the SecuritySchemeHandler for security scheme '" + schemeName
                                + "' registered no authentication handler; a protected operation must always "
                                + "have an authentication handler (fail-closed)"
                        : "no SecuritySchemeHandler is registered for security scheme '" + schemeName
                                + "'; a protected operation must name a configured scheme (fail-closed)");
    }

    /**
     * Runs {@code step}, prefixing the operation's origin to any configuration failure it raises.
     *
     * @param origin the operation's origin
     * @param step   the step to run
     * @param <T>    the step's result type
     * @return the step's result
     * @throws RestConfigurationException the step's failure, its message prefixed by {@code origin}
     */
    private static <T> T prefixed(String origin, Supplier<T> step) {
        try {
            return step.get();
        } catch (RestConfigurationException e) {
            throw rejected(origin, e);
        }
    }

    /**
     * Builds the failure for a rejected operation.
     *
     * @param origin the operation's origin, the message's prefix
     * @param detail what the operation got wrong
     * @return the failure to throw
     */
    private static RestConfigurationException rejected(String origin, String detail) {
        return new RestConfigurationException(origin + ": " + detail);
    }

    /**
     * Builds the failure for an operation rejected by a shared check, or by a contributor or Vert.x
     * while its route was being built, keeping that failure, with any structured violations, as the
     * cause.
     *
     * @param origin the operation's origin, the message's prefix
     * @param cause  the shared check's, contributor's, or Vert.x's failure
     * @return the failure to throw
     */
    private static RestConfigurationException rejected(String origin, RuntimeException cause) {
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return new RestConfigurationException(origin + ": " + detail, cause);
    }

    /**
     * One router's installer state.
     *
     * @param securityHandlers the authentication handlers the router's scheme handlers collected
     * @param operationIds     the synthetic operation ids installed on the router
     */
    private record RouterState(SecuritySchemeHandlerCollector securityHandlers, Set<String> operationIds) {}

    /**
     * A synthetic route's failure handler: ends every failure with an {@code application/problem+json}
     * body {@code {"type":"about:blank","title":<reason phrase>,"status":<code>}} and {@code
     * Cache-Control: no-store}, and never continues to a later failure handler, route, or mount. A
     * failure status outside 4xx and 5xx answers 500. A response already under way is reset, since
     * no problem body can follow it.
     */
    private static final class ProblemFailureHandler implements Handler<RoutingContext> {

        private final String operationId;

        ProblemFailureHandler(String operationId) {
            this.operationId = operationId;
        }

        @Override
        public void handle(RoutingContext ctx) {
            HttpServerResponse response = ctx.response();
            if (response.ended()) {
                return;
            }
            if (response.headWritten()) {
                response.reset();
                return;
            }
            Throwable failure = ctx.failure();
            int status =
                    failure instanceof HttpException httpException ? httpException.getStatusCode() : ctx.statusCode();
            if (status < 400 || status >= 600) {
                status = 500;
            }
            if (status >= 500) {
                log.error("Synthetic operation {} failed with status {}", operationId, status, failure);
            }
            String body = new JsonObject()
                    .put("type", "about:blank")
                    .put("title", HttpResponseStatus.valueOf(status).reasonPhrase())
                    .put("status", status)
                    .encode();
            response.setStatusCode(status)
                    .putHeader("Content-Type", "application/problem+json")
                    .putHeader("Cache-Control", "no-store")
                    .end(body);
        }
    }
}
