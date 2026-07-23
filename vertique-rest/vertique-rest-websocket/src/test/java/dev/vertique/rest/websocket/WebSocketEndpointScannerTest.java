// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.validation.ValidateWith;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketEndpointScanner}, verifying lifecycle method discovery,
 * validation constraints, message type resolution, path parameter binding, and security
 * policy scanning.
 */
@DisplayName("WebSocketEndpointScanner")
class WebSocketEndpointScannerTest {

    private WebSocketEndpointScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new WebSocketEndpointScanner();
    }

    // --- Valid endpoint fixtures ---

    @WebSocketEndpoint("/ws/test")
    static class SimpleEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}

        @OnMessage
        void onMessage(WebSocketSession session, String msg) {}

        @OnClose
        void onClose(WebSocketSession session) {}

        @OnError
        void onError(WebSocketSession session, Throwable error) {}
    }

    @WebSocketEndpoint("/ws/{type}/{id}")
    static class MultiParamEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session, @PathParam("type") String type, @PathParam("id") String id) {}
    }

    @WebSocketEndpoint("/ws/binary")
    static class BinaryEndpoint {

        @OnMessage
        void onMessage(WebSocketSession session, Buffer data) {}
    }

    @WebSocketEndpoint("/ws/custom")
    static class CustomMessageEndpoint {

        @OnMessage
        void onMessage(WebSocketSession session, CustomMessage msg) {}

        record CustomMessage(String text, int count) {}
    }

    @WebSocketEndpoint("/ws/future")
    static class FutureReturnEndpoint {

        @OnMessage
        Future<Void> onMessage(WebSocketSession session, String msg) {
            return Future.succeededFuture();
        }
    }

    /** Test validation group marker interface. */
    interface TestValidationGroup {}

    @WebSocketEndpoint("/ws/validated")
    static class ValidateWithEndpoint {

        @OnMessage
        @ValidateWith({TestValidationGroup.class})
        void handle(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/no-validate")
    static class NoValidateWithEndpoint {

        @OnMessage
        void handle(WebSocketSession session, String msg) {}
    }

    // --- Invalid endpoint fixtures ---

    @WebSocketEndpoint("/ws/test")
    static class DuplicateOnOpen {

        @OnOpen
        void one(WebSocketSession session) {}

        @OnOpen
        void two(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/test")
    static class BadReturnType {

        @OnMessage
        String handle(WebSocketSession session, String msg) {
            return "";
        }
    }

    @WebSocketEndpoint("/ws/{id}")
    static class WrongPathParam {

        @OnOpen
        void onOpen(WebSocketSession session, @PathParam("wrong") String x) {}
    }

    static class NotAnnotated {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    // --- Tests ---

    @Nested
    @DisplayName("valid endpoint scanning")
    class ValidEndpoints {

        @Test
        @DisplayName("scans all lifecycle methods on SimpleEndpoint")
        void scansAllLifecycleMethods() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());

            assertNotNull(meta.onOpen(), "onOpen must be discovered");
            assertNotNull(meta.onMessage(), "onMessage must be discovered");
            assertNotNull(meta.onClose(), "onClose must be discovered");
            assertNotNull(meta.onError(), "onError must be discovered");
        }

        @Test
        @DisplayName("path is taken from @WebSocketEndpoint value")
        void pathFromAnnotation() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());
            assertEquals("/ws/test", meta.path());
        }

        @Test
        @DisplayName("endpoint instance is stored in meta")
        void endpointInstanceStored() {
            SimpleEndpoint endpoint = new SimpleEndpoint();
            WebSocketEndpointMeta meta = scanner.scan(endpoint);
            assertEquals(endpoint, meta.instance());
        }

        @Test
        @DisplayName("message type defaults to String when param is String")
        void messageTypeDefaultsToString() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());
            assertEquals(String.class, meta.messageType());
            assertFalse(meta.binaryMessage());
        }

        @Test
        @DisplayName("message type is Buffer for binary endpoint")
        void messageTypeIsBufferForBinary() {
            WebSocketEndpointMeta meta = scanner.scan(new BinaryEndpoint());
            assertEquals(Buffer.class, meta.messageType());
            assertTrue(meta.binaryMessage());
        }

        @Test
        @DisplayName("message type resolves to custom record type")
        void messageTypeResolvesToCustomType() {
            WebSocketEndpointMeta meta = scanner.scan(new CustomMessageEndpoint());
            assertEquals(CustomMessageEndpoint.CustomMessage.class, meta.messageType());
            assertFalse(meta.binaryMessage());
        }

        @Test
        @DisplayName("Future<Void> return type is accepted")
        void futureVoidReturnTypeAccepted() {
            WebSocketEndpointMeta meta = scanner.scan(new FutureReturnEndpoint());
            assertNotNull(meta.onMessage());
        }

        @Test
        @DisplayName("null lifecycle methods when not declared")
        void nullLifecycleMethodsWhenNotDeclared() {
            WebSocketEndpointMeta meta = scanner.scan(new BinaryEndpoint());
            assertNull(meta.onOpen(), "onOpen must be null when not declared");
            assertNull(meta.onClose(), "onClose must be null when not declared");
            assertNull(meta.onError(), "onError must be null when not declared");
        }
    }

    @Nested
    @DisplayName("path parameter binding")
    class PathParameterBinding {

        @Test
        @DisplayName("@PathParam names matching template are collected")
        void pathParamsCollectedFromLifecycleMethod() {
            WebSocketEndpointMeta meta = scanner.scan(new MultiParamEndpoint());
            assertEquals(2, meta.pathParams().size());
            assertTrue(meta.pathParams().stream().anyMatch(p -> p.name().equals("type")));
            assertTrue(meta.pathParams().stream().anyMatch(p -> p.name().equals("id")));
        }

        @Test
        @DisplayName("@PathParam name not in template throws IllegalArgumentException")
        void pathParamNotInTemplateThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new WrongPathParam()));
        }
    }

    @Nested
    @DisplayName("duplicate lifecycle annotation detection")
    class DuplicateAnnotations {

        @Test
        @DisplayName("duplicate @OnOpen throws IllegalArgumentException")
        void duplicateOnOpenThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new DuplicateOnOpen()));
        }
    }

    @Nested
    @DisplayName("return type validation")
    class ReturnTypeValidation {

        @Test
        @DisplayName("invalid return type throws IllegalArgumentException")
        void invalidReturnTypeThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new BadReturnType()));
        }
    }

    @Nested
    @DisplayName("missing @WebSocketEndpoint annotation")
    class MissingAnnotation {

        @Test
        @DisplayName("class without @WebSocketEndpoint throws IllegalArgumentException")
        void missingAnnotationThrows() {
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(new NotAnnotated()));
        }
    }

    @Nested
    @DisplayName("@ValidateWith resolution")
    class ValidateWithResolution {

        @Test
        @DisplayName("@ValidateWith on @OnMessage method is captured in validationGroups")
        void validateWithGroupsAreCaptured() {
            WebSocketEndpointMeta meta = scanner.scan(new ValidateWithEndpoint());

            assertNotNull(meta.validationGroups(), "validationGroups must not be null when @ValidateWith is present");
            assertArrayEquals(
                    new Class<?>[] {TestValidationGroup.class},
                    meta.validationGroups(),
                    "validationGroups must contain the groups declared in @ValidateWith");
        }

        @Test
        @DisplayName("validationGroups is null when @ValidateWith is absent from @OnMessage")
        void validationGroupsIsNullWhenAnnotationAbsent() {
            WebSocketEndpointMeta meta = scanner.scan(new NoValidateWithEndpoint());

            assertNull(meta.validationGroups(), "validationGroups must be null when @ValidateWith is not present");
        }

        @Test
        @DisplayName("validationGroups is null when there is no @OnMessage method")
        void validationGroupsIsNullWhenNoOnMessage() {
            WebSocketEndpointMeta meta = scanner.scan(new BinaryEndpoint());

            assertNull(meta.validationGroups(), "validationGroups must be null when there is no @OnMessage");
        }
    }

    @Nested
    @DisplayName("security policy resolution")
    class SecurityPolicyResolution {

        @WebSocketEndpoint("/ws/secure")
        @jakarta.annotation.security.PermitAll
        class PermitAllEndpoint {

            @OnOpen
            void onOpen(WebSocketSession session) {}
        }

        @WebSocketEndpoint("/ws/denied")
        @jakarta.annotation.security.DenyAll
        class DenyAllEndpoint {

            @OnOpen
            void onOpen(WebSocketSession session) {}
        }

        @Test
        @DisplayName("@PermitAll on class resolves to PermitAll security policy variant")
        void permitAllResolvedFromClassAnnotation() {
            WebSocketEndpointMeta meta = scanner.scan(new PermitAllEndpoint());
            assertNotNull(meta.securityPolicy(), "Security policy must not be null");
            assertTrue(
                    meta.securityPolicy() instanceof dev.vertique.rest.core.security.SecurityPolicy.PermitAll,
                    "Expected PermitAll policy variant but got: "
                            + meta.securityPolicy().getClass().getSimpleName());
        }

        @Test
        @DisplayName("@DenyAll on class resolves to DenyAll security policy variant")
        void denyAllResolvedFromClassAnnotation() {
            WebSocketEndpointMeta meta = scanner.scan(new DenyAllEndpoint());
            assertNotNull(meta.securityPolicy());
            assertTrue(
                    meta.securityPolicy() instanceof dev.vertique.rest.core.security.SecurityPolicy.DenyAll,
                    "Expected DenyAll policy variant but got: "
                            + meta.securityPolicy().getClass().getSimpleName());
        }

        @Test
        @DisplayName("no security annotation produces None security policy variant")
        void noAnnotationProducesNonePolicy() {
            WebSocketEndpointMeta meta = scanner.scan(new SimpleEndpoint());
            assertNotNull(meta.securityPolicy());
            assertTrue(
                    meta.securityPolicy() instanceof dev.vertique.rest.core.security.SecurityPolicy.None,
                    "Expected None policy variant for unannotated endpoint but got: "
                            + meta.securityPolicy().getClass().getSimpleName());
        }
    }
}
