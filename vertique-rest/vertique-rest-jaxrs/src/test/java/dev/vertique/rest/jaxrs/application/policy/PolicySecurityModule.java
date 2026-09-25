// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.routing.SecuritySchemeRegistry;
import dev.vertique.rest.core.security.AuthEnforcementCapability;
import dev.vertique.rest.core.security.SecuritySchemeHandler;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.ResourceRef;
import io.vertx.core.Future;
import io.vertx.ext.web.handler.SimpleAuthenticationHandler;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T005 TP-002/TP-004 fixture module: the security runtime the {@code policy} resources unit's
 * (c), (d), and (e) variants need to register without a startup violation, so any FR-013
 * classification observed by {@code ExplicitSecurityPolicyTest} reflects C-POLICY alone, never a
 * missing collaborator.
 *
 * <ul>
 *   <li>a {@link SecuritySchemeHandler} for {@link #SCHEME_NAME}, so the registrar's fail-closed
 *       scheme guard ({@code JaxRsRouteRegistrar#applySecurity}) has a handler to collect for (c)
 *       and (e)'s {@code @SecurityRequirement};</li>
 *   <li>the {@link AuthEnforcementCapability} marker, so the auth-enforcement runtime reads as
 *       installed ({@code authEnabled}), a precondition {@code resolveRequiredAction} checks
 *       before an action gate can resolve;</li>
 *   <li>an {@link ActionRegistry} holding exactly {@link #REQUIRED_ACTION}, and an
 *       {@link Authorizer} whose sole role is to be <em>present</em> — {@code resolveRequiredAction}
 *       only checks {@code Optional<Authorizer>#isPresent()} at startup, never invokes it — so (d)'s
 *       {@code @RequiresAction} resolves instead of failing closed.
 * </ul>
 */
@Module
public final class PolicySecurityModule {

    /** The security scheme name (c) and (e)'s {@code @SecurityRequirement} declare. */
    public static final String SCHEME_NAME = "bearerAuth";

    /** The canonical action (d)'s {@code @RequiresAction} declares and this module registers. */
    public static final String REQUIRED_ACTION = "mgmt.items.read";

    private PolicySecurityModule() {}

    /**
     * Installs {@link #SCHEME_NAME}'s authentication handler so a declared
     * {@code @SecurityRequirement} for that scheme registers instead of fail-closing.
     *
     * @return the scheme handler
     */
    @Provides
    @IntoSet
    static SecuritySchemeHandler policySchemeHandler() {
        return new SecuritySchemeHandler() {
            @Override
            public String schemeName() {
                return SCHEME_NAME;
            }

            @Override
            public void configure(SecuritySchemeRegistry registry) {
                registry.authenticationHandler(SimpleAuthenticationHandler.create());
            }
        };
    }

    /**
     * Marks the auth-enforcement runtime as installed, so {@code resolveRequiredAction} treats
     * {@code authEnabled} as {@code true}.
     *
     * @return the single framework-owned marker instance
     */
    @Provides
    static AuthEnforcementCapability authEnforcementCapability() {
        return AuthEnforcementCapability.INSTANCE;
    }

    /**
     * A minimal {@link ActionRegistry} holding exactly {@link #REQUIRED_ACTION}.
     *
     * @return the registry
     */
    @Provides
    static ActionRegistry actionRegistry() {
        return new SingleActionRegistry(ActionRef.parse(REQUIRED_ACTION));
    }

    /**
     * A presence-only {@link Authorizer}: {@code resolveRequiredAction} checks only that this
     * binding is present at startup, so its decision methods are never called by TP-002 or TP-004.
     *
     * @return the authorizer
     */
    @Provides
    static Authorizer authorizer() {
        return new PresenceOnlyAuthorizer();
    }

    /** {@link ActionRegistry} holding exactly one {@link ActionDefinition}. */
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

    /**
     * {@link Authorizer} whose decision methods are never exercised by this proof: only its
     * presence in the Dagger graph matters to {@code resolveRequiredAction}.
     */
    private static final class PresenceOnlyAuthorizer implements Authorizer {

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            throw new UnsupportedOperationException(
                    "PresenceOnlyAuthorizer is a presence-only fixture; TP-002/TP-004 never invoke it");
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException(
                    "PresenceOnlyAuthorizer is a presence-only fixture; TP-002/TP-004 never invoke it");
        }
    }
}
