// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.events;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link SecurityEventObserver} default-method contract.
 *
 * <p>Verifies: a no-op observer (anonymous class with no overrides) compiles and all four default
 * methods return a non-null succeeded {@link Future}; a selective observer that overrides only
 * {@code onCredentialAccepted} still satisfies the interface and default methods for the other
 * three event types return success.
 */
class SecurityEventObserverTest {

    // --- shared fixtures ---

    private static final Instant NOW = Instant.parse("2026-01-01T16:00:00Z");
    private static final CorrelationContext CORRELATION = EventTestFixtures.minimalCorrelation();
    private static final SecurityContext SECURITY_CTX = EventTestFixtures.minimalSecurityContext();

    /** Minimal {@link SecurityContext} stub with a user actor for {@link AuthorizationRequest}. */
    private static final SecurityContext AUTH_CTX = new SecurityContext() {
        private final SecurityIdentity identity =
                SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        private final AuthenticationState authentication = new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());

        @Override
        public SecurityIdentity identity() {
            return identity;
        }

        @Override
        public AuthenticationState authentication() {
            return authentication;
        }

        @Override
        public AuthorizationClaims authorization() {
            return AuthorizationClaims.empty();
        }

        @Override
        public Optional<RequestOrigin> origin() {
            return Optional.empty();
        }
    };

    private static CredentialAcceptedEvent credentialAcceptedEvent() {
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, "user-1", Map.of()));
        return new CredentialAcceptedEvent(NOW, CORRELATION, Optional.empty(), auth, identity);
    }

    private static CredentialRejectedEvent credentialRejectedEvent() {
        return new CredentialRejectedEvent(
                NOW,
                CORRELATION,
                Optional.empty(),
                DefaultAuthMethod.jwt(),
                Optional.empty(),
                Optional.empty(),
                "TOKEN_EXPIRED",
                Map.of());
    }

    private static AuthorizationDecisionEvent authorizationDecisionEvent() {
        AuthorizationRequest request =
                new AuthorizationRequest(AUTH_CTX, "READ", new ResourceRef("order", "42", Map.of()), Map.of());
        return new AuthorizationDecisionEvent(
                NOW, CORRELATION, Optional.empty(), request, AuthorizationDecision.permit("PERMITTED"));
    }

    private static ChannelLifecycleEvent channelLifecycleEvent() {
        return new ChannelOpenedEvent(NOW, "ch-001", SECURITY_CTX, CORRELATION);
    }

    // --- no-op observer ---

    @Test
    @DisplayName("no-op observer default methods return non-null succeeded Future for onCredentialAccepted")
    void noOpObserverOnCredentialAccepted() {
        SecurityEventObserver observer = new SecurityEventObserver() {};
        Future<Void> result = observer.onCredentialAccepted(credentialAcceptedEvent());

        assertNotNull(result);
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("no-op observer default methods return non-null succeeded Future for onCredentialRejected")
    void noOpObserverOnCredentialRejected() {
        SecurityEventObserver observer = new SecurityEventObserver() {};
        Future<Void> result = observer.onCredentialRejected(credentialRejectedEvent());

        assertNotNull(result);
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("no-op observer default methods return non-null succeeded Future for onAuthorizationDecided")
    void noOpObserverOnAuthorizationDecided() {
        SecurityEventObserver observer = new SecurityEventObserver() {};
        Future<Void> result = observer.onAuthorizationDecided(authorizationDecisionEvent());

        assertNotNull(result);
        assertTrue(result.succeeded());
    }

    @Test
    @DisplayName("no-op observer default methods return non-null succeeded Future for onChannelLifecycle")
    void noOpObserverOnChannelLifecycle() {
        SecurityEventObserver observer = new SecurityEventObserver() {};
        Future<Void> result = observer.onChannelLifecycle(channelLifecycleEvent());

        assertNotNull(result);
        assertTrue(result.succeeded());
    }

    // --- selective observer ---

    /**
     * Observer that only handles credential-accepted events.
     * All other default methods are inherited.
     */
    private static final class SelectiveObserver implements SecurityEventObserver {

        boolean accepted = false;

        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            accepted = true;
            return Future.succeededFuture();
        }
    }

    @Test
    @DisplayName("selective observer satisfies the interface and non-overridden defaults return success")
    void selectiveObserverDefaultMethodsReturnSuccess() {
        SelectiveObserver observer = new SelectiveObserver();

        Future<Void> rejectedResult = observer.onCredentialRejected(credentialRejectedEvent());
        Future<Void> authzResult = observer.onAuthorizationDecided(authorizationDecisionEvent());
        Future<Void> channelResult = observer.onChannelLifecycle(channelLifecycleEvent());

        assertNotNull(rejectedResult);
        assertTrue(rejectedResult.succeeded());
        assertNotNull(authzResult);
        assertTrue(authzResult.succeeded());
        assertNotNull(channelResult);
        assertTrue(channelResult.succeeded());
    }

    @Test
    @DisplayName("selective observer overridden method is invoked and updates state")
    void selectiveObserverOverriddenMethodInvoked() {
        SelectiveObserver observer = new SelectiveObserver();
        observer.onCredentialAccepted(credentialAcceptedEvent());

        assertTrue(observer.accepted);
    }
}
