// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.policy;

import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.exception.ForbiddenException;
import dev.vertique.examples.services.codegen.policy.service.PolicyHandlerService;
import dev.vertique.examples.services.codegen.policy.service.PolicyService;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.authz.DefaultActionRegistry;
import dev.vertique.security.runtime.authz.DefaultAuthorizer;
import dev.vertique.security.runtime.authz.InMemoryPolicyDefinitionSource;
import dev.vertique.security.runtime.authz.InMemoryRolePolicyResolver;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.vertx.core.Future;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Shared fixtures of the typed access-policy proof application: named policies, the counting
 * business effects, the application-owned eligibility flag, the recording authorization engine and
 * decision log, and the identity resolver that produces a marked REST caller.
 *
 * <p>The two service contracts and the REST resource of the proof live in the sibling packages; the
 * generated registration modules wire them into {@link PolicyTestComponent}.
 */
public final class PolicyFixtures {

    /** Symmetric key the JWT authentication provider and the test token factory share. */
    public static final String JWT_KEY = "super-secret-key-for-policy-fixture-app-minimum-256-bits-long!!";

    /** The registered action the action-bearing policies declare. */
    public static final String ACTION_VALUE = "svc.exec.run";

    /** Role the recording authorizer grants {@link #ACTION_VALUE} to. */
    public static final String EXECUTOR_ROLE = "executor";

    /** Role the local predicate of the role-bearing service policies requires. */
    public static final String OPERATOR_ROLE = "operator";

    /** Role the outer REST policy requires. */
    public static final String ADMIN_ROLE = "admin";

    /** First of the two scopes the scope policy requires. */
    public static final String READ_SCOPE = "svc.read";

    /** Second of the two scopes the scope policy requires. */
    public static final String WRITE_SCOPE = "svc.write";

    /** Resource id the eligibility flag accepts by default. */
    public static final String ELIGIBLE_RESOURCE = "mine";

    /** Resource id the eligibility flag rejects by default. */
    public static final String INELIGIBLE_RESOURCE = "someone-elses";

    /** The {@code sid} claim value that makes the REST identity carry a subject. */
    public static final String SID_SUBJECT = "on-behalf";

    /** The {@code sid} claim value that makes the REST identity carry a delegation context. */
    public static final String SID_DELEGATION = "delegated";

    /** Reason code literal for a caller shape local typed predicates do not support. */
    public static final String UNSUPPORTED_POLICY_CALLER = "UNSUPPORTED_POLICY_CALLER";

    /** Operation without any security declaration. */
    public static final String OP_UNRESTRICTED = "unrestricted";

    /** Operation whose policy permits everyone. */
    public static final String OP_PERMIT = "permit";

    /** Operation whose policy denies everyone. */
    public static final String OP_DENY = "deny";

    /** Operation whose policy requires only authentication. */
    public static final String OP_AUTHENTICATED = "authenticated";

    /** Operation whose policy requires the operator role. */
    public static final String OP_OPERATOR = "operator";

    /** Operation whose policy requires both scopes. */
    public static final String OP_SCOPED = "scoped";

    /** Operation whose policy declares only an action. */
    public static final String OP_ACTION = "action";

    /** Operation whose policy requires the operator role and declares an action. */
    public static final String OP_OPERATOR_ACTION = "operatorAction";

    /** Operation whose policy requires the operator role and whose body checks eligibility. */
    public static final String OP_OWNED = "owned";

    private static final String GRANTING_POLICY_NAME = "executor-policy";

    private PolicyFixtures() {}

    // --- Named policies ---

    /** Permits every caller, authenticated or not. */
    @PermitAll
    public interface PermitPolicy extends AccessPolicy {}

    /** Denies every caller. */
    @DenyAll
    public interface DenyPolicy extends AccessPolicy {}

    /** Requires a non-anonymous caller and nothing else. */
    @Authorized
    public interface AuthenticatedPolicy extends AccessPolicy {}

    /** Requires the operator role. */
    @RolesAllowed(OPERATOR_ROLE)
    public interface OperatorPolicy extends AccessPolicy {}

    /** Requires the admin role. */
    @RolesAllowed(ADMIN_ROLE)
    public interface AdminPolicy extends AccessPolicy {}

    /** Requires both the read and the write scope. */
    @Authorized(scopes = {READ_SCOPE, WRITE_SCOPE})
    public interface BothScopesPolicy extends AccessPolicy {}

    /** Declares only the registered action. */
    @RequiresAction(ACTION_VALUE)
    public interface ActionOnlyPolicy extends AccessPolicy {}

    /** Requires the operator role first and the registered action second. */
    @RolesAllowed(OPERATOR_ROLE)
    @RequiresAction(ACTION_VALUE)
    public interface OperatorAndActionPolicy extends AccessPolicy {}

    // --- Business effects and application-owned state ---

    /** One business effect: a service body that ran. */
    public record Effect(String pattern, String operation, String actorId) {}

    /** Counts every business effect the service bodies perform. */
    @Singleton
    public static final class Effects {
        private final List<Effect> performed = new CopyOnWriteArrayList<>();

        /** Creates the empty effect log. */
        @Inject
        public Effects() {}

        /**
         * Records that a service body ran.
         *
         * @param pattern   the implementation pattern, {@code direct} or {@code handler}
         * @param operation the operation name
         * @param actorId   the actor id the body observed, or {@code none}
         */
        public void record(String pattern, String operation, String actorId) {
            performed.add(new Effect(pattern, operation, actorId));
        }

        /**
         * Returns every effect in arrival order.
         *
         * @return the effects
         */
        public List<Effect> all() {
            return List.copyOf(performed);
        }

        /**
         * Returns how many effects were performed.
         *
         * @return the effect count
         */
        public int total() {
            return performed.size();
        }

        /** Forgets every effect. */
        public void reset() {
            performed.clear();
        }
    }

    /** The application-owned resource eligibility check the service bodies consult. */
    @Singleton
    public static final class Eligibility {
        private final Set<String> eligible = ConcurrentHashMap.newKeySet();

        /** Creates the check with {@link #ELIGIBLE_RESOURCE} eligible. */
        @Inject
        public Eligibility() {
            reset();
        }

        /**
         * Returns whether the resource may be acted on.
         *
         * @param resourceId the resource id
         * @return whether it is eligible
         */
        public boolean isEligible(String resourceId) {
            return eligible.contains(resourceId);
        }

        /** Restores the default: only {@link #ELIGIBLE_RESOURCE} is eligible. */
        public void reset() {
            eligible.clear();
            eligible.add(ELIGIBLE_RESOURCE);
        }
    }

    /** Raised by a service body when the caller may not act on the resource. */
    public static final class NotEligibleException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param resourceId the resource the caller may not act on
         */
        public NotEligibleException(String resourceId) {
            super("resource not eligible: " + resourceId);
        }
    }

    // --- Operation tables shared by the REST resource and the test ---

    /**
     * Maps every operation name to the generated client call of the direct-implementation contract.
     *
     * @param client the generated client
     * @return the operation table
     */
    public static Map<String, Function<String, Future<String>>> operations(PolicyService client) {
        return Map.of(
                OP_UNRESTRICTED, client::unrestricted,
                OP_PERMIT, client::permit,
                OP_DENY, client::deny,
                OP_AUTHENTICATED, client::authenticated,
                OP_OPERATOR, client::operator,
                OP_SCOPED, client::scoped,
                OP_ACTION, client::action,
                OP_OPERATOR_ACTION, client::operatorAction,
                OP_OWNED, client::owned);
    }

    /**
     * Maps every operation name to the generated client call of the handler contract.
     *
     * @param client the generated client
     * @return the operation table
     */
    public static Map<String, Function<String, Future<String>>> operations(PolicyHandlerService client) {
        return Map.of(
                OP_UNRESTRICTED, client::unrestricted,
                OP_PERMIT, client::permit,
                OP_DENY, client::deny,
                OP_AUTHENTICATED, client::authenticated,
                OP_OPERATOR, client::operator,
                OP_SCOPED, client::scoped,
                OP_ACTION, client::action,
                OP_OPERATOR_ACTION, client::operatorAction,
                OP_OWNED, client::owned);
    }

    // --- Authorization engine ---

    /** An {@link Authorizer} that counts and records every request and can misbehave on demand. */
    @Singleton
    public static final class RecordingAuthorizer implements Authorizer {

        /** How the authorizer answers. */
        public enum Mode {
            /** Delegate to the granting authorizer. */
            DELEGATE,
            /** Throw instead of answering. */
            THROW,
            /** Return a {@code null} future. */
            NULL_FUTURE,
            /** Return a failed future. */
            FAILED_FUTURE,
            /** Return a future that resolves to {@code null}. */
            NULL_DECISION
        }

        private final DefaultActionRegistry registry;
        private final Authorizer delegate;
        private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DELEGATE);
        private final List<AuthorizationRequest> requests = new CopyOnWriteArrayList<>();

        /** Creates the authorizer over a registry that contains only {@link PolicyFixtures#ACTION_VALUE}. */
        @Inject
        public RecordingAuthorizer() {
            ActionContributor contributor = new ActionContributor() {
                @Override
                public Collection<ActionDefinition> actions() {
                    return List.of(new ActionDefinition(ActionRef.parse(ACTION_VALUE)));
                }
            };
            this.registry = new DefaultActionRegistry(Set.of(contributor));
            PolicyDefinition policy = new PolicyDefinition(
                    GRANTING_POLICY_NAME,
                    List.of(new PolicyStatement(
                            dev.vertique.security.authz.Effect.ALLOW, Set.of(new ActionPattern(ACTION_VALUE)))));
            InMemoryPolicyDefinitionSource source = new InMemoryPolicyDefinitionSource(List.of(policy));
            source.withRegistry(registry);
            this.delegate = new DefaultAuthorizer(
                    registry,
                    source,
                    new InMemoryRolePolicyResolver(Map.of(EXECUTOR_ROLE, List.of(GRANTING_POLICY_NAME))));
        }

        /**
         * Returns the action registry this authorizer validates against.
         *
         * @return the registry
         */
        public DefaultActionRegistry registry() {
            return registry;
        }

        /**
         * Chooses how the authorizer answers from now on.
         *
         * @param newMode the behavior
         */
        public void answer(Mode newMode) {
            mode.set(newMode);
        }

        /**
         * Returns how many evaluations were requested.
         *
         * @return the call count
         */
        public int calls() {
            return requests.size();
        }

        /**
         * Returns every request received, in arrival order.
         *
         * @return the requests
         */
        public List<AuthorizationRequest> requests() {
            return List.copyOf(requests);
        }

        /** Forgets every request and restores {@link Mode#DELEGATE}. */
        public void reset() {
            requests.clear();
            mode.set(Mode.DELEGATE);
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            requests.add(request);
            return switch (mode.get()) {
                case DELEGATE -> delegate.authorize(request);
                case THROW -> throw new IllegalStateException("evaluator failure");
                case NULL_FUTURE -> null;
                case FAILED_FUTURE -> Future.failedFuture("evaluator failed");
                case NULL_DECISION -> Future.succeededFuture(null);
            };
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            return authorize(
                    new AuthorizationRequest(ctx, action.value(), resource, InvocationOrigin.unspecified(), Map.of()));
        }
    }

    /** Records every authorization decision event the application emits, whichever boundary raised it. */
    @Singleton
    public static final class DecisionLog implements SecurityEventObserver {
        private final List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();

        /** Creates the empty log. */
        @Inject
        public DecisionLog() {}

        @Override
        public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
            events.add(event);
            return Future.succeededFuture();
        }

        /**
         * Returns every event in arrival order.
         *
         * @return the events
         */
        public List<AuthorizationDecisionEvent> all() {
            return List.copyOf(events);
        }

        /**
         * Returns the events the REST pipeline raised.
         *
         * @return the REST boundary events
         */
        public List<AuthorizationDecisionEvent> rest() {
            return ofBoundary(DispatchBoundary.REST);
        }

        /**
         * Returns the events the service dispatch raised.
         *
         * @return the service boundary events
         */
        public List<AuthorizationDecisionEvent> service() {
            return ofBoundary(DispatchBoundary.SERVICE_DISPATCH);
        }

        /** Forgets every event. */
        public void reset() {
            events.clear();
        }

        private List<AuthorizationDecisionEvent> ofBoundary(String kind) {
            return events.stream()
                    .filter(event -> kind.equals(event.request().origin().kind()))
                    .toList();
        }
    }

    /**
     * Application interceptor that tries to turn every authorization denial into a success; a
     * denial must never reach it.
     */
    @Singleton
    public static final class DenialRecovery implements ServiceInterceptor {
        private final AtomicInteger recovered = new AtomicInteger();

        /** Creates the interceptor. */
        @Inject
        public DenialRecovery() {}

        /**
         * Returns how many authorization denials this interceptor was asked to recover.
         *
         * @return the recovery attempt count
         */
        public int attempts() {
            return recovered.get();
        }

        /** Forgets every attempt. */
        public void reset() {
            recovered.set(0);
        }

        @Override
        public Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
            if (error instanceof ForbiddenException) {
                recovered.incrementAndGet();
                return Future.succeededFuture();
            }
            return Future.failedFuture(error);
        }
    }

    // --- Callers ---

    /**
     * A named {@link SecurityContext} for direct generated-client calls, whose unsupported-shape
     * markers (reconstruction, subject, delegation) are added independently.
     */
    public static final class Caller implements SecurityContext {
        private final SecurityIdentity identity;
        private final AuthorizationClaims claims;
        private final Optional<ReconstructionMarker> reconstruction;

        private Caller(
                SecurityIdentity identity, AuthorizationClaims claims, Optional<ReconstructionMarker> reconstruction) {
            this.identity = identity;
            this.claims = claims;
            this.reconstruction = reconstruction;
        }

        /**
         * Creates a trusted user with no claims and no markers.
         *
         * @param id the actor id
         * @return the caller
         */
        public static Caller user(String id) {
            return new Caller(
                    SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, id, Map.of())),
                    AuthorizationClaims.empty(),
                    Optional.empty());
        }

        /**
         * Creates a bound anonymous caller.
         *
         * @return the caller
         */
        public static Caller anonymous() {
            return new Caller(SecurityIdentity.anonymous(), AuthorizationClaims.empty(), Optional.empty());
        }

        /**
         * Returns a copy that also holds the given ROLE claims.
         *
         * @param roles the roles
         * @return the new caller
         */
        public Caller withRoles(String... roles) {
            return withClaims(AuthorityKind.ROLE, roles);
        }

        /**
         * Returns a copy that also holds the given SCOPE claims.
         *
         * @param scopes the scopes
         * @return the new caller
         */
        public Caller withScopes(String... scopes) {
            return withClaims(AuthorityKind.SCOPE, scopes);
        }

        /**
         * Returns a copy that carries a verified-reconstruction marker.
         *
         * @return the marked caller
         */
        public Caller reconstructed() {
            return new Caller(
                    identity, claims, Optional.of(new ReconstructionMarker(ReconstructedAuthorityMode.CAPTURED)));
        }

        /**
         * Returns a copy whose identity carries a subject.
         *
         * @return the marked caller
         */
        public Caller withSubject() {
            PrincipalRef subject = new PrincipalRef(PrincipalType.USER, "subject-of-" + id(), Map.of());
            return new Caller(
                    new SecurityIdentity(
                            identity.actor(), Optional.of(subject), identity.delegation(), identity.client()),
                    claims,
                    reconstruction);
        }

        /**
         * Returns a copy whose identity carries a delegation context.
         *
         * @return the marked caller
         */
        public Caller withDelegation() {
            DelegationContext delegation =
                    new DelegationContext("test-delegation", "authority-" + id(), Optional.empty(), Map.of());
            return new Caller(
                    new SecurityIdentity(
                            identity.actor(), identity.subject(), Optional.of(delegation), identity.client()),
                    claims,
                    reconstruction);
        }

        /**
         * Returns the actor id.
         *
         * @return the id
         */
        public String id() {
            return identity.actor().id();
        }

        private Caller withClaims(AuthorityKind kind, String... values) {
            Set<AuthorityClaim> merged = new HashSet<>(claims.claims());
            for (String value : values) {
                merged.add(new AuthorityClaim(kind, value, "", "", "test", Map.of()));
            }
            return new Caller(identity, new AuthorizationClaims(merged, Map.of()), reconstruction);
        }

        @Override
        public SecurityIdentity identity() {
            return identity;
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return claims;
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }

        @Override
        public Optional<ReconstructionMarker> reconstruction() {
            return reconstruction;
        }
    }

    /**
     * Produces a marked REST caller: a verified JWT whose {@code sid} claim is {@link #SID_SUBJECT}
     * or {@link #SID_DELEGATION} resolves to an identity carrying a subject or a delegation context.
     * Every other token falls through to the framework resolver. The REST route policies accept the
     * marked caller; the adopted service predicates do not.
     */
    public static final class MarkerIdentityResolver implements SecurityIdentityResolver {

        /** Creates the resolver. */
        public MarkerIdentityResolver() {}

        /** {@inheritDoc} */
        @Override
        public int priority() {
            return 50;
        }

        /** {@inheritDoc} */
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            if (context.evidence().isEmpty()) {
                return Future.succeededFuture(Optional.empty());
            }
            Map<String, Object> attributes = context.evidence().get(0).safeAttributes();
            Object sub = attributes.get("sub");
            Object sid = attributes.get("sid");
            if (!(sub instanceof String actorId) || !(sid instanceof String marker)) {
                return Future.succeededFuture(Optional.empty());
            }
            PrincipalRef actor = new PrincipalRef(PrincipalType.USER, actorId, Map.of());
            if (SID_SUBJECT.equals(marker)) {
                PrincipalRef subject = new PrincipalRef(PrincipalType.USER, "subject-of-" + actorId, Map.of());
                return Future.succeededFuture(Optional.of(
                        new SecurityIdentity(actor, Optional.of(subject), Optional.empty(), Optional.empty())));
            }
            if (SID_DELEGATION.equals(marker)) {
                DelegationContext delegation =
                        new DelegationContext("test-delegation", "authority-" + actorId, Optional.empty(), Map.of());
                return Future.succeededFuture(Optional.of(
                        new SecurityIdentity(actor, Optional.empty(), Optional.of(delegation), Optional.empty())));
            }
            return Future.succeededFuture(Optional.empty());
        }
    }
}
