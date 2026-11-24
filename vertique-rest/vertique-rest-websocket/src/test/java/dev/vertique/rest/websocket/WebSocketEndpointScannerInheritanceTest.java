// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lifecycle annotations declared on a superclass method count for the endpoint. */
@DisplayName("WebSocketEndpointScanner lifecycle annotation inheritance")
class WebSocketEndpointScannerInheritanceTest {

    private final WebSocketEndpointScanner scanner = new WebSocketEndpointScanner();

    static class BaseHandlers {

        @OnOpen
        void onOpen(WebSocketSession session) {}

        @OnMessage
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/inherit")
    static class InheritingEndpoint extends BaseHandlers {

        @OnClose
        void onClose(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/override")
    static class OverridingEndpoint extends BaseHandlers {

        @Override
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/reannotated")
    static class ReannotatedOverrideEndpoint extends BaseHandlers {

        @Override
        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/second-open")
    static class SecondOpenEndpoint extends BaseHandlers {

        @OnOpen
        void another(WebSocketSession session) {}
    }

    static class RestrictedBase {

        @OnMessage
        @RolesAllowed("admin")
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/restricted")
    static class RestrictedOverrideEndpoint extends RestrictedBase {

        @Override
        void onMessage(WebSocketSession session, String msg) {}
    }

    @Test
    void superclassLifecycleMethodsAreDiscovered() {
        var meta = scanner.scan(new InheritingEndpoint());

        assertEquals(BaseHandlers.class, meta.onOpen().getDeclaringClass());
        assertEquals(BaseHandlers.class, meta.onMessage().getDeclaringClass());
        assertEquals(InheritingEndpoint.class, meta.onClose().getDeclaringClass());
        assertNull(meta.onError());
    }

    @Test
    void overrideWithoutTheAnnotationInheritsItAndIsTheHandler() {
        var meta = scanner.scan(new OverridingEndpoint());

        assertNotNull(meta.onOpen());
        assertEquals(OverridingEndpoint.class, meta.onOpen().getDeclaringClass());
    }

    @Test
    void overrideRepeatingTheAnnotationIsOneHandler() {
        var meta = scanner.scan(new ReannotatedOverrideEndpoint());

        assertEquals(ReannotatedOverrideEndpoint.class, meta.onOpen().getDeclaringClass());
    }

    @Test
    void inheritedAndDeclaredHandlerForOneAnnotationFailStartup() {
        var failure = assertThrows(IllegalArgumentException.class, () -> scanner.scan(new SecondOpenEndpoint()));

        assertTrue(failure.getMessage().contains("@OnOpen"));
    }

    @Test
    void securityAnnotationOnTheOverriddenDeclarationFailsStartup() {
        var failure =
                assertThrows(IllegalArgumentException.class, () -> scanner.scan(new RestrictedOverrideEndpoint()));

        assertTrue(failure.getMessage().contains("@RolesAllowed"));
    }
}
