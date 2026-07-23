// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket.dagger;

import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.rest.core.dagger.RestCoreModule;
import dev.vertique.rest.core.request.InputObjectProcessor;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.AuthorizationDecisionPoint;
import dev.vertique.rest.security.SecurityClaimMapper;
import dev.vertique.rest.websocket.WebSocketConfig;
import dev.vertique.rest.websocket.WebSocketMessageCodec;
import dev.vertique.rest.websocket.WebSocketMount;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationPolicy;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.channel.ChannelIdentityManager;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Dagger module providing WebSocket endpoint support.
 *
 * <p>Include this module in your application's Dagger component to enable WebSocket endpoint
 * registration. The application contributes endpoint instances via the {@link WebSocketEndpoints}
 * multibinding:
 *
 * <pre>{@code
 * @Component(modules = {VertxModule.class, RestModule.class, WebSocketModule.class, ...})
 * interface AppComponent { ... }
 * }</pre>
 *
 * <p>WebSocket endpoints are contributed via:
 *
 * <pre>{@code
 * @Provides @IntoSet @WebSocketEndpoints
 * Object chatEndpoint(ChatEndpoint e) { return e; }
 * }</pre>
 *
 * <p>When no endpoints are contributed the module registers no {@link RouterMount}, keeping the
 * overhead at zero for components that include the module as an optional dependency.
 *
 * <p><strong>Security:</strong> Authentication and authorization are enabled automatically when
 * the security module (e.g. {@code JwtAuthModule}) is also included in the component. When the
 * security module is absent, WebSocket endpoints operate without authentication — suitable for
 * unauthenticated use-cases or local development.
 */
@Module(includes = {RestCoreModule.class, SecurityEventsModule.class})
public abstract class WebSocketModule {

    // --- Multibinding declarations ---

    /**
     * Declares the empty WebSocket endpoint multibinding set. Applications contribute endpoint
     * instances via {@code @Provides @IntoSet @WebSocketEndpoints} methods.
     *
     * @return an empty set (populated by Dagger from {@code @IntoSet} contributions)
     */
    @Multibinds
    @WebSocketEndpoints
    abstract Set<Object> webSocketEndpoints();

    /**
     * Declares the empty {@link RouteAuthHandler} multibinding set. This declaration allows
     * the WebSocket module to operate without {@code AuthModule}. When the auth module is present,
     * it contributes handlers to this set via its own multibinding declaration.
     *
     * @return an empty set (populated by contributions from the auth module)
     */
    @Multibinds
    abstract Set<RouteAuthHandler> routeAuthHandlers();

    /**
     * Declares the empty {@link AuthorizationProvider} multibinding set. This declaration
     * allows {@link dev.vertique.rest.security.SecurityPolicyEnforcer} to be constructed with an
     * empty provider set when the auth module is absent.
     *
     * @return an empty set (populated by contributions from the auth module)
     */
    @Multibinds
    abstract Set<AuthorizationProvider> authorizationProviders();

    /**
     * Declares the empty {@link SecurityIdentityResolver} multibinding set. This declaration
     * allows {@link dev.vertique.rest.security.IdentityResolutionMiddleware} to be constructed
     * with an empty resolver set when the auth module is absent.
     *
     * @return an empty set (populated by contributions from the auth module)
     */
    @Multibinds
    abstract Set<SecurityIdentityResolver> securityIdentityResolvers();

    // --- Optional security bindings ---

    /**
     * Optional binding for {@link AuthorizationDecisionPoint}. Present when an application binds a
     * custom async decision point; absent otherwise. Declared here so a WebSocket-only component
     * (no auth module) still satisfies {@link WebSocketMount.Factory}'s injection. Coalesces with
     * {@code AuthModule}'s declaration when both modules are present.
     *
     * @return the optional {@link AuthorizationDecisionPoint} binding declaration
     */
    @BindsOptionalOf
    abstract AuthorizationDecisionPoint optionalAuthorizationDecisionPoint();

    /**
     * Optional binding for {@link AuthorizationPolicy}. Present when an application binds a custom
     * synchronous policy; absent otherwise. Declared here for WebSocket-only components; coalesces
     * with {@code AuthModule}'s declaration when both modules are present.
     *
     * @return the optional {@link AuthorizationPolicy} binding declaration
     */
    @BindsOptionalOf
    abstract AuthorizationPolicy optionalAuthorizationPolicy();

    /**
     * Optional binding for {@link SecurityRuntime}. Present when the security module is included
     * in the Dagger component; absent when running without security. Used by
     * {@link WebSocketMount.Factory} to decide whether to construct the security middleware.
     *
     * @return the optional {@link SecurityRuntime} binding declaration
     */
    @BindsOptionalOf
    abstract SecurityRuntime optionalSecurityRuntime();

    /**
     * Optional binding for {@link SecurityClaimMapper}. Present when the application provides a
     * custom claim mapper implementation; absent otherwise (falls back to the default mapper).
     *
     * @return the optional {@link SecurityClaimMapper} binding declaration
     */
    @BindsOptionalOf
    abstract SecurityClaimMapper optionalSecurityClaimMapper();

    /**
     * Optional binding for {@link BeanValidator}. Present when the {@code ValidationModule} is
     * included in the Dagger component; absent when running without Bean Validation support.
     * When absent, incoming messages are not validated against Jakarta Bean Validation constraints.
     *
     * @return the optional {@link BeanValidator} binding declaration
     */
    @BindsOptionalOf
    abstract BeanValidator optionalBeanValidator();

    /**
     * Optional binding for {@link InputObjectProcessor}. Present when the
     * {@code SanitizationModule} is included in the Dagger component; absent when running
     * without canonicalization and sanitization support. When absent, incoming messages are
     * deserialized directly without any pre-materialization processing.
     *
     * @return the optional {@link InputObjectProcessor} binding declaration
     */
    @BindsOptionalOf
    abstract InputObjectProcessor optionalInputObjectProcessor();

    /**
     * Optional binding for {@link ChannelIdentityManager}. Present when {@code AuthModule} is
     * included in the Dagger component; absent when the security module is not configured.
     * Consumed by {@link WebSocketMount.Factory} to construct a
     * {@link dev.vertique.rest.websocket.WebSocketChannelAdapter} inline when both the manager
     * and the security runtime are available.
     *
     * @return the optional {@link ChannelIdentityManager} binding declaration
     */
    @BindsOptionalOf
    abstract ChannelIdentityManager optionalChannelIdentityManager();

    /**
     * Optional binding for the core action {@link Authorizer}. Present when the authorization engine
     * ({@code SecurityAuthzModule}) is in the component; absent otherwise. Threaded by
     * {@link WebSocketMount.Factory} into the {@link dev.vertique.rest.security.SecurityPolicyEnforcer}
     * so a class-level {@code @RequiresAction} gate is enforced once at upgrade (FR-AUTHZ-048,
     * ADR-0115). Declared here so a WebSocket-only component (no authz engine) still satisfies the
     * factory's injection; coalesces with {@code AuthModule}'s declaration when both are present.
     *
     * @return the optional core {@link Authorizer} binding declaration
     */
    @BindsOptionalOf
    abstract Authorizer optionalAuthorizer();

    /**
     * Optional binding for {@link ActionRegistry}. Present when the authorization engine
     * ({@code SecurityAuthzModule}) is in the component; absent otherwise. Used by
     * {@link dev.vertique.rest.websocket.WebSocketEndpointScanner} to validate a class-level
     * {@code @RequiresAction} at startup. When absent, any WebSocket endpoint declaring
     * {@code @RequiresAction} fails startup (fail-closed) because the action gate cannot be enforced.
     *
     * @return the optional {@link ActionRegistry} binding declaration
     */
    @BindsOptionalOf
    abstract ActionRegistry optionalActionRegistry();

    // --- Singleton providers ---

    /**
     * Provides the {@link WebSocketMessageCodec} singleton used for JSON serialization and
     * deserialization of WebSocket messages.
     *
     * @return a new {@link WebSocketMessageCodec} instance
     */
    @Provides
    @Singleton
    static WebSocketMessageCodec webSocketMessageCodec() {
        return new WebSocketMessageCodec();
    }

    /**
     * Provides {@link WebSocketConfig} by deserializing the {@code "websocket"} section of the
     * application configuration. Missing fields fall back to {@link WebSocketConfig} defaults
     * (base path {@code "/*"}).
     *
     * @param config the full application configuration
     * @param parser the injected config parser
     * @return the deserialized WebSocket configuration
     */
    @Provides
    @Singleton
    static WebSocketConfig webSocketConfig(
            @dev.vertique.core.VertxConfig io.vertx.core.json.JsonObject config, ConfigParser parser) {
        return parser.parse(JsonConfigPaths.navigateObject(config, "websocket"), WebSocketConfig.class);
    }

    // --- RouterMount contribution ---

    /**
     * Registers the WebSocket sub-router mount when endpoint instances are contributed via the
     * {@link WebSocketEndpoints} multibinding. Returns an empty set when no endpoints are
     * registered, avoiding an unnecessary sub-router allocation.
     *
     * @param factory   the WebSocket mount factory holding shared framework services
     * @param endpoints the set of WebSocket endpoint instances contributed by the application
     * @param config    the WebSocket configuration providing the base mount path
     * @return a singleton set containing the WebSocket mount, or an empty set if no endpoints
     *         are registered
     */
    @Provides
    @ElementsIntoSet
    static Set<RouterMount> webSocketRouterMount(
            WebSocketMount.Factory factory, @WebSocketEndpoints Set<Object> endpoints, WebSocketConfig config) {
        if (endpoints.isEmpty()) {
            return Set.of();
        }
        return Set.of(factory.create(config.basePath(), endpoints));
    }
}
