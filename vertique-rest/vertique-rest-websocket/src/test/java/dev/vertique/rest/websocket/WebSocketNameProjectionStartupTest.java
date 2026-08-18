// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.input.processing.InputObjectProcessor;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Startup composition of the WebSocket wire &rarr; Java message-name projection.
 *
 * <p>{@link WebSocketEndpointRegistrar} knows every endpoint's message type at registration, so the
 * projection for each is composed there rather than lazily on the message path. Composing it at
 * registration is what turns an unresolvable projection into a boot failure instead of a failure on
 * every message that touches the type — and it is what keeps the message path free of bean
 * introspection on an event-loop thread.
 */
class WebSocketNameProjectionStartupTest {

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

    /**
     * Message type whose two properties claim the same {@code @JsonAlias}. The projection cannot
     * decide which property owns the key without disagreeing with Jackson, which resolves the same
     * collision in hash order.
     */
    public static class DuplicateAliasMessage {
        @JsonAlias({"shared"})
        public String alpha;

        @JsonAlias({"shared"})
        public String beta;
    }

    /** Message type whose projection composes cleanly. */
    public static class PlainMessage {
        public String text;
    }

    @WebSocketEndpoint("/ws/unprojectable")
    static class UnprojectableMessageEndpoint {
        @OnMessage
        void onMessage(WebSocketSession session, DuplicateAliasMessage message) {}
    }

    /** Message type that projects cleanly itself but declares a field of an unprojectable type. */
    public static class NestedAliasHolderMessage {
        public String label;
        public DuplicateAliasMessage nested;
    }

    @WebSocketEndpoint("/ws/plain")
    static class PlainMessageEndpoint {
        @OnMessage
        void onMessage(WebSocketSession session, PlainMessage message) {}
    }

    @WebSocketEndpoint("/ws/nested")
    static class NestedUnprojectableMessageEndpoint {
        @OnMessage
        void onMessage(WebSocketSession session, NestedAliasHolderMessage message) {}
    }

    @WebSocketEndpoint("/ws/array")
    static class ArrayMessageEndpoint {
        @OnMessage
        void onMessage(WebSocketSession session, DuplicateAliasMessage[] message) {}
    }

    @WebSocketEndpoint("/ws/text")
    static class TextMessageEndpoint {
        @OnMessage
        void onMessage(WebSocketSession session, String message) {}
    }

    /**
     * Message type reaching {@link DuplicateAliasMessage} only through a private field with no
     * accessor.
     *
     * <p>Jackson's property introspection does not expose such a field, so a warm-up walk driven by
     * Jackson properties never reaches its target. The engine records every declared field of a
     * descendable type unconditionally and dispatches nested fragments against it, so this is a type
     * whose projection <em>is</em> consulted on the message path.
     */
    public static class HiddenAliasHolderMessage {
        public String label;

        @SuppressWarnings("unused")
        private DuplicateAliasMessage hidden;
    }

    @WebSocketEndpoint("/ws/hidden")
    static class HiddenUnprojectableMessageEndpoint {
        @OnMessage
        void onMessage(WebSocketSession session, HiddenAliasHolderMessage message) {}
    }

    /**
     * The real engine, which owns the warm-up walk. A test that asserts <em>which</em> message types
     * get their projection composed cannot use a double: the walk is the engine's own descent, so a
     * no-op {@code precomputeFieldNameResolution} would compose nothing and prove nothing.
     *
     * <p>The resolver functions throw because no fixture here declares a chain, and both are
     * consulted only when a value is actually processed.
     *
     * @return a default engine over policy-free fixtures
     */
    private static InputObjectProcessor realEngine() {
        return InputObjectProcessor.createDefault(
                type -> {
                    throw new AssertionError("no canonicalizer is declared by these fixtures: " + type);
                },
                type -> {
                    throw new AssertionError("no sanitizer is declared by these fixtures: " + type);
                });
    }

    private static WebSocketEndpointRegistrar registrar() {
        return new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(), null, null, null, Set.of(), null, realEngine(), null, null, null);
    }

    // --- Tests ---

    @Test
    @DisplayName("a message type whose projection cannot be composed fails startup, not the first message")
    void shouldFailStartupWhenAMessageTypesProjectionCannotBeComposed() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> registrar().registerAll(Set.of(new UnprojectableMessageEndpoint()), router),
                "the projection is composed at endpoint registration, so an unresolvable one fails startup");

        String message = failure.getMessage();
        assertTrue(
                message.contains(DuplicateAliasMessage.class.getName()),
                "the failure must name the message type whose projection cannot be composed: " + message);
        assertTrue(message.contains("shared"), "the failure must name the contested wire name: " + message);
    }

    @Test
    @DisplayName("a message type whose NESTED type cannot be projected fails startup, not the first message")
    void shouldFailStartupWhenANestedMessageTypesProjectionCannotBeComposed() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> registrar().registerAll(Set.of(new NestedUnprojectableMessageEndpoint()), router),
                "the engine resolves a nested type's projection on the message path, so warming has to "
                        + "cover the declared field graph — not only the message type itself");

        String message = failure.getMessage();
        assertTrue(
                message.contains(DuplicateAliasMessage.class.getName()),
                "the failure must name the nested type whose projection cannot be composed: " + message);
        assertTrue(message.contains("shared"), "the failure must name the contested wire name: " + message);
    }

    @Test
    @DisplayName("an array message type is unwrapped to its component type, exactly as the REST path unwraps it")
    void shouldUnwrapAnArrayMessageTypeToItsComponentType() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> registrar().registerAll(Set.of(new ArrayMessageEndpoint()), router),
                "warming an array message type must compose the component type's projection — the array "
                        + "class itself has no property set the engine ever keys against");

        assertTrue(
                failure.getMessage().contains(DuplicateAliasMessage.class.getName()),
                "the failure must name the component type, not the array class: " + failure.getMessage());
    }

    @Test
    @DisplayName("a colliding message type reached only through a Jackson-invisible field fails endpoint registration")
    void collidingMessageTypeFailsEndpointRegistration() {
        ConfigurationException failure = assertThrows(
                ConfigurationException.class,
                () -> registrar().registerAll(Set.of(new HiddenUnprojectableMessageEndpoint()), router),
                "the engine descends every declared field of a descendable type, so warming must follow "
                        + "the engine's own owner set — not the narrower set Jackson exposes as properties");

        String message = failure.getMessage();
        assertTrue(
                message.contains(DuplicateAliasMessage.class.getName()),
                "the failure must name the type whose projection cannot be composed: " + message);
        assertTrue(message.contains("shared"), "the failure must name the contested wire name: " + message);
    }

    @Test
    @DisplayName("an ordinary message type registers normally")
    void shouldRegisterAnOrdinaryMessageType() {
        assertDoesNotThrow(
                () -> registrar().registerAll(Set.of(new PlainMessageEndpoint()), router),
                "warming the projection must not reject message types whose names project cleanly");
    }

    @Test
    @DisplayName("a String message type registers normally — a scalar has no projection to compose")
    void shouldRegisterAStringMessageType() {
        assertDoesNotThrow(
                () -> registrar().registerAll(Set.of(new TextMessageEndpoint()), router),
                "the default String message type carries no declared property set, so warming it is a no-op");
    }
}
