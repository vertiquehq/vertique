// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.rest.core.security.scheme.Http;
import dev.vertique.rest.core.security.scheme.SecuritySchemeDescription;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import io.vertx.ext.web.handler.AuthenticationHandler;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The security runtime every {@link CatalogApi} composition binds, so each restricting resource
 * registers without a startup violation:
 *
 * <ul>
 *   <li>a {@link SecuritySchemeHandler} for {@value #SCHEME_NAME}, describing HTTP bearer with
 *       format {@code JWT}, whose authentication handler rejects every request with {@code 401};
 *   <li>the {@link AuthEnforcementCapability} marker, as an installed authentication module would
 *       bind it, so role and action gates are enforceable;
 *   <li>an {@link ActionRegistry} holding exactly {@value #REQUIRED_ACTION};
 *   <li>a presence-only {@link Authorizer}: startup checks only that one is bound, and no request in
 *       the tests reaches it.
 * </ul>
 */
@Module
public final class CatalogSecurityModule {

    /** The security scheme {@link ScopelessResource} requires. */
    public static final String SCHEME_NAME = "bearerAuth";

    /** The canonical action {@link ActionResource} requires and the registry holds. */
    public static final String REQUIRED_ACTION = "catalog.items.read";

    private CatalogSecurityModule() {}

    /**
     * Contributes the {@value #SCHEME_NAME} handler.
     *
     * @return the handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler bearerAuthHandler() {
        return new DescribedBearerHandler();
    }

    /**
     * Provides the auth enforcement marker.
     *
     * @return the marker instance
     */
    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    /**
     * Provides a registry holding exactly {@value #REQUIRED_ACTION}.
     *
     * @return the registry
     */
    @Provides
    static ActionRegistry actionRegistry() {
        return new SingleActionRegistry(ActionRef.parse(REQUIRED_ACTION));
    }

    /**
     * Provides the presence-only authorizer.
     *
     * @return the authorizer
     */
    @Provides
    static Authorizer authorizer() {
        return new PresenceOnlyAuthorizer();
    }

    /** The {@value #SCHEME_NAME} handler: rejects every request, describes {@code Http.bearer("JWT")}. */
    private static final class DescribedBearerHandler implements SecuritySchemeHandler {

        @Override
        public String schemeName() {
            return SCHEME_NAME;
        }

        @Override
        public void configure(SecuritySchemeRegistry registry) {
            AuthenticationHandler rejectAll = ctx -> ctx.fail(401);
            registry.authenticationHandler(rejectAll);
        }

        @Override
        public Optional<SecuritySchemeDescription> openApiDescription() {
            return Optional.of(Http.bearer("JWT"));
        }
    }

    /** An {@link ActionRegistry} holding exactly one {@link ActionDefinition}. */
    private static final class SingleActionRegistry implements ActionRegistry {

        private final ActionRef action;
        private final ActionDefinition definition;

        SingleActionRegistry(ActionRef action) {
            this.action = action;
            this.definition = new ActionDefinition(action);
        }

        @Override
        public Collection<ActionDefinition> actions() {
            return List.of(definition);
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef candidate) {
            Objects.requireNonNull(candidate, "candidate");
            return action.equals(candidate) ? Optional.of(definition) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef candidate) {
            Objects.requireNonNull(candidate, "candidate");
            return action.equals(candidate);
        }
    }

    /** An {@link Authorizer} that only needs to be bound; its decision methods are never called. */
    private static final class PresenceOnlyAuthorizer implements Authorizer {

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException("presence-only fixture authorizer");
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("presence-only fixture authorizer");
        }
    }
}
