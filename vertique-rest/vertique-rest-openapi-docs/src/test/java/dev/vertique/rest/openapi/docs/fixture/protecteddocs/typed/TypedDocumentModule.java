// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.typed;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.auth.jwt.JwtAuthFactory;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.router.OperationHandlerContributor;
import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.runtime.GeneratedRestApplicationRegistration;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.LaterDocsPrefixMount;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.Observations;
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
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The fixtures of the typed document policy deployments, beside {@code RestModule}, the documentation
 * module, and the real {@code JwtAuthModule} (scheme {@code bearerAuth}), which brings the framework's
 * authentication and security modules.
 *
 * <ul>
 *   <li>Registers every application of {@link TypedDocumentApis} exactly as the generated registration
 *       module does, since the annotation processor does not run on framework test sources, and
 *       contributes their resources as manual {@code @JaxRsResources} instances.
 *   <li>Binds the action registry and a counting authorizer directly. The authorizer belongs to the
 *       application: it permits every subject except {@value #BLOCKED}, denies nobody else, throws for
 *       {@value #THROWING} and fails its future for {@value #FAILING}.
 *   <li>Contributes a late contributor, which runs after authorization and refuses a request carrying
 *       {@value #LATE_REFUSAL_HEADER}, and a later plain mount at {@code /apidocs/*} whose counters
 *       show whether a request fell through the documentation routes.
 * </ul>
 */
@Module
public final class TypedDocumentModule {

    /** The HS256 signing secret of the JWT provider; tests mint their tokens with it. */
    public static final String SIGNING_KEY = "typed-docs-test-secret-key-with-at-least-256-bits-for-hs256";

    /** The subject the authorizer never permits. */
    public static final String BLOCKED = "blocked";

    /** The subject for which the authorizer throws. */
    public static final String THROWING = "throwing";

    /** The subject for which the authorizer returns a failed future. */
    public static final String FAILING = "failing";

    /** The request header that makes the late contributor refuse the request. */
    public static final String LATE_REFUSAL_HEADER = "X-Late-Refusal";

    /** The priority of the late contributor: after authentication and authorization. */
    public static final int LATE_PRIORITY = 450;

    private TypedDocumentModule() {}

    /**
     * Provides the observation hub of the later mount.
     *
     * @return a new hub, one per component
     */
    @Provides
    @Singleton
    static Observations mountObservations() {
        return new Observations();
    }

    /**
     * Provides the typed observations.
     *
     * @return new observations, one per component
     */
    @Provides
    @Singleton
    static TypedDocumentObservations typedObservations() {
        return new TypedDocumentObservations();
    }

    /**
     * Provides the JWT provider the JWT authentication module's handler verifies tokens with.
     *
     * @param vertx the Vert.x instance
     * @return the provider
     */
    @Provides
    @Singleton
    static JWTAuth jwtAuth(Vertx vertx) {
        return JwtAuthFactory.fromSymmetricKey(vertx, "HS256", SIGNING_KEY);
    }

    /**
     * Mints a token.
     *
     * @param provider the provider of the deployment
     * @param subject  the subject
     * @param roles    the roles claim
     * @param scope    the space-separated scope claim, or {@code null} for none
     * @return the token
     */
    public static String token(JWTAuth provider, String subject, List<String> roles, String scope) {
        JsonObject claims = new JsonObject().put("sub", subject).put("roles", new JsonArray(roles));
        if (scope != null) {
            claims.put("scope", scope);
        }
        return provider.generateToken(claims);
    }

    /**
     * Registers {@link TypedDocumentApis.Open}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration open() {
        return registration(
                TypedDocumentApis.Open.class,
                TypedDocumentApis.Open.NAME,
                TypedDocumentApis.Open.PATH,
                TypedDocumentApis.Open.Items.class);
    }

    /**
     * Registers {@link TypedDocumentApis.Deny}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration deny() {
        return registration(
                TypedDocumentApis.Deny.class,
                TypedDocumentApis.Deny.NAME,
                TypedDocumentApis.Deny.PATH,
                TypedDocumentApis.Deny.Items.class);
    }

    /**
     * Registers {@link TypedDocumentApis.Authenticated}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration authenticated() {
        return registration(
                TypedDocumentApis.Authenticated.class,
                TypedDocumentApis.Authenticated.NAME,
                TypedDocumentApis.Authenticated.PATH,
                TypedDocumentApis.Authenticated.Items.class);
    }

    /**
     * Registers {@link TypedDocumentApis.Roles}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration roles() {
        return registration(
                TypedDocumentApis.Roles.class,
                TypedDocumentApis.Roles.NAME,
                TypedDocumentApis.Roles.PATH,
                TypedDocumentApis.Roles.Items.class);
    }

    /**
     * Registers {@link TypedDocumentApis.Scopes}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration scopes() {
        return registration(
                TypedDocumentApis.Scopes.class,
                TypedDocumentApis.Scopes.NAME,
                TypedDocumentApis.Scopes.PATH,
                TypedDocumentApis.Scopes.Items.class);
    }

    /**
     * Registers {@link TypedDocumentApis.Action}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration action() {
        return registration(
                TypedDocumentApis.Action.class,
                TypedDocumentApis.Action.NAME,
                TypedDocumentApis.Action.PATH,
                TypedDocumentApis.Action.Items.class);
    }

    /**
     * Registers {@link TypedDocumentApis.Combined}.
     *
     * @return the registration
     */
    @Provides
    @IntoSet
    static GeneratedRestApplicationRegistration combined() {
        return registration(
                TypedDocumentApis.Combined.class,
                TypedDocumentApis.Combined.NAME,
                TypedDocumentApis.Combined.PATH,
                TypedDocumentApis.Combined.Items.class);
    }

    private static GeneratedRestApplicationRegistration registration(
            Class<?> declaringType, String name, String path, Class<?> resource) {
        return GeneratedRestApplicationRegistration.of(declaringType, name, path, List.of(resource), false, "", true);
    }

    /**
     * Contributes the resources of every application.
     *
     * @return the resource instances
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object openItems() {
        return new TypedDocumentApis.Open.Items();
    }

    /**
     * Contributes the resource of {@link TypedDocumentApis.Deny}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object denyItems() {
        return new TypedDocumentApis.Deny.Items();
    }

    /**
     * Contributes the resource of {@link TypedDocumentApis.Authenticated}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object authenticatedItems() {
        return new TypedDocumentApis.Authenticated.Items();
    }

    /**
     * Contributes the resource of {@link TypedDocumentApis.Roles}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object rolesItems() {
        return new TypedDocumentApis.Roles.Items();
    }

    /**
     * Contributes the resource of {@link TypedDocumentApis.Scopes}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object scopesItems() {
        return new TypedDocumentApis.Scopes.Items();
    }

    /**
     * Contributes the resource of {@link TypedDocumentApis.Action}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object actionItems() {
        return new TypedDocumentApis.Action.Items();
    }

    /**
     * Contributes the resource of {@link TypedDocumentApis.Combined}.
     *
     * @return a new resource instance
     */
    @Provides
    @IntoSet
    @JaxRsResources
    static Object combinedItems() {
        return new TypedDocumentApis.Combined.Items();
    }

    /**
     * Binds the action registry, which registers exactly {@link TypedDocumentApis#ACTION}.
     *
     * @return the registry
     */
    @Provides
    static ActionRegistry actionRegistry() {
        return new FixedActions(ActionRef.parse(TypedDocumentApis.ACTION));
    }

    /**
     * Binds the application's counting authorizer.
     *
     * @param observations the typed observations
     * @return the authorizer
     */
    @Provides
    @Singleton
    static Authorizer authorizer(TypedDocumentObservations observations) {
        return new CountingAuthorizer(observations);
    }

    /**
     * Contributes the late contributor.
     *
     * @param observations the typed observations
     * @return the contributor
     */
    @Provides
    @IntoSet
    static OperationHandlerContributor lateContributor(TypedDocumentObservations observations) {
        return new LateContributor(observations);
    }

    /**
     * Contributes the later plain mount at the documentation prefix.
     *
     * @param observations the mount observation hub
     * @return the mount
     */
    @Provides
    @IntoSet
    static RouterMount laterDocsPrefixMount(Observations observations) {
        return new LaterDocsPrefixMount(observations);
    }

    /** An action registry that knows exactly one action. */
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
     * An authorizer that records every action it evaluates, denies {@value #BLOCKED}, throws for
     * {@value #THROWING}, fails its future for {@value #FAILING}, and permits everyone else.
     */
    private static final class CountingAuthorizer implements Authorizer {

        private final TypedDocumentObservations observations;

        CountingAuthorizer(TypedDocumentObservations observations) {
            this.observations = observations;
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            observations.recordAuthorization(request.action());
            String subject = request.securityContext().identity().actor().id();
            if (THROWING.equals(subject)) {
                throw new IllegalStateException("the evaluator failed on purpose");
            }
            if (FAILING.equals(subject)) {
                return Future.failedFuture(new IllegalStateException("the evaluator's future failed on purpose"));
            }
            return Future.succeededFuture(
                    BLOCKED.equals(subject)
                            ? AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED)
                            : AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("the action gate evaluates the request overload only");
        }
    }

    /**
     * A contributor after authorization: it counts the requests that reach it and fails a request
     * carrying {@value #LATE_REFUSAL_HEADER} with {@code 403}.
     */
    private static final class LateContributor implements OperationHandlerContributor {

        private final TypedDocumentObservations observations;

        LateContributor(TypedDocumentObservations observations) {
            this.observations = observations;
        }

        @Override
        public int priority() {
            return LATE_PRIORITY;
        }

        @Override
        public void contribute(OperationRegistrationContext context) {
            context.route().addHandler(ctx -> {
                observations.recordLateContributorRun();
                if (ctx.request().getHeader(LATE_REFUSAL_HEADER) != null) {
                    ctx.fail(403);
                    return;
                }
                ctx.next();
            });
        }
    }
}
