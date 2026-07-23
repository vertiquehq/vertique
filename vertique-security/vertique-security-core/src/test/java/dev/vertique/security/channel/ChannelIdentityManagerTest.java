// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Future;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ChannelIdentityManager} SPI contract.
 *
 * <p>Verifies: an in-memory fixture implementation satisfies the interface; {@code register()}
 * stores the initial context; {@code current()} returns empty before registration and present after;
 * {@code refreshIdentity()} replaces the context; {@code closeChannel()} removes the entry. The
 * real behavioral correctness is verified in slice 22 ({@code DefaultChannelIdentityManager}).
 */
class ChannelIdentityManagerTest {

    // --- fixture ---

    /** Minimal {@link SecurityContext} stub. */
    private static SecurityContext stubContext(String userId) {
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
            }

            @Override
            public AuthenticationState authentication() {
                return new AuthenticationState(
                        DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
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
    }

    /** No-op {@link ChannelBinding} that always succeeds. */
    private static ChannelBinding noOpBinding() {
        return new ChannelBinding() {
            @Override
            public Future<Void> rebind(SecurityContext newCtx) {
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> close(String reasonCode) {
                return Future.succeededFuture();
            }

            @Override
            public Future<Void> releaseResources() {
                return Future.succeededFuture();
            }
        };
    }

    /**
     * Minimal in-memory {@link ChannelIdentityManager} fixture that satisfies the interface
     * contract. This is a test-only implementation; behavioral correctness tests belong to slice 22.
     */
    private static final class InMemoryChannelIdentityManager implements ChannelIdentityManager {

        private final Map<String, SecurityContext> contexts = new HashMap<>();
        private final Map<String, ChannelBinding> bindings = new HashMap<>();

        @Override
        public Future<Void> register(String channelId, SecurityContext ctx, ChannelBinding binding) {
            contexts.put(channelId, ctx);
            bindings.put(channelId, binding);
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> refreshIdentity(String channelId, SecurityContext newCtx) {
            if (!contexts.containsKey(channelId)) {
                return Future.failedFuture("Channel not found: " + channelId);
            }
            contexts.put(channelId, newCtx);
            ChannelBinding binding = bindings.get(channelId);
            return binding.rebind(newCtx);
        }

        @Override
        public Future<Void> closeChannel(String channelId, String reasonCode) {
            ChannelBinding binding = bindings.get(channelId);
            if (binding == null) {
                return Future.failedFuture("Channel not found: " + channelId);
            }
            return binding.close(reasonCode);
        }

        @Override
        public Future<Void> deregister(String channelId, String fallbackReasonCode) {
            ChannelBinding binding = bindings.remove(channelId);
            contexts.remove(channelId);
            if (binding == null) {
                return Future.succeededFuture();
            }
            return binding.releaseResources();
        }

        @Override
        public Optional<SecurityContext> current(String channelId) {
            return Optional.ofNullable(contexts.get(channelId));
        }
    }

    // --- register + current ---

    @Test
    @DisplayName("current() returns empty before any registration")
    void currentReturnsEmptyBeforeRegistration() {
        InMemoryChannelIdentityManager manager = new InMemoryChannelIdentityManager();

        Optional<SecurityContext> result = manager.current("ch-001");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("register() stores the initial context; current() returns it")
    void registerStoresContextAndCurrentReturnsIt() {
        InMemoryChannelIdentityManager manager = new InMemoryChannelIdentityManager();
        SecurityContext ctx = stubContext("user-1");

        Future<Void> result = manager.register("ch-001", ctx, noOpBinding());

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals(Optional.of(ctx), manager.current("ch-001"));
    }

    // --- refreshIdentity ---

    @Test
    @DisplayName("refreshIdentity() replaces the stored context; current() returns the new one")
    void refreshIdentityReplacesContext() {
        InMemoryChannelIdentityManager manager = new InMemoryChannelIdentityManager();
        SecurityContext initial = stubContext("user-1");
        SecurityContext refreshed = stubContext("user-2");

        manager.register("ch-001", initial, noOpBinding());
        Future<Void> result = manager.refreshIdentity("ch-001", refreshed);

        assertNotNull(result);
        assertTrue(result.succeeded());
        assertEquals(Optional.of(refreshed), manager.current("ch-001"));
    }

    // --- closeChannel ---

    @Test
    @DisplayName("closeChannel() initiates close but keeps the channel; deregister() removes it")
    void closeChannelInitiatesThenDeregisterRemoves() {
        InMemoryChannelIdentityManager manager = new InMemoryChannelIdentityManager();
        SecurityContext ctx = stubContext("user-1");

        manager.register("ch-001", ctx, noOpBinding());

        // closeChannel only initiates the transport close — the channel is still registered so the
        // transport's close callback can run @OnClose with the context still bound.
        Future<Void> closeResult = manager.closeChannel("ch-001", "CHANNEL_CLOSED_BY_SERVER");
        assertNotNull(closeResult);
        assertTrue(closeResult.succeeded());
        assertFalse(manager.current("ch-001").isEmpty(), "channel must remain registered until deregister()");

        // deregister (invoked by the transport after @OnClose) performs the final removal.
        Future<Void> deregResult = manager.deregister("ch-001", "CHANNEL_CLOSED_BY_PEER");
        assertTrue(deregResult.succeeded());
        assertTrue(manager.current("ch-001").isEmpty());
    }

    // --- multiple channels ---

    @Test
    @DisplayName("register() with multiple channel IDs stores contexts independently")
    void multipleChannelsAreIndependent() {
        InMemoryChannelIdentityManager manager = new InMemoryChannelIdentityManager();
        SecurityContext ctx1 = stubContext("user-1");
        SecurityContext ctx2 = stubContext("user-2");

        manager.register("ch-001", ctx1, noOpBinding());
        manager.register("ch-002", ctx2, noOpBinding());

        assertEquals(Optional.of(ctx1), manager.current("ch-001"));
        assertEquals(Optional.of(ctx2), manager.current("ch-002"));
    }

    @Test
    @DisplayName("deregister() on one channel does not affect other channels")
    void deregisterDoesNotAffectOtherChannels() {
        InMemoryChannelIdentityManager manager = new InMemoryChannelIdentityManager();
        SecurityContext ctx1 = stubContext("user-1");
        SecurityContext ctx2 = stubContext("user-2");

        manager.register("ch-001", ctx1, noOpBinding());
        manager.register("ch-002", ctx2, noOpBinding());
        manager.deregister("ch-001", "CHANNEL_CLOSED_BY_PEER");

        assertTrue(manager.current("ch-001").isEmpty());
        assertEquals(Optional.of(ctx2), manager.current("ch-002"));
    }
}
