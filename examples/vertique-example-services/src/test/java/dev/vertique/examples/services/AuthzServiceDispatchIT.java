// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.DispatchMetadata;
import dev.vertique.core.eventbus.Result;
import dev.vertique.examples.services.security.AuthzEventCollector;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration test proving the services {@code @RequiresAction} policy enforcement point runs inside
 * the <strong>real, Dagger-wired</strong> dispatch pipeline.
 *
 * <p>Unlike {@code ServiceAuthorizationInterceptorIT} in {@code vertique-services} — which hand-wires
 * a bare {@code ServiceMethodInvoker} with a manually constructed interceptor and no Dagger — this
 * test boots the example application's real {@link AppComponent} through the host-neutral lifecycle
 * runner ({@link dev.vertique.application.VertiqueApplicationBootstrap}, via the generated
 * {@code AppComponentVertiqueComponentFactory}) using {@link VertiqueAppExtension}, which deploys
 * the services through the {@code SERVICES}-phase
 * {@link dev.vertique.services.ServiceDeploymentManager#deployAll()} step, and dispatches over the
 * event bus. The {@code ServiceAuthorizationInterceptor} therefore reaches the deployed
 * {@code ServiceVerticle}'s {@code ServiceMethodInvoker} exactly as it does in production:
 * contributed {@code @IntoSet} by {@code DispatchModule}, enforcing the {@code @RequiresAction}
 * gate declared on {@link dev.vertique.examples.services.service.AuthzProbeService#run(String)}
 * against the live {@link dev.vertique.security.authz.Authorizer} from {@code SecurityAuthzModule},
 * and emitting decision events through the {@code SecurityEventsModule} emitter.
 *
 * <p>Three scenarios are covered:
 * <ol>
 *   <li>permitted actor (ROLE {@code prober}) → handler runs and exactly one permit
 *       {@link AuthorizationDecisionEvent} is emitted;</li>
 *   <li>denied actor (ROLE {@code viewer}) → dispatch short-circuits, the handler never runs, and
 *       exactly one deny event is emitted;</li>
 *   <li>missing identity (no {@link SecurityContext} in the dispatch context) → dispatch fails closed,
 *       the handler never runs, and exactly one deny event is emitted.</li>
 * </ol>
 *
 * <h3>Proof scope &amp; residual</h3>
 *
 * <p>example-services excludes {@code AuthModule}, so the
 * {@code SecurityContextServiceDispatchEncoder}/{@code Decoder} (registered only in
 * {@code vertique-rest-security}'s {@code AuthModule}) are not on the classpath. The caller
 * {@link SecurityContext} is therefore bound by carrying it in the dispatch-context map under
 * {@code SecurityContext.class.getName()}, which {@code ServiceMethodInvoker} installs into the
 * request-scoped holder through the real inbound execution scope before the {@code beforeDispatch}
 * chain runs — exercising the Dagger-wired interceptor on a scope-bound security context. The
 * encoder→decoder wire round-trip itself is covered by the {@code SecurityContextServiceDispatchEncoder}
 * / {@code Decoder} unit tests in {@code vertique-rest-security}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class AuthzServiceDispatchIT {

    // --- Constants ---

    /** Event bus address of the guarded operation: {@code services/{namespace}/{name}/{operation}}. */
    private static final String PROBE_ADDRESS = "services/authz/probe/run";

    /** Role granted the guarding action by {@code AuthzModule}. */
    private static final String PERMITTED_ROLE = "prober";

    /** Role NOT granted the guarding action. */
    private static final String DENIED_ROLE = "viewer";

    private static final DeliveryOptions ENVELOPE_CODEC = new DeliveryOptions().setCodecName("dispatch.envelope");

    // --- Shared application graph ---

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put("management", new JsonObject().put("enabled", false))
                    .put(
                            "rateLimit",
                            new JsonObject()
                                    .put(
                                            "policies",
                                            new JsonObject()
                                                    .put(
                                                            "rate-limit-probe-shared",
                                                            RateLimitTestPolicies.probeShared()))));

    /** Isolates each scenario's event/invocation assertions. */
    @BeforeEach
    void resetCollector() {
        AppComponent component = app.component();
        component.authzEventCollector().reset();
    }

    // --- Scenario 1: permitted actor reaches the handler ---

    @Test
    @DisplayName("permitted actor reaches the handler and emits exactly one permit event")
    void permittedActor_reachesHandler_andEmitsOnePermitEvent(VertxTestContext ctx) {
        AppComponent component = app.component();
        AuthzEventCollector collector = component.authzEventCollector();
        DispatchEnvelope<String> envelope = envelopeWithRole("hello", PERMITTED_ROLE);

        app.vertx()
                .eventBus()
                .<Result<?>>request(PROBE_ADDRESS, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    Result<?> result = msg.body();
                    assertTrue(result.isSuccess(), "dispatch should succeed for a permitted actor");
                    assertEquals("probe:hello", result.get(), "handler echo should be returned");
                    assertEquals(1, collector.handlerInvocations(), "handler must run exactly once when permitted");
                    List<AuthorizationDecisionEvent> events = collector.events();
                    assertEquals(1, events.size(), "exactly one authorization event expected");
                    assertTrue(events.get(0).decision().permitted(), "event must carry a permit decision");
                    ctx.completeNow();
                })));
    }

    // --- Scenario 2: denied actor is short-circuited before the handler ---

    @Test
    @DisplayName("denied actor is short-circuited (handler never runs) and emits exactly one deny event")
    void deniedActor_shortCircuits_handlerNeverRuns_andEmitsOneDenyEvent(VertxTestContext ctx) {
        AppComponent component = app.component();
        AuthzEventCollector collector = component.authzEventCollector();
        DispatchEnvelope<String> envelope = envelopeWithRole("hello", DENIED_ROLE);

        app.vertx()
                .eventBus()
                .<Result<?>>request(PROBE_ADDRESS, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    Result<?> result = msg.body();
                    assertFalse(result.isSuccess(), "dispatch should fail for a denied actor");
                    assertEquals(0, collector.handlerInvocations(), "handler must NOT run when denied");
                    List<AuthorizationDecisionEvent> events = collector.events();
                    assertEquals(1, events.size(), "exactly one authorization event expected");
                    assertFalse(events.get(0).decision().permitted(), "event must carry a deny decision");
                    ctx.completeNow();
                })));
    }

    // --- Scenario 3: missing identity fails closed ---

    @Test
    @DisplayName("missing identity fails closed (handler never runs) and emits exactly one deny event")
    void missingIdentity_failsClosed_andEmitsOneDenyEvent(VertxTestContext ctx) {
        AppComponent component = app.component();
        AuthzEventCollector collector = component.authzEventCollector();
        // No SecurityContext in the dispatch context — the gate must fail closed.
        DispatchEnvelope<String> envelope = DispatchEnvelope.of("hello");

        app.vertx()
                .eventBus()
                .<Result<?>>request(PROBE_ADDRESS, envelope, ENVELOPE_CODEC)
                .onComplete(ctx.succeeding(msg -> ctx.verify(() -> {
                    Result<?> result = msg.body();
                    assertFalse(result.isSuccess(), "dispatch should fail closed when no identity is bound");
                    assertEquals(0, collector.handlerInvocations(), "handler must NOT run on fail-closed");
                    List<AuthorizationDecisionEvent> events = collector.events();
                    assertEquals(1, events.size(), "exactly one authorization event expected on fail-closed");
                    assertFalse(events.get(0).decision().permitted(), "event must carry a deny decision");
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Builds a dispatch envelope carrying a {@link SecurityContext} (with the given ROLE claim) in
     * the dispatch-context map under the canonical {@code SecurityContext.class.getName()} key, the
     * way the receive side reinstates a propagated security context.
     *
     * @param payload the request payload
     * @param role    the ROLE claim value to attach to the caller context
     * @return the envelope with the security context bound in its metadata
     */
    private static DispatchEnvelope<String> envelopeWithRole(String payload, String role) {
        SecurityContext sc = securityContextWithRole(role);
        Map<String, Object> dispatchContext = Map.of(SecurityContext.class.getName(), sc);
        return DispatchEnvelope.of(payload, DispatchMetadata.of(dispatchContext));
    }

    /**
     * Builds an authenticated {@link SecurityContext} carrying a single {@link AuthorityKind#ROLE}
     * claim with the given value.
     *
     * @param role the role value
     * @return a security context whose authorization claims contain the given role
     */
    private static SecurityContext securityContextWithRole(String role) {
        AuthorityClaim claim = new AuthorityClaim(AuthorityKind.ROLE, role, "", "", "test", Map.of());
        return new RoleSecurityContext(new AuthorizationClaims(Set.of(claim), Map.of()));
    }

    /**
     * Minimal authenticated {@link SecurityContext} whose authorization claims are configurable. Uses
     * a fixed {@code user-1} user principal so the emitted event has a non-anonymous actor.
     *
     * @param authz the authorization claims to expose
     */
    private record RoleSecurityContext(AuthorizationClaims authz) implements SecurityContext {
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
}
