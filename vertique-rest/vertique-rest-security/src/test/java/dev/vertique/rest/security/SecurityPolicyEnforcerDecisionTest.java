// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

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
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.Authorizer;
import dev.vertique.security.authz.AuthzReasonCodes;
import dev.vertique.security.authz.InvocationOrigin;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link SecurityPolicyEnforcer#decide} — the T005 frozen programmatic decision
 * operation that mirrors the existing {@code createHandler} contract without a {@link
 * io.vertx.ext.web.RoutingContext} (ADR-0113 / ADR-0114; {@code
 * contracts/authorization-and-input-pipeline.md} § Frozen programmatic decision operation).
 *
 * <p>Every row uses the canonical anonymous context or the bearer subject {@code "alice"} with
 * role {@code "ops"}, and isolates exactly one boundary of the frozen contract.
 */
class SecurityPolicyEnforcerDecisionTest {

    // --- Literal fixture values (Given) ---

    private static final ResourceRef TOOL_RESOURCE = new ResourceRef("mcp-tool", "sample-tool", Map.of());
    private static final InvocationOrigin MCP_ORIGIN = InvocationOrigin.of(DispatchBoundary.MCP);
    private static final ActionRef SAMPLE_ACTION = ActionRef.parse("mcp.tool.invoke");

    @ParameterizedTest(name = "{0}")
    @MethodSource("t005ContractMatrixRows")
    void shouldEnforceT005ContractMatrix(String rowName) {
        switch (rowName) {
            case "shouldPermitNoneAndPermitAllWithoutEmittingAnEvent" -> {
                List<AuthorizationDecisionEvent> events = new ArrayList<>();
                RecordingDecisionPoint dp = RecordingDecisionPoint.throwing(); // must never be consulted
                RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // must never be consulted
                SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);

                Future<AuthorizationDecision> noneResult = enforcer.decide(
                        anonymousContext(), new SecurityPolicy.None(), Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);
                Future<AuthorizationDecision> permitAllResult = enforcer.decide(
                        anonymousContext(),
                        new SecurityPolicy.PermitAll(),
                        Optional.empty(),
                        TOOL_RESOURCE,
                        MCP_ORIGIN);

                assertThat(noneResult.succeeded()).isTrue();
                assertThat(noneResult.result().permitted()).isTrue();
                assertThat(noneResult.result().reasonCode()).isEqualTo(AuthzReasonCodes.PERMITTED);
                assertThat(permitAllResult.succeeded()).isTrue();
                assertThat(permitAllResult.result().permitted()).isTrue();
                assertThat(permitAllResult.result().reasonCode()).isEqualTo(AuthzReasonCodes.PERMITTED);
                assertThat(events)
                        .as("None/PermitAll with no action must emit no event")
                        .isEmpty();
                assertThat(dp.callCount())
                        .as("decision point must not be consulted")
                        .isZero();
                assertThat(authorizer.callCount())
                        .as("authorizer must not be consulted")
                        .isZero();
            }

            case "shouldDenyAllAndEmitOneDenyAllEvent" -> {
                List<AuthorizationDecisionEvent> events = new ArrayList<>();
                RecordingDecisionPoint dp = RecordingDecisionPoint.throwing(); // must never be consulted
                RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // must never be consulted
                SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);

                Future<AuthorizationDecision> result = enforcer.decide(
                        anonymousContext(), new SecurityPolicy.DenyAll(), Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);

                assertThat(result.succeeded()).isTrue();
                AuthorizationDecision decision = result.result();
                assertThat(decision.permitted()).isFalse();
                assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.DENY_ALL);
                assertThat(events).hasSize(1);
                Map<String, Object> attrs = events.get(0).decision().safeAttributes();
                assertThat(attrs.get("rolesSatisfied")).isEqualTo(Boolean.FALSE);
                assertThat(attrs.get("actionSatisfied")).isEqualTo(Boolean.FALSE);
                assertThat(attrs.get("actionEvaluated")).isEqualTo(Boolean.FALSE);
                assertThat(dp.callCount()).isZero();
                assertThat(authorizer.callCount()).isZero();
            }

            case "shouldEvaluateConstrainedRolesThroughTheExistingDecisionPoint" -> {
                List<AuthorizationDecisionEvent> events = new ArrayList<>();
                RecordingDecisionPoint dp = RecordingDecisionPoint.roleChecking();
                RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // no action gate here
                SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
                SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

                Future<AuthorizationDecision> result = enforcer.decide(
                        aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);

                assertThat(result.succeeded()).isTrue();
                AuthorizationDecision decision = result.result();
                assertThat(decision.permitted()).isTrue();
                assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.PERMITTED);
                assertThat(dp.callCount())
                        .as("the existing AuthorizationDecisionPoint must evaluate the constrained policy")
                        .isEqualTo(1);
                assertThat(authorizer.callCount())
                        .as("no action gate to evaluate")
                        .isZero();
                assertThat(events).hasSize(1);
                Map<String, Object> attrs = events.get(0).decision().safeAttributes();
                assertThat(attrs.get("rolesSatisfied")).isEqualTo(Boolean.TRUE);
                assertThat(attrs.get("actionEvaluated")).isEqualTo(Boolean.FALSE);
            }

            case "shouldEvaluateActionOnlyThroughTheExistingAuthorizer" -> {
                List<AuthorizationDecisionEvent> events = new ArrayList<>();
                RecordingDecisionPoint dp = RecordingDecisionPoint.throwing(); // None auto-permits; must not run
                RecordingAuthorizer authorizer =
                        RecordingAuthorizer.returning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
                SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);

                Future<AuthorizationDecision> result = enforcer.decide(
                        aliceContext(Set.of("ops")),
                        new SecurityPolicy.None(),
                        Optional.of(SAMPLE_ACTION),
                        TOOL_RESOURCE,
                        MCP_ORIGIN);

                assertThat(result.succeeded()).isTrue();
                assertThat(result.result().permitted()).isTrue();
                assertThat(dp.callCount())
                        .as("None auto-permits the role/scope gate; the decision point is not consulted")
                        .isZero();
                assertThat(authorizer.callCount())
                        .as("the existing core Authorizer must evaluate the action gate")
                        .isEqualTo(1);
                AuthorizationRequest actionRequest = authorizer.received().get(0);
                assertThat(actionRequest.action()).isEqualTo(SAMPLE_ACTION.value());
                assertThat(actionRequest.origin()).isEqualTo(MCP_ORIGIN);
                assertThat(events).hasSize(1);
                Map<String, Object> attrs = events.get(0).decision().safeAttributes();
                assertThat(attrs.get("rolesSatisfied")).isEqualTo(Boolean.TRUE);
                assertThat(attrs.get("actionSatisfied")).isEqualTo(Boolean.TRUE);
                assertThat(attrs.get("actionEvaluated")).isEqualTo(Boolean.TRUE);
            }

            case "shouldRequireBothGatesWhenRolesAndActionAreCombined" -> {
                List<AuthorizationDecisionEvent> events = new ArrayList<>();
                RecordingDecisionPoint dp = RecordingDecisionPoint.roleChecking();
                RecordingAuthorizer authorizer =
                        RecordingAuthorizer.returning(AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED));
                SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
                SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

                // SENSITIVITY PROOF (T005 TP-001): the role set below is alice's actual role, "ops".
                // Changing only this literal to Set.of() must flip `permitted` true→false and
                // `actionEvaluated` true→false while the emitted event count stays 1 (fail-fast:
                // the action gate is not evaluated once the role/scope gate denies).
                Future<AuthorizationDecision> result = enforcer.decide(
                        aliceContext(Set.of("ops")), policy, Optional.of(SAMPLE_ACTION), TOOL_RESOURCE, MCP_ORIGIN);

                AuthorizationDecision decision = result.result();
                assertThat(decision.permitted()).isTrue();
                Map<String, Object> attrs = decision.safeAttributes();
                assertThat(attrs.get("actionEvaluated")).isEqualTo(Boolean.TRUE);
                assertThat(authorizer.callCount())
                        .as("both gates must run when roles and action are combined")
                        .isEqualTo(1);
                assertThat(events).hasSize(1);
            }

            case "shouldFailClosedOnThrowNullFutureFailedFutureAndNullDecision" -> {
                // Role/scope gate (AuthorizationDecisionPoint) contract violations — Constrained
                // policy, no action gate to compose.
                assertRoleScopeGateFailsClosed(RecordingDecisionPoint.throwing());
                assertRoleScopeGateFailsClosed(RecordingDecisionPoint.returningNullFuture());
                assertRoleScopeGateFailsClosed(RecordingDecisionPoint.returningFailedFuture());
                assertRoleScopeGateFailsClosed(RecordingDecisionPoint.returningNullDecision());

                // Action gate (Authorizer) contract violations — the role/scope gate permits, the
                // action gate misbehaves.
                assertActionGateFailsClosed(RecordingAuthorizer.throwing());
                assertActionGateFailsClosed(RecordingAuthorizer.returningNullFuture());
                assertActionGateFailsClosed(RecordingAuthorizer.returningFailedFuture());
                assertActionGateFailsClosed(RecordingAuthorizer.returningNullDecision());
            }

            case "shouldEmitExactlyOneCombinedEventPerRestrictiveEvaluation" -> {
                List<AuthorizationDecisionEvent> events = new ArrayList<>();
                RecordingDecisionPoint dp = RecordingDecisionPoint.roleChecking();
                RecordingAuthorizer authorizer =
                        RecordingAuthorizer.returning(AuthorizationDecision.deny(AuthzReasonCodes.ACTION_NOT_ALLOWED));
                SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
                SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

                // Three distinct restrictive evaluations sharing one events sink.
                enforcer.decide(aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);
                enforcer.decide(aliceContext(Set.of()), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);
                enforcer.decide(
                        aliceContext(Set.of("ops")), policy, Optional.of(SAMPLE_ACTION), TOOL_RESOURCE, MCP_ORIGIN);

                assertThat(events)
                        .as("exactly one combined event per restrictive evaluation — no duplication, no drop")
                        .hasSize(3);
                assertThat(events.get(0).decision().permitted()).isTrue();
                assertThat(events.get(1).decision().permitted()).isFalse();
                assertThat(events.get(2).decision().permitted()).isFalse();
            }

            default -> fail("unknown T005 contract matrix row: " + rowName);
        }
    }

    static Stream<String> t005ContractMatrixRows() {
        return Stream.of(
                "shouldPermitNoneAndPermitAllWithoutEmittingAnEvent",
                "shouldDenyAllAndEmitOneDenyAllEvent",
                "shouldEvaluateConstrainedRolesThroughTheExistingDecisionPoint",
                "shouldEvaluateActionOnlyThroughTheExistingAuthorizer",
                "shouldRequireBothGatesWhenRolesAndActionAreCombined",
                "shouldFailClosedOnThrowNullFutureFailedFutureAndNullDecision",
                "shouldEmitExactlyOneCombinedEventPerRestrictiveEvaluation");
    }

    // --- Fail-closed sub-case assertions shared by the fail-closed row only ---

    private static void assertRoleScopeGateFailsClosed(RecordingDecisionPoint dp) {
        List<AuthorizationDecisionEvent> events = new ArrayList<>();
        RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // must not be consulted
        SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        Future<AuthorizationDecision> result =
                enforcer.decide(aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);

        assertThat(result.succeeded())
                .as("decide() must never fail the returned future")
                .isTrue();
        AuthorizationDecision decision = result.result();
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).decision().reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(authorizer.callCount())
                .as("action gate must not be evaluated after a role/scope contract violation")
                .isZero();
    }

    private static void assertActionGateFailsClosed(RecordingAuthorizer authorizer) {
        List<AuthorizationDecisionEvent> events = new ArrayList<>();
        RecordingDecisionPoint dp = RecordingDecisionPoint.roleChecking();
        SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        Future<AuthorizationDecision> result = enforcer.decide(
                aliceContext(Set.of("ops")), policy, Optional.of(SAMPLE_ACTION), TOOL_RESOURCE, MCP_ORIGIN);

        assertThat(result.succeeded())
                .as("decide() must never fail the returned future")
                .isTrue();
        AuthorizationDecision decision = result.result();
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(events).hasSize(1);
    }

    // --- Shared framework construction (fixture helpers) ---

    private static final AuthenticationState NO_EVIDENCE_AUTH =
            new AuthenticationState(DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());

    /** The canonical anonymous {@link SecurityContext}. */
    private static SecurityContext anonymousContext() {
        return new AuthenticatedSecurityContext(
                SecurityIdentity.anonymous(), NO_EVIDENCE_AUTH, AuthorizationClaims.empty(), Optional.empty());
    }

    /** The bearer subject {@code "alice"} holding the given {@link AuthorityKind#ROLE} claims. */
    private static SecurityContext aliceContext(Set<String> roles) {
        Set<AuthorityClaim> claims = roles.stream()
                .map(role -> new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of()))
                .collect(Collectors.toUnmodifiableSet());
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

    private static SecurityEventEmitter capturingEmitter(List<AuthorizationDecisionEvent> sink) {
        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                sink.add(event);
                return Future.succeededFuture();
            }
        };
        return new SecurityEventEmitter(Set.of(observer));
    }

    private static SecurityPolicyEnforcer enforcerWith(
            AuthorizationDecisionPoint dp, Authorizer authorizer, List<AuthorizationDecisionEvent> events) {
        return new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                capturingEmitter(events),
                NO_OP_CONTEXT_HOLDER,
                NO_OP_SECURITY_RUNTIME,
                Optional.ofNullable(authorizer));
    }

    // --- Recording test doubles ---

    /**
     * A recording {@link AuthorizationDecisionPoint} that records every request it receives and
     * delegates its outcome to a configurable {@code behavior} function, so both realistic
     * role-checking evaluation and every gate contract violation (synchronous throw, {@code null}
     * future, failed future, {@code null} decision) can be exercised.
     */
    private static final class RecordingDecisionPoint implements AuthorizationDecisionPoint {

        private final List<AuthorizationRequest> received = new ArrayList<>();
        private final Function<AuthorizationRequest, Future<AuthorizationDecision>> behavior;

        private RecordingDecisionPoint(Function<AuthorizationRequest, Future<AuthorizationDecision>> behavior) {
            this.behavior = behavior;
        }

        /** Evaluates the request's {@code CTX_REQUIRED_ROLES} against the caller's real ROLE claims. */
        static RecordingDecisionPoint roleChecking() {
            return new RecordingDecisionPoint(request -> {
                @SuppressWarnings("unchecked")
                List<String> requiredRoles = (List<String>)
                        request.context().getOrDefault(VertxProviderDecisionPoint.CTX_REQUIRED_ROLES, List.of());
                Set<String> actualRoles =
                        request.securityContext().authorization().valuesOf(AuthorityKind.ROLE);
                boolean satisfied = actualRoles.containsAll(requiredRoles);
                return Future.succeededFuture(
                        satisfied
                                ? AuthorizationDecision.permit(AuthzReasonCodes.PERMITTED)
                                : AuthorizationDecision.deny(AuthzReasonCodes.ROLE_MISSING));
            });
        }

        static RecordingDecisionPoint throwing() {
            return new RecordingDecisionPoint(request -> {
                throw new RuntimeException("decision point exploded synchronously");
            });
        }

        static RecordingDecisionPoint returningNullFuture() {
            return new RecordingDecisionPoint(request -> null);
        }

        static RecordingDecisionPoint returningFailedFuture() {
            return new RecordingDecisionPoint(request -> Future.failedFuture("decision point failed"));
        }

        static RecordingDecisionPoint returningNullDecision() {
            return new RecordingDecisionPoint(request -> Future.succeededFuture(null));
        }

        int callCount() {
            return received.size();
        }

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            received.add(request);
            return behavior.apply(request);
        }
    }

    /**
     * A recording {@link Authorizer} that records every request it receives and delegates its
     * outcome to a configurable {@code behavior} function, so both a fixed decision and every gate
     * contract violation can be exercised.
     */
    private static final class RecordingAuthorizer implements Authorizer {

        private final List<AuthorizationRequest> received = new ArrayList<>();
        private final Function<AuthorizationRequest, Future<AuthorizationDecision>> behavior;

        private RecordingAuthorizer(Function<AuthorizationRequest, Future<AuthorizationDecision>> behavior) {
            this.behavior = behavior;
        }

        static RecordingAuthorizer returning(AuthorizationDecision decision) {
            return new RecordingAuthorizer(request -> Future.succeededFuture(decision));
        }

        static RecordingAuthorizer throwing() {
            return new RecordingAuthorizer(request -> {
                throw new RuntimeException("authorizer exploded synchronously");
            });
        }

        static RecordingAuthorizer returningNullFuture() {
            return new RecordingAuthorizer(request -> null);
        }

        static RecordingAuthorizer returningFailedFuture() {
            return new RecordingAuthorizer(request -> Future.failedFuture("authorizer failed"));
        }

        static RecordingAuthorizer returningNullDecision() {
            return new RecordingAuthorizer(request -> Future.succeededFuture(null));
        }

        int callCount() {
            return received.size();
        }

        List<AuthorizationRequest> received() {
            return received;
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            received.add(request);
            return behavior.apply(request);
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("decide() uses the 5-arg AuthorizationRequest overload only");
        }
    }
}
