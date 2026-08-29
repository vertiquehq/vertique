// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.LocalMessageCodec;
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
import dev.vertique.security.authz.Effect;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

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
