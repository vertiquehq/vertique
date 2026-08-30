// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.rest.core.security.SecurityPolicy;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R07 item 5 (security/architecture review, post-R01) —
 * {@code shouldSettleOnTheCallersContextEvenWhenTheGateFutureHasNone}.
 *
 * <p>{@link SecurityPolicyEnforcerGateDeadlineTest} already proves the never-completing-gate deadline
 * fails closed exactly once and completes within a bounded await, but it runs with no {@link Vertx}
 * instance at all, so it cannot distinguish "settled on the request-owning context" from "settled on
 * Netty's {@code GlobalEventExecutor}" — both look identical to a plain result assertion. This test
 * drives {@link SecurityPolicyEnforcer#decide} from inside a genuine Vert.x context (mirroring how
 * every real caller — {@code McpRequestDispatcher}, the REST authorization handlers — actually invokes
 * it) with a gate future built the same context-less way {@code NeverCompletingDecisionPoint} always
 * has, and asserts the thread the returned future's completion handler runs on is the same
 * request-owning thread {@code decide()} was called from, not some other thread the internal
 * {@code .timeout()} continuation happened to fire on.
 *
 * <p><strong>Sensitivity (DECISIVE):</strong> verified by temporarily reverting the context-anchored
 * {@code Promise} back to the unconditional {@code Promise.promise()} in {@code decide()} and
 * confirming this test fails — the completion then observably runs on a different thread than the one
 * that called {@code decide()} (see R07 completion evidence for the before/after capture).
 */
class SecurityPolicyEnforcerGateTimeoutContextTest {

    private static final ResourceRef TOOL_RESOURCE = new ResourceRef("mcp-tool", "sample-tool", Map.of());
    private static final InvocationOrigin MCP_ORIGIN = InvocationOrigin.of(DispatchBoundary.MCP);

    /** Small enough to keep this proof fast; large enough to never fire under normal test-host load. */
    private static final long TEST_GATE_DEADLINE_MS = 100L;

    /** Comfortably larger than {@link #TEST_GATE_DEADLINE_MS} so a correctly-bounded gate always settles. */
    private static final long AWAIT_BOUND_MS = 3_000L;

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("shouldSettleOnTheCallersContextEvenWhenTheGateFutureHasNone")
    void shouldSettleOnTheCallersContextEvenWhenTheGateFutureHasNone() throws Exception {
        NeverCompletingDecisionPoint dp = new NeverCompletingDecisionPoint();
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                NO_OP_SECURITY_RUNTIME,
                Optional.empty(),
                Optional.of(new AuthorizationGateConfig(TEST_GATE_DEADLINE_MS)));
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        Context requestContext = vertx.getOrCreateContext();
        AtomicReference<Thread> callingThread = new AtomicReference<>();
        AtomicReference<Thread> settlingThread = new AtomicReference<>();
        AtomicReference<Context> settlingContext = new AtomicReference<>();
        AtomicReference<AuthorizationDecision> settledDecision = new AtomicReference<>();
        CountDownLatch settled = new CountDownLatch(1);

        requestContext.runOnContext(ignored -> {
            callingThread.set(Thread.currentThread());
            Future<AuthorizationDecision> result =
                    enforcer.decide(aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);
            result.onComplete(ar -> {
                settlingThread.set(Thread.currentThread());
                settlingContext.set(Vertx.currentContext());
                settledDecision.set(ar.succeeded() ? ar.result() : null);
                settled.countDown();
            });
        });

        assertThat(settled.await(AWAIT_BOUND_MS, TimeUnit.MILLISECONDS))
                .as("decide() must still settle within the bound even though the internal gate future "
                        + "never completes on its own")
                .isTrue();

        assertThat(settledDecision.get()).isNotNull();
        assertThat(settledDecision.get().permitted())
                .as("the gate deadline must still fail closed")
                .isFalse();

        // DECISIVE: the returned future's completion handler must run on the exact same request-owning
        // context (and therefore the same pinned event-loop thread) decide() was called from — not on
        // Netty's GlobalEventExecutor, which is what a context-less .timeout() continuation would use
        // for a gate future built with the static Promise.promise() (issue #417's own fixture shape).
        assertThat(settlingContext.get())
                .as("DECISIVE: the completion must observe the same Vert.x context decide() was called on")
                .isEqualTo(requestContext);
        assertThat(settlingThread.get())
                .as("DECISIVE: the completion must run on the same pinned event-loop thread decide() was "
                        + "called from, not a foreign thread (e.g. Netty's GlobalEventExecutor) the "
                        + "context-less gate future's own timeout continuation happened to fire on")
                .isEqualTo(callingThread.get());
    }

    // --- Shared fixture construction (mirrors SecurityPolicyEnforcerGateDeadlineTest) ---

    private static final AuthenticationState NO_EVIDENCE_AUTH =
            new AuthenticationState(DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());

    private static SecurityContext aliceContext(Set<String> roles) {
        Set<AuthorityClaim> claims = roles.stream()
                .map(role -> new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "alice", Map.of()));
        return new AuthenticatedSecurityContext(
                identity, NO_EVIDENCE_AUTH, new AuthorizationClaims(claims, Map.of()), Optional.empty());
    }

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    private static final SecurityRuntime NO_OP_SECURITY_RUNTIME = new SecurityRuntime() {
        @Override
        public SecurityContext current() {
            throw new UnsupportedOperationException("decide() takes securityContext as a parameter");
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            throw new UnsupportedOperationException("decide() does not bind an ambient SecurityContext");
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            throw new UnsupportedOperationException("decide() performs no JAX-RS bridging");
        }
    };

    /** An {@link AuthorizationDecisionPoint} whose returned future never completes — the issue #417 shape. */
    private static final class NeverCompletingDecisionPoint implements AuthorizationDecisionPoint {
        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            return Promise.<AuthorizationDecision>promise().future(); // never completed; no Vert.x context
        }
    }
}
