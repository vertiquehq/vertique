// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.ACTION_VALUE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.EXECUTOR_ROLE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.OPERATOR_ROLE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.READ_SCOPE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.UNSUPPORTED_POLICY_CALLER;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.WRITE_SCOPE;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.await;
import static dev.vertique.services.interceptor.TypedPolicyServiceFixtures.metaFor;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.ActionContributor;
import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionPattern;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.Effect;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.PolicyDefinition;
import dev.vertique.security.authz.PolicyStatement;
import dev.vertique.security.authz.RequiresAction;
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
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Caller;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Engine;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.Harness;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.RecordingAuthorizer;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.TypeLevelPolicyContract;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.TypeLevelPolicyService;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.TypedPolicyContract;
import dev.vertique.services.interceptor.TypedPolicyServiceFixtures.TypedPolicyService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * Integration test for {@link ServiceAuthorizationInterceptor} wired into a real Vert.x event bus
 * via a {@link ServiceMethodInvoker} consumer.
 *
 * <p>Three scenarios are covered:
 * <ol>
 *   <li>Permitted actor — dispatch proceeds and exactly one {@link AuthorizationDecisionEvent} with
 *       {@code permitted=true} is emitted.</li>
 *   <li>Denied actor — dispatch short-circuits (handler never runs) with a failed reply and exactly
 *       one {@link AuthorizationDecisionEvent} with {@code permitted=false} is emitted.</li>
 *   <li>Fail-closed (no {@link SecurityContext} in the dispatch context) — dispatch short-circuits
 *       with a failed reply and exactly one deny event is emitted.</li>
 * </ol>
 *
 * <p>The harness follows the minimal event-bus-only pattern established by
 * {@code ServiceDispatchMetricsIT}: no Dagger, no HTTP server; all setup is done directly against
 * the real implementations.
 *
 * <p>All tests are class-level timeout-guarded at 20&nbsp;s to prevent hangs on CI.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ServiceAuthorizationInterceptorIT {

    // --- Test action constant ---

    private static final String ACTION_VALUE = "svc.exec.run";
    private static final ActionRef TEST_ACTION = ActionRef.parse(ACTION_VALUE);
    private static final String POLICY_NAME = "executor-policy";
    private static final String PERMITTED_ROLE = "executor";
    private static final String DENIED_ROLE = "viewer";

    // --- Contract fixture ---

    /** Service contract interface with a {@link RequiresAction}-annotated operation. */
    @ServiceContract(namespace = "it", value = "authz-it")
    interface AuthzItContract {
        /**
         * Operation guarded by {@link RequiresAction}.
         *
         * @param payload the input payload
         * @return the echoed payload
         */
        @RequiresAction(ACTION_VALUE)
        @ServiceOperation("run")
        Future<String> run(String payload);
    }

    // --- Service implementation fixture ---

    /** Implementation that echoes its payload. Always executes if not blocked. */
    static class EchoImpl implements AuthzItContract {
        @Override
        public Future<String> run(String payload) {
            return Future.succeededFuture("echo:" + payload);
        }
    }

    // --- Shared state ---

    private static final AtomicInteger ADDR = new AtomicInteger(0);

    private static String uniqueAddress() {
        return "it/authz/" + ADDR.incrementAndGet();
    }

    private static final DeliveryOptions ENVELOPE_CODEC = new DeliveryOptions().setCodecName("dispatch.envelope");

    /** Codecs are registered once per Vert.x instance. */
    @BeforeAll
    static void registerCodecs(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // Already registered by another test class sharing the same Vert.x instance.
        }
    }

    // --- Engine builders ---

    /**
     * Builds a {@link DefaultActionRegistry} containing only {@link #TEST_ACTION}.
     *
     * @return the action registry
     */
    private static DefaultActionRegistry testRegistry() {
        return new DefaultActionRegistry(
                Set.of(new FixedActionContributor(List.of(new ActionDefinition(TEST_ACTION)))));
    }

    /**
     * Builds a {@link DefaultAuthorizer} that permits the {@link #PERMITTED_ROLE} role on
     * {@link #TEST_ACTION} and denies every other role.
     *
     * @return the authorizer
     */
    private static DefaultAuthorizer permittingAuthorizer() {
        DefaultActionRegistry registry = testRegistry();
        PolicyDefinition policy = new PolicyDefinition(
                POLICY_NAME, List.of(new PolicyStatement(Effect.ALLOW, Set.of(new ActionPattern(ACTION_VALUE)))));
        InMemoryPolicyDefinitionSource source = new InMemoryPolicyDefinitionSource(List.of(policy));
        // withRegistry validates the policy against the registry (startup check).
        source.withRegistry(registry);
        RolePolicyResolver resolver = new InMemoryRolePolicyResolver(Map.of(PERMITTED_ROLE, List.of(POLICY_NAME)));
        return new DefaultAuthorizer(registry, source, resolver);
    }

    // --- SecurityContext stubs ---

    /**
     * Builds a {@link SecurityContext} stub whose authorization claims carry the given role.
     *
     * @param role the ROLE claim value to include
     * @return a stub security context with the given role
     */
    private static SecurityContext secCtxWithRole(String role) {
        Set<AuthorityClaim> claims = Set.of(new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of()));
        AuthorizationClaims authz = new AuthorizationClaims(claims, Map.of());
        return new StubSecurityContext(authz);
    }

    // --- Meta builder ---

    /**
     * Builds {@link ServiceMethodMeta} for the {@code run} operation at the given address,
     * with method annotations harvested from {@link AuthzItContract#run}.
     *
     * @param impl    the service implementation
     * @param address the event bus address to bind the consumer to
     * @return the meta record
     * @throws Exception if method lookup fails
     */
    private static ServiceMethodMeta runMeta(Object impl, String address) throws Exception {
        Method method = AuthzItContract.class.getMethod("run", String.class);
        List<Annotation> methodAnnotations = List.of(method.getAnnotations());
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                "it.authz-it.run",
                "it",
                "authz-it",
                "run",
                String.class,
                String.class,
                List.of(new ParamMeta("payload", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                methodAnnotations,
                List.of(),
                false);
    }

    // --- Scenario: permitted actor ---

    @Test
    @DisplayName("permitted actor: dispatch proceeds and one permit event is emitted")
    void permitted_actorHasPermittedRole_dispatchSucceedsAndPermitEventEmitted(Vertx vertx, VertxTestContext ctx)
            throws Exception {
        CopyOnWriteArrayList<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(capturingObserver(events)));
        DefaultAuthorizer authorizer = permittingAuthorizer();
        DefaultActionRegistry registry = testRegistry();
        DefaultContextHolder holder = new DefaultContextHolder();

        String address = uniqueAddress();
        ServiceMethodMeta meta = runMeta(new EchoImpl(), address);
        ServiceAuthorizationInterceptor wiredInterceptor = new ServiceAuthorizationInterceptor(
                Optional.of(authorizer), Optional.of(registry), emitter, holder, Set.of(meta));

        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(wiredInterceptor), null);
        vertx.eventBus().consumer(address, invoker);

        SecurityContext permittedCtx = secCtxWithRole(PERMITTED_ROLE);
        Map<String, Object> ctxMap = Map.of(SecurityContext.class.getName(), permittedCtx);
        DispatchEnvelope<String> envelope = DispatchEnvelope.of("hello", DispatchMetadata.of(ctxMap));

        vertx.eventBus()
                .<dev.vertique.core.eventbus.Result<?>>request(address, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    dev.vertique.core.eventbus.Result<?> result = msg.body();
                    assertTrue(result.isSuccess(), "dispatch should succeed for permitted actor");
                    assertEquals(1, events.size(), "exactly one authorization event expected");
                    assertTrue(events.get(0).decision().permitted(), "event must carry a permit decision");
                    ctx.completeNow();
                })));
    }

    // --- Scenario: denied actor ---

    @Test
    @DisplayName("denied actor: dispatch short-circuits with failure and one deny event is emitted")
    void denied_actorHasUnpermittedRole_dispatchFailsAndDenyEventEmitted(Vertx vertx, VertxTestContext ctx)
            throws Exception {
        CopyOnWriteArrayList<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(capturingObserver(events)));
        DefaultAuthorizer authorizer = permittingAuthorizer();
        DefaultActionRegistry registry = testRegistry();
        DefaultContextHolder holder = new DefaultContextHolder();

        String address = uniqueAddress();
        ServiceMethodMeta meta = runMeta(new EchoImpl(), address);
        ServiceAuthorizationInterceptor wiredInterceptor = new ServiceAuthorizationInterceptor(
                Optional.of(authorizer), Optional.of(registry), emitter, holder, Set.of(meta));

        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(wiredInterceptor), null);
        vertx.eventBus().consumer(address, invoker);

        SecurityContext deniedCtx = secCtxWithRole(DENIED_ROLE);
        Map<String, Object> ctxMap = Map.of(SecurityContext.class.getName(), deniedCtx);
        DispatchEnvelope<String> envelope = DispatchEnvelope.of("hello", DispatchMetadata.of(ctxMap));

        // The interceptor short-circuits with a failed Future → ServiceMethodInvoker replies with
        // a Result.failure. The event bus reply itself still succeeds (result envelope returned),
        // so we use succeeding() and inspect the Result body.
        vertx.eventBus()
                .<dev.vertique.core.eventbus.Result<?>>request(address, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    dev.vertique.core.eventbus.Result<?> result = msg.body();
                    assertFalse(result.isSuccess(), "dispatch should fail for denied actor");
                    assertEquals(1, events.size(), "exactly one authorization event expected");
                    assertFalse(events.get(0).decision().permitted(), "event must carry a deny decision");
                    ctx.completeNow();
                })));
    }

    // --- Scenario: fail-closed (no SecurityContext) ---

    @Test
    @DisplayName("fail-closed: absent SecurityContext → dispatch short-circuits with failure and one deny event")
    void failClosed_noSecurityContext_dispatchFailsAndDenyEventEmitted(Vertx vertx, VertxTestContext ctx)
            throws Exception {
        CopyOnWriteArrayList<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(capturingObserver(events)));
        DefaultAuthorizer authorizer = permittingAuthorizer();
        DefaultActionRegistry registry = testRegistry();
        DefaultContextHolder holder = new DefaultContextHolder();

        String address = uniqueAddress();
        ServiceMethodMeta meta = runMeta(new EchoImpl(), address);
        ServiceAuthorizationInterceptor wiredInterceptor = new ServiceAuthorizationInterceptor(
                Optional.of(authorizer), Optional.of(registry), emitter, holder, Set.of(meta));

        ServiceMethodInvoker invoker =
                new ServiceMethodInvoker(meta, new ServiceExceptionMapper(), List.of(wiredInterceptor), null);
        vertx.eventBus().consumer(address, invoker);

        // No SecurityContext in the dispatch context — interceptor must fail closed.
        DispatchEnvelope<String> envelope = DispatchEnvelope.of("hello");

        vertx.eventBus()
                .<dev.vertique.core.eventbus.Result<?>>request(address, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    dev.vertique.core.eventbus.Result<?> result = msg.body();
                    assertFalse(result.isSuccess(), "dispatch should fail when SecurityContext is absent");
                    assertEquals(1, events.size(), "exactly one authorization event expected on fail-closed");
                    assertFalse(
                            events.get(0).decision().permitted(), "event must carry a deny decision on fail-closed");
                    ctx.completeNow();
                })));
    }

    // --- Scenario: permissive recoverError must NOT resurrect a deny (fail-closed) ---

    @Test
    @DisplayName("fail-closed: a permissive recoverError does NOT resurrect a denied @RequiresAction dispatch")
    void failClosed_permissiveRecoverError_doesNotResurrectDeny(Vertx vertx, VertxTestContext ctx) throws Exception {
        CopyOnWriteArrayList<AuthorizationDecisionEvent> events = new CopyOnWriteArrayList<>();
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(capturingObserver(events)));
        DefaultAuthorizer authorizer = permittingAuthorizer();
        DefaultActionRegistry registry = testRegistry();
        DefaultContextHolder holder = new DefaultContextHolder();

        String address = uniqueAddress();
        ServiceMethodMeta meta = runMeta(new EchoImpl(), address);
        ServiceAuthorizationInterceptor wiredInterceptor = new ServiceAuthorizationInterceptor(
                Optional.of(authorizer), Optional.of(registry), emitter, holder, Set.of(meta));

        // A broadly permissive recoverError that recovers EVERY failure. It is ordered after the
        // action gate (gate is SYSTEM_FIRST/-100; this app interceptor is later), so a deny reaches it
        // — and must still NOT be recovered, because the deny is a NonRecoverableDispatchFailure.
        RecoverEverythingInterceptor permissive = new RecoverEverythingInterceptor();
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                meta, new ServiceExceptionMapper(), List.of(wiredInterceptor, permissive), null);
        vertx.eventBus().consumer(address, invoker);

        // Denied actor: viewer role is not permitted on the action.
        SecurityContext deniedCtx = secCtxWithRole(DENIED_ROLE);
        Map<String, Object> ctxMap = Map.of(SecurityContext.class.getName(), deniedCtx);
        DispatchEnvelope<String> envelope = DispatchEnvelope.of("hello", DispatchMetadata.of(ctxMap));

        vertx.eventBus()
                .<dev.vertique.core.eventbus.Result<?>>request(address, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    dev.vertique.core.eventbus.Result<?> result = msg.body();
                    assertFalse(
                            result.isSuccess(),
                            "a permissive recoverError must NOT turn an authorization deny into success");
                    assertFalse(permissive.recoverInvoked.get(), "recoverError must be bypassed for the authz deny");
                    assertEquals(1, events.size(), "exactly one authorization event expected");
                    assertFalse(events.get(0).decision().permitted(), "event must carry a deny decision");
                    ctx.completeNow();
                })));
    }

    // --- Typed policy: rejection before dispatch effects ---

    private static final Set<String> LOCAL_ONLY_OPERATIONS = Set.of(
            "rolesOnly",
            "allScopes",
            "anyScope",
            "authenticatedOnly",
            "denyAll",
            "publicOp",
            "typeGuarded",
            "methodReplaces");

    @Test
    @DisplayName("typed policies reject with exactly one event before any dispatch effect, per policy shape and caller")
    void shouldRejectTypedPolicyBeforeDispatchEffects(Vertx vertx) throws Exception {
        List<Executable> scenarios = new ArrayList<>();

        // Given local-only policies (roles, scopes, authenticated-only, deny, public, type-level),
        // exercised both with an installed authorization engine and with none installed
        for (Supplier<Engine> engine : List.of((Supplier<Engine>) Engine::installed, Engine::absent)) {
            scenarios.add(() -> rolePolicyEnforcesEveryCallerShape(vertx, engine));
            scenarios.add(() -> scopePoliciesCombineScopeAndPermissionClaims(vertx, engine));
            scenarios.add(() -> authenticatedOnlyPolicyRequiresATrustedCaller(vertx, engine));
            scenarios.add(() -> denyPolicyRefusesEvenWithoutACaller(vertx, engine));
            scenarios.add(() -> publicPolicyDoesNoAuthorizationWorkAndEmitsNothing(vertx, engine));
            scenarios.add(() -> typeLevelPolicyGuardsUntilAMethodPolicyReplacesIt(vertx, engine));
        }

        // Given an action-only policy and a combined local-plus-action policy
        scenarios.add(() -> actionOnlyPolicyFollowsTheExistingActionPath(vertx));
        scenarios.add(() -> combinedPolicyEvaluatesLocalChecksBeforeTheActionOnce(vertx));
        scenarios.add(() -> evaluatorFailuresDenyNonRecoverablyForActionPolicies(vertx));

        // When claims change between calls and different callers are dispatched concurrently
        scenarios.add(() -> claimsAreEvaluatedOnEveryCall(vertx));
        scenarios.add(() -> concurrentLocalCallersDoNotLeakIntoEachOther(vertx));
        scenarios.add(() -> concurrentActionCallersKeepTheirOwnIdentityAndOrigin(vertx));

        // Then every restrictive attempt is rejected or permitted on its own merits
        assertAll("typed policy dispatch", scenarios);
    }

    @Test
    @DisplayName("characterization: inline-only declarations keep their existing action-gate behavior")
    void characterization_inlineOnlyDeclarations_keepExistingBehavior(Vertx vertx) throws Exception {
        List<Executable> scenarios = new ArrayList<>();

        // Given an inline @RequiresAction operation and an unrestricted operation
        scenarios.add(() -> {
            try (Probe probe = Probe.typed(vertx, "inlineAction", Engine.installed())) {
                // When a caller holding the granted role dispatches
                Result<?> result = probe.call(Caller.user("inline-allowed").withRoles(EXECUTOR_ROLE));
                // Then the action is evaluated once and the work runs
                assertTrue(result.isSuccess(), "granted caller must be permitted");
                assertEquals(1, probe.executions(), "business work must run once");
                assertEquals(1, probe.harness.events().size(), "exactly one decision event");
                assertEquals(1, probe.engine.authorizer().calls(), "the authorizer is called exactly once");
                assertEquals(
                        ACTION_VALUE,
                        probe.engine.authorizer().requests().get(0).action(),
                        "the declared action is evaluated");
            }
        });
        scenarios.add(() -> {
            try (Probe probe = Probe.typed(vertx, "inlineAction", Engine.installed())) {
                // When a caller without the granted role dispatches
                Result<?> result = probe.call(Caller.user("inline-denied").withRoles(DENIED_ROLE));
                // Then the call is denied before the work, with one event
                assertTrue(result.isFailure(), "caller without the grant must be denied");
                assertEquals(0, probe.executions(), "business work must not run");
                assertEquals(1, probe.harness.events().size(), "exactly one decision event");
                assertFalse(probe.harness.events().get(0).decision().permitted());
                assertFalse(probe.harness.recoverInvoked(), "recoverError must be bypassed");
            }
        });
        scenarios.add(() -> {
            try (Probe probe = Probe.typed(vertx, "inlineAction", Engine.installed())) {
                // When no security context is bound
                Result<?> result = probe.call(null);
                // Then the existing fail-closed reason applies and the authorizer is not asked
                assertTrue(result.isFailure());
                assertEquals(0, probe.executions());
                assertEquals(1, probe.harness.events().size());
                assertEquals(
                        AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                        probe.harness.events().get(0).decision().reasonCode());
                assertEquals(0, probe.engine.authorizer().calls());
            }
        });
        scenarios.add(() -> {
            try (Probe probe = Probe.typed(vertx, "unrestricted", Engine.installed())) {
                // When an operation declares nothing
                Result<?> result = probe.call(null);
                // Then it runs with no authorization work and no event
                assertTrue(result.isSuccess());
                assertEquals(1, probe.executions());
                assertEquals(0, probe.harness.events().size(), "no event for an undeclared operation");
                assertEquals(0, probe.engine.authorizer().calls());
            }
        });
        assertAll("inline-only dispatch", scenarios);
    }

    // --- Scenario: role policy ---

    private static void rolePolicyEnforcesEveryCallerShape(Vertx vertx, Supplier<Engine> engine) throws Exception {
        Caller executor = Caller.user("role-holder").withRoles(EXECUTOR_ROLE);
        List<Executable> checks = new ArrayList<>();

        // Given a role-only policy, a caller lacking the role is rejected with the claim reason
        checks.add(() -> expectRejected(
                vertx,
                "rolesOnly",
                engine,
                "trusted caller lacking the role",
                Caller.user("viewer").withRoles(DENIED_ROLE),
                "ROLE_MISSING",
                PrincipalType.USER,
                0));
        // When no caller or an anonymous caller dispatches, authentication is required
        checks.add(() -> expectRejected(
                vertx,
                "rolesOnly",
                engine,
                "no caller",
                null,
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "rolesOnly",
                engine,
                "anonymous caller carrying the role claim",
                Caller.anonymous().withRoles(EXECUTOR_ROLE),
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        // When a caller satisfying the claims carries any one unsupported marker, it is rejected first
        checks.add(() -> expectRejected(
                vertx,
                "rolesOnly",
                engine,
                "reconstruction marker",
                executor.reconstructed(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "rolesOnly",
                engine,
                "identity subject marker",
                executor.withSubject(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "rolesOnly",
                engine,
                "identity delegation marker",
                executor.withDelegation(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        // Then a trusted caller holding the role is permitted with one permit event
        checks.add(() -> expectPermitted(vertx, "rolesOnly", engine, "trusted caller with the role", executor, 1, 0));
        assertAll("role policy", checks);
    }

    // --- Scenario: scope policies ---

    private static void scopePoliciesCombineScopeAndPermissionClaims(Vertx vertx, Supplier<Engine> engine)
            throws Exception {
        List<Executable> checks = new ArrayList<>();

        // Given a policy requiring both scopes, a caller with only one is rejected
        checks.add(() -> expectRejected(
                vertx,
                "allScopes",
                engine,
                "one of two required scopes",
                Caller.user("one-scope").withScopes(READ_SCOPE),
                "SCOPE_MISSING",
                PrincipalType.USER,
                0));
        // When the second scope arrives as a PERMISSION claim, the union satisfies the policy
        checks.add(() -> expectPermitted(
                vertx,
                "allScopes",
                engine,
                "scope plus permission union",
                Caller.user("union").withScopes(READ_SCOPE).withPermissions(WRITE_SCOPE),
                1,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "allScopes",
                engine,
                "reconstruction marker with every scope",
                Caller.user("marked-scopes").withScopes(READ_SCOPE, WRITE_SCOPE).reconstructed(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        // Given a policy requiring any scope, a caller with none is rejected and one permission passes
        checks.add(() -> expectRejected(
                vertx,
                "anyScope",
                engine,
                "no scope at all",
                Caller.user("no-scope").withRoles(DENIED_ROLE),
                "SCOPE_INSUFFICIENT",
                PrincipalType.USER,
                0));
        checks.add(() -> expectPermitted(
                vertx,
                "anyScope",
                engine,
                "one permission satisfies any-scope",
                Caller.user("any-scope").withPermissions(WRITE_SCOPE),
                1,
                0));
        assertAll("scope policies", checks);
    }

    // --- Scenario: authenticated-only policy ---

    private static void authenticatedOnlyPolicyRequiresATrustedCaller(Vertx vertx, Supplier<Engine> engine)
            throws Exception {
        Caller trusted = Caller.user("plain-user");
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> expectRejected(
                vertx,
                "authenticatedOnly",
                engine,
                "no caller",
                null,
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "authenticatedOnly",
                engine,
                "anonymous caller",
                Caller.anonymous(),
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "authenticatedOnly",
                engine,
                "delegation marker",
                trusted.withDelegation(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "authenticatedOnly",
                engine,
                "subject marker",
                trusted.withSubject(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectRejected(
                vertx,
                "authenticatedOnly",
                engine,
                "reconstruction marker",
                trusted.reconstructed(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectPermitted(vertx, "authenticatedOnly", engine, "trusted caller", trusted, 1, 0));
        assertAll("authenticated-only policy", checks);
    }

    // --- Scenario: deny policy ---

    private static void denyPolicyRefusesEvenWithoutACaller(Vertx vertx, Supplier<Engine> engine) throws Exception {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> expectRejected(
                vertx, "denyAll", engine, "no caller", null, AuthzReasonCodes.DENY_ALL, PrincipalType.ANONYMOUS, 0));
        checks.add(() -> expectRejected(
                vertx,
                "denyAll",
                engine,
                "fully privileged trusted caller",
                Caller.user("admin").withRoles(EXECUTOR_ROLE, OPERATOR_ROLE),
                AuthzReasonCodes.DENY_ALL,
                PrincipalType.USER,
                0));
        assertAll("deny policy", checks);
    }

    // --- Scenario: public policy ---

    private static void publicPolicyDoesNoAuthorizationWorkAndEmitsNothing(Vertx vertx, Supplier<Engine> engine)
            throws Exception {
        Caller trusted = Caller.user("public-user");
        List<Executable> checks = new ArrayList<>();
        // Given a public policy, every caller shape reaches the work and no event is emitted
        checks.add(() -> expectPermitted(vertx, "publicOp", engine, "no caller", null, 0, 0));
        checks.add(() -> expectPermitted(vertx, "publicOp", engine, "trusted caller", trusted, 0, 0));
        checks.add(() -> expectPermitted(vertx, "publicOp", engine, "marked caller", trusted.reconstructed(), 0, 0));
        assertAll("public policy", checks);
    }

    // --- Scenario: type-level policy ---

    private static void typeLevelPolicyGuardsUntilAMethodPolicyReplacesIt(Vertx vertx, Supplier<Engine> engine)
            throws Exception {
        List<Executable> checks = new ArrayList<>();
        // Given a contract whose type-level policy requires the executor role
        checks.add(() -> {
            try (Probe probe = Probe.typeLevel(vertx, "typeGuarded", engine.get())) {
                // When a caller lacking the role dispatches an operation with no method policy
                Result<?> result = probe.call(Caller.user("type-viewer").withRoles(DENIED_ROLE));
                // Then the type-level policy rejects it before the work
                assertRejectedOutcome("type-level policy", probe, result, "ROLE_MISSING", PrincipalType.USER, 0);
            }
        });
        checks.add(() -> {
            try (Probe probe = Probe.typeLevel(vertx, "typeGuarded", engine.get())) {
                Result<?> result = probe.call(Caller.user("type-executor").withRoles(EXECUTOR_ROLE));
                assertPermittedOutcome("type-level policy permits", probe, result, 1, 0);
            }
        });
        checks.add(() -> {
            try (Probe probe = Probe.typeLevel(vertx, "methodReplaces", engine.get())) {
                // When the method declares its own public policy, it replaces the type-level one
                Result<?> result = probe.call(null);
                assertPermittedOutcome("method policy replaces type policy", probe, result, 0, 0);
            }
        });
        assertAll("type-level policy", checks);
    }

    // --- Scenario: action-only policy ---

    private static void actionOnlyPolicyFollowsTheExistingActionPath(Vertx vertx) throws Exception {
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> {
            try (Probe probe = Probe.typed(vertx, "actionOnly", Engine.installed())) {
                // Given an action-only policy and a caller whose role is granted the action
                InvocationOrigin origin = InvocationOrigin.of("action-only-origin");
                Result<?> result =
                        await(probe.harness.dispatch(Caller.user("granted").withRoles(EXECUTOR_ROLE), origin));
                // Then the authorizer is asked once, with only the declared action, and the work runs
                assertPermittedOutcome("action-only permit", probe, result, 1, 1);
                AuthorizationRequest asked =
                        probe.engine.authorizer().requests().get(0);
                assertEquals(ACTION_VALUE, asked.action(), "only the declared action is evaluated");
                assertTrue(asked.context().isEmpty(), "no claim-evaluation context leaks into the action request");
                assertEquals(origin, asked.origin(), "the real invocation origin reaches the authorizer");
            }
        });
        checks.add(() -> expectActionRejected(
                vertx,
                "actionOnly",
                "caller without the grant",
                Caller.user("ungranted").withRoles(DENIED_ROLE),
                null,
                PrincipalType.USER,
                1));
        checks.add(() -> expectActionRejected(
                vertx,
                "actionOnly",
                "no caller",
                null,
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        checks.add(() -> expectActionRejected(
                vertx,
                "actionOnly",
                "anonymous caller",
                Caller.anonymous().withRoles(EXECUTOR_ROLE),
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        checks.add(() -> {
            try (Probe probe = Probe.typed(vertx, "actionOnly", Engine.installed())) {
                // When a reconstructed caller reaches an action-only policy, the existing action path
                // (and its reconstruction handling inside the authorizer) decides: no local marker gate
                Result<?> result = probe.call(
                        Caller.user("rebuilt").withRoles(EXECUTOR_ROLE).reconstructed());
                assertEquals(
                        1,
                        probe.engine.authorizer().calls(),
                        "a reconstructed caller still reaches the authorizer exactly once");
                assertEquals(1, probe.harness.events().size(), "exactly one decision event");
                assertNotEquals(
                        UNSUPPORTED_POLICY_CALLER,
                        probe.harness.events().get(0).decision().reasonCode(),
                        "action-only policies have no local caller-shape predicate");
                assertEquals(
                        result.isSuccess(),
                        probe.harness.events().get(0).decision().permitted(),
                        "the outcome follows the authorizer decision");
            }
        });
        assertAll("action-only policy", checks);
    }

    // --- Scenario: combined policy ---

    private static void combinedPolicyEvaluatesLocalChecksBeforeTheActionOnce(Vertx vertx) throws Exception {
        Caller operatorAndExecutor = Caller.user("both").withRoles(OPERATOR_ROLE, EXECUTOR_ROLE);
        List<Executable> checks = new ArrayList<>();
        checks.add(() -> {
            try (Probe probe = Probe.typed(vertx, "operatorAndAction", Engine.installed())) {
                // Given a combined policy and a caller passing both the local role and the action
                Result<?> result = probe.call(operatorAndExecutor);
                // Then one composed event is emitted and the authorizer is called exactly once
                assertPermittedOutcome("combined permit", probe, result, 1, 1);
            }
        });
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "local role missing precedes the action",
                Caller.user("executor-only").withRoles(EXECUTOR_ROLE),
                "ROLE_MISSING",
                PrincipalType.USER,
                0));
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "local check passes, action denies",
                Caller.user("operator-only").withRoles(OPERATOR_ROLE),
                null,
                PrincipalType.USER,
                1));
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "reconstruction marker is rejected even though the action would permit",
                operatorAndExecutor.reconstructed(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "subject marker is rejected even though the action would permit",
                operatorAndExecutor.withSubject(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "delegation marker is rejected even though the action would permit",
                operatorAndExecutor.withDelegation(),
                UNSUPPORTED_POLICY_CALLER,
                PrincipalType.USER,
                0));
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "no caller",
                null,
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        checks.add(() -> expectActionRejected(
                vertx,
                "operatorAndAction",
                "anonymous caller",
                Caller.anonymous().withRoles(OPERATOR_ROLE, EXECUTOR_ROLE),
                AuthzReasonCodes.AUTHENTICATION_REQUIRED,
                PrincipalType.ANONYMOUS,
                0));
        assertAll("combined policy", checks);
    }

    // --- Scenario: evaluator failures ---

    private static void evaluatorFailuresDenyNonRecoverablyForActionPolicies(Vertx vertx) throws Exception {
        Caller operatorAndExecutor = Caller.user("both-failing").withRoles(OPERATOR_ROLE, EXECUTOR_ROLE);
        List<Executable> checks = new ArrayList<>();
        for (RecordingAuthorizer.Mode mode : List.of(
                RecordingAuthorizer.Mode.THROW,
                RecordingAuthorizer.Mode.NULL_FUTURE,
                RecordingAuthorizer.Mode.FAILED_FUTURE,
                RecordingAuthorizer.Mode.NULL_DECISION)) {
            for (String operation : List.of("actionOnly", "operatorAndAction")) {
                checks.add(() -> {
                    try (Probe probe = Probe.typed(vertx, operation, Engine.misbehaving(mode))) {
                        // Given an evaluator that misbehaves and a caller that passes every local check
                        Result<?> result = probe.call(operatorAndExecutor);
                        // Then dispatch is denied non-recoverably with one event and no business work
                        assertRejectedOutcome(
                                operation + " with evaluator " + mode,
                                probe,
                                result,
                                AuthzReasonCodes.INTERNAL_AUTHZ_ERROR,
                                PrincipalType.USER,
                                1);
                    }
                });
            }
        }
        assertAll("evaluator failures", checks);
    }

    // --- Scenario: per-call evaluation ---

    private static void claimsAreEvaluatedOnEveryCall(Vertx vertx) throws Exception {
        try (Probe probe = Probe.typed(vertx, "rolesOnly", Engine.installed())) {
            // Given one caller instance whose claims are replaced between calls
            Caller caller = Caller.user("shifting");
            AuthorizationClaims noRole = AuthorizationClaims.empty();
            AuthorizationClaims executorRole = new AuthorizationClaims(
                    Set.of(new AuthorityClaim(AuthorityKind.ROLE, EXECUTOR_ROLE, "", "", "test", Map.of())), Map.of());

            // When it dispatches without, with, and again without the role
            caller.replaceClaims(noRole);
            Result<?> first = probe.call(caller);
            caller.replaceClaims(executorRole);
            Result<?> second = probe.call(caller);
            caller.replaceClaims(noRole);
            Result<?> third = probe.call(caller);

            // Then each call is decided on the claims it carried
            List<AuthorizationDecisionEvent> events = probe.harness.events();
            assertAll(
                    "claims change between calls",
                    () -> assertEquals(
                            List.of(false, true, false),
                            List.of(first.isSuccess(), second.isSuccess(), third.isSuccess())),
                    () -> assertEquals(1, probe.executions(), "only the permitted call reaches the work"),
                    () -> assertEquals(3, events.size(), "one event per attempt"),
                    () -> assertEquals(
                            List.of(false, true, false),
                            events.stream().map(e -> e.decision().permitted()).toList()));
        }
    }

    private static void concurrentLocalCallersDoNotLeakIntoEachOther(Vertx vertx) throws Exception {
        int callers = 24;
        try (Probe probe = Probe.typed(vertx, "rolesOnly", Engine.absent())) {
            // Given interleaved callers, alternately holding and lacking the role, each with its own origin
            List<Future<Result<?>>> pending = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Caller caller = Caller.user("caller-" + i).withRoles(i % 2 == 0 ? EXECUTOR_ROLE : DENIED_ROLE);
                pending.add(probe.harness.dispatch(caller, InvocationOrigin.of("origin-" + i)));
            }

            // When all are dispatched before any reply is awaited
            await(Future.all(pending));

            // Then each caller got its own outcome and its own event identity and origin
            List<Executable> checks = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                int index = i;
                checks.add(() -> assertEquals(
                        index % 2 == 0, pending.get(index).result().isSuccess(), "outcome of caller " + index));
            }
            checks.add(() -> assertEquals(callers / 2, probe.executions(), "only role holders reach the work"));
            checks.add(() -> assertEquals(callers, probe.harness.events().size(), "one event per caller"));
            for (AuthorizationDecisionEvent event : probe.harness.events()) {
                checks.add(() -> assertEventBelongsToItsCaller(event));
            }
            assertAll("concurrent local callers", checks);
        }
    }

    private static void concurrentActionCallersKeepTheirOwnIdentityAndOrigin(Vertx vertx) throws Exception {
        try (Probe probe = Probe.typed(vertx, "operatorAndAction", Engine.gated(2))) {
            // Given two callers whose action evaluations are held until both are pending
            Caller denied = Caller.user("caller-denied").withRoles(OPERATOR_ROLE);
            Caller allowed = Caller.user("caller-allowed").withRoles(OPERATOR_ROLE, EXECUTOR_ROLE);
            Future<Result<?>> deniedCall = probe.harness.dispatch(denied, InvocationOrigin.of("origin-denied"));
            Future<Result<?>> allowedCall = probe.harness.dispatch(allowed, InvocationOrigin.of("origin-allowed"));
            assertTrue(probe.engine.authorizer().awaitGateReached(), "both evaluations must be pending");

            // When they are released in reverse arrival order
            probe.engine.authorizer().releaseGatesInReverseOrder();
            Result<?> deniedResult = await(deniedCall);
            Result<?> allowedResult = await(allowedCall);

            // Then each outcome, event actor and origin belongs to its own caller
            assertAll(
                    "concurrent action callers",
                    () -> assertTrue(allowedResult.isSuccess(), "the granted caller is permitted"),
                    () -> assertTrue(deniedResult.isFailure(), "the ungranted caller is denied"),
                    () -> assertEquals(1, probe.executions(), "only the granted caller reaches the work"),
                    () -> assertEquals(2, probe.harness.events().size(), "one event per caller"),
                    () -> assertEquals(2, probe.engine.authorizer().calls(), "each caller is evaluated exactly once"));
            for (AuthorizationDecisionEvent event : probe.harness.events()) {
                assertEventBelongsToItsCaller(event);
            }
            for (AuthorizationRequest request : probe.engine.authorizer().requests()) {
                String actor = request.securityContext().identity().actor().id();
                assertEquals(
                        InvocationOrigin.of(actor.equals("caller-denied") ? "origin-denied" : "origin-allowed"),
                        request.origin(),
                        "the request for " + actor + " carries its own origin");
            }
        }
    }

    /** Callers named {@code caller-N} permit on even N; {@code caller-allowed} permits; others deny. */
    private static void assertEventBelongsToItsCaller(AuthorizationDecisionEvent event) {
        String actor = event.request().securityContext().identity().actor().id();
        boolean expectedPermit;
        InvocationOrigin expectedOrigin;
        if (actor.equals("caller-allowed")) {
            expectedPermit = true;
            expectedOrigin = InvocationOrigin.of("origin-allowed");
        } else if (actor.equals("caller-denied")) {
            expectedPermit = false;
            expectedOrigin = InvocationOrigin.of("origin-denied");
        } else {
            int index = Integer.parseInt(actor.substring("caller-".length()));
            expectedPermit = index % 2 == 0;
            expectedOrigin = InvocationOrigin.of("origin-" + index);
        }
        assertEquals(expectedPermit, event.decision().permitted(), "decision for " + actor);
        assertEquals(expectedOrigin, event.invocationOrigin(), "origin for " + actor);
    }

    // --- Scenario helpers ---

    /** One dispatch target: a harness plus the counting implementation behind it. */
    private static final class Probe implements AutoCloseable {
        private final Harness harness;
        private final IntSupplier executions;
        private final String operation;
        private final Engine engine;

        private Probe(Harness harness, IntSupplier executions, String operation, Engine engine) {
            this.harness = harness;
            this.executions = executions;
            this.operation = operation;
            this.engine = engine;
        }

        static Probe typed(Vertx vertx, String operation, Engine engine) {
            TypedPolicyService service = new TypedPolicyService();
            return open(vertx, TypedPolicyContract.class, service, service::executions, operation, engine);
        }

        static Probe typeLevel(Vertx vertx, String operation, Engine engine) {
            TypeLevelPolicyService service = new TypeLevelPolicyService();
            return open(vertx, TypeLevelPolicyContract.class, service, service::executions, operation, engine);
        }

        private static Probe open(
                Vertx vertx, Class<?> contract, Object impl, IntSupplier executions, String operation, Engine engine) {
            ServiceMethodMeta meta = metaFor(contract, impl, operation, uniqueAddress());
            return new Probe(Harness.start(vertx, meta, engine), executions, operation, engine);
        }

        Result<?> call(Caller caller) throws Exception {
            return harness.dispatchAndAwait(caller);
        }

        int executions() {
            return executions.getAsInt();
        }

        boolean isLocalOnly() {
            return LOCAL_ONLY_OPERATIONS.contains(operation);
        }

        @Override
        public void close() {
            harness.close();
        }
    }

    private static void expectRejected(
            Vertx vertx,
            String operation,
            Supplier<Engine> engine,
            String scenario,
            Caller caller,
            String reason,
            PrincipalType actor,
            int authorizerCalls)
            throws Exception {
        try (Probe probe = Probe.typed(vertx, operation, engine.get())) {
            Result<?> result = probe.call(caller);
            assertRejectedOutcome(operation + ": " + scenario, probe, result, reason, actor, authorizerCalls);
        }
    }

    private static void expectActionRejected(
            Vertx vertx,
            String operation,
            String scenario,
            Caller caller,
            String reason,
            PrincipalType actor,
            int authorizerCalls)
            throws Exception {
        try (Probe probe = Probe.typed(vertx, operation, Engine.installed())) {
            Result<?> result = probe.call(caller);
            assertRejectedOutcome(operation + ": " + scenario, probe, result, reason, actor, authorizerCalls);
        }
    }

    private static void expectPermitted(
            Vertx vertx,
            String operation,
            Supplier<Engine> engine,
            String scenario,
            Caller caller,
            int expectedEvents,
            int authorizerCalls)
            throws Exception {
        try (Probe probe = Probe.typed(vertx, operation, engine.get())) {
            Result<?> result = probe.call(caller);
            assertPermittedOutcome(operation + ": " + scenario, probe, result, expectedEvents, authorizerCalls);
        }
    }

    /**
     * Asserts the dispatch was rejected before any effect: failed reply, bypassed recovery, no business
     * work, exactly one denying event with the expected reason and actor, the expected authorizer call
     * count, and for local-only policies the operation label with no registry lookup.
     */
    private static void assertRejectedOutcome(
            String scenario, Probe probe, Result<?> result, String reason, PrincipalType actor, int authorizerCalls) {
        List<AuthorizationDecisionEvent> events = probe.harness.events();
        assertAll(
                scenario,
                () -> assertTrue(result.isFailure(), "the dispatch must fail"),
                () -> assertFalse(probe.harness.recoverInvoked(), "an application recoverError must be bypassed"),
                () -> assertEquals(0, probe.executions(), "business work must not run"),
                () -> assertEquals(1, events.size(), "exactly one decision event"),
                () -> {
                    assertFalse(events.isEmpty(), "a decision event must exist");
                    AuthorizationDecisionEvent event = events.get(0);
                    assertFalse(event.decision().permitted(), "the event must record a denial");
                    if (reason != null) {
                        assertEquals(reason, event.decision().reasonCode(), "denial reason");
                    }
                    assertEquals(
                            actor,
                            event.request().securityContext().identity().actor().type(),
                            "event actor type");
                    if (probe.isLocalOnly()) {
                        assertEquals(
                                probe.operation,
                                event.request().action(),
                                "a local-only event carries the operation label");
                    }
                },
                () -> assertEngineUse(probe, authorizerCalls));
    }

    /** Asserts the dispatch was permitted: success, one execution, the expected events and engine use. */
    private static void assertPermittedOutcome(
            String scenario, Probe probe, Result<?> result, int expectedEvents, int authorizerCalls) {
        List<AuthorizationDecisionEvent> events = probe.harness.events();
        assertAll(
                scenario,
                () -> assertTrue(result.isSuccess(), "the dispatch must succeed"),
                () -> assertEquals(1, probe.executions(), "business work runs exactly once"),
                () -> assertEquals(expectedEvents, events.size(), "decision event count"),
                () -> assertTrue(
                        events.stream().allMatch(e -> e.decision().permitted()), "every event records a permit"),
                () -> {
                    if (probe.isLocalOnly() && expectedEvents == 1) {
                        assertEquals(
                                probe.operation,
                                events.get(0).request().action(),
                                "a local-only event carries the operation label");
                    }
                },
                () -> assertEngineUse(probe, authorizerCalls));
    }

    private static void assertEngineUse(Probe probe, int authorizerCalls) {
        if (probe.engine.authorizer() == null) {
            return;
        }
        assertEquals(authorizerCalls, probe.engine.authorizer().calls(), "authorizer call count");
        if (probe.isLocalOnly()) {
            assertEquals(0, probe.engine.registry().lookups(), "a local-only policy never consults the registry");
        }
    }

    // --- Helpers ---

    /**
     * Returns a {@link SecurityEventObserver} that captures every {@link AuthorizationDecisionEvent}
     * into the given list.
     *
     * @param target the list to append captured events to
     * @return a new capturing observer
     */
    private static SecurityEventObserver capturingObserver(List<AuthorizationDecisionEvent> target) {
        return new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                target.add(event);
                return Future.succeededFuture();
            }
        };
    }

    // --- Stubs ---

    /**
     * Minimal {@link ActionContributor} returning a fixed list of {@link ActionDefinition}s.
     *
     * @param definitions the fixed list of action definitions to contribute
     */
    private record FixedActionContributor(List<ActionDefinition> definitions) implements ActionContributor {
        @Override
        public Collection<ActionDefinition> actions() {
            return definitions;
        }
    }

    /**
     * Minimal {@link SecurityContext} stub whose authorization claims carry a configurable
     * set of {@link AuthorityClaim}s.
     *
     * @param authz the authorization claims
     */
    private record StubSecurityContext(AuthorizationClaims authz) implements SecurityContext {
        @Override
        public SecurityIdentity identity() {
            return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        }

        @Override
        public AuthenticationState authentication() {
            return new AuthenticationState(
                    DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        }

        @Override
        public AuthorizationClaims authorization() {
            return authz;
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    }

    /**
     * A maximally permissive {@link ServiceInterceptor} whose {@link #recoverError} recovers
     * <em>every</em> failure. Used to prove that an authorization deny (a
     * {@code NonRecoverableDispatchFailure}) bypasses the recover chain and cannot be resurrected.
     * Records whether {@code recoverError} was invoked so the test can assert it was skipped.
     */
    static final class RecoverEverythingInterceptor implements ServiceInterceptor {
        final java.util.concurrent.atomic.AtomicBoolean recoverInvoked =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
            recoverInvoked.set(true);
            // Recover unconditionally — if the action-gate deny were routed here it would be masked.
            return Future.succeededFuture();
        }
    }
}
