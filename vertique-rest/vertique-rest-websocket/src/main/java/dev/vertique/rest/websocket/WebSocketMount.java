// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.router.MountMeta;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.AuthorizationGateConfig;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityClaimMapper;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.rest.security.VertxAuthorizationImporter;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.channel.ChannelIdentityManager;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.web.Router;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link RouterMount} implementation that registers WebSocket endpoints on a Vert.x sub-router.
 *
 * <p>Mounts at default priority {@code -100} (before the default JAX-RS mount at {@code 1000})
 * to ensure WebSocket routes are matched before catch-all REST routes.
 *
 * <p>The sub-router installs request interceptor hooks ({@code onRequest}, {@code beforeRequest})
 * for the upgrade request, authentication and authorization handlers based on the endpoint's
 * security policy, and the WebSocket upgrade handler.
 *
 * <p>Instances are created exclusively via the inner {@link Factory} class, which holds all
 * shared framework services injected once by Dagger. Per-mount configuration ({@code mountPath},
 * {@code endpoints}, and {@code priority}) is supplied at creation time.
 */
@Slf4j
public class WebSocketMount implements RouterMount {

    // --- Instance fields ---

    private final String mountPath;
    private final Set<Object> endpoints;
    private final int priority;
    private final Factory factory;

    /**
     * Creates a new WebSocket mount. Only {@link Factory} should call this constructor.
     *
     * @param mountPath the path prefix where the sub-router is mounted
     * @param endpoints the set of WebSocket endpoint instances to register
     * @param priority  mount priority (lower values are mounted first)
     * @param factory   shared services factory
     */
    private WebSocketMount(String mountPath, Set<Object> endpoints, int priority, Factory factory) {
        this.mountPath = mountPath;
        this.endpoints = endpoints;
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
     * Returns metadata for this mount. The {@code mountId} is {@code "websocket:"} followed by
     * the mount path; the {@code resourceTypes} set contains the classes of all registered
     * endpoint instances.
     *
     * @return mount metadata with a stable {@code "websocket:<mountPath>"} identifier
     */
    @Override
    public MountMeta meta() {
        Set<Class<?>> endpointTypes = endpoints.stream().map(Object::getClass).collect(Collectors.toUnmodifiableSet());
        return new MountMeta("websocket:" + mountPath, mountPath, null, endpointTypes);
    }

    /**
     * Creates the WebSocket sub-router for this mount.
     *
     * <p>The construction pipeline:
     * <ol>
     *   <li>Install request interceptor hooks ({@code onRequest} sync + {@code beforeRequest}
     *       async chain) on the sub-router root route when interceptors are present.</li>
     *   <li>Delegate endpoint scanning and registration to {@link WebSocketEndpointRegistrar},
     *       which installs authentication, authorization, and WebSocket upgrade handlers per
     *       endpoint.</li>
     * </ol>
     *
     * @param vertx the Vert.x instance
     * @return a future resolving to the configured WebSocket sub-router; always succeeds
     *         synchronously
     */
    @Override
    public Future<Router> createRouter(Vertx vertx) {
        Router router = Router.router(vertx);

        // --- Install request interceptor hooks for upgrade requests ---
        if (!factory.sortedInterceptors.isEmpty()) {
            router.route().order(Integer.MIN_VALUE + 1).handler(ctx -> {
                for (RequestInterceptor interceptor : factory.sortedInterceptors) {
                    try {
                        interceptor.onRequest(ctx);
                    } catch (Exception e) {
                        // swallow — sync observers must not affect outcome
                    }
                }
                Future<Void> chain = Future.succeededFuture();
                for (RequestInterceptor interceptor : factory.sortedInterceptors) {
                    chain = chain.flatMap(v -> interceptor.beforeRequest(ctx));
                }
                chain.onSuccess(v -> ctx.next()).onFailure(ctx::fail);
            });
        }

        // --- Register all WebSocket endpoints ---
        WebSocketEndpointRegistrar registrar = new WebSocketEndpointRegistrar(
                factory.messageCodec,
                factory.securityPolicyEnforcer,
                factory.identityResolutionMiddleware,
                factory.securityRuntime,
                factory.routeAuthHandlers,
                factory.beanValidator,
                factory.objectProcessor,
                factory.channelAdapter,
                factory.actionRegistry,
                factory.authorizer);
        registrar.registerAll(endpoints, router);

        return Future.succeededFuture(router);
    }

    // --- Factory ---

    /**
     * Factory for creating {@link WebSocketMount} instances. Holds all shared framework services
     * injected once by Dagger and reused across multiple mount instances.
     *
     * <p>The security components ({@link SecurityPolicyEnforcer} and
     * {@link IdentityResolutionMiddleware}) are constructed lazily inside the factory constructor
     * based on the available optional bindings. When the security module is absent, these remain
     * {@code null} and all WebSocket endpoints operate without authentication or authorization.
     *
     * <p>Inject this factory into application modules to create one or more WebSocket mounts
     * without having to declare each individual dependency.
     */
    @Singleton
    public static class Factory {

        // --- Shared services (package-private for direct access by WebSocketMount) ---

        final WebSocketMessageCodec messageCodec;
        final @Nullable SecurityPolicyEnforcer securityPolicyEnforcer;
        final @Nullable IdentityResolutionMiddleware identityResolutionMiddleware;
        /** Security runtime passed to {@link WebSocketEndpointRegistrar} for SC param resolution. */
        final @Nullable SecurityRuntime securityRuntime;

        /**
         * Channel adapter bridging WebSocket lifecycle to {@link dev.vertique.security.channel.ChannelIdentityManager}.
         * {@code null} when the security module is absent.
         */
        final @Nullable WebSocketChannelAdapter channelAdapter;

        final Set<RouteAuthHandler> routeAuthHandlers;
        final List<RequestInterceptor> sortedInterceptors;
        final @Nullable BeanValidator beanValidator;
        final @Nullable InputObjectProcessor objectProcessor;

        /**
         * Framework action registry used by {@link WebSocketEndpointScanner} to validate a class-level
         * {@code @RequiresAction} at startup; {@code null} when the authorization engine is absent.
         */
        final @Nullable ActionRegistry actionRegistry;

        /**
         * Core action {@link Authorizer} passed to {@link WebSocketEndpointRegistrar} so a class-level
         * {@code @RequiresAction} endpoint fails startup (fail-closed) when the {@link ActionRegistry}
         * is present but the {@code Authorizer} — bound through a separate optional seam — is absent
         * (finding W2); {@code null} when the authorization engine is absent.
         */
        final @Nullable Authorizer authorizer;

        /**
         * Creates the factory without a Vert.x authorization importer.
         *
         * <p>Convenience delegate equivalent to passing {@link Optional#empty()} as the importer to
         * the canonical injected constructor below, i.e. the authorization-import step is skipped
         * during identity resolution at upgrade time.
         *
         * @param messageCodec                   codec for JSON message serialization/deserialization
         * @param authorizationProviders         set of authorization providers; empty when security
         *                                       module is absent
         * @param identityResolvers              set of identity resolvers; empty when security
         *                                       module is absent
         * @param securityRuntime                optional security runtime; present when the security
         *                                       module is included
         * @param claimMapper                    optional custom claim mapper for token claim
         *                                       extraction
         * @param contextHolder                  the context holder for reading ambient correlation
         * @param securityEventEmitter           the security event emitter for lifecycle events
         * @param authorizationDecisionPoint     optional app-provided async authorization decision
         *                                       point; takes precedence over the sync policy
         * @param authorizationPolicy            optional app-provided sync authorization policy
         * @param routeAuthHandlers              set of registered route-level authentication handlers
         * @param requestInterceptors            HTTP-level request interceptors
         * @param beanValidator                  optional Bean Validation engine
         * @param objectProcessor                optional canonicalization/sanitization processor
         * @param channelIdentityManager         optional channel identity manager
         * @param authorizer                     optional core action {@link Authorizer}
         * @param actionRegistry                 optional framework {@link ActionRegistry}
         */
        public Factory(
                WebSocketMessageCodec messageCodec,
                Set<AuthorizationProvider> authorizationProviders,
                Set<dev.vertique.security.resolver.SecurityIdentityResolver> identityResolvers,
                Optional<SecurityRuntime> securityRuntime,
                Optional<SecurityClaimMapper> claimMapper,
                dev.vertique.core.context.ContextHolder contextHolder,
                dev.vertique.security.runtime.events.SecurityEventEmitter securityEventEmitter,
                Optional<AuthorizationDecisionPoint> authorizationDecisionPoint,
                Optional<AuthorizationPolicy> authorizationPolicy,
                Set<RouteAuthHandler> routeAuthHandlers,
                Set<RequestInterceptor> requestInterceptors,
                Optional<BeanValidator> beanValidator,
                Optional<InputObjectProcessor> objectProcessor,
                Optional<ChannelIdentityManager> channelIdentityManager,
                Optional<Authorizer> authorizer,
                Optional<ActionRegistry> actionRegistry) {
            this(
                    messageCodec,
                    authorizationProviders,
                    identityResolvers,
                    securityRuntime,
                    claimMapper,
                    contextHolder,
                    securityEventEmitter,
                    authorizationDecisionPoint,
                    authorizationPolicy,
                    routeAuthHandlers,
                    requestInterceptors,
                    beanValidator,
                    objectProcessor,
                    channelIdentityManager,
                    authorizer,
                    actionRegistry,
                    Optional.empty(),
                    Optional.empty());
        }

        /**
         * Creates the factory with all shared framework services — the canonical constructor, and
         * the one Dagger injects. The security components ({@link SecurityPolicyEnforcer},
         * {@link IdentityResolutionMiddleware}) are constructed only when the security module is
         * present ({@code securityRuntime} is non-empty). The validation and sanitization
         * components are only present when the respective optional modules are included in the
         * Dagger component.
         *
         * <p>The {@code vertxAuthorizationImporter} parameter mirrors the seam
         * {@link IdentityResolutionMiddleware}'s canonical constructor exposes, so WebSocket
         * upgrades participate in the Vert.x authorization import the same way OpenAPI routes do
         * when the application opts in via
         * {@link dev.vertique.rest.security.VertxAuthorizationImportModule}.
         *
         * @param messageCodec                   codec for JSON message serialization/deserialization
         * @param authorizationProviders         set of authorization providers; empty when security
         *                                       module is absent
         * @param identityResolvers              set of identity resolvers; empty when security
         *                                       module is absent
         * @param securityRuntime                optional security runtime; present when the security
         *                                       module is included
         * @param claimMapper                    optional custom claim mapper for token claim
         *                                       extraction
         * @param contextHolder                  the context holder for reading ambient correlation
         * @param securityEventEmitter           the security event emitter for lifecycle events
         * @param authorizationDecisionPoint     optional app-provided async authorization decision
         *                                       point; takes precedence over the sync policy
         * @param authorizationPolicy            optional app-provided sync authorization policy;
         *                                       wrapped in a {@link dev.vertique.rest.security.SyncPolicyDecisionPoint}
         *                                       when no async override is present
         * @param routeAuthHandlers              set of registered route-level authentication handlers
         * @param requestInterceptors            HTTP-level request interceptors sorted by
         *                                       {@link dev.vertique.core.extension.OrderedExtension#comparator()}
         *                                       (phase → priority → orderKey) at construction time
         * @param beanValidator                  optional Bean Validation engine; present when the
         *                                       validation module is included
         * @param objectProcessor                optional canonicalization/sanitization processor;
         *                                       present when the sanitization module is included
         * @param channelIdentityManager         optional channel identity manager; present when
         *                                       {@code AuthModule} is in the graph. When present
         *                                       (alongside {@code securityRuntime}), a
         *                                       {@link WebSocketChannelAdapter} is constructed here
         *                                       to bridge WebSocket lifecycle to the manager.
         * @param authorizer                     optional core action {@link Authorizer} used by the
         *                                       {@link SecurityPolicyEnforcer} to evaluate a class-level
         *                                       {@code @RequiresAction} gate; present when the
         *                                       authorization engine ({@code SecurityAuthzModule})
         *                                       is in the graph. Threaded into the enforcer so the
         *                                       action gate is enforced once at upgrade (FR-AUTHZ-048).
         * @param actionRegistry                 optional framework {@link ActionRegistry} used by
         *                                       {@link WebSocketEndpointScanner} to validate a class-level
         *                                       {@code @RequiresAction} at startup; present when the
         *                                       authorization engine is in the graph. Absent → any
         *                                       {@code @RequiresAction} endpoint fails startup (fail-closed).
         * @param vertxAuthorizationImporter     optional Vert.x authorization importer; present only
         *                                       when the application opts in by including
         *                                       {@link dev.vertique.rest.security.VertxAuthorizationImportModule}.
         *                                       Threaded into the {@link IdentityResolutionMiddleware}
         *                                       so contributed providers are consulted during identity
         *                                       resolution at upgrade time. Absent → the import step
         *                                       is skipped.
         * @param authorizationGateConfig        optional operator-configured {@link
         *                                       SecurityPolicyEnforcer#decide} gate deadline (issue
         *                                       #417, R42); empty defaults to {@link
         *                                       AuthorizationGateConfig#defaults()}.
         *                                       Threaded into the enforcer so the WebSocket upgrade
         *                                       gate honors the same operator-configured deadline as
         *                                       REST and MCP — a single knob across all three
         *                                       transports.
         */
        @Inject
        public Factory(
                WebSocketMessageCodec messageCodec,
                Set<AuthorizationProvider> authorizationProviders,
                Set<dev.vertique.security.resolver.SecurityIdentityResolver> identityResolvers,
                Optional<SecurityRuntime> securityRuntime,
                Optional<SecurityClaimMapper> claimMapper,
                dev.vertique.core.context.ContextHolder contextHolder,
                dev.vertique.security.runtime.events.SecurityEventEmitter securityEventEmitter,
                Optional<AuthorizationDecisionPoint> authorizationDecisionPoint,
                Optional<AuthorizationPolicy> authorizationPolicy,
                Set<RouteAuthHandler> routeAuthHandlers,
                Set<RequestInterceptor> requestInterceptors,
                Optional<BeanValidator> beanValidator,
                Optional<InputObjectProcessor> objectProcessor,
                Optional<ChannelIdentityManager> channelIdentityManager,
                Optional<Authorizer> authorizer,
                Optional<ActionRegistry> actionRegistry,
                Optional<VertxAuthorizationImporter> vertxAuthorizationImporter,
                Optional<AuthorizationGateConfig> authorizationGateConfig) {
            this.messageCodec = messageCodec;
            this.routeAuthHandlers = routeAuthHandlers;
            this.sortedInterceptors = requestInterceptors.stream()
                    .sorted(OrderedExtension.comparator())
                    .toList();
            this.beanValidator = beanValidator.orElse(null);
            this.objectProcessor = objectProcessor.orElse(null);
            this.actionRegistry = actionRegistry.orElse(null);
            this.authorizer = authorizer.orElse(null);

            if (securityRuntime.isPresent()) {
                this.securityRuntime = securityRuntime.get();
                this.securityPolicyEnforcer = new SecurityPolicyEnforcer(
                        authorizationDecisionPoint,
                        authorizationPolicy,
                        authorizationProviders,
                        securityEventEmitter,
                        contextHolder,
                        this.securityRuntime,
                        // Thread the real action Authorizer so a class-level @RequiresAction gate is
                        // composed and enforced once at upgrade (FR-AUTHZ-048, ADR-0115). Empty when the
                        // authz engine is absent — in which case no @RequiresAction endpoint passes the
                        // scanner's startup validation, so the enforcer never reads it.
                        authorizer,
                        // Thread the operator-configured gate deadline (issue #417, R42) so the
                        // WebSocket upgrade gate honors the same deadline as REST and MCP.
                        authorizationGateConfig);
                this.identityResolutionMiddleware = new IdentityResolutionMiddleware(
                        identityResolvers,
                        claimMapper,
                        securityEventEmitter,
                        this.securityRuntime,
                        contextHolder,
                        // No identity snapshot capture at WebSocket upgrade — unchanged behavior.
                        Optional.empty(),
                        // Thread the importer so contributed Vert.x AuthorizationProviders change
                        // authorization outcomes at upgrade time when the app opts in via
                        // VertxAuthorizationImportModule — parity with OpenAPI routes.
                        vertxAuthorizationImporter);
                // Build the channel adapter only when both manager and runtime are present.
                // ChannelIdentityManager is optional because it is only bound by AuthModule.
                this.channelAdapter = channelIdentityManager
                        .map(mgr -> new WebSocketChannelAdapter(mgr, this.securityRuntime))
                        .orElse(null);
            } else {
                this.securityRuntime = null;
                this.securityPolicyEnforcer = null;
                this.identityResolutionMiddleware = null;
                this.channelAdapter = null;
            }
        }

        /**
         * Creates a new {@link WebSocketMount} with the default priority of {@code -100}.
         *
         * @param mountPath the path prefix where the sub-router is mounted (e.g. {@code "/*"})
         * @param endpoints the set of WebSocket endpoint instances to register
         * @return a configured mount instance
         */
        public WebSocketMount create(String mountPath, Set<Object> endpoints) {
            return new WebSocketMount(mountPath, endpoints, -100, this);
        }

        /**
         * Creates a new {@link WebSocketMount} with an explicit priority.
         *
         * @param mountPath the path prefix where the sub-router is mounted (e.g. {@code "/*"})
         * @param endpoints the set of WebSocket endpoint instances to register
         * @param priority  mount priority (lower values are mounted first by {@link
         *                  dev.vertique.rest.core.router.HttpVerticle})
         * @return a configured mount instance
         */
        public WebSocketMount create(String mountPath, Set<Object> endpoints, int priority) {
            return new WebSocketMount(mountPath, endpoints, priority, this);
        }
    }
}
