// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.DelegationContext;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.ReconstructionMarker;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.AccessPolicyResolver;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorized;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.ReconstructedAuthorityMode;
import dev.vertique.security.authz.RequiresAction;
import dev.vertique.security.authz.RequiresPolicy;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.authz.RolePolicyResolver;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.origin.RequestOrigin;
import dev.vertique.security.runtime.authz.DefaultActionRegistry;
import dev.vertique.security.runtime.authz.DefaultAuthorizer;
import dev.vertique.security.runtime.authz.InMemoryPolicyDefinitionSource;
import dev.vertique.security.runtime.authz.InMemoryRolePolicyResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared fixtures for the typed access-policy service dispatch proofs: named policies, named
 * callers, service contracts with counting implementations, and a dispatch harness that wires a
 * real event-bus consumer to {@link ServiceAuthorizationInterceptor}.
 *
 * <p>Policies are public nested interfaces so the policy resolver accepts them. Every policy name
 * states the requirement it carries.
 */
public final class TypedPolicyServiceFixtures {

    /** The registered action the action-bearing policies and the inline action declaration name. */
    public static final String ACTION_VALUE = "svc.exec.run";

    /** Role the test authorizer grants {@link #ACTION_VALUE} to. */
    public static final String EXECUTOR_ROLE = "executor";

    /** Role the local predicate of the combined policy requires. */
    public static final String OPERATOR_ROLE = "operator";

    /** First of the two scopes the scope policies require. */
    public static final String READ_SCOPE = "svc.read";

    /** Second of the two scopes the scope policies require. */
    public static final String WRITE_SCOPE = "svc.write";

    /** Reason code literal for a caller shape local typed predicates do not support. */
    public static final String UNSUPPORTED_POLICY_CALLER = "UNSUPPORTED_POLICY_CALLER";

    private static final String GRANTING_POLICY_NAME = "executor-policy";
    private static final DeliveryOptions ENVELOPE_CODEC = new DeliveryOptions().setCodecName("dispatch.envelope");

    private TypedPolicyServiceFixtures() {}

    // --- Named policies ---

    /** Local role predicate: the caller must hold the executor role. */
    @RolesAllowed(EXECUTOR_ROLE)
    public interface ExecutorRolePolicy extends AccessPolicy {}

    /** Local scope predicate: the caller must hold both scopes. */
    @Authorized(scopes = {READ_SCOPE, WRITE_SCOPE})
    public interface AllScopesPolicy extends AccessPolicy {}

    /** Local scope predicate: the caller must hold at least one of the scopes. */
    @Authorized(
            scopes = {READ_SCOPE, WRITE_SCOPE},
            matchAll = false)
    public interface AnyScopePolicy extends AccessPolicy {}

    /** Local authentication predicate only. */
    @Authorized
    public interface AuthenticatedOnlyPolicy extends AccessPolicy {}

    /** Public: no authorization work and no event. */
    @PermitAll
    public interface PublicPolicy extends AccessPolicy {}

    /** Deny every caller, including no caller. */
    @DenyAll
    public interface DenyEveryonePolicy extends AccessPolicy {}

    /** Action only: the configured authorizer decides. */
    @RequiresAction(ACTION_VALUE)
    public interface ActionOnlyPolicy extends AccessPolicy {}

    /** Local operator role plus the registered action. */
    @RolesAllowed(OPERATOR_ROLE)
    @RequiresAction(ACTION_VALUE)
    public interface OperatorAndActionPolicy extends AccessPolicy {}

    /** Action that is syntactically invalid. */
    @RequiresAction("INVALID")
    public interface MalformedActionPolicy extends AccessPolicy {}

    /** Action that parses but is not registered. */
    @RequiresAction("unknown.x.y")
    public interface UnregisteredActionPolicy extends AccessPolicy {}

    // --- Contracts and counting implementations ---

    /** One operation per policy shape, plus inline-only and unrestricted operations. */
    @ServiceContract(namespace = "it", value = "typed-policy")
    public interface TypedPolicyContract {
        /**
         * Local role predicate.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(ExecutorRolePolicy.class)
        @ServiceOperation("rolesOnly")
        Future<String> rolesOnly(String payload);

        /**
         * Local all-scopes predicate.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(AllScopesPolicy.class)
        @ServiceOperation("allScopes")
        Future<String> allScopes(String payload);

        /**
         * Local any-scope predicate.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(AnyScopePolicy.class)
        @ServiceOperation("anyScope")
        Future<String> anyScope(String payload);

        /**
         * Local authentication predicate.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(AuthenticatedOnlyPolicy.class)
        @ServiceOperation("authenticatedOnly")
        Future<String> authenticatedOnly(String payload);

        /**
         * Public operation.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(PublicPolicy.class)
        @ServiceOperation("publicOp")
        Future<String> publicOp(String payload);

        /**
         * Deny-all operation.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(DenyEveryonePolicy.class)
        @ServiceOperation("denyAll")
        Future<String> denyAll(String payload);

        /**
         * Action-only operation.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(ActionOnlyPolicy.class)
        @ServiceOperation("actionOnly")
        Future<String> actionOnly(String payload);

        /**
         * Combined local and action operation.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(OperatorAndActionPolicy.class)
        @ServiceOperation("operatorAndAction")
        Future<String> operatorAndAction(String payload);

        /**
         * Inline-only action operation that keeps its existing behavior.
         *
         * @param payload input
         * @return result
         */
        @RequiresAction(ACTION_VALUE)
        @ServiceOperation("inlineAction")
        Future<String> inlineAction(String payload);

        /**
         * Operation with no security declaration.
         *
         * @param payload input
         * @return result
         */
        @ServiceOperation("unrestricted")
        Future<String> unrestricted(String payload);
    }

    /** Contract whose type-level policy guards its operations unless a method policy replaces it. */
    @ServiceContract(namespace = "it", value = "typed-type-level")
    @RequiresPolicy(ExecutorRolePolicy.class)
    public interface TypeLevelPolicyContract {
        /**
         * Operation guarded by the type-level policy.
         *
         * @param payload input
         * @return result
         */
        @ServiceOperation("typeGuarded")
        Future<String> typeGuarded(String payload);

        /**
         * Operation whose method policy replaces the type-level policy.
         *
         * @param payload input
         * @return result
         */
        @RequiresPolicy(PublicPolicy.class)
        @ServiceOperation("methodReplaces")
        Future<String> methodReplaces(String payload);
    }

    /** Implementation of {@link TypedPolicyContract} that counts every business execution. */
    public static final class TypedPolicyService implements TypedPolicyContract {
        private final AtomicInteger executions = new AtomicInteger();

        /**
         * Returns how many operations reached business logic.
         *
         * @return the execution count
         */
        public int executions() {
            return executions.get();
        }

        private Future<String> run(String operation, String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture(operation + ":" + payload);
        }

        @Override
        public Future<String> rolesOnly(String payload) {
            return run("rolesOnly", payload);
        }

        @Override
        public Future<String> allScopes(String payload) {
            return run("allScopes", payload);
        }

        @Override
        public Future<String> anyScope(String payload) {
            return run("anyScope", payload);
        }

        @Override
        public Future<String> authenticatedOnly(String payload) {
            return run("authenticatedOnly", payload);
        }

        @Override
        public Future<String> publicOp(String payload) {
            return run("publicOp", payload);
        }

        @Override
        public Future<String> denyAll(String payload) {
            return run("denyAll", payload);
        }

        @Override
        public Future<String> actionOnly(String payload) {
            return run("actionOnly", payload);
        }

        @Override
        public Future<String> operatorAndAction(String payload) {
            return run("operatorAndAction", payload);
        }

        @Override
        public Future<String> inlineAction(String payload) {
            return run("inlineAction", payload);
        }

        @Override
        public Future<String> unrestricted(String payload) {
            return run("unrestricted", payload);
        }
    }

    /** Implementation of {@link TypeLevelPolicyContract} that counts every business execution. */
    public static final class TypeLevelPolicyService implements TypeLevelPolicyContract {
        private final AtomicInteger executions = new AtomicInteger();

        /**
         * Returns how many operations reached business logic.
         *
         * @return the execution count
         */
        public int executions() {
            return executions.get();
        }

        @Override
        public Future<String> typeGuarded(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("typeGuarded:" + payload);
        }

        @Override
        public Future<String> methodReplaces(String payload) {
            executions.incrementAndGet();
            return Future.succeededFuture("methodReplaces:" + payload);
        }
    }

    // --- Metadata ---

    /**
     * Builds the metadata a registrar or generated contributor produces for one contract operation,
     * collecting security declarations from the actual contract.
     *
     * @param contract  the service contract
     * @param impl      the implementation instance
     * @param operation the operation name (method name and {@link ServiceOperation} value)
     * @param address   the event bus address to bind
     * @return the operation metadata
     */
    public static ServiceMethodMeta metaFor(Class<?> contract, Object impl, String operation, String address) {
        Method method = methodNamed(contract, operation);
        ServiceContract annotation = contract.getAnnotation(ServiceContract.class);
        List<Annotation> methodAnnotations =
                AccessPolicyResolver.collectMethodAnnotations(contract, method, List.of(method.getAnnotations()));
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                "it." + annotation.value() + "." + operation,
                annotation.namespace(),
                annotation.value(),
                operation,
                String.class,
                String.class,
                List.of(new ParamMeta("payload", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                methodAnnotations,
                List.of(contract.getAnnotations()),
                false);
    }

    /**
     * Returns the one declared method with the given name.
     *
     * @param type the declaring type
     * @param name the method name
     * @return the method
     */
    public static Method methodNamed(Class<?> type, String name) {
        return Arrays.stream(type.getMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no method " + name + " on " + type.getName()));
    }

    /**
     * Returns the {@link RequiresPolicy} annotation declared on a contract method.
     *
     * @param contract  the contract
     * @param operation the method name
     * @return the declared annotation
     */
    public static RequiresPolicy requiresPolicyOn(Class<?> contract, String operation) {
        RequiresPolicy found = methodNamed(contract, operation).getAnnotation(RequiresPolicy.class);
        if (found == null) {
            throw new IllegalStateException("no @RequiresPolicy on " + operation);
        }
        return found;
    }

    // --- Callers ---

    /**
     * A named {@link SecurityContext} whose claims can be replaced between calls and whose three
     * unsupported-shape markers (reconstruction, subject, delegation) can be added independently.
     */
    public static final class Caller implements SecurityContext {
        private final SecurityIdentity identity;
        private final AtomicReference<AuthorizationClaims> claims;
        private final Optional<ReconstructionMarker> reconstruction;

        private Caller(
                SecurityIdentity identity, AuthorizationClaims claims, Optional<ReconstructionMarker> reconstruction) {
            this.identity = identity;
            this.claims = new AtomicReference<>(claims);
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
         * Returns a copy that also holds the given PERMISSION claims.
         *
         * @param permissions the permissions
         * @return the new caller
         */
        public Caller withPermissions(String... permissions) {
            return withClaims(AuthorityKind.PERMISSION, permissions);
        }

        /**
         * Returns a copy that carries a verified-reconstruction marker.
         *
         * @return the marked caller
         */
        public Caller reconstructed() {
            return new Caller(
                    identity, claims.get(), Optional.of(new ReconstructionMarker(ReconstructedAuthorityMode.CAPTURED)));
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
                    claims.get(),
                    reconstruction);
        }

        /**
         * Returns a copy whose identity carries a delegation context.
         *
         * @return the marked caller
         */
        public Caller withDelegation() {
            DelegationContext delegation =
                    new DelegationContext("deferred-execution", "authority-" + id(), Optional.empty(), Map.of());
            return new Caller(
                    new SecurityIdentity(
                            identity.actor(), identity.subject(), Optional.of(delegation), identity.client()),
                    claims.get(),
                    reconstruction);
        }

        /**
         * Replaces the claims this same instance reports from now on.
         *
         * @param replacement the new claims
         */
        public void replaceClaims(AuthorizationClaims replacement) {
            claims.set(replacement);
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
            Set<AuthorityClaim> merged = new HashSet<>(claims.get().claims());
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
            return claims.get();
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

    // --- Authorization engine fixtures ---

    /**
     * An {@link Authorizer} that counts and records every request and can be told to misbehave
     * or to hold every evaluation until released.
     */
    public static final class RecordingAuthorizer implements Authorizer {

        /** How the authorizer answers. */
        public enum Mode {
            /** Delegate to the wrapped authorizer. */
            DELEGATE,
            /** Throw instead of answering. */
            THROW,
            /** Return a {@code null} future. */
            NULL_FUTURE,
            /** Return a failed future. */
            FAILED_FUTURE,
            /** Return a future resolving to {@code null}. */
            NULL_DECISION,
            /** Hold every evaluation until {@link #releaseGatesInReverseOrder()}, then delegate. */
            GATED
        }

        private final Authorizer delegate;
        private final Mode mode;
        private final int gateSize;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<AuthorizationRequest> requests = new CopyOnWriteArrayList<>();
        private final List<Promise<Void>> gates = new CopyOnWriteArrayList<>();
        private final CountDownLatch gateReached = new CountDownLatch(1);

        private RecordingAuthorizer(Authorizer delegate, Mode mode, int gateSize) {
            this.delegate = delegate;
            this.mode = mode;
            this.gateSize = gateSize;
        }

        /**
         * Creates an authorizer that delegates to the executor-granting authorizer.
         *
         * @param registry the registry the delegate validates against
         * @return the recording authorizer
         */
        public static RecordingAuthorizer delegating(DefaultActionRegistry registry) {
            return new RecordingAuthorizer(executorAuthorizer(registry), Mode.DELEGATE, 0);
        }

        /**
         * Creates an authorizer that answers in a contract-violating way.
         *
         * @param registry the registry the unused delegate validates against
         * @param mode     the misbehavior
         * @return the recording authorizer
         */
        public static RecordingAuthorizer misbehaving(DefaultActionRegistry registry, Mode mode) {
            return new RecordingAuthorizer(executorAuthorizer(registry), mode, 0);
        }

        /**
         * Creates an authorizer that holds evaluations until released.
         *
         * @param registry the registry the delegate validates against
         * @param gateSize how many pending evaluations count as "reached"
         * @return the recording authorizer
         */
        public static RecordingAuthorizer gated(DefaultActionRegistry registry, int gateSize) {
            return new RecordingAuthorizer(executorAuthorizer(registry), Mode.GATED, gateSize);
        }

        /**
         * Returns how many evaluations were requested.
         *
         * @return the call count
         */
        public int calls() {
            return calls.get();
        }

        /**
         * Returns every request received, in arrival order.
         *
         * @return the requests
         */
        public List<AuthorizationRequest> requests() {
            return List.copyOf(requests);
        }

        /**
         * Waits until {@code gateSize} evaluations are pending.
         *
         * @return whether the gate size was reached in time
         * @throws InterruptedException if interrupted
         */
        public boolean awaitGateReached() throws InterruptedException {
            return gateReached.await(5, TimeUnit.SECONDS);
        }

        /** Completes every pending evaluation, last arrival first. */
        public void releaseGatesInReverseOrder() {
            List<Promise<Void>> snapshot = new ArrayList<>(gates);
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                snapshot.get(i).complete();
            }
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            return switch (mode) {
                case DELEGATE -> delegate.authorize(request);
                case THROW -> throw new IllegalStateException("evaluator failure");
                case NULL_FUTURE -> null;
                case FAILED_FUTURE -> Future.failedFuture("evaluator failed");
                case NULL_DECISION -> Future.succeededFuture(null);
                case GATED -> {
                    Promise<Void> gate = Promise.promise();
                    gates.add(gate);
                    if (gates.size() == gateSize) {
                        gateReached.countDown();
                    }
                    yield gate.future().compose(v -> delegate.authorize(request));
                }
            };
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            return authorize(
                    new AuthorizationRequest(ctx, action.value(), resource, InvocationOrigin.unspecified(), Map.of()));
        }
    }

    /** An {@link ActionRegistry} that counts every lookup made through it. */
    public static final class CountingActionRegistry implements ActionRegistry {
        private final ActionRegistry delegate;
        private final AtomicInteger lookups = new AtomicInteger();

        private CountingActionRegistry(ActionRegistry delegate) {
            this.delegate = delegate;
        }

        /**
         * Returns how many lookups were made.
         *
         * @return the lookup count
         */
        public int lookups() {
            return lookups.get();
        }

        @Override
        public Collection<ActionDefinition> actions() {
            lookups.incrementAndGet();
            return delegate.actions();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            lookups.incrementAndGet();
            return delegate.find(action);
        }

        @Override
        public boolean contains(ActionRef action) {
            lookups.incrementAndGet();
            return delegate.contains(action);
        }
    }

    /**
     * The authorization engine handed to the interceptor: either absent or a counted registry plus a
     * recording authorizer.
     *
     * @param authorizer the recording authorizer, or {@code null} when the engine is absent
     * @param registry   the counting registry, or {@code null} when the engine is absent
     */
    public record Engine(RecordingAuthorizer authorizer, CountingActionRegistry registry) {

        /**
         * Returns an engine that is not installed.
         *
         * @return the absent engine
         */
        public static Engine absent() {
            return new Engine(null, null);
        }

        /**
         * Returns an installed engine whose authorizer delegates to the executor-granting authorizer.
         *
         * @return the engine
         */
        public static Engine installed() {
            DefaultActionRegistry registry = actionRegistry();
            return new Engine(RecordingAuthorizer.delegating(registry), new CountingActionRegistry(registry));
        }

        /**
         * Returns an installed engine whose authorizer misbehaves.
         *
         * @param mode the misbehavior
         * @return the engine
         */
        public static Engine misbehaving(RecordingAuthorizer.Mode mode) {
            DefaultActionRegistry registry = actionRegistry();
            return new Engine(RecordingAuthorizer.misbehaving(registry, mode), new CountingActionRegistry(registry));
        }

        /**
         * Returns an installed engine whose authorizer holds evaluations until released.
         *
         * @param gateSize how many pending evaluations count as reached
         * @return the engine
         */
        public static Engine gated(int gateSize) {
            DefaultActionRegistry registry = actionRegistry();
            return new Engine(RecordingAuthorizer.gated(registry, gateSize), new CountingActionRegistry(registry));
        }
    }

    /**
     * Builds a registry that contains only {@link #ACTION_VALUE}.
     *
     * @return the registry
     */
    public static DefaultActionRegistry actionRegistry() {
        return new DefaultActionRegistry(
                Set.of(new FixedActionContributor(List.of(new ActionDefinition(ActionRef.parse(ACTION_VALUE))))));
    }

    private static DefaultAuthorizer executorAuthorizer(DefaultActionRegistry registry) {
        PolicyDefinition policy = new PolicyDefinition(
                GRANTING_POLICY_NAME,
                List.of(new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern(ACTION_VALUE)))));
        InMemoryPolicyDefinitionSource source = new InMemoryPolicyDefinitionSource(List.of(policy));
        source.withRegistry(registry);
        RolePolicyResolver resolver =
                new InMemoryRolePolicyResolver(Map.of(EXECUTOR_ROLE, List.of(GRANTING_POLICY_NAME)));
        return new DefaultAuthorizer(registry, source, resolver);
    }

    private record FixedActionContributor(List<ActionDefinition> definitions) implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return definitions;
        }
    }

    // --- Application interceptor that tries to recover every failure ---

    /** Recovers every failure it sees; a denial must never reach it. */
    public static final class RecoverEverything implements ServiceInterceptor {
        private final AtomicBoolean recoverInvoked = new AtomicBoolean();

        /**
         * Returns whether {@code recoverError} was ever invoked.
         *
         * @return whether recovery ran
         */
        public boolean recoverInvoked() {
            return recoverInvoked.get();
        }

        @Override
        public Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
            recoverInvoked.set(true);
            return Future.succeededFuture();
        }
    }

    // --- Dispatch harness ---

    /**
     * A real event-bus consumer for one operation, guarded by a real
     * {@link ServiceAuthorizationInterceptor} followed by an application interceptor that recovers
     * every failure.
     */
    public static final class Harness implements AutoCloseable {
        private final Vertx vertx;
        private final MessageConsumer<?> consumer;
        private final String address;
        private final List<AuthorizationDecisionEvent> events;
        private final Engine engine;
        private final RecoverEverything permissive;

        private Harness(
                Vertx vertx,
                MessageConsumer<?> consumer,
                String address,
                List<AuthorizationDecisionEvent> events,
                Engine engine,
                RecoverEverything permissive) {
            this.vertx = vertx;
            this.consumer = consumer;
            this.address = address;
            this.events = events;
            this.engine = engine;
            this.permissive = permissive;
        }

        /**
         * Starts a consumer for the operation described by the metadata.
         *
         * @param vertx  the Vert.x instance
         * @param meta   the operation metadata, whose address is bound
         * @param engine the authorization engine handed to the interceptor
         * @return the harness
         */
        public static Harness start(Vertx vertx, ServiceMethodMeta meta, Engine engine) {
            registerCodecs(vertx);
            List<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(capturingObserver(events)));
            ServiceAuthorizationInterceptor interceptor = new ServiceAuthorizationInterceptor(
                    Optional.ofNullable(engine.authorizer()),
                    Optional.ofNullable(engine.registry()),
                    emitter,
                    new DefaultContextHolder(),
                    Set.of(meta));
            RecoverEverything permissive = new RecoverEverything();
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                    meta, new ServiceExceptionMapper(), List.of(interceptor, permissive), null);
            MessageConsumer<?> consumer = vertx.eventBus().consumer(meta.address(), invoker);
            return new Harness(vertx, consumer, meta.address(), events, engine, permissive);
        }

        /**
         * Returns every authorization decision event emitted so far.
         *
         * @return the events
         */
        public List<AuthorizationDecisionEvent> events() {
            return events;
        }

        /**
         * Returns the engine this harness was started with.
         *
         * @return the engine
         */
        public Engine engine() {
            return engine;
        }

        /**
         * Returns whether the application interceptor was asked to recover a failure.
         *
         * @return whether recovery ran
         */
        public boolean recoverInvoked() {
            return permissive.recoverInvoked();
        }

        /**
         * Dispatches one call and waits for the reply.
         *
         * @param caller the propagated caller, or {@code null} for a dispatch with no security context
         * @return the dispatch result
         * @throws Exception if the reply does not arrive in time
         */
        public Result<?> dispatchAndAwait(Caller caller) throws Exception {
            return await(dispatch(caller, null));
        }

        /**
         * Dispatches one call without waiting.
         *
         * @param caller the propagated caller, or {@code null} for none
         * @param origin the upstream invocation origin to propagate, or {@code null} for none
         * @return the pending dispatch result
         */
        public Future<Result<?>> dispatch(Caller caller, InvocationOrigin origin) {
            Map<String, Object> carried = new HashMap<>();
            if (caller != null) {
                carried.put(SecurityContext.class.getName(), caller);
            }
            if (origin != null) {
                carried.put(InvocationOrigin.class.getName(), origin);
            }
            DispatchEnvelope<String> envelope = carried.isEmpty()
                    ? DispatchEnvelope.of("hello")
                    : DispatchEnvelope.of("hello", DispatchMetadata.of(carried));
            return vertx.eventBus()
                    .<Result<?>>request(address, envelope, ENVELOPE_CODEC)
                    .map(Message::body);
        }

        @Override
        public void close() {
            try {
                await(consumer.unregister());
            } catch (Exception e) {
                throw new IllegalStateException("could not unregister consumer at " + address, e);
            }
        }
    }

    /**
     * Waits for a future from a non-event-loop thread.
     *
     * @param future the future
     * @param <T>    the value type
     * @return the value
     * @throws Exception if it fails or does not complete in time
     */
    public static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
    }

    /**
     * Registers the dispatch codecs once per Vert.x instance.
     *
     * @param vertx the Vert.x instance
     */
    public static void registerCodecs(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // Already registered by another class sharing this Vert.x instance.
        }
    }

    /**
     * Returns an observer that appends every authorization decision event to the list.
     *
     * @param target the list to append to
     * @return the observer
     */
    public static SecurityEventObserver capturingObserver(List<AuthorizationDecisionEvent> target) {
        return new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                target.add(event);
                return Future.succeededFuture();
            }
        };
    }
}
