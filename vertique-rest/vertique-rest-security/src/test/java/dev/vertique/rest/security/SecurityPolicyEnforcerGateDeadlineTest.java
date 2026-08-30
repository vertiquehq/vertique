// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.JsonConfigPaths;
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
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R01 TP-001 — {@code shouldFailClosedOnceWhenAGateExceedsTheDeadline} (issue #417).
 *
 * <p>Before this repair, {@link SecurityPolicyEnforcer#decide} failed closed on a synchronous throw,
 * a null future, a failed future, and a null decision from either the {@link
 * AuthorizationDecisionPoint} or the {@link Authorizer} — but not on a future that simply never
 * resolves. An app-provided decision point or authorizer that hangs (a remote PDP with no timeout, an
 * OPA sidecar under load) left the future {@code decide()} returns pending forever: on the MCP path
 * there is no {@code RoutingContext} idle timeout to eventually reclaim it.
 *
 * <p>Uses the public {@link AuthorizationGateConfig}-carrying constructor ({@link
 * SecurityPolicyEnforcer#SecurityPolicyEnforcer(
 * Optional, Optional, Set, SecurityEventEmitter, ContextHolder, SecurityRuntime, Optional, Optional)}) so
 * this proof completes in milliseconds rather than {@link AuthorizationGateConfig#DEFAULT_GATE_DEADLINE_MS}.
 * The {@code tools/list} scan-termination half of TP-001 — asserting the decision-point invocation
 * count across a multi-candidate scan, not just elapsed time — lives in {@code vertique-mcp-server}
 * (module boundary: {@code McpRequestDispatcher} is not visible here), in
 * {@code McpToolsListAuthorizationInfrastructureFailureIT}: it proves the scan aborts the whole list on
 * the first {@code INTERNAL_AUTHZ_ERROR} candidate regardless of cause (a failed future there; a
 * gate-deadline timeout here), since the scan keys off the reason code alone.
 */
class SecurityPolicyEnforcerGateDeadlineTest {

    private static final ResourceRef TOOL_RESOURCE = new ResourceRef("mcp-tool", "sample-tool", Map.of());
    private static final InvocationOrigin MCP_ORIGIN = InvocationOrigin.of(DispatchBoundary.MCP);
    private static final ActionRef SAMPLE_ACTION = ActionRef.parse("mcp.tool.invoke");

    /** Small enough to keep this proof fast; large enough to never fire under normal test-host load. */
    private static final long TEST_GATE_DEADLINE_MS = 100L;

    /**
     * The bound this proof awaits {@code decide()}'s returned future within. Comfortably larger than
     * {@link #TEST_GATE_DEADLINE_MS} so a correctly-bounded gate always completes well inside it.
     *
     * <p><strong>Sensitivity (this is the decisive part of the proof):</strong> with the {@code
     * .timeout(...)} call removed from {@code SecurityPolicyEnforcer#decide}, the never-completing
     * decision point/authorizer below leaves {@code decide()}'s returned future pending forever, and
     * this test's {@code .get(AWAIT_BOUND_MS, ...)} call — not an assertion on the result — is what
     * turns that hang into a {@link TimeoutException} the test framework reports as a failure. A
     * reader must not "simplify" this into a bare result assertion: doing so would restore the exact
     * failure this proof exists to catch (a JUnit run that hangs instead of failing red). Verified by
     * temporarily reverting the two {@code .timeout(...)} calls in {@code decide()} and confirming
     * this test times out rather than reporting a wrong value (see R01 completion evidence).
     */
    private static final long AWAIT_BOUND_MS = 3_000L;

    /**
     * R42 (issue #417's deferred configurability half): the gate deadline threaded through the
     * PUBLIC {@link AuthorizationGateConfig} construction path — not the package-private test-only
     * seam {@link #enforcerWith} still uses above. Deliberately far below {@link
     * AuthorizationGateConfig#DEFAULT_GATE_DEADLINE_MS} so a hung gate denies fast rather than
     * merely eventually.
     */
    private static final long CONFIGURED_GATE_DEADLINE_MS = 50L;

    /**
     * Comfortably larger than {@link #CONFIGURED_GATE_DEADLINE_MS} so a correctly-bounded gate
     * always settles well within it, yet an order of magnitude below the old fixed 5s default —
     * the decisive assertion below is that the deny arrives fast, not merely that it arrives.
     */
    private static final long CONFIGURED_AWAIT_BOUND_MS = 1_000L;

    @Test
    @DisplayName("shouldFailClosedOnceWhenAGateExceedsTheDeadline")
    void shouldFailClosedOnceWhenAGateExceedsTheDeadline() throws Exception {
        assertRoleScopeGateTimesOutAndFailsClosedOnce();
        assertActionGateTimesOutAndFailsClosedOnce();
    }

    /**
     * R42: an enforcer constructed through the PUBLIC {@code @Inject} constructor with a configured
     * {@link AuthorizationGateConfig} (50ms) must deny a hung role/scope gate fast — well under the
     * old fixed 5s default — proving the deadline is genuinely operator-configurable rather than
     * reachable only through the package-private test seam (issue #417's deferred question).
     */
    @Test
    @DisplayName("shouldFailClosedFastWhenConstructedWithAConfiguredGateDeadline")
    void shouldFailClosedFastWhenConstructedWithAConfiguredGateDeadline() throws Exception {
        List<AuthorizationDecisionEvent> events = new ArrayList<>();
        NeverCompletingDecisionPoint dp = new NeverCompletingDecisionPoint();
        RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // must not be consulted
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                capturingEmitter(events),
                NO_OP_CONTEXT_HOLDER,
                NO_OP_SECURITY_RUNTIME,
                Optional.ofNullable(authorizer),
                Optional.of(new AuthorizationGateConfig(CONFIGURED_GATE_DEADLINE_MS)));
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        long startNanos = System.nanoTime();
        Future<AuthorizationDecision> result =
                enforcer.decide(aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);
        AuthorizationDecision decision =
                result.toCompletionStage().toCompletableFuture().get(CONFIGURED_AWAIT_BOUND_MS, TimeUnit.MILLISECONDS);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(events).hasSize(1);
        assertThat(elapsedMs)
                .as("the configured 50ms deadline must deny well under the old fixed 5s default")
                .isLessThan(1_000L);
    }

    /**
     * The role/scope gate ({@link AuthorizationDecisionPoint}) never completes: {@code decide()} must
     * still fail closed with exactly one event, and the returned future must complete within {@link
     * #AWAIT_BOUND_MS} — DECISIVE: without the deadline this {@code .get(...)} throws {@link
     * TimeoutException} instead of returning, which is the red signal, not a wrong assertion value.
     */
    private void assertRoleScopeGateTimesOutAndFailsClosedOnce() throws Exception {
        List<AuthorizationDecisionEvent> events = new ArrayList<>();
        NeverCompletingDecisionPoint dp = new NeverCompletingDecisionPoint();
        RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // must not be consulted
        SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        Future<AuthorizationDecision> result =
                enforcer.decide(aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);

        AuthorizationDecision decision =
                result.toCompletionStage().toCompletableFuture().get(AWAIT_BOUND_MS, TimeUnit.MILLISECONDS);

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(events).hasSize(1);
        assertThat(dp.callCount())
                .as("the hanging decision point is invoked exactly once")
                .isEqualTo(1);
        assertThat(authorizer.callCount())
                .as("the action gate must not be evaluated after a role/scope gate timeout")
                .isZero();
    }

    /**
     * The action gate ({@link Authorizer}) never completes while the role/scope gate permits
     * immediately: {@code decide()} must still fail closed with exactly one event, and the returned
     * future must complete within {@link #AWAIT_BOUND_MS} — same DECISIVE sensitivity as the role/scope
     * case above.
     */
    private void assertActionGateTimesOutAndFailsClosedOnce() throws Exception {
        List<AuthorizationDecisionEvent> events = new ArrayList<>();
        RecordingDecisionPoint dp = RecordingDecisionPoint.roleChecking();
        NeverCompletingAuthorizer authorizer = new NeverCompletingAuthorizer();
        SecurityPolicyEnforcer enforcer = enforcerWith(dp, authorizer, events);
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        Future<AuthorizationDecision> result = enforcer.decide(
                aliceContext(Set.of("ops")), policy, Optional.of(SAMPLE_ACTION), TOOL_RESOURCE, MCP_ORIGIN);

        AuthorizationDecision decision =
                result.toCompletionStage().toCompletableFuture().get(AWAIT_BOUND_MS, TimeUnit.MILLISECONDS);

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(events).hasSize(1);
        assertThat(dp.callCount()).as("the role/scope gate must still run once").isEqualTo(1);
        assertThat(authorizer.callCount())
                .as("the hanging authorizer is invoked exactly once")
                .isEqualTo(1);
    }

    /**
     * R48 proof-gap closure — drives {@code security.authz.gateDeadlineMs} through the REAL config
     * path: a JSON application config, {@link AuthorizationGateConfigModule}'s own {@code @Provides}
     * factory method (the exact call Dagger generates for an application that installs the module),
     * and {@link ConfigParser#parse}, rather than the public {@code @Inject}-constructor test above's
     * hand-built {@code new AuthorizationGateConfig(CONFIGURED_GATE_DEADLINE_MS)} — closing the
     * remaining proof gap between "the module parses this shape correctly" ({@code
     * AuthorizationGateConfigTest}, unit-only) and "the parsed value genuinely bounds a hung gate"
     * (this method, into observable {@link SecurityPolicyEnforcer#decide} behavior). Mirrors {@code
     * JwtAuthConfigTest}'s {@code DefaultConfigParser(DefaultConfigMapper.lenient())} construction —
     * the established test-side {@link ConfigParser} instance shape this module's other config-path
     * tests use.
     */
    @Test
    @DisplayName("shouldConfigDriveTheGateDeadlineThroughTheParserAndModuleIntoAFastFailClosedDeny")
    void shouldConfigDriveTheGateDeadlineThroughTheParserAndModuleIntoAFastFailClosedDeny() throws Exception {
        // Given: a real application config JSON carrying security.authz.gateDeadlineMs, parsed through
        // the same ConfigParser#parse(JsonObject, Class) seam every real config-driven module uses.
        JsonObject applicationConfig = new JsonObject()
                .put(
                        "security",
                        new JsonObject()
                                .put("authz", new JsonObject().put("gateDeadlineMs", CONFIGURED_GATE_DEADLINE_MS)));
        ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());
        JsonObject authzSection = JsonConfigPaths.navigateObject(applicationConfig, "security", "authz");

        // When: AuthorizationGateConfigModule's own @Provides factory method — the exact call Dagger
        // generates for an application that installs this module — parses that section.
        AuthorizationGateConfig configDriven =
                AuthorizationGateConfigModule.authorizationGateConfig(applicationConfig, parser);
        assertThat(configDriven.gateDeadlineMs())
                .as("sanity: the config-path value must genuinely be the configured 50ms, not the "
                        + "framework default — otherwise the timing assertion below is vacuous")
                .isEqualTo(CONFIGURED_GATE_DEADLINE_MS);
        // authzSection is read only to keep JsonConfigPaths.navigateObject's own reachable shape (the
        // section the module itself resolves internally) visible at this call site; the module call
        // above re-navigates it independently, exactly as production does.
        assertThat(authzSection.getLong("gateDeadlineMs")).isEqualTo(CONFIGURED_GATE_DEADLINE_MS);

        // Then: constructing the enforcer with this config-parsed (not hand-built) AuthorizationGateConfig
        // and driving a hung role/scope gate through it must still deny fast — DECISIVE, the same
        // sensitivity as shouldFailClosedFastWhenConstructedWithAConfiguredGateDeadline above: a
        // gateDeadlineMs that silently reverted to the 5s default would blow the bound below.
        List<AuthorizationDecisionEvent> events = new ArrayList<>();
        NeverCompletingDecisionPoint dp = new NeverCompletingDecisionPoint();
        RecordingAuthorizer authorizer = RecordingAuthorizer.throwing(); // must not be consulted
        SecurityPolicyEnforcer enforcer = new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                capturingEmitter(events),
                NO_OP_CONTEXT_HOLDER,
                NO_OP_SECURITY_RUNTIME,
                Optional.ofNullable(authorizer),
                Optional.of(configDriven));
        SecurityPolicy.Constrained policy = new SecurityPolicy.Constrained(List.of("ops"), List.of(), false);

        long startNanos = System.nanoTime();
        Future<AuthorizationDecision> result =
                enforcer.decide(aliceContext(Set.of("ops")), policy, Optional.empty(), TOOL_RESOURCE, MCP_ORIGIN);
        AuthorizationDecision decision =
                result.toCompletionStage().toCompletableFuture().get(CONFIGURED_AWAIT_BOUND_MS, TimeUnit.MILLISECONDS);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reasonCode()).isEqualTo(AuthzReasonCodes.INTERNAL_AUTHZ_ERROR);
        assertThat(events).hasSize(1);
        assertThat(elapsedMs)
                .as("DECISIVE: the config-path-parsed 50ms deadline must deny well under the old fixed "
                        + "5s default, proving security.authz.gateDeadlineMs genuinely reaches enforcer "
                        + "behavior through the parser and opt-in module, not merely the record's own "
                        + "constructor")
                .isLessThan(1_000L);
    }

    // --- Shared fixture construction ---

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

    /** Builds an enforcer bound at {@link #TEST_GATE_DEADLINE_MS} via the package-private test seam. */
    private static SecurityPolicyEnforcer enforcerWith(
            AuthorizationDecisionPoint dp, Authorizer authorizer, List<AuthorizationDecisionEvent> events) {
        return new SecurityPolicyEnforcer(
                Optional.of(dp),
                Optional.empty(),
                Set.of(),
                capturingEmitter(events),
                NO_OP_CONTEXT_HOLDER,
                NO_OP_SECURITY_RUNTIME,
                Optional.ofNullable(authorizer),
                Optional.of(new AuthorizationGateConfig(TEST_GATE_DEADLINE_MS)));
    }

    // --- Test doubles ---

    /** An {@link AuthorizationDecisionPoint} whose returned future never completes. */
    private static final class NeverCompletingDecisionPoint implements AuthorizationDecisionPoint {
        private int callCount;

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            callCount++;
            return Promise.<AuthorizationDecision>promise().future(); // never completed
        }

        int callCount() {
            return callCount;
        }
    }

    /** An {@link Authorizer} whose returned future never completes. */
    private static final class NeverCompletingAuthorizer implements Authorizer {
        private int callCount;

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            callCount++;
            return Promise.<AuthorizationDecision>promise().future(); // never completed
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("decide() uses the 5-arg AuthorizationRequest overload only");
        }

        int callCount() {
            return callCount;
        }
    }

    /** A minimal role-checking {@link AuthorizationDecisionPoint}, mirroring the T005 fixture shape. */
    private static final class RecordingDecisionPoint implements AuthorizationDecisionPoint {
        private int callCount;

        static RecordingDecisionPoint roleChecking() {
            return new RecordingDecisionPoint();
        }

        int callCount() {
            return callCount;
        }

        @Override
        public Future<AuthorizationDecision> decide(AuthorizationRequest request) {
            callCount++;
            @SuppressWarnings("unchecked")
            List<String> requiredRoles = (List<String>)
                    request.context().getOrDefault(VertxProviderDecisionPoint.CTX_REQUIRED_ROLES, List.of());
            Set<String> actualRoles = request.securityContext().authorization().valuesOf(AuthorityKind.ROLE);
            boolean satisfied = actualRoles.containsAll(requiredRoles);
            return Future.succeededFuture(AuthorizationDecision.permit(
                    satisfied ? AuthzReasonCodes.PERMITTED : AuthzReasonCodes.ROLE_MISSING));
        }
    }

    /** A recording {@link Authorizer} used only for its "must not be consulted" throwing variant. */
    private static final class RecordingAuthorizer implements Authorizer {
        private int callCount;

        static RecordingAuthorizer throwing() {
            return new RecordingAuthorizer();
        }

        int callCount() {
            return callCount;
        }

        @Override
        public Future<AuthorizationDecision> authorize(AuthorizationRequest request) {
            callCount++;
            throw new RuntimeException("this authorizer must not be consulted");
        }

        @Override
        public Future<AuthorizationDecision> authorize(SecurityContext ctx, ActionRef action, ResourceRef resource) {
            throw new UnsupportedOperationException("decide() uses the 5-arg AuthorizationRequest overload only");
        }
    }
}
