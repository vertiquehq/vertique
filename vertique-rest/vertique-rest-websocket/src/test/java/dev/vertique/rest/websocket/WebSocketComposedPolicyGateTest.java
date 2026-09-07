// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipSanitization;
import dev.vertique.input.processing.InvocationPolicyConflictException;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Composition-gate coverage for a <em>composed</em> policy annotation on a WebSocket lifecycle
 * method. A custom annotation meta-annotated with {@code @Sanitize} is the documented, REST-supported
 * idiom, so the WebSocket gate must resolve meta-annotations exactly as {@code AnnotationResolver}
 * does for REST — otherwise an endpoint whose {@code @OnMessage} declares a composed chain boots with
 * no engine bound and processes nothing.
 */
class WebSocketComposedPolicyGateTest {

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

    // --- Fixtures ---

    /** Inert sanitizer; the gate is about the declaration, never about what the chain does. */
    public static final class NoopSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value;
        }
    }

    /** Composed policy preset — the documented way to name a reusable chain. */
    @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Sanitize(NoopSanitizer.class)
    public @interface SafeText {}

    /** Endpoint declaring its chain through a composed annotation rather than a bare {@code @Sanitize}. */
    @WebSocketEndpoint("/ws/composed")
    static class ComposedPolicyEndpoint {

        @OnMessage
        @SafeText
        void onMessage(WebSocketSession session, String message) {}
    }

    /** Endpoint declaring nothing — the control arm. */
    @WebSocketEndpoint("/ws/plain")
    static class PlainEndpoint {

        @OnMessage
        void onMessage(WebSocketSession session, String message) {}
    }

    /**
     * Endpoint whose only policy is a class-level {@code @Sanitize} — a bare {@code @OnMessage} with no
     * method- or parameter-level annotation of its own (T020, issue #379). The gate must resolve this
     * exactly as the cached route policies would: a class-level chain now applies to every lifecycle
     * method, so it must trip the gate identically to a method-level declaration.
     */
    @Sanitize(NoopSanitizer.class)
    @WebSocketEndpoint("/ws/class-level")
    static class ClassLevelPolicyEndpoint {

        @OnMessage
        void onMessage(WebSocketSession session, String message) {}
    }

    /**
     * Endpoint whose lifecycle method declares a chain and its skip on the same element: a policy
     * conflict (a class-level chain with a method-level skip is a legitimate override, not a
     * conflict). Policies are resolved before the composition gate runs, so this must fail with the
     * conflict exception even when no engine is bound, never with the aggregated gate error.
     */
    @WebSocketEndpoint("/ws/conflict")
    static class ConflictingPolicyEndpoint {

        @OnMessage
        @Sanitize(NoopSanitizer.class)
        @SkipSanitization
        void onMessage(WebSocketSession session, String message) {}
    }

    private static WebSocketEndpointRegistrar unboundRegistrar() {
        return new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, null, Set.of(), null, null, null, null, null);
    }

    // --- Tests ---

    @Test
    @DisplayName("a composed @Sanitize on @OnMessage fails startup when no InputObjectProcessor is bound")
    void shouldFailStartupForAComposedPolicyWithNoBoundEngine() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> unboundRegistrar().registerAll(Set.of(new ComposedPolicyEndpoint()), router),
                "a composed policy annotation declares a chain exactly as a bare one does");

        String message = failure.getMessage();
        assertTrue(message.contains("/ws/composed"), "the aggregated error must name the endpoint: " + message);
        assertTrue(message.contains("onMessage"), "the aggregated error must name the lifecycle method: " + message);
    }

    @Test
    @DisplayName("an endpoint declaring no policy still starts without an InputObjectProcessor")
    void shouldStartWhenNoEndpointDeclaresPolicies() {
        assertDoesNotThrow(
                () -> unboundRegistrar().registerAll(Set.of(new PlainEndpoint()), router),
                "the gate must not fire for endpoints that declare nothing to process");
    }

    @Test
    @DisplayName("a class-level @Sanitize with a bare @OnMessage fails startup when no InputObjectProcessor is bound")
    void shouldFailStartupForAClassLevelPolicyWithNoBoundEngine() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> unboundRegistrar().registerAll(Set.of(new ClassLevelPolicyEndpoint()), router),
                "a class-level policy annotation declares a chain for every lifecycle method, even with no"
                        + " method- or parameter-level annotation of its own");

        String message = failure.getMessage();
        assertTrue(message.contains("/ws/class-level"), "the aggregated error must name the endpoint: " + message);
        assertTrue(message.contains("onMessage"), "the aggregated error must name the lifecycle method: " + message);
    }

    @Test
    @DisplayName(
            "a policy conflict fails startup with the conflict exception even when no InputObjectProcessor is bound")
    void shouldFailStartupOnAPolicyConflictWithNoBoundEngine() {
        InvocationPolicyConflictException failure = assertThrows(
                InvocationPolicyConflictException.class,
                () -> unboundRegistrar().registerAll(Set.of(new ConflictingPolicyEndpoint()), router),
                "policies are resolved before the composition gate, so a conflict is a conflict error");

        String message = failure.getMessage();
        assertTrue(
                message.contains("ConflictingPolicyEndpoint"),
                "the conflict must name the declaration sites: " + message);
        assertTrue(message.contains("onMessage"), "the conflict must name the lifecycle method: " + message);
    }
}
