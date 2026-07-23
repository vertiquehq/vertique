// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.authz.ActionDefinition;
import dev.vertique.security.authz.ActionRef;
import dev.vertique.security.authz.ActionRegistry;
import dev.vertique.security.authz.RequiresAction;
import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the WebSocket {@code @RequiresAction} discovery and startup-validation rules in
 * {@link WebSocketEndpointScanner} (FR-AUTHZ-048, ADR-0115).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>a class-level {@code @RequiresAction} that is registered in the {@link ActionRegistry} is
 *       resolved and stored on {@link WebSocketEndpointMeta#requiredAction()};</li>
 *   <li>a class-level {@code @RequiresAction} whose action is not registered fails startup;</li>
 *   <li>a {@code @RequiresAction} on any lifecycle method ({@code @OnOpen}/{@code @OnMessage}/
 *       {@code @OnClose}/{@code @OnError}) fails startup — WebSocket authorizes once at upgrade, so a
 *       per-message annotation is never silently ignored;</li>
 *   <li>a class-level {@code @RequiresAction} combined with a blanket {@code @PermitAll} or
 *       {@code @DenyAll} fails startup — {@code @RequiresAction} composes only with
 *       {@code @RolesAllowed}/{@code @Authorized}, mirroring the REST registrar and codegen conflict
 *       checks (the combination is documented as unreachable in {@code SecurityPolicyEnforcer});</li>
 *   <li>an endpoint with no {@code @RequiresAction} resolves to {@link Optional#empty()}.</li>
 * </ul>
 */
@DisplayName("WebSocketEndpointScanner @RequiresAction")
class WebSocketEndpointScannerRequiresActionTest {

    private static final ActionRef CONTENT_READ = ActionRef.parse("cms.content.read");

    /** Registry containing only {@code cms.content.read}. */
    private final ActionRegistry registry = new StubActionRegistry(Set.of(CONTENT_READ));

    private WebSocketEndpointScanner scanner() {
        return new WebSocketEndpointScanner(registry);
    }

    // --- Fixtures ---

    @WebSocketEndpoint("/ws/content")
    @RequiresAction("cms.content.read")
    static class ClassLevelActionEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/unknown")
    @RequiresAction("cms.unknown.read")
    static class UnregisteredActionEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/method-open")
    static class OnOpenMethodActionEndpoint {

        @OnOpen
        @RequiresAction("cms.content.read")
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/method-message")
    static class OnMessageMethodActionEndpoint {

        @OnMessage
        @RequiresAction("cms.content.read")
        void onMessage(WebSocketSession session, String msg) {}
    }

    @WebSocketEndpoint("/ws/method-close")
    static class OnCloseMethodActionEndpoint {

        @OnClose
        @RequiresAction("cms.content.read")
        void onClose(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/method-error")
    static class OnErrorMethodActionEndpoint {

        @OnError
        @RequiresAction("cms.content.read")
        void onError(WebSocketSession session, Throwable error) {}
    }

    @WebSocketEndpoint("/ws/none")
    static class NoActionEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/permit")
    @RequiresAction("cms.content.read")
    @PermitAll
    static class PermitAllActionEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    @WebSocketEndpoint("/ws/deny")
    @RequiresAction("cms.content.read")
    @DenyAll
    static class DenyAllActionEndpoint {

        @OnOpen
        void onOpen(WebSocketSession session) {}
    }

    // --- Tests ---

    @Nested
    @DisplayName("class-level resolution")
    class ClassLevel {

        @Test
        @DisplayName("registered class-level @RequiresAction is resolved and stored in meta")
        void classLevelRequiredActionResolvedAndStoredInMeta() {
            WebSocketEndpointMeta meta = scanner().scan(new ClassLevelActionEndpoint());

            assertTrue(meta.requiredAction().isPresent(), "requiredAction must be present");
            assertEquals(CONTENT_READ, meta.requiredAction().get(), "requiredAction must equal cms.content.read");
        }

        @Test
        @DisplayName("unregistered class-level @RequiresAction fails startup")
        void unregisteredActionThrowsAtStartup() {
            WebSocketEndpointScanner scanner = scanner();
            UnregisteredActionEndpoint endpoint = new UnregisteredActionEndpoint();
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        }
    }

    @Nested
    @DisplayName("method-level rejection (FR-048)")
    class MethodLevelRejection {

        @Test
        @DisplayName("@RequiresAction on @OnOpen fails startup")
        void onOpenMethodActionThrows() {
            WebSocketEndpointScanner scanner = scanner();
            OnOpenMethodActionEndpoint endpoint = new OnOpenMethodActionEndpoint();
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        }

        @Test
        @DisplayName("@RequiresAction on @OnMessage fails startup")
        void onMessageMethodActionThrows() {
            WebSocketEndpointScanner scanner = scanner();
            OnMessageMethodActionEndpoint endpoint = new OnMessageMethodActionEndpoint();
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        }

        @Test
        @DisplayName("@RequiresAction on @OnClose fails startup")
        void onCloseMethodActionThrows() {
            WebSocketEndpointScanner scanner = scanner();
            OnCloseMethodActionEndpoint endpoint = new OnCloseMethodActionEndpoint();
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        }

        @Test
        @DisplayName("@RequiresAction on @OnError fails startup")
        void onErrorMethodActionThrows() {
            WebSocketEndpointScanner scanner = scanner();
            OnErrorMethodActionEndpoint endpoint = new OnErrorMethodActionEndpoint();
            assertThrows(IllegalArgumentException.class, () -> scanner.scan(endpoint));
        }
    }

    @Nested
    @DisplayName("policy conflict (@RequiresAction + @PermitAll/@DenyAll)")
    class PolicyConflict {

        @Test
        @DisplayName("class-level @RequiresAction + @PermitAll fails startup")
        void requiresActionWithPermitAllThrows() {
            WebSocketEndpointScanner scanner = scanner();
            PermitAllActionEndpoint endpoint = new PermitAllActionEndpoint();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scanner.scan(endpoint),
                    "@RequiresAction combined with @PermitAll must fail startup — it composes only with "
                            + "@RolesAllowed/@Authorized");
        }

        @Test
        @DisplayName("class-level @RequiresAction + @DenyAll fails startup")
        void requiresActionWithDenyAllThrows() {
            WebSocketEndpointScanner scanner = scanner();
            DenyAllActionEndpoint endpoint = new DenyAllActionEndpoint();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> scanner.scan(endpoint),
                    "@RequiresAction combined with @DenyAll must fail startup — it composes only with "
                            + "@RolesAllowed/@Authorized");
        }
    }

    @Nested
    @DisplayName("no annotation")
    class NoAnnotation {

        @Test
        @DisplayName("endpoint without @RequiresAction resolves to empty")
        void noRequiresActionMetaEmpty() {
            WebSocketEndpointMeta meta = scanner().scan(new NoActionEndpoint());
            assertFalse(meta.requiredAction().isPresent(), "requiredAction must be empty");
        }
    }

    // --- Test double ---

    /**
     * Minimal {@link ActionRegistry} backed by a fixed set of registered {@link ActionRef}s.
     *
     * @param registered the set of action references considered registered
     */
    record StubActionRegistry(Set<ActionRef> registered) implements ActionRegistry {

        @Override
        public Collection<ActionDefinition> actions() {
            return registered.stream().map(ActionDefinition::new).toList();
        }

        @Override
        public Optional<ActionDefinition> find(ActionRef action) {
            return registered.contains(action) ? Optional.of(new ActionDefinition(action)) : Optional.empty();
        }

        @Override
        public boolean contains(ActionRef action) {
            return registered.contains(action);
        }
    }
}
