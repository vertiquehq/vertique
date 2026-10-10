// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.synthetic;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.rest.core.security.RouteAuthHandler;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The building blocks of the typed synthetic compositions. Each block supplies one capability, so a
 * component omits exactly the capability a refusal case needs missing:
 *
 * <ul>
 *   <li>{@link ConfigSupport}: the configuration parser every composition needs;
 *   <li>{@link NoSecurity}: the absent security-policy validator of a graph without the security
 *       modules;
 *   <li>{@link BearerScheme}: the bearer security scheme handler;
 *   <li>{@link OneRouteAuthHandler} and {@link SecondRouteAuthHandler}: route authentication
 *       handlers for action-only operations;
 *   <li>{@link RegisteredActions} and {@link EmptyActions}: an action registry that does or does not
 *       register {@link TypedPolicies#ACTION};
 *   <li>{@link CountingAuthorizer}: an authorizer counting every action it evaluates;
 *   <li>{@link Observing}: the shared observations.
 * </ul>
 *
 * <p>The security runtime itself, the identity middleware and the authorization contributors come
 * from the framework's own {@code AuthModule} and {@code SecurityModule}, never from here.
 */
final class TypedSyntheticModules {

    /** The subject the counting authorizer never permits. */
    static final String BLOCKED_SUBJECT = "blocked";

    private TypedSyntheticModules() {}

    @Module
    static final class ConfigSupport {

        private ConfigSupport() {}

        /**
         * The Vert.x instance the resilience runtime installed by the security modules needs. One
         * instance is shared by every synthetic component in the test JVM, which exits without
         * closing it.
         *
         * @return the shared Vert.x instance
         */
        @Provides
        static Vertx vertx() {
            return SharedVertx.INSTANCE;
        }

        /** Lazily creates the shared instance. */
        private static final class SharedVertx {

            static final Vertx INSTANCE = Vertx.vertx();

            private SharedVertx() {}
        }

        @Provides
        static ConfigParser configParser() {
            return new DefaultConfigParser(DefaultConfigMapper.lenient());
        }

        /**
         * The application configuration: a loopback server on an ephemeral port and no request
         * validation.
         *
         * @return the configuration
         */
        @Provides
        @VertxConfig
        static JsonObject config() {
            return new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("jaxrs", new JsonObject().put("validationStrategy", "none"));
        }
    }

    @Module
    static final class NoSecurity {

        private NoSecurity() {}

        /**
         * The absent validator of a graph that includes neither {@code AuthModule} nor
         * {@code SecurityModule}.
         *
         * @return {@code null}
         */
        @Provides
        @Nullable
        static SecurityPolicyValidator securityPolicyValidator() {
            return null;
        }
    }

    @Module
    static final class BearerScheme {

        private BearerScheme() {}

        @Provides
        @IntoSet
        static SecuritySchemeHandler bearerSchemeHandler() {
            return TypedBearerAuthentication.schemeHandler(TypedBearerAuthentication.SCHEME);
        }
    }

    @Module
    static final class OneRouteAuthHandler {

        private OneRouteAuthHandler() {}

        @Provides
        @IntoSet
        static RouteAuthHandler bearerRouteHandler() {
            return TypedBearerAuthentication.routeHandler(TypedBearerAuthentication.SCHEME);
        }
    }

    @Module
    static final class SecondRouteAuthHandler {

        private SecondRouteAuthHandler() {}

        @Provides
        @IntoSet
        static RouteAuthHandler otherRouteHandler() {
            return TypedBearerAuthentication.routeHandler("otherAuth");
        }
    }

    @Module
    static final class Observing {

        private Observing() {}

        @Provides
        @Singleton
        static TypedSyntheticObservations observations() {
            return new TypedSyntheticObservations();
        }
    }

    @Module
    static final class RegisteredActions {

        private RegisteredActions() {}

        @Provides
        static ActionRegistry actionRegistry() {
            return new FixedActions(ActionRef.parse(TypedPolicies.ACTION));
        }
    }

    @Module
    static final class EmptyActions {

        private EmptyActions() {}

        @Provides
        static ActionRegistry actionRegistry() {
            return new FixedActions(ActionRef.parse("typed.doc.other"));
        }
    }

    @Module
    static final class CountingAuthorizer {

        private CountingAuthorizer() {}

        @Provides
        @Singleton
        static Authorizer authorizer(TypedSyntheticObservations observations) {
            return new Counting(observations);
        }
    }

    /** An action registry that knows exactly the given actions. */
    private static final class FixedActions implements ActionRegistry {

        private final ActionRef known;

        FixedActions(ActionRef known) {
            this.known = known;
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(new ActionDefinition(known));
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return known.value().equals(action.value());
        }
    }

    /**
     * An authorizer that records every action it evaluates and permits every subject except the
     * blocked one.
     */
    private static final class Counting implements Authorizer {

        private final TypedSyntheticObservations observations;

        Counting(TypedSyntheticObservations observations) {
            this.observations = observations;
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            observations.recordAuthorization(request.action());
            boolean blocked = BLOCKED_SUBJECT.equals(
                    request.securityContext().identity().actor().id());
            return Future.succeededFuture(
                    blocked
                            ? AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED)
                            : AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("the action gate evaluates the request overload only");
        }
    }
}
