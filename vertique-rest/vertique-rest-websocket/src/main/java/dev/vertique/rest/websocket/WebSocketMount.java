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
import dev.vertique.rest.security.IdentityPipelineFactory;
import dev.vertique.rest.security.IdentityPipelineOptions;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.channel.ChannelIdentityManager;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
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
                factory.identityResolutionHandler,
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
     * Factory for creating {@link WebSocketMount} instances. This factory itself is the one Dagger
     * {@code @Singleton} injected once and reused across multiple mount instances; most of the shared
     * services it holds are Dagger-injected constructor arguments, held as-is.
     *
     * <p>The identity/authorization pieces are not assembled here: they are obtained from the
     * security-owned {@link IdentityPipelineFactory} — the single assembly point every transport reads
     * from ({@code AuthModule} binds it; this factory declares it {@link Optional}, so a WebSocket-only
     * graph builds without the security module). When present, {@link #securityRuntime} is
     * {@code f.securityRuntime()}, {@link #identityResolutionHandler} is
     * {@code f.identityResolutionHandler(IdentityPipelineOptions.webSocket())} — a
     * {@link Handler}{@code <}{@link RoutingContext}{@code >} bound to the {@code websocket} origin
     * with identity-snapshot capture off (ADR-0164); the factory holds this handler, never an
     * {@link IdentityResolutionMiddleware} instance, because no middleware accessor honours a non-REST
     * origin — and {@link #securityPolicyEnforcer} is {@code f.policyEnforcer()}. When absent, all four
     * fields (including {@link #channelAdapter}) remain {@code null} and every WebSocket endpoint
     * operates without authentication or authorization.
     *
     * <p>Inject this factory into application modules to create one or more WebSocket mounts
     * without having to declare each individual dependency.
     */
    @Singleton
    public static class Factory {

        // --- Shared services (package-private for direct access by WebSocketMount) ---

        final WebSocketMessageCodec messageCodec;
        final @Nullable SecurityPolicyEnforcer securityPolicyEnforcer;
        final @Nullable Handler<RoutingContext> identityResolutionHandler;
        /** Security runtime passed to {@link WebSocketEndpointRegistrar} for SC param resolution. */
        final @Nullable SecurityRuntime securityRuntime;

        /**
         * Channel adapter bridging WebSocket lifecycle to {@link dev.vertique.security.channel.ChannelIdentityManager}.
         * {@code null} when no identity pipeline is bound.
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
         * is present but the {@code Authorizer} — bound through a separate optional seam — is absent;
         * {@code null} when the authorization engine is absent.
         */
        final @Nullable Authorizer authorizer;

        /**
         * Creates the factory with all shared framework services — the canonical constructor, and the
         * one Dagger injects.
         *
         * <p>{@link #securityRuntime}, {@link #identityResolutionHandler}, and
         * {@link #securityPolicyEnforcer} are derived from {@code identityPipeline} when present:
         * {@code f.securityRuntime()}, {@code f.identityResolutionHandler(IdentityPipelineOptions.webSocket())},
         * and {@code f.policyEnforcer()} respectively; {@link #channelAdapter} is then built from
         * {@code channelIdentityManager} (present only when {@code AuthModule} is in the graph) and the
         * resolved {@link #securityRuntime}. When {@code identityPipeline} is absent, all four remain
         * {@code null} and every WebSocket endpoint operates without authentication or authorization.
         * The validation and sanitization components are only present when the respective optional
         * modules are included in the Dagger component.
         *
         * @param messageCodec           codec for JSON message serialization/deserialization
         * @param identityPipeline       the security-owned identity pipeline factory; present when
         *                               {@code AuthModule} is in the graph, absent otherwise
         * @param routeAuthHandlers      set of registered route-level authentication handlers
         * @param requestInterceptors    HTTP-level request interceptors sorted by
         *                               {@link dev.vertique.core.extension.OrderedExtension#comparator()}
         *                               (phase → priority → orderKey) at construction time
         * @param beanValidator          optional Bean Validation engine; present when the validation
         *                               module is included
         * @param objectProcessor        optional canonicalization/sanitization processor; present when
         *                               the sanitization module is included
         * @param channelIdentityManager optional channel identity manager; present when
         *                               {@code AuthModule} is in the graph. When present (alongside a
         *                               present {@code identityPipeline}), a {@link WebSocketChannelAdapter}
         *                               is constructed here to bridge WebSocket lifecycle to the manager.
         * @param authorizer             optional core action {@link Authorizer} used by the
         *                               {@link SecurityPolicyEnforcer} to evaluate a class-level
         *                               {@code @RequiresAction} gate; present when the authorization
         *                               engine ({@code SecurityAuthzModule}) is in the graph.
         * @param actionRegistry         optional framework {@link ActionRegistry} used by
         *                               {@link WebSocketEndpointScanner} to validate a class-level
         *                               {@code @RequiresAction} at startup; present when the
         *                               authorization engine is in the graph. Absent → any
         *                               {@code @RequiresAction} endpoint fails startup (fail-closed).
         */
        @Inject
        public Factory(
                WebSocketMessageCodec messageCodec,
                Optional<IdentityPipelineFactory> identityPipeline,
                Set<RouteAuthHandler> routeAuthHandlers,
                Set<RequestInterceptor> requestInterceptors,
                Optional<BeanValidator> beanValidator,
                Optional<InputObjectProcessor> objectProcessor,
                Optional<ChannelIdentityManager> channelIdentityManager,
                Optional<Authorizer> authorizer,
                Optional<ActionRegistry> actionRegistry) {
            this.messageCodec = messageCodec;
            this.routeAuthHandlers = routeAuthHandlers;
            this.sortedInterceptors = requestInterceptors.stream()
                    .sorted(OrderedExtension.comparator())
                    .toList();
            this.beanValidator = beanValidator.orElse(null);
            this.objectProcessor = objectProcessor.orElse(null);
            this.actionRegistry = actionRegistry.orElse(null);
            this.authorizer = authorizer.orElse(null);

            if (identityPipeline.isPresent()) {
                IdentityPipelineFactory f = identityPipeline.get();
                this.securityRuntime = f.securityRuntime();
                // The handler assembled for the WebSocket origin with capture off (ADR-0164) — never
                // the REST middleware, since no middleware accessor honours a non-REST origin.
                this.identityResolutionHandler = f.identityResolutionHandler(IdentityPipelineOptions.webSocket());
                this.securityPolicyEnforcer = f.policyEnforcer();
                // Build the channel adapter only when both manager and runtime are present.
                // ChannelIdentityManager is optional because it is only bound by AuthModule.
                this.channelAdapter = channelIdentityManager
                        .map(mgr -> new WebSocketChannelAdapter(mgr, this.securityRuntime))
                        .orElse(null);
            } else {
                this.securityRuntime = null;
                this.securityPolicyEnforcer = null;
                this.identityResolutionHandler = null;
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
