// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import java.lang.reflect.Type;
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

    /** Pass-through engine standing in for a bound {@code SanitizationModule}. */
    private static final class PassThroughProcessor implements InputObjectProcessor {
        @Override
        public Object processInput(
                Object input,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            return input;
        }
    }

    private static WebSocketEndpointRegistrar registrar() {
        return new WebSocketEndpointRegistrar(
                new WebSocketMessageCodec(),
                null,
                null,
                null,
                Set.of(),
                null,
                new PassThroughProcessor(),
                null,
                null,
                null);
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
