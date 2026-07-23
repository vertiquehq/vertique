// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.rest.security.HolderBackedSecurityRuntime;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.origin.RequestOrigin;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link WebSocketChannelBinding} exercising the REAL binding (not a mock) against a
 * live Vert.x duplicated context.
 *
 * <p>The load-bearing scenario is identity refresh: {@link WebSocketChannelBinding#rebind} must swap
 * only the {@link SecurityContext} while leaving the rest of the per-channel snapshot —
 * {@link CorrelationContext} and any other context values bound at upgrade time — intact. A prior
 * implementation closed the whole snapshot scope on rebind, dropping correlation; this test pins the
 * fix so that regression cannot return silently behind a mocked {@code ChannelBinding}.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WebSocketChannelBindingTest {

    private static SecurityContext userContext(String userId) {
        SecurityIdentity identity = SecurityIdentity.user(new PrincipalRef(PrincipalType.USER, userId, Map.of()));
        AuthenticationState auth = new AuthenticationState(
                DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of());
        AuthorizationClaims claims = AuthorizationClaims.empty();
        return new SecurityContext() {
            @Override
            public SecurityIdentity identity() {
                return identity;
            }

            @Override
            public AuthenticationState authentication() {
                return auth;
            }

            @Override
            public AuthorizationClaims authorization() {
                return claims;
            }

            @Override
            public Optional<RequestOrigin> origin() {
                return Optional.empty();
            }
        };
    }

    private static CorrelationContext correlation(String requestId) {
        return new CorrelationContextFactory(Optional.empty())
                .create(
                        new CorrelationIdentifier(requestId, "test"),
                        new CorrelationIdentifier("cor-" + requestId, "test"));
    }

    @Test
    @DisplayName("rebind() swaps SecurityContext but preserves the snapshot's CorrelationContext")
    void rebindPreservesCorrelation(Vertx vertx, VertxTestContext testCtx) {
        SecurityContext original = userContext("alice");
        SecurityContext refreshed = userContext("alice-stepped-up");
        CorrelationContext corr = correlation("req-1");

        HolderBackedSecurityRuntime runtime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        ServerWebSocket socket = mock(ServerWebSocket.class);
        when(socket.isClosed()).thenReturn(false);

        ContextInternal channelCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        channelCtx.runOnContext(v -> {
            // Build the combined snapshot the way the upgrade path does: SecurityContext +
            // CorrelationContext bound together, captured into one snapshot.
            ContextHolder.Scope scA = ContextValues.bind(SecurityContext.class, original);
            ContextHolder.Scope scC = ContextValues.bind(CorrelationContext.class, corr);
            var snapshot = ContextValues.snapshot();
            scC.close();
            scA.close();

            ContextHolder.Scope combined = ContextValues.bindSnapshot(snapshot);
            WebSocketChannelBinding binding = new WebSocketChannelBinding(channelCtx, socket, runtime, combined);

            binding.rebind(refreshed)
                    .onComplete(testCtx.succeeding(r -> channelCtx.runOnContext(v2 -> testCtx.verify(() -> {
                        // SecurityContext was swapped to the refreshed identity...
                        assertSame(
                                refreshed,
                                ContextValues.current(SecurityContext.class).orElseThrow(),
                                "rebind must install the refreshed SecurityContext");
                        // ...but the snapshot's CorrelationContext must survive the refresh.
                        // (snapshot()/bindSnapshot() re-wraps it, so compare by value, not identity.)
                        assertEquals(
                                "req-1",
                                ContextValues.current(CorrelationContext.class)
                                        .orElseThrow()
                                        .requestId()
                                        .value(),
                                "rebind must NOT drop the snapshot's CorrelationContext");

                        binding.releaseResources()
                                .onComplete(testCtx.succeeding(r2 -> channelCtx.runOnContext(v3 -> testCtx.verify(
                                        () -> {
                                            assertTrue(
                                                    ContextValues.current(SecurityContext.class)
                                                            .isEmpty(),
                                                    "releaseResources must clear SecurityContext");
                                            assertTrue(
                                                    ContextValues.current(CorrelationContext.class)
                                                            .isEmpty(),
                                                    "releaseResources must clear CorrelationContext");
                                            testCtx.completeNow();
                                        }))));
                    }))));
        });
    }

    @Test
    @DisplayName("repeated rebind() restores the prior SecurityContext override before applying the next")
    void repeatedRebindDoesNotLeakOverrides(Vertx vertx, VertxTestContext testCtx) {
        SecurityContext original = userContext("alice");
        SecurityContext step1 = userContext("alice-1");
        SecurityContext step2 = userContext("alice-2");
        CorrelationContext corr = correlation("req-2");

        HolderBackedSecurityRuntime runtime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        ServerWebSocket socket = mock(ServerWebSocket.class);
        when(socket.isClosed()).thenReturn(false);

        ContextInternal channelCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        channelCtx.runOnContext(v -> {
            ContextHolder.Scope scA = ContextValues.bind(SecurityContext.class, original);
            ContextHolder.Scope scC = ContextValues.bind(CorrelationContext.class, corr);
            var snapshot = ContextValues.snapshot();
            scC.close();
            scA.close();

            ContextHolder.Scope combined = ContextValues.bindSnapshot(snapshot);
            WebSocketChannelBinding binding = new WebSocketChannelBinding(channelCtx, socket, runtime, combined);

            binding.rebind(step1)
                    .compose(r -> binding.rebind(step2))
                    .onComplete(testCtx.succeeding(r -> channelCtx.runOnContext(v2 -> testCtx.verify(() -> {
                        assertSame(
                                step2,
                                ContextValues.current(SecurityContext.class).orElseThrow(),
                                "latest rebind wins");
                        // snapshot()/bindSnapshot() re-wraps CorrelationContext, so compare by value.
                        assertEquals(
                                "req-2",
                                ContextValues.current(CorrelationContext.class)
                                        .orElseThrow()
                                        .requestId()
                                        .value(),
                                "correlation survives repeated rebinds");

                        binding.releaseResources()
                                .onComplete(testCtx.succeeding(r2 -> channelCtx.runOnContext(v3 -> testCtx.verify(
                                        () -> {
                                            assertTrue(
                                                    ContextValues.current(SecurityContext.class)
                                                            .isEmpty(),
                                                    "all SecurityContext overrides released");
                                            assertEquals(
                                                    Optional.empty(),
                                                    ContextValues.current(CorrelationContext.class),
                                                    "snapshot released");
                                            testCtx.completeNow();
                                        }))));
                    }))));
        });
    }

    @Test
    @DisplayName("rebind() after releaseResources() fails and binds no override")
    void rebindAfterReleaseFails(Vertx vertx, VertxTestContext testCtx) {
        SecurityContext original = userContext("alice");
        SecurityContext refreshed = userContext("alice-late");
        CorrelationContext corr = correlation("req-3");

        HolderBackedSecurityRuntime runtime = new HolderBackedSecurityRuntime((sc, secure) -> null);
        ServerWebSocket socket = mock(ServerWebSocket.class);
        when(socket.isClosed()).thenReturn(false);

        ContextInternal channelCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        channelCtx.runOnContext(v -> {
            ContextHolder.Scope scA = ContextValues.bind(SecurityContext.class, original);
            ContextHolder.Scope scC = ContextValues.bind(CorrelationContext.class, corr);
            var snapshot = ContextValues.snapshot();
            scC.close();
            scA.close();

            ContextHolder.Scope combined = ContextValues.bindSnapshot(snapshot);
            WebSocketChannelBinding binding = new WebSocketChannelBinding(channelCtx, socket, runtime, combined);

            // Release the binding first (simulating channel close/deregister), then attempt a stale
            // refresh — it must fail and leave nothing bound, so a dead channel's identity cannot be
            // resurrected and no override scope leaks.
            binding.releaseResources()
                    .onComplete(testCtx.succeeding(r -> channelCtx.runOnContext(v2 -> binding.rebind(refreshed)
                            .onComplete(ar -> channelCtx.runOnContext(v3 -> testCtx.verify(() -> {
                                assertTrue(ar.failed(), "rebind after releaseResources must fail");
                                assertInstanceOf(
                                        IllegalStateException.class,
                                        ar.cause(),
                                        "failure must be IllegalStateException");
                                assertTrue(
                                        ContextValues.current(SecurityContext.class)
                                                .isEmpty(),
                                        "no SecurityContext override may be bound after release");
                                testCtx.completeNow();
                            }))))));
        });
    }
}
