// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.context.ContextSnapshot;
import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.util.AnnotationResolver;
import dev.vertique.core.validation.BeanValidationException;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.JacksonFieldNameResolver;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.jackson.DatabindCodec;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Registers WebSocket endpoints discovered by {@link WebSocketEndpointScanner} onto a Vert.x
 * {@link Router}. For each endpoint, installs authentication and authorization handlers derived
 * from the endpoint's {@link SecurityPolicy}, then wires the WebSocket upgrade handler and
 * lifecycle callbacks.
 *
 * <p>Security and middleware handlers are only installed when the corresponding optional
 * dependencies ({@link SecurityPolicyEnforcer}, {@link IdentityResolutionMiddleware}) are present,
 * allowing the WebSocket module to operate without the security module.
 *
 * <p><b>Context propagation.</b> On a successful upgrade this registrar:
 * <ol>
 *   <li>Pauses the WebSocket so no frames are delivered before handlers are installed.</li>
 *   <li>Captures a {@link ContextSnapshot} from the HTTP request's current context via
 *       {@link ContextValues#snapshot()} — all bound values (security context, MDC keys, etc.)
 *       are included in the snapshot.</li>
 *   <li>Registers an {@code afterClose} task with the request's {@link RequestContextLifecycle.Handle}
 *       that: binds the snapshot into the WebSocket context, installs frame/close handlers,
 *       invokes {@link OnOpen}, and resumes the socket.</li>
 *   <li>Calls {@link RequestContextLifecycle.Handle#completeNow()} to synchronously run all
 *       {@code onClose} and {@code afterClose} registrations. This explicit completion is required
 *       because Vert.x Web 5.0.8's {@code Http1xServerResponse.completeHandshake()} writes the
 *       101 response without firing the normal response end handler.</li>
 * </ol>
 *
 * <p>The connection close handler closes the session's {@link ContextHolder.Scope} exactly once
 * after the user's {@link OnClose} future settles. An {@link AtomicBoolean} guard defends against
 * double-close on {@code onError}-then-{@code onClose} paths.
 *
 * <p><b>Failure policy.</b> If anything inside the {@code afterClose} task throws <em>or</em> if
 * the user's {@link OnOpen} method returns a failed {@code Future<Void>}, the registrar tears down
 * any partially-bound scope, clears it from the session, logs the error, and closes the WebSocket
 * with close code {@code 1011} (internal server error). Synchronous {@link OnOpen} failures
 * (thrown exceptions) are wrapped into a failed future so both paths share one cleanup routine.
 * {@code ws.resume()} is only called on the success path — a failing {@link OnOpen} guarantees
 * the socket is never resumed. Other {@code afterClose} tasks registered alongside this one are
 * unaffected because {@link RequestContextLifecycle.Handle#closeAll()} wraps each task in its own
 * {@code try/catch}.
 */
@Slf4j
class WebSocketEndpointRegistrar {

    /**
     * RFC 6455 close code 1011 ("Internal Server Error"): used when bootstrap fails after the
     * handshake completed but before frame handlers were installed. Vert.x 5.0.8 does not expose
     * a public {@code WebSocketCloseStatus} enum; the constant is named locally.
     */
    private static final short CLOSE_CODE_INTERNAL_ERROR = 1011;

    private final WebSocketMessageCodec messageCodec;
    private final @Nullable SecurityPolicyEnforcer securityPolicyEnforcer;
    private final @Nullable IdentityResolutionMiddleware identityResolutionMiddleware;
    private final @Nullable SecurityRuntime securityRuntime;
    private final Set<RouteAuthHandler> routeAuthHandlers;
    private final @Nullable BeanValidator beanValidator;
    private final @Nullable InputObjectProcessor objectProcessor;
    private final @Nullable WebSocketChannelAdapter channelAdapter;

    /**
     * The framework action registry used by {@link WebSocketEndpointScanner} to validate a class-level
     * {@code @RequiresAction} at startup, or {@code null} when the authorization engine is absent (any
     * {@code @RequiresAction} then fails startup, fail-closed).
     */
    private final @Nullable ActionRegistry actionRegistry;

    /**
     * The core action {@link Authorizer} that the {@link SecurityPolicyEnforcer} calls to decide a
     * class-level {@code @RequiresAction} gate, or {@code null} when the authorization engine is absent.
     *
     * <p>Because the {@link ActionRegistry} and the {@code Authorizer} are bound through separate
     * optional seams, a non-default graph can have the registry present while the {@code Authorizer} is
     * absent. A {@code @RequiresAction} endpoint in such a graph fails startup (fail-closed) rather than
     * failing closed per-request when the enforcer evaluates the gate (finding W2).
     */
    private final @Nullable Authorizer authorizer;

    /**
     * Wire &rarr; Java property-name projection for object message bodies.
     *
     * <p>{@link WebSocketMessageCodec} binds every message through
     * {@link DatabindCodec#mapper()}, so that is the mapper whose naming decides which declared
     * policies apply: a field renamed by {@code @JsonProperty}, by a naming strategy, or reached
     * through a {@code @JsonAlias} arrives in the intermediate under its wire name, while the
     * input-processing engine keys its per-field metadata on the Java property name. Without this
     * projection a declared {@code @Canonicalize}/{@code @Sanitize} on such a field silently never
     * runs.
     *
     * <p>Created once per registrar, and every declared message type's projection is composed at
     * registration by {@link #warmMessageNameProjections}, so no introspection happens on the message
     * path. The bare-{@code String} call sites keep {@link InputFieldNameResolver#IDENTITY}: there is
     * no object whose fields could be renamed.
     */
    private final JacksonFieldNameResolver messageNameResolver =
            JacksonFieldNameResolver.forMapper(DatabindCodec.mapper());

    /**
     * Creates a new registrar.
     *
     * @param messageCodec                 codec for JSON message deserialization
     * @param securityPolicyEnforcer       optional authorization enforcer; {@code null} when security module is absent
     * @param identityResolutionMiddleware optional identity resolution middleware; {@code null} when security module
     *                                     is absent
     * @param securityRuntime              optional security runtime; {@code null} when security module is absent;
     *                                     used to resolve {@link SecurityContext}-typed lifecycle method parameters
     * @param routeAuthHandlers            set of registered authentication handlers
     * @param beanValidator                optional Bean Validation engine; {@code null} when validation module is absent
     * @param objectProcessor              optional canonicalization/sanitization processor; {@code null} when
     *                                     sanitization module is absent
     * @param channelAdapter               optional channel adapter bridging WebSocket lifecycle to
     *                                     {@link dev.vertique.security.channel.ChannelIdentityManager};
     *                                     {@code null} when the security module is absent
     * @param actionRegistry               optional framework action registry used to validate a class-level
     *                                     {@code @RequiresAction} at startup; {@code null} when the authorization
     *                                     engine is absent (any {@code @RequiresAction} then fails startup)
     * @param authorizer                   optional core action {@link Authorizer} the
     *                                     {@link SecurityPolicyEnforcer} calls to decide a class-level
     *                                     {@code @RequiresAction} gate; {@code null} when the authorization
     *                                     engine is absent. Because the registry and the {@code Authorizer}
     *                                     are bound through separate optional seams, an endpoint declaring
     *                                     {@code @RequiresAction} fails startup (fail-closed) when the
     *                                     registry is present but the {@code Authorizer} is absent (W2)
     */
    WebSocketEndpointRegistrar(
            WebSocketMessageCodec messageCodec,
            @Nullable SecurityPolicyEnforcer securityPolicyEnforcer,
            @Nullable IdentityResolutionMiddleware identityResolutionMiddleware,
            @Nullable SecurityRuntime securityRuntime,
            Set<RouteAuthHandler> routeAuthHandlers,
            @Nullable BeanValidator beanValidator,
            @Nullable InputObjectProcessor objectProcessor,
            @Nullable WebSocketChannelAdapter channelAdapter,
            @Nullable ActionRegistry actionRegistry,
            @Nullable Authorizer authorizer) {
        this.messageCodec = messageCodec;
        this.securityPolicyEnforcer = securityPolicyEnforcer;
        this.identityResolutionMiddleware = identityResolutionMiddleware;
        this.securityRuntime = securityRuntime;
        this.routeAuthHandlers = routeAuthHandlers;
        this.beanValidator = beanValidator;
        this.objectProcessor = objectProcessor;
        this.channelAdapter = channelAdapter;
        this.actionRegistry = actionRegistry;
        this.authorizer = authorizer;
    }

    /**
     * Scans and registers all provided endpoint instances onto the router.
     *
     * @param endpoints the set of endpoint instances to register
     * @param router    the Vert.x router to mount routes on
     */
    void registerAll(Set<Object> endpoints, Router router) {
        WebSocketEndpointScanner scanner = new WebSocketEndpointScanner(actionRegistry);
        List<WebSocketEndpointMeta> metas = new ArrayList<>(endpoints.size());
        for (Object endpoint : endpoints) {
            metas.add(scanner.scan(endpoint));
        }
        checkInputProcessingComposition(metas);
        warmMessageNameProjections(metas);
        for (WebSocketEndpointMeta meta : metas) {
            registerEndpoint(meta, router);
        }
    }

    /**
     * Composes the wire &rarr; Java name projection for every declared message type at registration, so
     * the message path is served entirely from the precomputed projection.
     *
     * <p>{@link InputFieldNameResolver} publishes that an implementation never throws and serves every
     * call from a precomputed projection; composing one runs a full Jackson bean introspection that can
     * also fail on a name collision. Left to the first message, that work would run on an event-loop
     * thread, a collision would surface as a per-message failure instead of a boot failure, and —
     * because a {@link ClassValue} does not memoise a {@code computeValue} that threw — every following
     * message would re-introspect before failing again. Warming only matters when the engine is bound:
     * without it no projection is ever consulted, and any declared policy already failed the
     * composition gate above.
     *
     * <p>The walk is {@link dev.vertique.json.JacksonFieldNameResolver#precomputeGraph} — the same one
     * the JAX-RS registrar uses for body types, rather than a second, narrower one here. It unwraps an
     * array message type to its component, skips a scalar such as the default {@code String} message
     * type (neither carries a property set the engine keys against), and follows each message type's
     * declared property types so a nested DTO is warmed with its owner.
     *
     * @param metas every scanned endpoint's metadata
     * @throws ConfigurationException if a reachable message type's projection cannot be composed
     */
    private void warmMessageNameProjections(List<WebSocketEndpointMeta> metas) {
        if (objectProcessor == null) {
            return;
        }
        for (WebSocketEndpointMeta meta : metas) {
            if (meta.onMessage() != null) {
                messageNameResolver.precomputeGraph(meta.messageType());
            }
        }
    }

    /**
     * Fails startup when an endpoint declares canonicalization or sanitization while no
     * {@link InputObjectProcessor} is bound.
     *
     * <p>{@code WebSocketModule} declares the engine binding optional and every consumer null-guards
     * it, so without this gate an endpoint whose {@code @OnMessage} carries {@code @Sanitize} — or
     * whose message type declares field-level policies — would accept messages with none of that
     * processing running. This mirrors the REST registrar's gate: every offending endpoint is
     * collected before throwing, and there is no opt-out flag.
     *
     * @param metas every scanned endpoint's metadata
     * @throws ConfigurationException if any endpoint declares processing that cannot run
     */
    private void checkInputProcessingComposition(List<WebSocketEndpointMeta> metas) {
        if (objectProcessor != null) {
            return;
        }
        List<String> offendingEndpoints = new ArrayList<>();
        for (WebSocketEndpointMeta meta : metas) {
            String reason = unboundPolicyReason(meta);
            if (reason != null) {
                offendingEndpoints.add(
                        "  - " + meta.path() + " (" + meta.instance().getClass().getName() + "): " + reason);
            }
        }
        if (offendingEndpoints.isEmpty()) {
            return;
        }
        throw new ConfigurationException(offendingEndpoints.size()
                + " WebSocket endpoint(s) declare input canonicalization or sanitization, but no "
                + "InputObjectProcessor is bound, so none of it would run:\n"
                + String.join("\n", offendingEndpoints)
                + "\nInstall a module providing an InputObjectProcessor (SanitizationModule) in the Dagger "
                + "component, or remove the declared policies.");
    }

    /**
     * Returns why the given endpoint's declared input processing cannot run, or {@code null} when it
     * declares none.
     *
     * <p>Policy annotations are resolved exactly as {@link #resolveMethodPolicies} resolves them on
     * the message path — meta-annotation-aware. The two must agree: a gate that saw composed
     * annotations the runtime ignored would fail startup for policies that still would not run, which
     * is worse than not gating them at all.
     *
     * @param meta the scanned endpoint metadata
     * @return a human-readable reason naming the declaration, or {@code null}
     */
    private static @Nullable String unboundPolicyReason(WebSocketEndpointMeta meta) {
        for (Method lifecycleMethod : new Method[] {meta.onOpen(), meta.onMessage(), meta.onClose(), meta.onError()}) {
            if (lifecycleMethod == null) {
                continue;
            }
            if (AnnotationResolver.findMetaAnnotation(lifecycleMethod, Canonicalize.class) != null
                    || AnnotationResolver.findMetaAnnotation(lifecycleMethod, Sanitize.class) != null) {
                return "lifecycle method '" + lifecycleMethod.getName()
                        + "' declares a canonicalizer or sanitizer chain";
            }
        }
        if (meta.onMessage() != null && InputObjectProcessor.declaresPolicies(meta.messageType())) {
            return "message type " + meta.messageType().getName() + " declares input policies on its own fields";
        }
        return null;
    }

    // --- Route registration ---

    /**
     * Registers a single endpoint onto the router, installing security and upgrade handlers.
     *
     * <p>Fails startup (fail-closed) when the endpoint carries a class-level {@code @RequiresAction}
     * but a dependency required to enforce the gate is absent:
     * <ul>
     *   <li>the enforcement pipeline (the {@link SecurityPolicyEnforcer}) is absent — the action gate
     *       handler would never be installed on the route; or</li>
     *   <li>the core action {@link Authorizer} is absent — the enforcer would have no function to call
     *       to decide the gate and would fail closed per-request via an NPE. Because the
     *       {@link ActionRegistry} and the {@code Authorizer} are bound through separate optional seams,
     *       a non-default graph can have the registry present while the {@code Authorizer} is absent
     *       (finding W2).</li>
     * </ul>
     * Accepting the annotation in either case would be a silent authorization bypass or a per-request
     * fail-closed instead of a boot-time rejection (FR-AUTHZ-048, ADR-0115). The scanner already
     * rejects a {@code @RequiresAction} when the authz engine ({@link ActionRegistry}) is absent; these
     * checks cover the complementary cases where the engine is present but the enforcer or the
     * {@code Authorizer} is not.
     *
     * @param meta   the scanned endpoint metadata
     * @param router the Vert.x router
     * @throws IllegalStateException if the endpoint declares {@code @RequiresAction} but no
     *     {@link SecurityPolicyEnforcer} or no {@link Authorizer} is installed to enforce it
     */
    private void registerEndpoint(WebSocketEndpointMeta meta, Router router) {
        if (meta.requiredAction().isPresent() && securityPolicyEnforcer == null) {
            throw new IllegalStateException("@RequiresAction('"
                    + meta.requiredAction().get().value() + "') on WebSocket endpoint "
                    + meta.instance().getClass().getName()
                    + " cannot be enforced: the authorization enforcement pipeline (SecurityPolicyEnforcer) is not"
                    + " installed. Include AuthModule in your Dagger component to enable security features.");
        }
        if (meta.requiredAction().isPresent() && authorizer == null) {
            // Enforcement pipeline present, but no core Authorizer is installed to decide the action
            // gate. ActionRegistry and Authorizer are bound through separate optional seams, so this
            // incomplete graph would otherwise pass startup and only fail closed per-request when the
            // enforcer evaluates the gate (NPE on the missing authorizer). Fail closed at boot (W2).
            throw new IllegalStateException("@RequiresAction('"
                    + meta.requiredAction().get().value() + "') on WebSocket endpoint "
                    + meta.instance().getClass().getName()
                    + " requires action '" + meta.requiredAction().get().value()
                    + "' but no Authorizer is installed to enforce it. Include SecurityAuthzModule in your"
                    + " Dagger component to enable the authorization engine.");
        }

        // Convert {param} placeholders to Vert.x :param syntax for route matching
        String routePath = meta.path().replaceAll("\\{([^/}]+)}", ":$1");
        Route route = router.route(routePath);

        // Handler order matters: authentication must set evidence, then identity resolution must
        // bind the SecurityContext, and only then can authorization evaluate claims against it.
        // 1. Authentication (sets ctx.user(), appends AuthenticationEvidence)
        installAuthenticationHandler(meta, route);

        // 2. Identity resolution (resolves SecurityIdentity, binds SecurityContext)
        if (identityResolutionMiddleware != null) {
            route.handler(identityResolutionMiddleware);
        }

        // 3. Authorization (reads the bound SecurityContext)
        installAuthorizationHandler(meta, route);

        // 4. WebSocket upgrade
        route.handler(ctx -> handleUpgrade(ctx, meta));

        log.info(
                "Registered WebSocket endpoint at {} (class={})",
                meta.path(),
                meta.instance().getClass().getSimpleName());
    }

    /**
     * Installs the authentication handler on the route when the endpoint requires authentication.
     * The auth handler sets {@code ctx.user()} and appends {@link AuthenticationEvidence} so that the
     * downstream identity-resolution middleware can bind a {@code SecurityContext}.
     *
     * <p>Authentication is required when the endpoint's {@link SecurityPolicy} is
     * {@link SecurityPolicy.AuthenticatedOnly} or {@link SecurityPolicy.Constrained}, <strong>or</strong>
     * when the endpoint carries a class-level {@code @RequiresAction} action gate. An action-only
     * endpoint ({@link SecurityPolicy.None} with a present {@code requiredAction}) must still
     * authenticate: without an auth handler no {@link AuthenticationEvidence} is appended, identity
     * resolution falls back to anonymous, and the action gate would evaluate an anonymous identity
     * rather than the real caller (FR-AUTHZ-048, ADR-0115).
     *
     * @param meta  the endpoint metadata containing the security policy, action gate, and auth scheme
     * @param route the route on which to install the handler
     * @throws IllegalStateException if an auth handler is required but none (or multiple) are registered
     */
    private void installAuthenticationHandler(WebSocketEndpointMeta meta, Route route) {
        SecurityPolicy policy = meta.securityPolicy();
        boolean requiresAuthentication = policy instanceof SecurityPolicy.AuthenticatedOnly
                || policy instanceof SecurityPolicy.Constrained
                || meta.requiredAction().isPresent();
        if (requiresAuthentication) {
            RouteAuthHandler authHandler = selectAuthHandler(meta);
            route.handler(authHandler.createHandler());
        }
    }

    /**
     * Installs the authorization enforcer on the route. Must be registered AFTER the identity
     * resolution middleware so the resolved {@code SecurityContext} is bound when the enforcer
     * evaluates the policy.
     *
     * <p>The enforcer composes the role/scope policy with the class-level {@code @RequiresAction}
     * action gate (FR-AUTHZ-048, ADR-0115): both gates are evaluated once at upgrade and the
     * connection is permitted only when both pass. An action-only endpoint
     * ({@link SecurityPolicy.None} with a present {@code requiredAction}) is still enforced.
     *
     * @param meta  the endpoint metadata containing the security policy and action gate
     * @param route the route on which to install the handler
     */
    private void installAuthorizationHandler(WebSocketEndpointMeta meta, Route route) {
        if (securityPolicyEnforcer != null) {
            Handler<RoutingContext> enforcer =
                    securityPolicyEnforcer.createHandler(meta.securityPolicy(), meta.requiredAction());
            if (enforcer != null) {
                route.handler(enforcer);
            }
        }
    }

    /**
     * Selects the appropriate {@link RouteAuthHandler} for the endpoint, taking into account
     * the optional {@link WebSocketEndpoint#authScheme()} selector.
     *
     * @param meta the endpoint metadata
     * @return the selected auth handler; never {@code null}
     * @throws IllegalStateException if no handler matches or if multiple handlers exist without an explicit scheme
     */
    private RouteAuthHandler selectAuthHandler(WebSocketEndpointMeta meta) {
        if (routeAuthHandlers.isEmpty()) {
            throw new IllegalStateException("WebSocket endpoint "
                    + meta.instance().getClass().getSimpleName()
                    + " requires authentication but no RouteAuthHandler is registered");
        }

        String scheme = meta.authScheme();
        if (!scheme.isEmpty()) {
            return routeAuthHandlers.stream()
                    .filter(h -> scheme.equals(h.schemeName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No RouteAuthHandler found for scheme '"
                            + scheme
                            + "' required by "
                            + meta.instance().getClass().getSimpleName()));
        }

        if (routeAuthHandlers.size() == 1) {
            return routeAuthHandlers.iterator().next();
        }

        throw new IllegalStateException("Multiple RouteAuthHandler bindings found ("
                + routeAuthHandlers.stream()
                        .map(RouteAuthHandler::schemeName)
                        .sorted()
                        .toList()
                + "); specify authScheme on @WebSocketEndpoint(\""
                + meta.path()
                + "\") to select one");
    }

    // --- WebSocket upgrade ---

    /**
     * Handles the HTTP upgrade to WebSocket.
     *
     * <p>On success: pauses the socket, captures a {@link ContextSnapshot}, stores it on the
     * session, registers an {@code afterClose} task on the {@link RequestContextLifecycle.Handle}
     * that binds the snapshot and installs frame/close handlers, then drives the lifecycle to
     * completion via {@link RequestContextLifecycle.Handle#completeNow()}.
     *
     * <p>On failure: logs the error and responds with HTTP 400 if the response has not already
     * ended.
     *
     * @param ctx  the Vert.x routing context for the upgrade request
     * @param meta the endpoint metadata (includes the pre-compiled path matcher)
     */
    private void handleUpgrade(RoutingContext ctx, WebSocketEndpointMeta meta) {
        ctx.request()
                .toWebSocket()
                .onSuccess(ws -> {
                    var pathParams = meta.pathMatcher().extractParams(ws.path());
                    DefaultWebSocketSession session = new DefaultWebSocketSession(
                            ws, pathParams, ctx.queryParams(), ctx.request().headers());

                    // --- Generic context snapshot/rebind handoff ---
                    // Gate frames before the afterClose rebind task runs.
                    ws.pause();

                    // Capture all currently-bound holder values from the HTTP request's context.
                    // This snapshot is immutable and isolated from subsequent mutations.
                    ContextSnapshot snapshot = ContextValues.snapshot();
                    session.contextSnapshot(snapshot);

                    // Register the rebind + frame-handler install inside afterClose so it runs
                    // after all request-scope onClose registrations have unwound. This prevents
                    // the request lifecycle's cleanup from removing holder keys that the WebSocket
                    // session just installed.
                    RequestContextLifecycle.Handle lifecycle = RequestContextLifecycle.fromRoutingContext(ctx);
                    lifecycle.afterClose(() -> bootstrapSession(session, ws, meta, snapshot));

                    // Explicitly drive the request lifecycle to completion. Required because
                    // Vert.x Web 5.0.8's Http1xServerResponse.completeHandshake() writes the 101
                    // response and marks the response complete without firing the response end
                    // handler, so the lifecycle's automatic closeAll() would never run.
                    lifecycle.completeNow();
                })
                .onFailure(cause -> {
                    log.warn("WebSocket upgrade failed for {}", meta.path(), cause);
                    if (!ctx.response().ended()) {
                        ctx.response().setStatusCode(400).end();
                    }
                });
    }

    // --- Session bootstrap (inside afterClose task) ---

    /**
     * Binds the snapshot into the WebSocket context, installs frame/close handlers, registers the
     * channel with the {@link WebSocketChannelAdapter} (when present), and — only after registration
     * succeeds — invokes {@link OnOpen} and resumes the paused socket.
     *
     * <p><b>Registration-gated ordering (F-W5).</b> Channel registration is asynchronous. Scope
     * ownership transfer to the binding, the {@link OnOpen} invocation, and {@code ws.resume()} are
     * sequenced onto the registration future's success branch via {@link #invokeOnOpenAndResume}.
     * If registration <em>fails</em>, the bootstrap fails closed: {@link OnOpen} is never invoked,
     * the socket is never resumed, and the socket is closed with code {@code 1011}. The adapter has
     * already released the binding's scope on the failed-register path
     * ({@link WebSocketChannelAdapter#onOpen}), so this method does not close it again — the scope is
     * not orphaned. When the channel adapter is absent (security module not installed) there is no
     * registration step and registration is treated as an immediate success.
     *
     * <p><b>Close-while-registration-pending guard.</b> The close handler is installed before the
     * asynchronous {@code register()} future settles, so the peer can close the socket while
     * registration is still pending. A {@code closedBeforeReady} {@link AtomicBoolean} is flipped by
     * the close handler; the registration-success branch checks it and, when set, fails closed
     * instead of running {@link OnOpen}/{@code ws.resume()} on an already-closed socket: it
     * deregisters (adapter path) or closes (no-adapter path) the now-registered binding so its scope
     * is not orphaned. The close handler's earlier {@code deregister} was a no-op because the channel
     * was not yet registered, so this second deregister is the one that actually releases the
     * binding's scope (the manager's {@code deregister} is idempotent).
     *
     * <p><b>Owner-context marshaling (F-W5 round-4).</b> The entire registration-completion body is
     * re-dispatched onto the WebSocket's owner event-loop context via
     * {@link io.vertx.core.Context#runOnContext} (see {@link #completeRegistration}). A custom
     * asynchronous {@link dev.vertique.security.channel.ChannelIdentityManager} may complete its
     * {@code register()} future from a worker / foreign Vert.x context; the {@code register} SPI
     * promises nothing about completion context. Without the hop the completion body would run on that
     * foreign thread, concurrently with the owner-loop close handler and with no mutual exclusion —
     * Vert.x associates the owner {@link io.vertx.core.Context} object with the callback but still runs
     * it on the completing thread. Marshaling makes the {@code closedBeforeReady}/{@code
     * ws.isClosed()} check-and-act atomic with the close handler, and guarantees {@link OnOpen},
     * {@code ws.resume()}, and every session/scope mutation run on the socket-owner event loop — the
     * same single-threaded discipline {@link WebSocketChannelBinding} already follows.
     *
     * <p>Both synchronous ({@code void}) and asynchronous ({@code Future<Void>}) {@link OnOpen}
     * methods are handled uniformly: synchronous returns are wrapped in
     * {@link Future#succeededFuture()}, and synchronous throws are wrapped in
     * {@link Future#failedFuture(Throwable)}, so there is a single success/failure code path (see
     * {@link #invokeOnOpenAndResume}).
     *
     * <p>This method runs inside the {@code afterClose} task registered on the
     * {@link RequestContextLifecycle.Handle} and is therefore called after all HTTP request
     * scope closes have completed.
     *
     * <p>NOTE: the bootstrap failure paths close {@code sessionScope} directly (or, on the
     * registration-failure path, rely on the adapter having released it) and clear it via
     * {@code session.contextScope(null)} without flipping the {@code scopeClosed} AtomicBoolean that
     * lives in {@link #installFrameAndCloseHandlers}. This is intentional: the close handler's
     * {@code scopeClosed} guard guards {@link #releaseChannelResources}, which only fires on a
     * WebSocket close; on a bootstrap failure the socket is closed with {@code 1011} and the scope is
     * already released/cleared, so the close-handler path is a no-op and there is no double-close.
     *
     * @param session  the session to bootstrap
     * @param ws       the underlying Vert.x WebSocket
     * @param meta     the endpoint metadata
     * @param snapshot the context snapshot captured at upgrade time
     */
    private void bootstrapSession(
            DefaultWebSocketSession session, ServerWebSocket ws, WebSocketEndpointMeta meta, ContextSnapshot snapshot) {
        // Flipped by the close handler when the socket closes before the (async) channel registration
        // settles. The registration-success branch reads it to fail closed instead of invoking
        // @OnOpen / ws.resume() on an already-closed socket and orphaning the late-registered binding.
        AtomicBoolean closedBeforeReady = new AtomicBoolean(false);

        ContextHolder.Scope sessionScope;
        try {
            // Bind the snapshot: restores all HTTP request context values (SC, MDC, etc.)
            // into the WebSocket session's duplicated Vert.x context.
            sessionScope = ContextValues.bindSnapshot(snapshot);
            session.contextScope(sessionScope);

            // Install frame and close handlers before invoking onOpen so that any frame
            // sent by the server inside onOpen is handled correctly.
            installFrameAndCloseHandlers(session, ws, meta, closedBeforeReady);
        } catch (RuntimeException e) {
            log.error("WebSocket session bootstrap failed for {}", session.id(), e);
            // sessionScope may or may not have been bound at the point of the exception;
            // check the session rather than a local variable to avoid a stale reference.
            ContextHolder.Scope partialScope = session.contextScope();
            if (partialScope != null) {
                try {
                    partialScope.close();
                } catch (RuntimeException closeErr) {
                    log.warn("Failed closing partially-bound WebSocket scope for {}", session.id(), closeErr);
                }
                session.contextScope(null);
            }
            ws.close(CLOSE_CODE_INTERNAL_ERROR, "internal server error");
            return;
        }

        // Capture the WebSocket's owner (event-loop) context once, up front. This is the same context
        // the close handler and WebSocketChannelBinding run on; the registration-completion body below
        // is marshaled back onto it so check-and-act against closedBeforeReady/ws.isClosed() is atomic
        // with the close handler (single-threaded event loop). bootstrapSession runs inside the
        // afterClose task driven synchronously from the toWebSocket() success callback, which executes
        // on the socket-owner event loop, so currentContext() here is that owner context.
        final io.vertx.core.Context ownerContext = io.vertx.core.Vertx.currentContext();

        // When the channel adapter is present, register the channel with the manager BEFORE invoking
        // @OnOpen or resuming the socket. The adapter wraps sessionScope in a WebSocketChannelBinding;
        // the manager takes ownership of the scope only once registration SUCCEEDS — from that point
        // the manager drives the scope lifecycle via the binding. Registration is asynchronous, so the
        // ownership transfer, the @OnOpen invocation, and ws.resume() are sequenced onto the
        // registration future's success branch only. A registration FAILURE fails closed (F-W5):
        // @OnOpen is never invoked and the socket is never resumed; the adapter has already released
        // the binding's scope on the failed-register path (WebSocketChannelAdapter#onOpen), so the
        // registrar must NOT close the scope again here. When the adapter is absent (security module
        // not installed) there is no registration step — registration is an immediate success and the
        // local close path (releaseChannelResources) retains scope ownership.
        boolean adapterRegistration = false;
        Future<Void> registrationFuture = Future.succeededFuture();
        if (channelAdapter != null && securityRuntime != null) {
            SecurityContext ctx = securityRuntime.current();
            if (ctx != null && ownerContext != null) {
                adapterRegistration = true;
                registrationFuture = channelAdapter.onOpen(session.id(), ctx, ws, ownerContext, sessionScope);
            }
        }

        final boolean ownershipTransferOnSuccess = adapterRegistration;
        // Marshal the ENTIRE completion body (both success and failure branches) onto the owner
        // event-loop context. A custom async ChannelIdentityManager may complete register() from a
        // worker/foreign Vert.x context; without this hop the body — including the
        // closedBeforeReady/ws.isClosed() check, @OnOpen, ws.resume(), and every session/scope
        // mutation — would run on that foreign thread, concurrently with the owner-loop close handler
        // and with no mutual exclusion (Vert.x associates the owner Context object but still runs the
        // callback on the completing thread). Running the body via ownerContext.runOnContext makes it
        // an indivisible event-loop task ordered against the close handler. The default
        // DefaultChannelIdentityManager completes register() inline on the owner loop, so this hop is
        // a no-op re-dispatch for it.
        registrationFuture.onComplete(registrationAr -> {
            if (ownerContext != null) {
                ownerContext.runOnContext(v -> completeRegistration(
                        session, ws, meta, closedBeforeReady, ownershipTransferOnSuccess, registrationAr));
            } else {
                // No owner context (defensive — a real WebSocket upgrade always has one, and the
                // adapter registration above was skipped when ownerContext is null). There is no event
                // loop to marshal onto; run inline.
                completeRegistration(session, ws, meta, closedBeforeReady, ownershipTransferOnSuccess, registrationAr);
            }
        });
    }

    /**
     * Handles the settled channel-registration future on the WebSocket's owner event-loop context.
     *
     * <p>Always invoked via {@code ownerContext.runOnContext(...)} from {@link #bootstrapSession}
     * (except the defensive no-owner-context path), so the {@code closedBeforeReady}/{@code
     * ws.isClosed()} check and the subsequent scope transfer / {@link OnOpen} / {@code ws.resume()}
     * run as one indivisible event-loop task, ordered against the close handler that flips
     * {@code closedBeforeReady}. This is what makes the check-and-act atomic even when a custom
     * asynchronous {@link dev.vertique.security.channel.ChannelIdentityManager} completes
     * {@code register()} from a worker / foreign context.
     *
     * <p>Branches:
     * <ul>
     *   <li><b>Registration failed</b> → fail closed: the adapter already released the binding's scope
     *       on the failed-register path, so the scope is not orphaned; clear the session reference and
     *       close the socket with {@code 1011}. {@link OnOpen} is never invoked.</li>
     *   <li><b>Socket closed before registration settled</b> ({@code closedBeforeReady} set or
     *       {@code ws.isClosed()}) → fail closed: skip {@link OnOpen}/{@code resume} and release the
     *       now-registered binding via {@link #releaseLateRegisteredBinding} so its scope is not
     *       orphaned.</li>
     *   <li><b>Success on a still-open socket</b> → transfer scope ownership to the binding (adapter
     *       path) and invoke {@link #invokeOnOpenAndResume}.</li>
     * </ul>
     *
     * @param session                    the session being bootstrapped
     * @param ws                         the underlying Vert.x WebSocket
     * @param meta                       the endpoint metadata
     * @param closedBeforeReady          flag flipped by the close handler when the socket closes while
     *                                   registration is pending
     * @param ownershipTransferOnSuccess {@code true} when the channel adapter performed the
     *                                   registration (adapter path); {@code false} for the no-adapter
     *                                   path
     * @param registrationAr             the settled registration result
     */
    private void completeRegistration(
            DefaultWebSocketSession session,
            ServerWebSocket ws,
            WebSocketEndpointMeta meta,
            AtomicBoolean closedBeforeReady,
            boolean ownershipTransferOnSuccess,
            io.vertx.core.AsyncResult<Void> registrationAr) {
        if (registrationAr.failed()) {
            // Channel registration failed → fail closed. The adapter already released the
            // binding's scope (so it is not orphaned); we must not close it again. @OnOpen has
            // NOT been invoked, so no user handler ran and the socket is never resumed. Close the
            // socket with 1011. The session's scope reference is irrelevant now (the scope is
            // released); clearing it keeps the close handler's legacy fallback a no-op.
            log.error(
                    "WebSocket channel registration failed for {} — failing closed",
                    session.id(),
                    registrationAr.cause());
            session.contextScope(null);
            ws.close(CLOSE_CODE_INTERNAL_ERROR, "internal server error");
            return;
        }

        // Registration succeeded, but the socket may have been closed by the peer while it was
        // pending. If so, fail closed: do NOT invoke @OnOpen or resume an already-closed socket,
        // and release the now-registered binding (the close handler's earlier deregister was a
        // no-op because the channel was not yet registered). This read is on the owner event loop,
        // so it cannot interleave with the close handler's closedBeforeReady.set(true).
        if (closedBeforeReady.get() || ws.isClosed()) {
            log.debug(
                    "WebSocket {} closed before channel registration completed — failing closed,"
                            + " skipping @OnOpen/resume",
                    session.id());
            releaseLateRegisteredBinding(session, ownershipTransferOnSuccess);
            return;
        }

        // Registration succeeded (or was skipped when no adapter is present). Only now is it safe
        // to transfer scope ownership to the binding, invoke @OnOpen, and (on success) resume.
        if (ownershipTransferOnSuccess) {
            // Scope ownership transferred to the binding — clear the session reference so the
            // local close path (releaseChannelResources) does not double-close it.
            session.contextScope(null);
        }
        invokeOnOpenAndResume(session, ws, meta);
    }

    /**
     * Releases the binding that was registered after the socket had already closed (the
     * close-while-registration-pending path). Because registration only completed after the close
     * handler ran, the close handler's {@code deregister} was a no-op (the channel was not yet in the
     * registry), so the binding's scope would otherwise be orphaned.
     *
     * <p>For the adapter path this deregisters the now-registered channel via
     * {@link WebSocketChannelAdapter#onClose(String)} — the manager's {@code deregister} is idempotent
     * and now finds the registered channel, so it releases the binding's scope. For the no-adapter
     * path there was no registration step; the session still owns the scope locally, so it is closed
     * directly (the close handler's {@code releaseChannelResources} guard may already have closed it,
     * in which case {@code session.contextScope()} is {@code null} and this is a no-op).
     *
     * @param session                    the session whose late-registered binding must be released
     * @param ownershipTransferOnSuccess {@code true} when the channel adapter performed the
     *                                   registration (adapter path); {@code false} for the no-adapter
     *                                   path
     */
    private void releaseLateRegisteredBinding(DefaultWebSocketSession session, boolean ownershipTransferOnSuccess) {
        if (ownershipTransferOnSuccess) {
            // Adapter path: the manager now holds the registered binding. Clear the session reference
            // and route the release through the manager's (idempotent) deregister so it releases the
            // binding's scope, emits the close event, and cancels any timer.
            session.contextScope(null);
            channelAdapter
                    .onClose(session.id())
                    .onFailure(t -> log.warn(
                            "Failed deregistering late-registered WebSocket channel {} after close-before-ready",
                            session.id(),
                            t));
        } else {
            // No-adapter path: the session still owns the scope. Close it directly if the close
            // handler's releaseChannelResources has not already done so.
            ContextHolder.Scope scope = session.contextScope();
            if (scope != null) {
                try {
                    scope.close();
                } catch (RuntimeException e) {
                    log.warn("Failed closing WebSocket scope for {} after close-before-ready", session.id(), e);
                }
                session.contextScope(null);
            }
        }
    }

    /**
     * Invokes {@link OnOpen} (when declared) after a successful channel registration, then resumes the
     * paused socket on success or fails closed on failure.
     *
     * <p>{@code invokeLifecycleMethodFuture} wraps {@code void} returns as
     * {@link Future#succeededFuture()} and synchronous throws as {@link Future#failedFuture(Throwable)},
     * so synchronous and asynchronous {@link OnOpen} methods share one success/failure code path.
     *
     * <p><b>Success</b>: {@code ws.resume()} unblocks queued frames only after the {@link OnOpen}
     * future completes successfully.
     *
     * <p><b>Failure</b>: {@code ws.resume()} is never called, so no frames reach user handlers. The
     * scope is closed only when it is still owned locally ({@code session.contextScope() != null} —
     * the no-adapter path); when the channel adapter took ownership the manager releases the scope via
     * {@link WebSocketChannelAdapter#onClose(String)}, which fires from the WebSocket close handler
     * triggered by the {@code ws.close(1011)} below. The socket is then closed with code {@code 1011}.
     *
     * <p><b>Already-closed guard.</b> If the socket is closed by the time this runs, {@link OnOpen}
     * is skipped entirely — a user open handler must never observe a dead socket. The caller
     * (registration-success branch) already handles scope release for the close-while-pending path;
     * this guard is a defensive backstop for any other path that reaches here after a close.
     *
     * @param session the session being bootstrapped
     * @param ws      the underlying Vert.x WebSocket
     * @param meta    the endpoint metadata
     */
    private void invokeOnOpenAndResume(
            DefaultWebSocketSession session, ServerWebSocket ws, WebSocketEndpointMeta meta) {
        if (ws.isClosed()) {
            // Socket already closed — never invoke @OnOpen or resume a dead socket. Scope release for
            // the close-while-pending path is handled by the caller (releaseLateRegisteredBinding).
            log.debug("WebSocket {} already closed before @OnOpen — skipping @OnOpen/resume", session.id());
            return;
        }

        Future<Void> onOpenFuture = meta.onOpen() != null
                ? invokeLifecycleMethodFuture(meta.onOpen(), meta.instance(), session, meta)
                : Future.succeededFuture();

        onOpenFuture.onComplete(ar -> {
            if (ar.succeeded()) {
                // @OnOpen completed successfully — unblock queued frames.
                ws.resume();
            } else {
                Throwable cause = ar.cause();
                log.error(
                        "WebSocket session bootstrap failed for {} — @OnOpen returned a failed future",
                        session.id(),
                        cause);
                // Close the scope only if the adapter has NOT taken ownership; otherwise let the
                // manager clean up via adapter.onClose() which fires from the ws.closeHandler when
                // the ws.close(1011) below runs.
                ContextHolder.Scope scope = session.contextScope();
                if (scope != null) {
                    try {
                        scope.close();
                    } catch (RuntimeException closeErr) {
                        log.warn("Failed closing partially-bound WebSocket scope for {}", session.id(), closeErr);
                    }
                    // Clear scope so the close handler does not try to close it again.
                    session.contextScope(null);
                }
                ws.close(CLOSE_CODE_INTERNAL_ERROR, "internal server error");
            }
        });
    }

    // --- Lifecycle wiring ---

    /**
     * Wires all declared lifecycle handlers ({@link OnMessage}, {@link OnClose},
     * {@link OnError}) onto the given WebSocket connection. Also installs the connection
     * close handler responsible for closing the session's {@link ContextHolder.Scope}.
     *
     * <p>An {@link AtomicBoolean} guards the scope-close so it runs exactly once even if both
     * {@code onError} and {@code onClose} paths trigger.
     *
     * <p>If the user's {@link OnClose} method returns a failed {@code Future<Void>}, the failure
     * is logged at WARN and dispatched to {@link OnError} (when declared and not the same method)
     * before the session scope is closed. The scope is closed unconditionally afterward.
     *
     * <p>The close handler flips {@code closedBeforeReady} as its first action so the
     * registration-success branch in {@link #completeRegistration} can detect a close that races an
     * in-flight channel registration and fail closed (skip {@link OnOpen}/{@code resume}, release the
     * late-registered binding) rather than acting on a dead socket. The close handler and
     * {@link #completeRegistration} both run on the WebSocket's owner event-loop context (the latter
     * via {@link io.vertx.core.Context#runOnContext}), so the flag write here and the check-and-act
     * there are serialized as ordered event-loop tasks even when the registration future is completed
     * from a worker / foreign context.
     *
     * @param session          the session wrapping this connection
     * @param ws               the underlying Vert.x WebSocket
     * @param meta             the endpoint metadata
     * @param closedBeforeReady flag flipped by the close handler when the socket closes; read by the
     *                          registration-success branch to fail closed on a close-while-pending race
     */
    private void installFrameAndCloseHandlers(
            DefaultWebSocketSession session,
            ServerWebSocket ws,
            WebSocketEndpointMeta meta,
            AtomicBoolean closedBeforeReady) {
        // --- Message handlers ---
        if (meta.onMessage() != null) {
            if (meta.binaryMessage()) {
                ws.binaryMessageHandler(
                        buf -> invokeLifecycleMethod(meta.onMessage(), meta.instance(), session, meta, buf, null));
            } else {
                ws.textMessageHandler(text -> {
                    Object message = deserializeMessage(text, meta, session);
                    if (message != null) {
                        invokeLifecycleMethod(meta.onMessage(), meta.instance(), session, meta, message, null);
                    }
                });
            }
        }

        // --- Error handler ---
        ws.exceptionHandler(cause -> {
            if (meta.onError() != null) {
                invokeLifecycleMethod(meta.onError(), meta.instance(), session, meta, null, cause);
            } else {
                log.warn("Unhandled WebSocket error on {}", meta.path(), cause);
            }
        });

        // --- Close handler: user onClose + scope/channel cleanup ---
        // The AtomicBoolean guards against double-close on onError-then-onClose paths.
        AtomicBoolean scopeClosed = new AtomicBoolean(false);
        ws.closeHandler(v -> {
            // Signal that the socket closed: if channel registration is still pending, the
            // registration-success branch reads this to fail closed instead of running @OnOpen/resume
            // on a dead socket and orphaning the late-registered binding.
            closedBeforeReady.set(true);
            // Invoke user's onClose callback and settle the future before releasing resources.
            if (meta.onClose() != null) {
                Future<Void> onCloseFuture =
                        invokeLifecycleMethodFuture(meta.onClose(), meta.instance(), session, meta);
                onCloseFuture.onComplete(ar -> {
                    if (ar.failed()) {
                        // Log the @OnClose failure and dispatch to @OnError (if declared and
                        // not the same method) while still inside the ambient context so the
                        // error handler sees the rebound snapshot. Resources are released afterward
                        // unconditionally via releaseChannelResources.
                        log.warn("WebSocket @OnClose failed for {}", session.id(), ar.cause());
                        if (meta.onError() != null && meta.onError() != meta.onClose()) {
                            invokeLifecycleMethod(meta.onError(), meta.instance(), session, meta, null, ar.cause());
                        }
                    }
                    releaseChannelResources(session, scopeClosed);
                });
            } else {
                releaseChannelResources(session, scopeClosed);
            }
        });
    }

    /**
     * Releases all per-channel resources after the WebSocket close handler fires, exactly once.
     * Guarded by the {@code scopeClosed} flag to defend against double-release on
     * {@code onError}-then-{@code onClose} paths.
     *
     * <p>When a {@link WebSocketChannelAdapter} is present, delegates to
     * {@link WebSocketChannelAdapter#onClose(String)} so the {@link dev.vertique.security.channel.ChannelIdentityManager}
     * can emit a {@link dev.vertique.security.events.ChannelClosedEvent} and cancel any pending
     * expiry timer before the binding closes the scope. When no adapter is present (security module
     * absent), closes the session's {@link ContextHolder.Scope} directly.
     *
     * @param session     the session whose resources should be released
     * @param scopeClosed the idempotency guard; set to {@code true} on first call
     */
    private void releaseChannelResources(DefaultWebSocketSession session, AtomicBoolean scopeClosed) {
        if (scopeClosed.compareAndSet(false, true)) {
            if (channelAdapter != null) {
                // Manager owns the scope lifecycle via the binding — route close through it
                // so it can emit events, cancel timers, and close the binding's scope.
                channelAdapter
                        .onClose(session.id())
                        .onFailure(t -> log.warn("WebSocket channel deregistration failed for {}", session.id(), t));
            } else {
                // No adapter present: close the scope directly (legacy path, security absent).
                ContextHolder.Scope scope = session.contextScope();
                if (scope != null) {
                    try {
                        scope.close();
                    } catch (RuntimeException e) {
                        log.warn("Failed closing WebSocket session scope for {}", session.id(), e);
                    }
                }
            }
        }
    }

    // --- Method invocation ---

    /**
     * Invokes a lifecycle method reflectively, building its argument list from the session,
     * message, and error context. If the method returns a {@code Future} that fails, the error
     * is dispatched to the {@link OnError} handler (if declared and not already the failing method).
     *
     * @param method   the lifecycle method to invoke
     * @param instance the endpoint object
     * @param session  the current WebSocket session
     * @param meta     the endpoint metadata
     * @param message  the incoming message (for {@link OnMessage}), or {@code null}
     * @param error    the throwable (for {@link OnError}), or {@code null}
     */
    @SuppressWarnings("unchecked")
    private void invokeLifecycleMethod(
            Method method,
            Object instance,
            DefaultWebSocketSession session,
            WebSocketEndpointMeta meta,
            @Nullable Object message,
            @Nullable Throwable error) {
        try {
            Object[] args = buildArgs(method, session, meta, message, error);
            Object result = method.invoke(instance, args);
            if (result instanceof Future<?> future) {
                ((Future<Void>) future).onFailure(cause -> {
                    log.warn("WebSocket lifecycle method {} failed", method.getName(), cause);
                    if (meta.onError() != null && method != meta.onError()) {
                        invokeLifecycleMethod(meta.onError(), instance, session, meta, null, cause);
                    }
                });
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            log.warn("WebSocket lifecycle method {} threw", method.getName(), cause);
            if (meta.onError() != null && method != meta.onError()) {
                invokeLifecycleMethod(meta.onError(), instance, session, meta, null, cause);
            }
        } catch (Exception e) {
            log.warn("Failed to invoke WebSocket lifecycle method {}", method.getName(), e);
        }
    }

    /**
     * Invokes a lifecycle method reflectively and returns a {@link Future} representing the
     * result. If the method returns a {@code Future}, that future is returned directly. If the
     * method returns void (or any other type), a succeeded future is returned. On invocation
     * error, a failed future is returned.
     *
     * @param method   the lifecycle method to invoke
     * @param instance the endpoint object
     * @param session  the current WebSocket session
     * @param meta     the endpoint metadata
     * @return a {@code Future<Void>} representing the method's result; never {@code null}
     */
    @SuppressWarnings("unchecked")
    private Future<Void> invokeLifecycleMethodFuture(
            Method method, Object instance, DefaultWebSocketSession session, WebSocketEndpointMeta meta) {
        try {
            Object[] args = buildArgs(method, session, meta, null, null);
            Object result = method.invoke(instance, args);
            if (result instanceof Future<?> future) {
                return (Future<Void>) future;
            }
            return Future.succeededFuture();
        } catch (InvocationTargetException e) {
            return Future.failedFuture(e.getCause());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Builds the argument array for a lifecycle method by matching each parameter's type
     * to the available context values (session, security context, path params, message, error).
     *
     * <p>{@link SecurityContext}-typed parameters are resolved via {@link SecurityRuntime#current()}
     * so they read the holder-bound value at invocation time rather than a stale snapshot-time value.
     *
     * @param method  the lifecycle method
     * @param session the current WebSocket session
     * @param meta    the endpoint metadata
     * @param message the incoming message, or {@code null}
     * @param error   the throwable, or {@code null}
     * @return an argument array ready for reflective invocation
     */
    private Object[] buildArgs(
            Method method,
            DefaultWebSocketSession session,
            WebSocketEndpointMeta meta,
            @Nullable Object message,
            @Nullable Throwable error) {
        var params = method.getParameters();
        Object[] args = new Object[params.length];
        var pathParams = session.pathParams();

        for (int i = 0; i < params.length; i++) {
            var param = params[i];
            Class<?> type = param.getType();

            if (WebSocketSession.class.isAssignableFrom(type)) {
                args[i] = session;
            } else if (Throwable.class.isAssignableFrom(type) && error != null) {
                args[i] = error;
            } else if (SecurityContext.class.isAssignableFrom(type)) {
                // Read from the ambient holder rather than a stored field so the value
                // reflects the current rebound snapshot on every callback invocation.
                args[i] = securityRuntime != null ? securityRuntime.current() : null;
            } else if (param.isAnnotationPresent(PathParam.class)) {
                String name = param.getAnnotation(PathParam.class).value();
                String rawValue = pathParams.get(name);
                // Apply canonicalization/sanitization to path params if available
                if (rawValue != null && objectProcessor != null) {
                    EffectiveInputPolicies policies = resolveMethodPolicies(method);
                    Object processed = objectProcessor.processInput(
                            rawValue, String.class, policies, InputLocation.PATH, InputFieldNameResolver.IDENTITY);
                    if (processed instanceof String s) {
                        rawValue = s;
                    }
                }
                args[i] = convertPathParam(rawValue, type);
            } else if (message != null) {
                args[i] = message;
            }
        }
        return args;
    }

    /**
     * Deserializes a raw text message to the endpoint's declared message type, optionally applying
     * canonicalization and sanitization via {@link InputObjectProcessor} before materialization,
     * and Bean Validation after.
     *
     * <p>When {@link InputObjectProcessor} is available and the {@link OnMessage} method is
     * present, uses a two-phase approach: decode to intermediate → process → convert to target
     * type. Without the sanitization module, falls back to direct deserialization.
     *
     * <p>Returns {@code null} on deserialization or validation failure. When the endpoint has an
     * {@link OnError} handler, it is invoked with the exception.
     *
     * @param text    the raw text message
     * @param meta    the endpoint metadata containing the target message type
     * @param session the current WebSocket session (for error handler invocation)
     * @return the deserialized (and optionally validated) message, or {@code null} on failure
     */
    @Nullable
    private Object deserializeMessage(String text, WebSocketEndpointMeta meta, DefaultWebSocketSession session) {
        if (meta.messageType() == String.class) {
            // For raw String messages, apply scalar processing if available
            if (objectProcessor != null && meta.onMessage() != null) {
                EffectiveInputPolicies policies = resolveMethodPolicies(meta.onMessage());
                Object processed = objectProcessor.processInput(
                        text, String.class, policies, InputLocation.PAYLOAD, InputFieldNameResolver.IDENTITY);
                if (processed != null) {
                    return processed;
                }
            }
            return text;
        }

        try {
            Object decoded;
            if (objectProcessor != null && meta.onMessage() != null) {
                // Two-phase: intermediate → process → materialize
                EffectiveInputPolicies policies = resolveMethodPolicies(meta.onMessage());
                Object intermediate = messageCodec.decodeToIntermediate(text);
                Object processed = objectProcessor.processInput(
                        intermediate, meta.messageType(), policies, InputLocation.PAYLOAD, messageNameResolver);
                decoded = messageCodec.convertFromIntermediate(
                        processed != null ? processed : intermediate, meta.messageType());
            } else {
                // Direct deserialization (no sanitization module)
                decoded = messageCodec.decode(text, meta.messageType());
            }

            // Bean validation
            if (beanValidator != null && decoded != null) {
                if (meta.validationGroups() != null) {
                    beanValidator.validate(decoded, meta.validationGroups());
                } else {
                    beanValidator.validate(decoded);
                }
            }

            return decoded;
        } catch (BeanValidationException e) {
            log.warn("WebSocket message validation failed: {}", e.getMessage());
            if (meta.onError() != null) {
                invokeLifecycleMethod(meta.onError(), meta.instance(), session, meta, null, e);
            }
            return null;
        } catch (Exception e) {
            log.warn(
                    "Failed to deserialize WebSocket message to {}",
                    meta.messageType().getSimpleName(),
                    e);
            if (meta.onError() != null) {
                invokeLifecycleMethod(meta.onError(), meta.instance(), session, meta, null, e);
            }
            return null;
        }
    }

    /**
     * Resolves canonicalization and sanitization policies from annotations on the given lifecycle
     * method only. No class-level fallback — avoids cross-method policy contamination.
     *
     * <p>Resolution is meta-annotation-aware through {@link AnnotationResolver#findMetaAnnotation},
     * matching REST: a custom annotation itself meta-annotated with {@code @Canonicalize} or
     * {@code @Sanitize} is the documented way to name a reusable chain, and a bare
     * {@code Method#getAnnotation} would silently ignore it.
     *
     * @param method the lifecycle method to inspect for {@code @Canonicalize} and {@code @Sanitize}
     *               annotations, directly or through a composed annotation
     * @return the resolved effective input policies; {@link EffectiveInputPolicies#NONE} when no
     *         annotations are present on the method
     */
    private EffectiveInputPolicies resolveMethodPolicies(Method method) {
        Canonicalize canonicalize = AnnotationResolver.findMetaAnnotation(method, Canonicalize.class);
        Sanitize sanitize = AnnotationResolver.findMetaAnnotation(method, Sanitize.class);

        if (canonicalize == null && sanitize == null) {
            return EffectiveInputPolicies.NONE;
        }

        List<Class<? extends Canonicalizer>> canonicalizers =
                canonicalize != null ? List.of(canonicalize.value()) : List.of();
        List<Class<? extends Sanitizer>> sanitizers = sanitize != null ? List.of(sanitize.value()) : List.of();

        return new EffectiveInputPolicies(canonicalizers, sanitizers);
    }

    /**
     * Converts a path parameter string value to the declared parameter type.
     * Supports {@code String}, {@code int}/{@code Integer}, and {@code long}/{@code Long}.
     *
     * @param value the raw string value, or {@code null} if the parameter was not present
     * @param type  the declared target type
     * @return the converted value, or {@code null} if {@code value} is {@code null}
     */
    @Nullable
    private Object convertPathParam(@Nullable String value, Class<?> type) {
        if (value == null) return null;
        if (type == String.class) return value;
        try {
            if (type == int.class || type == Integer.class) return Integer.parseInt(value);
            if (type == long.class || type == Long.class) return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Cannot convert path parameter value '" + value + "' to " + type.getSimpleName(), e);
        }
        throw new IllegalArgumentException("Unsupported path parameter type: " + type.getName()
                + "; supported types are String, int/Integer, long/Long");
    }
}
