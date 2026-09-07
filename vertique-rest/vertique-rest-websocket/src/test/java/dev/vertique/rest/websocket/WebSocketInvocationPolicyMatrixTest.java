// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import dev.vertique.input.processing.PolicyAxis;
import dev.vertique.input.processing.testkit.A;
import dev.vertique.input.processing.testkit.B;
import dev.vertique.input.processing.testkit.C;
import dev.vertique.input.processing.testkit.ComposedSanitize;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.input.processing.testkit.InvocationPolicyScenarios.Row;
import dev.vertique.input.processing.testkit.K;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 (T020, issue #379): {@code WebSocketEndpointRegistrar} resolves and caches the IP-01..IP-19
 * invocation-policy matrix ({@code contracts/invocation-policy-resolver.md}) at registration, through
 * the package-private seam {@code cachedRoutePolicies(Method)} /
 * {@code cachedParameterPolicies(Method, int)}.
 *
 * <p>Every row is materialized as its own {@code @WebSocketEndpoint("/ws/ipNN/{id}")} nested class with
 * {@code @OnMessage void onMessage(String message, @PathParam("id") String id)} declared on the
 * concrete class (the scanner only discovers lifecycle annotations via
 * {@code Class#getDeclaredMethods()}); the hierarchy enters only through the policy annotations
 * themselves, exactly mirroring {@link InvocationPolicyScenarios}'s own carriers (interface/superclass
 * declaration for IP-09/10/12/16/17/18/19; message-parameter (index 0) declaration for IP-07/08/12/15).
 * IP-06's skip sits on the method (mirrors the testkit, not the contract table — ruling A-2, evidence
 * {@code T020.md} L00).
 *
 * <p>Each row's expected route/parameter chains are read directly off
 * {@link InvocationPolicyScenarios.Row#canonicalize()} / {@link InvocationPolicyScenarios.Row#sanitize()}
 * — the matrix stays the single source of truth — while the conflict rows' declaration-site assertions
 * are hand-written against this fixture's own class/method names (the testkit's {@code Row} carries
 * declaration sites for its own reflection carriers, not for this module's).
 */
class WebSocketInvocationPolicyMatrixTest {

    private Vertx vertx;
    private Router router;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        router = Router.router(vertx);
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }

    /** No-op engine: satisfies the composition gate so every row's declared policy is cached. */
    private static final InputObjectProcessor NOOP_PROCESSOR = new InputObjectProcessor() {
        @Override
        public Object processInput(
                Object input,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            return input;
        }

        @Override
        public void precomputeFieldNameResolution(Type declaredType, InputFieldNameResolver resolver) {
            // No per-type metadata to precompute for this double.
        }
    };

    private WebSocketEndpointRegistrar registrar() {
        return new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, null, Set.of(), null, NOOP_PROCESSOR, null, null, null);
    }

    // --- Test ---

    @ParameterizedTest(name = "{0}")
    @DisplayName("registerAll caches route/parameter policies matching the matrix")
    @MethodSource("dev.vertique.input.processing.testkit.InvocationPolicyScenarios#rows")
    void cachesPoliciesMatchingTheMatrix(Row row) throws Exception {
        Object endpoint = endpointFor(row.id());
        Method onMessage = endpoint.getClass().getDeclaredMethod("onMessage", String.class, String.class);
        WebSocketEndpointRegistrar registrar = registrar();

        // Registration resolves the route and every processed parameter in one call, so — unlike
        // ReflectiveInvocationPoliciesTest, which drives resolveRoute and resolveParameter separately —
        // a conflict on either axis surfaces as the same failure of registerAll. IP-15 conflicts only on
        // the parameter axis (route resolves to []), so both axes must be checked here.
        if (row.sanitize().route() instanceof Outcome.Conflict || row.sanitize().param() instanceof Outcome.Conflict) {
            InvocationPolicyConflictException failure = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> registrar.registerAll(Set.of(endpoint), router),
                    row.id() + " must fail registration with a policy conflict");
            assertConflictSites(row.id(), failure.getMessage());
            return;
        }

        assertDoesNotThrow(() -> registrar.registerAll(Set.of(endpoint), router), row.id() + " must register cleanly");

        EffectiveInputPolicies route = registrar.cachedRoutePolicies(onMessage);
        assertEquals(chain(row.canonicalize().route()), route.canonicalizers(), row.id() + " route canonicalizers");
        assertEquals(chain(row.sanitize().route()), route.sanitizers(), row.id() + " route sanitizers");

        EffectiveInputPolicies parameter = registrar.cachedParameterPolicies(onMessage, 0);
        assertEquals(
                chain(row.canonicalize().param()), parameter.canonicalizers(), row.id() + " parameter canonicalizers");
        assertEquals(chain(row.sanitize().param()), parameter.sanitizers(), row.id() + " parameter sanitizers");
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private static <V> List<Class<? extends V>> chain(Outcome outcome) {
        if (outcome instanceof Outcome.Chain chain) {
            return (List<Class<? extends V>>) (List<?>) chain.values();
        }
        throw new IllegalStateException("expected a Chain outcome, got " + outcome);
    }

    /**
     * Asserts that the conflict exception names both real declaration sites for this fixture's own
     * class/method names (not the testkit's — {@link InvocationPolicyScenarios} builds its exact
     * message against its own reflection carriers, which this module does not reuse).
     */
    private static void assertConflictSites(String id, String message) {
        switch (id) {
            case "IP-14" ->
                assertTrue(
                        message.contains("Ip14Endpoint.onMessage"),
                        "message must name the conflicting method on both sides: " + message);
            case "IP-15" -> {
                assertTrue(
                        message.contains("parameter 0 of method Ip15Endpoint.onMessage"),
                        "message must describe the conflicting parameter: " + message);
                assertTrue(message.contains("Ip15Endpoint.onMessage"), "message must name the site: " + message);
            }
            case "IP-17" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.onMessage", "FooImpl.onMessage", "method FooImpl.onMessage"),
                        message,
                        "message must match the shared resolver's exact conflict literal");
            case "IP-19" -> {
                assertTrue(message.contains("Ip19Endpoint"), "message must name the subclass site: " + message);
                assertTrue(message.contains("Ip19BaseEndpoint"), "message must name the superclass site: " + message);
            }
            default -> fail("unexpected conflict row " + id + ": " + message);
        }
    }

    private static Object endpointFor(String id) {
        return switch (id) {
            case "IP-01" -> new Ip01Endpoint();
            case "IP-02" -> new Ip02Endpoint();
            case "IP-03" -> new Ip03Endpoint();
            case "IP-04" -> new Ip04Endpoint();
            case "IP-05" -> new Ip05Endpoint();
            case "IP-06" -> new Ip06Endpoint();
            case "IP-07" -> new Ip07Endpoint();
            case "IP-08" -> new Ip08Endpoint();
            case "IP-09" -> new Ip09Endpoint();
            case "IP-10" -> new Ip10Endpoint();
            case "IP-11" -> new Ip11Endpoint();
            case "IP-12" -> new Ip12Endpoint();
            case "IP-13" -> new Ip13Endpoint();
            case "IP-14" -> new Ip14Endpoint();
            case "IP-15" -> new Ip15Endpoint();
            case "IP-16" -> new Ip16Endpoint();
            case "IP-17" -> new FooImpl();
            case "IP-18" -> new Ip18Endpoint();
            case "IP-19" -> new Ip19Endpoint();
            default -> throw new IllegalArgumentException("no WebSocket fixture for scenario " + id);
        };
    }

    // --- Fixtures ---
    // Every carrier's @OnMessage is onMessage(String message, @PathParam("id") String id), declared on
    // the concrete class the scanner discovers via getDeclaredMethods(); only the policy annotation
    // itself is placed per the row (interface/superclass carriers, or the message parameter).

    /** IP-01: nothing declared anywhere. */
    @WebSocketEndpoint("/ws/ip01/{id}")
    static class Ip01Endpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-02: class-level {@code @Sanitize(A)} only. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/ip02/{id}")
    static class Ip02Endpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-03: method-level {@code @Sanitize(B)} only. */
    @WebSocketEndpoint("/ws/ip03/{id}")
    static class Ip03Endpoint {
        @OnMessage
        @Sanitize(B.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-04: class-level {@code @Sanitize(A)}, method-level {@code @Sanitize(B)} — method wins. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/ip04/{id}")
    static class Ip04Endpoint {
        @OnMessage
        @Sanitize(B.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-05: class-level {@code @Sanitize(A)}, method-level {@code @SkipSanitization} — skip wins, no conflict. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/ip05/{id}")
    static class Ip05Endpoint {
        @OnMessage
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-06: method-level {@code @SkipSanitization} only (mirrors the testkit, ruling A-2). */
    @WebSocketEndpoint("/ws/ip06/{id}")
    static class Ip06Endpoint {
        @OnMessage
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-07: class-level {@code @Sanitize(A)}, parameter-level {@code @Sanitize(C)} on the message. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/ip07/{id}")
    static class Ip07Endpoint {
        @OnMessage
        public void onMessage(@Sanitize(C.class) String message, @PathParam("id") String id) {}
    }

    /** IP-08: class-level {@code @Sanitize(A)}, parameter-level {@code @SkipSanitization} on the message. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/ip08/{id}")
    static class Ip08Endpoint {
        @OnMessage
        public void onMessage(@SkipSanitization String message, @PathParam("id") String id) {}
    }

    /** IP-09: interface method {@code @Sanitize(B)}, concrete override declares no policy. */
    interface Ip09Iface {
        @Sanitize(B.class)
        void onMessage(String message, String id);
    }

    @WebSocketEndpoint("/ws/ip09/{id}")
    static class Ip09Endpoint implements Ip09Iface {
        @Override
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-10: superclass {@code @Sanitize(A)} at class level; the subclass declares its own {@code @OnMessage}. */
    @Sanitize(A.class)
    static class Ip10BaseEndpoint {}

    @WebSocketEndpoint("/ws/ip10/{id}")
    static class Ip10Endpoint extends Ip10BaseEndpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-11: a composed annotation meta-annotated with {@code @Sanitize(B)} (testkit's {@code @ComposedSanitize}). */
    @WebSocketEndpoint("/ws/ip11/{id}")
    static class Ip11Endpoint {
        @OnMessage
        @ComposedSanitize
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-12: interface method's own message parameter carries {@code @Sanitize(C)}; the override repeats nothing. */
    interface Ip12Iface {
        void onMessage(@Sanitize(C.class) String message, String id);
    }

    @WebSocketEndpoint("/ws/ip12/{id}")
    static class Ip12Endpoint implements Ip12Iface {
        @Override
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-13: class-level {@code @Canonicalize(K)}, method-level {@code @Sanitize(B)} — different axes, no conflict. */
    @Canonicalize(K.class)
    @WebSocketEndpoint("/ws/ip13/{id}")
    static class Ip13Endpoint {
        @OnMessage
        @Sanitize(B.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-14: method-level {@code @Sanitize(B)} + {@code @SkipSanitization} on the same declaration — conflict. */
    @WebSocketEndpoint("/ws/ip14/{id}")
    static class Ip14Endpoint {
        @OnMessage
        @Sanitize(B.class)
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-15: parameter-level {@code @Sanitize(C)} + {@code @SkipSanitization} on the message — conflict. */
    @WebSocketEndpoint("/ws/ip15/{id}")
    static class Ip15Endpoint {
        @OnMessage
        public void onMessage(@Sanitize(C.class) @SkipSanitization String message, @PathParam("id") String id) {}
    }

    /** IP-16: interface method {@code @SkipSanitization}, concrete override declares no policy — no conflict. */
    interface Ip16Iface {
        @SkipSanitization
        void onMessage(String message, String id);
    }

    @WebSocketEndpoint("/ws/ip16/{id}")
    static class Ip16Endpoint implements Ip16Iface {
        @Override
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-17: override {@code @SkipSanitization} of an interface method {@code @Sanitize(B)} — conflict. */
    interface IFoo {
        @Sanitize(B.class)
        void onMessage(String message, String id);
    }

    @WebSocketEndpoint("/ws/ip17/{id}")
    static class FooImpl implements IFoo {
        @Override
        @OnMessage
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-18: override {@code @Sanitize(A)} of a superclass method {@code @Sanitize(B)} — nearest wins, no conflict. */
    static class Ip18BaseEndpoint {
        @Sanitize(B.class)
        public void onMessage(String message, String id) {}
    }

    @WebSocketEndpoint("/ws/ip18/{id}")
    static class Ip18Endpoint extends Ip18BaseEndpoint {
        @Override
        @OnMessage
        @Sanitize(A.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** IP-19: superclass {@code @SkipSanitization}, subclass {@code @Sanitize(A)}, both class-level — conflict. */
    @SkipSanitization
    static class Ip19BaseEndpoint {}

    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/ip19/{id}")
    static class Ip19Endpoint extends Ip19BaseEndpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }
}
