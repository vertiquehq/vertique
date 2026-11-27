// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.input.InputFieldNameResolver;
import dev.vertique.core.input.InputLocation;
import dev.vertique.sanitization.Canonicalize;
import dev.vertique.sanitization.Sanitize;
import dev.vertique.sanitization.SkipSanitization;
import dev.vertique.sanitization.processing.EffectiveInputPolicies;
import dev.vertique.sanitization.processing.InputObjectProcessor;
import dev.vertique.sanitization.processing.InvocationPolicyConflictException;
import dev.vertique.sanitization.processing.PolicyAxis;
import dev.vertique.sanitization.processing.testkit.A;
import dev.vertique.sanitization.processing.testkit.B;
import dev.vertique.sanitization.processing.testkit.C;
import dev.vertique.sanitization.processing.testkit.ComposedSanitize;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Outcome;
import dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios.Row;
import dev.vertique.sanitization.processing.testkit.K;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code WebSocketEndpointRegistrar} resolves and caches the invocation-policy matrix at registration,
 * through the package-private seam {@code cachedRoutePolicies(Class, Method)} /
 * {@code cachedParameterPolicies(Class, Method, int)}.
 *
 * <p>Every row is materialized as its own {@code @WebSocketEndpoint("/ws/<scenario>/{id}")} nested class with
 * {@code @OnMessage void onMessage(String message, @PathParam("id") String id)} declared on the
 * concrete class (the scanner also finds inherited handlers, but a handler on the concrete class
 * keeps each row independent); the hierarchy enters only through the policy annotations
 * themselves, exactly mirroring {@link InvocationPolicyScenarios}'s own carriers (interface/superclass
 * declaration for {@code interface-method-chain}, {@code superclass-type-chain},
 * {@code interface-parameter-chain}, {@code interface-method-skip}, {@code override-skip-conflict},
 * {@code override-replaces-chain}, and {@code supertype-skip-conflict}; message-parameter (index 0)
 * declaration for {@code parameter-overrides-type}, {@code parameter-skip-over-type-chain},
 * {@code interface-parameter-chain}, and {@code same-parameter-conflict}). The {@code method-skip-only}
 * skip sits on the method, mirroring the testkit.
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

    @Test
    @DisplayName("registerAll rejects @OnMessage with a second payload-eligible parameter")
    void rejectsSecondPayloadParameterAtStartup() {
        ConfigurationException failure = assertThrows(ConfigurationException.class, () -> registrar()
                .registerAll(Set.of(new MultiPayloadEndpoint()), router));
        assertTrue(
                failure.getMessage().contains("MultiPayloadEndpoint.onMessage"),
                "message must name the method: " + failure.getMessage());
    }

    /** A message handler shared by endpoints that declare different class-level policies. */
    static class SharedMessageHandler {
        @OnMessage
        public void onMessage(String message) {}
    }

    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/inherited-a")
    static class InheritingEndpointA extends SharedMessageHandler {}

    @Sanitize(B.class)
    @WebSocketEndpoint("/ws/inherited-b")
    static class InheritingEndpointB extends SharedMessageHandler {}

    @Test
    @DisplayName("an inherited handler is cached per endpoint class, each with its own class-level policy")
    void cachesAnInheritedHandlerPerEndpointClass() throws Exception {
        Method shared = SharedMessageHandler.class.getDeclaredMethod("onMessage", String.class);
        WebSocketEndpointRegistrar registrar = registrar();

        registrar.registerAll(Set.of(new InheritingEndpointA(), new InheritingEndpointB()), router);

        assertEquals(
                List.of(A.class),
                registrar.cachedRoutePolicies(InheritingEndpointA.class, shared).sanitizers());
        assertEquals(
                List.of(B.class),
                registrar.cachedRoutePolicies(InheritingEndpointB.class, shared).sanitizers());
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("registerAll caches route/parameter policies matching the matrix")
    @MethodSource("dev.vertique.sanitization.processing.testkit.InvocationPolicyScenarios#rows")
    void cachesPoliciesMatchingTheMatrix(Row row) throws Exception {
        Object endpoint = endpointFor(row.id());
        Method onMessage = endpoint.getClass().getDeclaredMethod("onMessage", String.class, String.class);
        WebSocketEndpointRegistrar registrar = registrar();

        // Registration resolves the route and every processed parameter in one call, so — unlike
        // ReflectiveInvocationPoliciesTest, which drives resolveRoute and resolveParameter separately —
        // a conflict on either axis surfaces as the same failure of registerAll. same-parameter-conflict
        // conflicts only on the parameter axis (route resolves to []), so both axes must be checked here.
        if (row.sanitize().route() instanceof Outcome.Conflict || row.sanitize().param() instanceof Outcome.Conflict) {
            InvocationPolicyConflictException failure = assertThrows(
                    InvocationPolicyConflictException.class,
                    () -> registrar.registerAll(Set.of(endpoint), router),
                    row.id() + " must fail registration with a policy conflict");
            assertConflictSites(row.id(), failure.getMessage());
            return;
        }

        assertDoesNotThrow(() -> registrar.registerAll(Set.of(endpoint), router), row.id() + " must register cleanly");

        EffectiveInputPolicies route = registrar.cachedRoutePolicies(endpoint.getClass(), onMessage);
        assertEquals(chain(row.canonicalize().route()), route.canonicalizers(), row.id() + " route canonicalizers");
        assertEquals(chain(row.sanitize().route()), route.sanitizers(), row.id() + " route sanitizers");

        EffectiveInputPolicies parameter = registrar.cachedParameterPolicies(endpoint.getClass(), onMessage, 0);
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
            case "same-method-conflict" ->
                assertTrue(
                        message.contains("SameMethodConflictEndpoint.onMessage"),
                        "message must name the conflicting method on both sides: " + message);
            case "same-parameter-conflict" -> {
                assertTrue(
                        message.contains("parameter 0 of method SameParameterConflictEndpoint.onMessage"),
                        "message must describe the conflicting parameter: " + message);
                assertTrue(
                        message.contains("SameParameterConflictEndpoint.onMessage"),
                        "message must name the site: " + message);
            }
            case "override-skip-conflict" ->
                assertEquals(
                        InvocationPolicyScenarios.expectedConflictMessage(
                                PolicyAxis.SANITIZE, "IFoo.onMessage", "FooImpl.onMessage", "method FooImpl.onMessage"),
                        message,
                        "message must match the shared resolver's exact conflict literal");
            case "supertype-skip-conflict" -> {
                assertTrue(
                        message.contains("SupertypeSkipConflictEndpoint"),
                        "message must name the subclass site: " + message);
                assertTrue(
                        message.contains("SupertypeSkipConflictBaseEndpoint"),
                        "message must name the superclass site: " + message);
            }
            default -> fail("unexpected conflict row " + id + ": " + message);
        }
    }

    private static Object endpointFor(String id) {
        return switch (id) {
            case "no-policy" -> new NoPolicyEndpoint();
            case "type-chain" -> new TypeChainEndpoint();
            case "method-chain" -> new MethodChainEndpoint();
            case "method-overrides-type" -> new MethodOverridesTypeEndpoint();
            case "method-skip-over-type-chain" -> new MethodSkipOverTypeChainEndpoint();
            case "method-skip-only" -> new MethodSkipOnlyEndpoint();
            case "parameter-overrides-type" -> new ParameterOverridesTypeEndpoint();
            case "parameter-skip-over-type-chain" -> new ParameterSkipOverTypeChainEndpoint();
            case "interface-method-chain" -> new InterfaceMethodChainEndpoint();
            case "superclass-type-chain" -> new SuperclassTypeChainEndpoint();
            case "composed-annotation" -> new ComposedAnnotationEndpoint();
            case "interface-parameter-chain" -> new InterfaceParameterChainEndpoint();
            case "independent-axes" -> new IndependentAxesEndpoint();
            case "same-method-conflict" -> new SameMethodConflictEndpoint();
            case "same-parameter-conflict" -> new SameParameterConflictEndpoint();
            case "interface-method-skip" -> new InterfaceMethodSkipEndpoint();
            case "override-skip-conflict" -> new FooImpl();
            case "override-replaces-chain" -> new OverrideReplacesChainEndpoint();
            case "supertype-skip-conflict" -> new SupertypeSkipConflictEndpoint();
            default -> throw new IllegalArgumentException("no WebSocket fixture for scenario " + id);
        };
    }

    // --- Fixtures ---
    // Every carrier's @OnMessage is onMessage(String message, @PathParam("id") String id), declared on
    // the concrete class, so each row stands alone; only the policy annotation
    // itself is placed per the row (interface/superclass carriers, or the message parameter).

    /** Two payload-eligible parameters — rejected at scan/startup. */
    @WebSocketEndpoint("/ws/multi-payload")
    static class MultiPayloadEndpoint {
        @OnMessage
        public void onMessage(String a, @Sanitize(A.class) String b) {}
    }

    /** no-policy: nothing declared anywhere. */
    @WebSocketEndpoint("/ws/no-policy/{id}")
    static class NoPolicyEndpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** type-chain: class-level {@code @Sanitize(A)} only. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/type-chain/{id}")
    static class TypeChainEndpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** method-chain: method-level {@code @Sanitize(B)} only. */
    @WebSocketEndpoint("/ws/method-chain/{id}")
    static class MethodChainEndpoint {
        @OnMessage
        @Sanitize(B.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** method-overrides-type: class-level {@code @Sanitize(A)}, method-level {@code @Sanitize(B)} — method wins. */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/method-overrides-type/{id}")
    static class MethodOverridesTypeEndpoint {
        @OnMessage
        @Sanitize(B.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * method-skip-over-type-chain: class-level {@code @Sanitize(A)}, method-level {@code @SkipSanitization} — skip
     * wins, no conflict.
     */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/method-skip-over-type-chain/{id}")
    static class MethodSkipOverTypeChainEndpoint {
        @OnMessage
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /** method-skip-only: method-level {@code @SkipSanitization} only (mirrors the testkit). */
    @WebSocketEndpoint("/ws/method-skip-only/{id}")
    static class MethodSkipOnlyEndpoint {
        @OnMessage
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * parameter-overrides-type: class-level {@code @Sanitize(A)}, parameter-level {@code @Sanitize(C)} on the message.
     */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/parameter-overrides-type/{id}")
    static class ParameterOverridesTypeEndpoint {
        @OnMessage
        public void onMessage(@Sanitize(C.class) String message, @PathParam("id") String id) {}
    }

    /**
     * parameter-skip-over-type-chain: class-level {@code @Sanitize(A)}, parameter-level {@code @SkipSanitization} on
     * the message.
     */
    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/parameter-skip-over-type-chain/{id}")
    static class ParameterSkipOverTypeChainEndpoint {
        @OnMessage
        public void onMessage(@SkipSanitization String message, @PathParam("id") String id) {}
    }

    /** interface-method-chain: interface method {@code @Sanitize(B)}, concrete override declares no policy. */
    interface InterfaceMethodChainIface {
        @Sanitize(B.class)
        void onMessage(String message, String id);
    }

    @WebSocketEndpoint("/ws/interface-method-chain/{id}")
    static class InterfaceMethodChainEndpoint implements InterfaceMethodChainIface {
        @Override
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * superclass-type-chain: superclass {@code @Sanitize(A)} at class level; the subclass declares its own {@code
     * @OnMessage}.
     */
    @Sanitize(A.class)
    static class SuperclassTypeChainBaseEndpoint {}

    @WebSocketEndpoint("/ws/superclass-type-chain/{id}")
    static class SuperclassTypeChainEndpoint extends SuperclassTypeChainBaseEndpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * composed-annotation: a composed annotation meta-annotated with {@code @Sanitize(B)} (testkit's {@code
     * @ComposedSanitize}).
     */
    @WebSocketEndpoint("/ws/composed-annotation/{id}")
    static class ComposedAnnotationEndpoint {
        @OnMessage
        @ComposedSanitize
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * interface-parameter-chain: interface method's own message parameter carries {@code @Sanitize(C)}; the override
     * repeats nothing.
     */
    interface InterfaceParameterChainIface {
        void onMessage(@Sanitize(C.class) String message, String id);
    }

    @WebSocketEndpoint("/ws/interface-parameter-chain/{id}")
    static class InterfaceParameterChainEndpoint implements InterfaceParameterChainIface {
        @Override
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * independent-axes: class-level {@code @Canonicalize(K)}, method-level {@code @Sanitize(B)} — different axes, no
     * conflict.
     */
    @Canonicalize(K.class)
    @WebSocketEndpoint("/ws/independent-axes/{id}")
    static class IndependentAxesEndpoint {
        @OnMessage
        @Sanitize(B.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * same-method-conflict: method-level {@code @Sanitize(B)} + {@code @SkipSanitization} on the same declaration —
     * conflict.
     */
    @WebSocketEndpoint("/ws/same-method-conflict/{id}")
    static class SameMethodConflictEndpoint {
        @OnMessage
        @Sanitize(B.class)
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * same-parameter-conflict: parameter-level {@code @Sanitize(C)} + {@code @SkipSanitization} on the message —
     * conflict.
     */
    @WebSocketEndpoint("/ws/same-parameter-conflict/{id}")
    static class SameParameterConflictEndpoint {
        @OnMessage
        public void onMessage(@Sanitize(C.class) @SkipSanitization String message, @PathParam("id") String id) {}
    }

    /**
     * interface-method-skip: interface method {@code @SkipSanitization}, concrete override declares no policy — no
     * conflict.
     */
    interface InterfaceMethodSkipIface {
        @SkipSanitization
        void onMessage(String message, String id);
    }

    @WebSocketEndpoint("/ws/interface-method-skip/{id}")
    static class InterfaceMethodSkipEndpoint implements InterfaceMethodSkipIface {
        @Override
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * override-skip-conflict: override {@code @SkipSanitization} of an interface method {@code @Sanitize(B)} —
     * conflict.
     */
    interface IFoo {
        @Sanitize(B.class)
        void onMessage(String message, String id);
    }

    @WebSocketEndpoint("/ws/override-skip-conflict/{id}")
    static class FooImpl implements IFoo {
        @Override
        @OnMessage
        @SkipSanitization
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * override-replaces-chain: override {@code @Sanitize(A)} of a superclass method {@code @Sanitize(B)} — nearest
     * wins, no conflict.
     */
    static class OverrideReplacesChainBaseEndpoint {
        @Sanitize(B.class)
        public void onMessage(String message, String id) {}
    }

    @WebSocketEndpoint("/ws/override-replaces-chain/{id}")
    static class OverrideReplacesChainEndpoint extends OverrideReplacesChainBaseEndpoint {
        @Override
        @OnMessage
        @Sanitize(A.class)
        public void onMessage(String message, @PathParam("id") String id) {}
    }

    /**
     * supertype-skip-conflict: superclass {@code @SkipSanitization}, subclass {@code @Sanitize(A)}, both class-level —
     * conflict.
     */
    @SkipSanitization
    static class SupertypeSkipConflictBaseEndpoint {}

    @Sanitize(A.class)
    @WebSocketEndpoint("/ws/supertype-skip-conflict/{id}")
    static class SupertypeSkipConflictEndpoint extends SupertypeSkipConflictBaseEndpoint {
        @OnMessage
        public void onMessage(String message, @PathParam("id") String id) {}
    }
}
