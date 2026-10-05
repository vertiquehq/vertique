// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.Authorized;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving that Jakarta/framework security annotations on WebSocket lifecycle methods
 * fail startup rather than being silently ignored by the sentinel-based class-level policy resolver.
 *
 * <p>WebSocket authorizes once at upgrade, so {@code @DenyAll}, {@code @RolesAllowed},
 * {@code @PermitAll}, and {@code @Authorized} are class-level only — mirroring the existing
 * {@code @RequiresAction} rejection ({@link WebSocketEndpointScannerRequiresActionTest}).
 */
@DisplayName("WebSocketEndpointScanner lifecycle security annotation rejection")
class WebSocketEndpointScannerLifecycleSecurityRejectTest {

    private final WebSocketEndpointScanner scanner = new WebSocketEndpointScanner();

    @WebSocketEndpoint("/ws/deny-method")
    static class DenyAllOnMessageEndpoint {

        @OnMessage
        @DenyAll
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/roles-method")
    static class RolesAllowedOnMessageEndpoint {

        @OnMessage
        @RolesAllowed("admin")
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/permit-method")
    static class PermitAllOnMessageEndpoint {

        @OnMessage
        @PermitAll
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/authorized-method")
    static class AuthorizedOnMessageEndpoint {

        @OnMessage
        @Authorized
        void onMessage(WebSocketSession session, String msg) {}
    }

    @Test
    @DisplayName("@DenyAll on @OnMessage fails startup")
    void denyAllOnLifecycleMethodThrows() {
        DenyAllOnMessageEndpoint endpoint = new DenyAllOnMessageEndpoint();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        assertTrue(thrown.getMessage().contains("@DenyAll"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("onMessage"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("class-level"), thrown.getMessage());
    }

    @Test
    @DisplayName("@RolesAllowed on @OnMessage fails startup")
    void rolesAllowedOnLifecycleMethodThrows() {
        RolesAllowedOnMessageEndpoint endpoint = new RolesAllowedOnMessageEndpoint();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        assertTrue(thrown.getMessage().contains("@RolesAllowed"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("onMessage"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("class-level"), thrown.getMessage());
    }

    @Test
    @DisplayName("@PermitAll on @OnMessage fails startup")
    void permitAllOnLifecycleMethodThrows() {
        PermitAllOnMessageEndpoint endpoint = new PermitAllOnMessageEndpoint();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        assertTrue(thrown.getMessage().contains("@PermitAll"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("onMessage"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("class-level"), thrown.getMessage());
    }

    @Test
    @DisplayName("@Authorized on @OnMessage fails startup")
    void authorizedOnLifecycleMethodThrows() {
        AuthorizedOnMessageEndpoint endpoint = new AuthorizedOnMessageEndpoint();
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        assertTrue(thrown.getMessage().contains("@Authorized"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("onMessage"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("class-level"), thrown.getMessage());
    }
}
