// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.channel.ChannelBinding;
import dev.vertique.security.events.ChannelClosedEvent;
import dev.vertique.security.events.ChannelIdentityRefreshedEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link DefaultChannelIdentityManager}.
 *
 * <p>Verifies the full channel lifecycle: registration, identity refresh, explicit server-initiated
 * close, peer-initiated close (via {@code deregister}), expiry timer scheduling, and idempotent
 * semantics. Timer-based tests use a real Vert.x instance provided by {@code VertxExtension}, with
 * {@link VertxTestContext} used to gate assertions on async completion.
 *
 * <p>A mock {@link ContextHolder} is injected into every manager instance to supply the
 * {@link CorrelationContext} returned from {@code contextHolder.current(CorrelationContext.class)},
 * eliminating the need to run test assertions on a live Vert.x duplicated context. Timer-based
 * tests use a real Vert.x event loop for accurate scheduling.
 *
 * <p>Key scenarios covered:
 * <ul>
 *   <li>Register emits {@link ChannelOpenedEvent} and stores the entry</li>
 *   <li>Channel-id collision closes the prior binding (close + releaseResources) and cancels its
 *       timer; the new entry takes over</li>
 *   <li>Registration without a {@code notAfter} schedules no timer</li>
 *   <li>Registration with a {@code notAfter} schedules auto-expiry via {@code closeChannel} +
 *       {@code deregister}</li>
 *   <li>refreshIdentity updates the registry, cancels prior timer, emits
 *       {@link ChannelIdentityRefreshedEvent} with prior and new context</li>
 *   <li>refreshIdentity on an unknown channel fails with {@link IllegalStateException}</li>
 *   <li>closeChannel records pending reason, calls {@code binding.close}, does NOT emit event or
 *       remove entry</li>
 *   <li>closeChannel is idempotent — second call on an unknown channel returns succeeded future, and
 *       a second close while one is already pending keeps the first reason and sends no second close</li>
 *   <li>deregister with pending reason emits {@link ChannelClosedEvent} with that reason, cancels
 *       timer, removes entry, calls {@code binding.releaseResources}</li>
 *   <li>deregister without pending reason emits event with the supplied fallback reason</li>
 *   <li>deregister is idempotent — second call returns succeeded future with no double emission</li>
 *   <li>current() returns the bound context, or empty after deregister</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefaultChannelIdentityManagerTest {

    private CorrelationContext stubCorrelation;
    private ContextHolder contextHolder;

    @BeforeEach
    void setUp() {
        stubCorrelation = buildCorrelation();
        contextHolder = holderWith(stubCorrelation);
    }

    // --- Fixtures ---

    /**
     * Creates a stub {@link CorrelationContext} via the factory.
     *
     * @return a stub correlation context
     */
    static CorrelationContext buildCorrelation() {
        CorrelationContextFactory factory = new CorrelationContextFactory(Optional.empty());
        return factory.create(
                new CorrelationIdentifier("req-001", "test"), new CorrelationIdentifier("cor-001", "test"));
    }

    /**
     * Creates a {@link ContextHolder} stub that returns the given correlation context.
     *
     * @param correlation the correlation context to return
     * @return a minimal ContextHolder implementation
     */
    static ContextHolder holderWith(CorrelationContext correlation) {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                if (type == CorrelationContext.class) {
                    @SuppressWarnings("unchecked")
                    Optional<T> result = (Optional<T>) Optional.of(correlation);
                    return result;
                }
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    /**
     * Creates a stub {@link AuthenticationState} with no evidence (no expiry).
     *
     * @return an anonymous authentication state with no notAfter
     */
    static AuthenticationState noExpiryAuthState() {
        return new AuthenticationState(
                DefaultAuthMethod.none(), List.of(), Optional.empty(), Optional.empty(), Map.of());
    }

    /**
     * Creates a stub {@link AuthenticationState} with a single evidence entry that expires at the
     * given instant.
     *
     * @param notAfter the expiry instant
     * @return an authentication state carrying one expiring evidence
     */
    static AuthenticationState expiringAuthState(Instant notAfter) {
        AuthenticationEvidence evidence = mock(AuthenticationEvidence.class);
        when(evidence.notAfter()).thenReturn(Optional.of(notAfter));
        return new AuthenticationState(
                DefaultAuthMethod.none(), List.of(evidence), Optional.empty(), Optional.empty(), Map.of());
    }

    /**
     * Creates a stub {@link SecurityContext} whose authentication state has no expiry.
     *
     * @return a mock security context with no-expiry auth state
     */
    static SecurityContext stubContext() {
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authentication()).thenReturn(noExpiryAuthState());
        return ctx;
    }

    /**
     * Creates a stub {@link SecurityContext} whose authentication state expires at the given instant.
     *
     * @param notAfter the expiry instant for the context's evidence
     * @return a mock security context with expiring auth state
     */
    static SecurityContext expiringContext(Instant notAfter) {
        // Build the AuthenticationState (which stubs the inner evidence mock) BEFORE entering the
        // outer when()-thenReturn chain so Mockito doesn't see nested unfinished stubbing.
        AuthenticationState state = expiringAuthState(notAfter);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.authentication()).thenReturn(state);
        return ctx;
    }

    /**
     * Creates a mock {@link ChannelBinding} whose {@code rebind}, {@code close}, and
     * {@code releaseResources} methods return {@link Future#succeededFuture()} by default.
     *
     * <p>All stubs are built before returning so that Mockito never sees nested unfinished-stubbing
     * errors when this helper is called inside a {@code when().thenReturn()} chain.
     *
     * @return a mock channel binding that always succeeds
     */
    static ChannelBinding happyBinding() {
        ChannelBinding binding = mock(ChannelBinding.class);
        when(binding.rebind(any())).thenReturn(Future.succeededFuture());
        when(binding.close(any())).thenReturn(Future.succeededFuture());
        when(binding.releaseResources()).thenReturn(Future.succeededFuture());
        return binding;
    }

    /**
     * Builds a manager with a capturing {@link SecurityEventObserver} that records every
     * {@link ChannelLifecycleEvent} for assertion.
     *
     * @param vertx    the Vert.x instance providing timer scheduling
     * @param captured mutable list that the observer appends events into
     * @return a fully wired {@link DefaultChannelIdentityManager}
     */
    DefaultChannelIdentityManager managerWithCapture(Vertx vertx, List<ChannelLifecycleEvent> captured) {
        SecurityEventObserver observer = new SecurityEventObserver() {
            @Override
            public Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
                captured.add(event);
                return Future.succeededFuture();
            }
        };
        SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
        return new DefaultChannelIdentityManager(vertx, emitter, contextHolder);
    }

    // --- register ---

    @Nested
    @DisplayName("register() — stores channel entry and emits ChannelOpenedEvent")
    class Register {

        @Test
        @DisplayName("register stores the channel and emits ChannelOpenedEvent with the supplied context")
        void registerStoresAndEmits(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);
            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-001", secCtx, binding).onComplete(ar -> {
                try {
                    assertTrue(ar.succeeded(), "register must succeed");
                    assertEquals(1, captured.size(), "one event must have been emitted");
                    ChannelLifecycleEvent event = captured.get(0);
                    assertInstanceOf(ChannelOpenedEvent.class, event);
                    ChannelOpenedEvent opened = (ChannelOpenedEvent) event;
                    assertEquals("ch-001", opened.channelId());
                    assertSame(secCtx, opened.securityContext());

                    // current() must return the registered context
                    Optional<SecurityContext> current = manager.current("ch-001");
                    assertTrue(current.isPresent(), "current() must return the registered context");
                    assertSame(secCtx, current.get());
                    ctx.completeNow();
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            }));
        }

        @Test
        @DisplayName("register without notAfter schedules no timer (no auto-close)")
        void registerWithoutNotAfterNoTimer(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);
            SecurityContext secCtx = stubContext(); // no notAfter
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-002", secCtx, binding).onComplete(ar -> {
                try {
                    assertTrue(ar.succeeded());

                    // After a short delay, channel must still be open (no auto-close)
                    vertx.setTimer(50L, id -> {
                        try {
                            assertTrue(
                                    manager.current("ch-002").isPresent(),
                                    "channel must remain open when no expiry is set");
                            // Only one event (opened), no closed event
                            assertEquals(1, captured.size(), "only ChannelOpenedEvent must be emitted");
                            verify(binding, never()).close(any());
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    });
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            }));
        }

        @Test
        @DisplayName(
                "register with notAfter schedules timer; timer fires closeChannel then deregister emits IDENTITY_EXPIRED")
        void registerWithNotAfterSchedulesTimer(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            // Expiry 80 ms from now
            Instant notAfter = Instant.now().plusMillis(80);
            SecurityContext secCtx = expiringContext(notAfter);
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-expiry", secCtx, binding).onComplete(ar -> {
                try {
                    assertTrue(ar.succeeded());
                    assertTrue(
                            manager.current("ch-expiry").isPresent(),
                            "channel must be open immediately after register");

                    // Wait past expiry (150 ms > 80 ms): timer fires closeChannel, which records the
                    // pending reason and initiates transport close but does NOT yet remove the entry.
                    vertx.setTimer(150L, id -> {
                        try {
                            // closeChannel records the reason and calls binding.close, but the channel
                            // entry stays in the registry until deregister() is called (simulating the
                            // transport's close callback running @OnClose first).
                            verify(binding, times(1)).close("IDENTITY_EXPIRED");
                            assertTrue(
                                    manager.current("ch-expiry").isPresent(),
                                    "entry must still be in registry before deregister is called");
                            assertEquals(1, captured.size(), "only ChannelOpenedEvent before deregister");

                            // Simulate the transport's close callback calling deregister after @OnClose.
                            manager.deregister("ch-expiry", "CHANNEL_CLOSED_BY_PEER")
                                    .onComplete(deregAr -> {
                                        try {
                                            assertTrue(deregAr.succeeded(), "deregister must succeed");
                                            assertFalse(
                                                    manager.current("ch-expiry").isPresent(),
                                                    "entry must be gone after deregister");

                                            // Events: opened + closed
                                            assertEquals(2, captured.size(), "opened + closed events expected");
                                            assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));
                                            ChannelClosedEvent closed = (ChannelClosedEvent) captured.get(1);
                                            assertEquals("ch-expiry", closed.channelId());
                                            // Pending reason from closeChannel wins over fallback
                                            assertEquals(
                                                    "IDENTITY_EXPIRED",
                                                    closed.reasonCode(),
                                                    "pending reason from closeChannel must be used");

                                            verify(binding, times(1)).releaseResources();
                                            ctx.completeNow();
                                        } catch (Throwable t) {
                                            ctx.failNow(t);
                                        }
                                    });
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    });
                } catch (Throwable t) {
                    ctx.failNow(t);
                }
            }));
        }

        @Test
        @DisplayName("register collision: prior binding closed and scope released immediately, new entry stored")
        void registerCollisionClosesPrior(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext firstCtx = stubContext();
            SecurityContext secondCtx = stubContext();
            ChannelBinding firstBinding = happyBinding();
            ChannelBinding secondBinding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-collision", firstCtx, firstBinding)
                    .compose(v2 -> manager.register("ch-collision", secondCtx, secondBinding))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded());

                            // After collision, manager holds the NEW entry
                            Optional<SecurityContext> current = manager.current("ch-collision");
                            assertTrue(current.isPresent());
                            assertSame(secondCtx, current.get(), "second context must win");

                            // Prior binding must have been closed AND its scope released immediately
                            // (orphaned binding has no @OnClose / deregister path).
                            // Use a short delay to allow the fire-and-forget close+release to settle.
                            vertx.setTimer(50L, id -> {
                                try {
                                    verify(firstBinding, times(1)).close("CHANNEL_CLOSED_BY_SERVER");
                                    verify(firstBinding, times(1)).releaseResources();
                                    // New binding must NOT have been closed or released
                                    verify(secondBinding, never()).close(any());
                                    verify(secondBinding, never()).releaseResources();
                                    ctx.completeNow();
                                } catch (Throwable t) {
                                    ctx.failNow(t);
                                }
                            });
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName(
                "observer may re-enter the manager (closeChannel) during ChannelOpenedEvent without breaking serialization")
        void observerReentrantCloseDuringOpenIsSafe(Vertx vertx, VertxTestContext ctx) {
            SecurityContext c0 = stubContext();
            ChannelBinding binding = happyBinding();

            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            AtomicReference<DefaultChannelIdentityManager> managerRef = new AtomicReference<>();
            AtomicReference<Future<Void>> reentrantClose = new AtomicReference<>();

            // Observer that, on channel-open, requests a server-initiated close of the SAME channel and
            // RETURNS that future — the natural async pattern. This is the deadlock case: the close is
            // queued behind the still-running register, so if register's future awaited the observer
            // future (which is the close), the two would wait on each other forever. The fix emits
            // lifecycle events fire-and-forget, so register does not await observers.
            SecurityEventObserver observer = new SecurityEventObserver() {
                @Override
                public Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
                    captured.add(event);
                    if (event instanceof ChannelOpenedEvent opened) {
                        Future<Void> close = managerRef.get().closeChannel(opened.channelId(), "CLOSED_BY_OBSERVER");
                        reentrantClose.set(close);
                        return close;
                    }
                    return Future.succeededFuture();
                }
            };
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
            DefaultChannelIdentityManager manager = new DefaultChannelIdentityManager(vertx, emitter, contextHolder);
            managerRef.set(manager);

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-reentrant", c0, binding)
                    .compose(regV -> reentrantClose.get()) // wait for the re-entrant close to settle
                    .compose(closeV -> manager.deregister("ch-reentrant", "FALLBACK"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "re-entrant closeChannel during open must not break register");

                            // The re-entrant close was serialized after register: it recorded its reason
                            // and initiated transport close (entry stayed until the later deregister).
                            verify(binding, times(1)).close("CLOSED_BY_OBSERVER");
                            verify(binding, times(1)).releaseResources();

                            ChannelClosedEvent closed = (ChannelClosedEvent) captured.stream()
                                    .filter(e -> e instanceof ChannelClosedEvent)
                                    .findFirst()
                                    .orElseThrow();
                            assertEquals(
                                    "CLOSED_BY_OBSERVER",
                                    closed.reasonCode(),
                                    "the observer-initiated close reason must win over the deregister fallback");
                            assertTrue(manager.current("ch-reentrant").isEmpty(), "channel removed after deregister");
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }
    }

    // --- refreshIdentity ---

    @Nested
    @DisplayName("refreshIdentity() — updates registry, emits ChannelIdentityRefreshedEvent")
    class RefreshIdentity {

        @Test
        @DisplayName("refreshIdentity calls rebind, updates registry, emits ChannelIdentityRefreshedEvent")
        void refreshIdentityUpdatesAndEmits(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext oldCtx = stubContext();
            SecurityContext newCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-refresh", oldCtx, binding)
                    .compose(v2 -> manager.refreshIdentity("ch-refresh", newCtx))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "refreshIdentity must succeed");

                            // Registry updated to new context
                            Optional<SecurityContext> current = manager.current("ch-refresh");
                            assertTrue(current.isPresent());
                            assertSame(newCtx, current.get(), "current() must return new context");

                            // Binding.rebind called with new context
                            verify(binding, times(1)).rebind(newCtx);

                            // Events: opened, refreshed
                            assertEquals(2, captured.size());
                            assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));
                            ChannelIdentityRefreshedEvent refreshed = (ChannelIdentityRefreshedEvent) captured.get(1);
                            assertEquals("ch-refresh", refreshed.channelId());
                            assertSame(newCtx, refreshed.securityContext());
                            assertSame(oldCtx, refreshed.priorSecurityContext());

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("refreshIdentity reschedules timer; after expiry, deregister emits IDENTITY_EXPIRED")
        void refreshIdentityReschedulesTimer(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            // First registration: no expiry
            SecurityContext oldCtx = stubContext();
            // New context expires in 80 ms
            Instant notAfter = Instant.now().plusMillis(80);
            SecurityContext newCtx = expiringContext(notAfter);
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-reschedule", oldCtx, binding)
                    .compose(v2 -> manager.refreshIdentity("ch-reschedule", newCtx))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded());

                            // Wait past the new expiry: timer fires closeChannel (records reason,
                            // calls binding.close) but does NOT remove the entry.
                            vertx.setTimer(150L, id -> {
                                try {
                                    verify(binding, times(1)).close("IDENTITY_EXPIRED");
                                    assertTrue(
                                            manager.current("ch-reschedule").isPresent(),
                                            "entry must still be present before deregister");

                                    // Simulate the transport close callback calling deregister.
                                    manager.deregister("ch-reschedule", "CHANNEL_CLOSED_BY_PEER")
                                            .onComplete(deregAr -> {
                                                try {
                                                    assertTrue(deregAr.succeeded());
                                                    assertFalse(
                                                            manager.current("ch-reschedule")
                                                                    .isPresent(),
                                                            "entry must be gone after deregister");
                                                    verify(binding, times(1)).releaseResources();
                                                    ctx.completeNow();
                                                } catch (Throwable t) {
                                                    ctx.failNow(t);
                                                }
                                            });
                                } catch (Throwable t) {
                                    ctx.failNow(t);
                                }
                            });
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("concurrent refreshes are serialized: the registry matches the last applied rebind")
        void concurrentRefreshesAreSerialized(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext c0 = stubContext();
            SecurityContext c1 = stubContext();
            SecurityContext c2 = stubContext();

            // Record the order rebind() is applied to the real channel, and gate the first rebind so
            // the second refresh is forced to overlap it. Build stubs before the when() chain.
            List<SecurityContext> rebindOrder = new ArrayList<>();
            Promise<Void> rebind1Invoked = Promise.promise();
            Promise<Void> gate1 = Promise.promise();
            ChannelBinding binding = mock(ChannelBinding.class);
            when(binding.rebind(any())).thenAnswer(inv -> {
                SecurityContext arg = inv.getArgument(0);
                rebindOrder.add(arg);
                if (arg == c1) {
                    rebind1Invoked.complete();
                    return gate1.future();
                }
                return Future.succeededFuture();
            });
            when(binding.close(any())).thenReturn(Future.succeededFuture());
            when(binding.releaseResources()).thenReturn(Future.succeededFuture());

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-conc", c0, binding).onComplete(regAr -> {
                Future<Void> r1 = manager.refreshIdentity("ch-conc", c1); // rebind(c1) gated
                Future<Void> r2 = manager.refreshIdentity("ch-conc", c2); // must wait for r1

                // The instant r1's rebind is invoked (and gated), r2 must not yet have started its
                // rebind — that is exactly what serialization guarantees.
                rebind1Invoked.future().onComplete(x -> {
                    try {
                        assertEquals(List.of(c1), rebindOrder, "second refresh must wait for the first");
                        gate1.complete();
                        r2.onComplete(r2Ar -> {
                            try {
                                assertTrue(r1.succeeded() && r2Ar.succeeded(), "both refreshes succeed in order");
                                assertEquals(List.of(c1, c2), rebindOrder, "rebinds applied in submission order");
                                assertSame(
                                        c2,
                                        manager.current("ch-conc").orElseThrow(),
                                        "registry must reflect the last applied rebind, not a racing winner");
                                ctx.completeNow();
                            } catch (Throwable t) {
                                ctx.failNow(t);
                            }
                        });
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    }
                });
            }));
        }

        @Test
        @DisplayName("deregister is serialized after an in-flight refresh: refresh applies, then close follows")
        void deregisterIsSerializedAfterInFlightRefresh(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext oldCtx = stubContext();
            SecurityContext newCtx = stubContext();

            // Gate the refresh's rebind so deregister is submitted while the refresh is in flight.
            Promise<Void> rebindInvoked = Promise.promise();
            Promise<Void> gate = Promise.promise();
            ChannelBinding binding = mock(ChannelBinding.class);
            when(binding.rebind(any())).thenAnswer(inv -> {
                rebindInvoked.complete();
                return gate.future();
            });
            when(binding.close(any())).thenReturn(Future.succeededFuture());
            when(binding.releaseResources()).thenReturn(Future.succeededFuture());

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-ser", oldCtx, binding).onComplete(regAr -> {
                Future<Void> refresh = manager.refreshIdentity("ch-ser", newCtx); // rebind gated
                Future<Void> dereg = manager.deregister("ch-ser", "CHANNEL_CLOSED_BY_PEER"); // queued behind

                rebindInvoked.future().onComplete(x -> {
                    try {
                        // While the refresh's rebind is gated, deregister must be blocked behind it:
                        // the entry is still present, no scope released, only the opened event so far.
                        assertTrue(manager.current("ch-ser").isPresent(), "deregister must wait for the refresh");
                        verify(binding, never()).releaseResources();
                        assertEquals(1, captured.size(), "only ChannelOpenedEvent before refresh completes");

                        gate.complete(); // let the refresh finish; deregister then runs
                        dereg.onComplete(dAr -> {
                            try {
                                assertTrue(refresh.succeeded(), "refresh applied before close");
                                assertTrue(dAr.succeeded(), "deregister succeeds after the refresh");
                                assertTrue(manager.current("ch-ser").isEmpty(), "channel removed after deregister");
                                verify(binding, times(1)).releaseResources();
                                // Strict order: opened, refreshed, closed.
                                assertEquals(3, captured.size(), "opened + refreshed + closed");
                                assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));
                                assertInstanceOf(ChannelIdentityRefreshedEvent.class, captured.get(1));
                                assertInstanceOf(ChannelClosedEvent.class, captured.get(2));
                                ctx.completeNow();
                            } catch (Throwable t) {
                                ctx.failNow(t);
                            }
                        });
                    } catch (Throwable t) {
                        ctx.failNow(t);
                    }
                });
            }));
        }

        @Test
        @DisplayName("refreshIdentity is rejected once a server-initiated close is pending")
        void refreshRejectedAfterCloseStarted(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext c0 = stubContext();
            SecurityContext c1 = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-p2", c0, binding)
                    .compose(r -> manager.closeChannel("ch-p2", "IDENTITY_REVOKED"))
                    .compose(r -> manager.refreshIdentity("ch-p2", c1))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.failed(), "refresh after a pending close must fail");
                            assertInstanceOf(IllegalStateException.class, ar.cause());
                            assertTrue(
                                    ar.cause().getMessage().contains("closing"),
                                    "failure message must indicate the channel is closing");
                            // The terminal channel was never rebound and its identity is unchanged.
                            verify(binding, never()).rebind(any());
                            assertSame(
                                    c0,
                                    manager.current("ch-p2").orElseThrow(),
                                    "identity must remain the pre-close context");
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("refreshIdentity on unknown channel fails with IllegalStateException")
        void refreshIdentityUnknownChannelFails(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);
            SecurityContext newCtx = stubContext();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(
                    v -> manager.refreshIdentity("unknown-channel", newCtx).onComplete(ar -> {
                        try {
                            assertTrue(ar.failed(), "refreshIdentity on unknown channel must fail");
                            assertInstanceOf(
                                    IllegalStateException.class, ar.cause(), "failure must be IllegalStateException");
                            assertTrue(
                                    ar.cause().getMessage().contains("unknown-channel"),
                                    "message must mention the channel id");
                            assertTrue(captured.isEmpty(), "no events must be emitted for unknown channel");
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }
    }

    // --- closeChannel ---

    @Nested
    @DisplayName("closeChannel() — records pending reason, calls binding.close, does NOT remove entry or emit event")
    class CloseChannel {

        @Test
        @DisplayName("closeChannel records reason, calls binding.close, entry still present, no event emitted yet")
        void closeChannelRecordsReasonAndInitiatesClose(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-close", secCtx, binding)
                    .compose(v2 -> manager.closeChannel("ch-close", "IDENTITY_REVOKED"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "closeChannel must succeed");

                            // Entry must still be in registry — deregister has not been called yet.
                            assertTrue(
                                    manager.current("ch-close").isPresent(),
                                    "entry must remain in registry until deregister is called");

                            // binding.close called with the reason code
                            verify(binding, times(1)).close("IDENTITY_REVOKED");
                            // binding.releaseResources must NOT have been called yet
                            verify(binding, never()).releaseResources();

                            // Only ChannelOpenedEvent — ChannelClosedEvent is deferred to deregister
                            assertEquals(1, captured.size(), "no ChannelClosedEvent before deregister");
                            assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName(
                "closeChannel is idempotent while a close is pending: first reason wins, transport close sent once")
        void closeChannelIdempotentWhileClosePending(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-2close", secCtx, binding)
                    .compose(r -> manager.closeChannel("ch-2close", "FIRST_REASON"))
                    .compose(r -> manager.closeChannel("ch-2close", "SECOND_REASON"))
                    .compose(r -> manager.deregister("ch-2close", "FALLBACK"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded());
                            // Only the first close reached the transport; the second was a no-op.
                            verify(binding, times(1)).close("FIRST_REASON");
                            verify(binding, never()).close("SECOND_REASON");
                            // The ChannelClosedEvent carries the reason of the close that actually ran.
                            ChannelClosedEvent closed = (ChannelClosedEvent) captured.stream()
                                    .filter(e -> e instanceof ChannelClosedEvent)
                                    .findFirst()
                                    .orElseThrow();
                            assertEquals(
                                    "FIRST_REASON",
                                    closed.reasonCode(),
                                    "the first close reason must win over the later one and the fallback");
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("closeChannel is idempotent — second call on unregistered channel returns succeeded future")
        void closeChannelIsIdempotentOnUnregistered(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(
                    v -> manager.closeChannel("never-registered", "SOME_REASON").onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "closeChannel on unregistered channel must succeed");
                            assertTrue(captured.isEmpty(), "no events for an unregistered channel");
                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("closeChannel succeeds even when binding.close() fails")
        void closeChannelSucceedsWhenBindingFails(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            // Build state before entering the when() chain to avoid nested-stubbing errors.
            ChannelBinding binding = mock(ChannelBinding.class);
            when(binding.close(any())).thenReturn(Future.failedFuture(new RuntimeException("transport error")));
            when(binding.releaseResources()).thenReturn(Future.succeededFuture());

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-fail-close", secCtx, binding)
                    .compose(v2 -> manager.closeChannel("ch-fail-close", "SERVER_SHUTDOWN"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "closeChannel must succeed even if binding.close fails");

                            // Entry still present (deregister not called yet)
                            assertTrue(
                                    manager.current("ch-fail-close").isPresent(),
                                    "entry must remain until deregister despite binding.close failure");

                            // Only ChannelOpenedEvent — no ChannelClosedEvent yet
                            assertEquals(1, captured.size());
                            assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }
    }

    // --- deregister ---

    @Nested
    @DisplayName("deregister() — single cleanup owner: emit event, cancel timer, remove entry, release scope")
    class Deregister {

        @Test
        @DisplayName("deregister after closeChannel: emits ChannelClosedEvent with the pending reason")
        void deregisterUsesPendingReasonFromCloseChannel(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-dereg-pending", secCtx, binding)
                    .compose(v2 -> manager.closeChannel("ch-dereg-pending", "IDENTITY_EXPIRED"))
                    .compose(v2 -> manager.deregister("ch-dereg-pending", "CHANNEL_CLOSED_BY_PEER"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "deregister must succeed");

                            // Entry removed from registry
                            assertFalse(
                                    manager.current("ch-dereg-pending").isPresent(),
                                    "entry must be gone after deregister");

                            // binding.releaseResources called exactly once
                            verify(binding, times(1)).releaseResources();

                            // Events: opened + closed (with the PENDING reason, not the fallback)
                            assertEquals(2, captured.size(), "opened + closed events expected");
                            assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));
                            ChannelClosedEvent closed = (ChannelClosedEvent) captured.get(1);
                            assertEquals("ch-dereg-pending", closed.channelId());
                            assertEquals(
                                    "IDENTITY_EXPIRED",
                                    closed.reasonCode(),
                                    "pending reason from closeChannel must win over fallback");
                            assertSame(secCtx, closed.securityContext());

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("deregister without prior closeChannel: emits ChannelClosedEvent with fallback reason")
        void deregisterUsesFallbackWhenNoPendingReason(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-dereg-fallback", secCtx, binding)
                    .compose(v2 -> manager.deregister("ch-dereg-fallback", "CHANNEL_CLOSED_BY_PEER"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "deregister must succeed");

                            assertFalse(
                                    manager.current("ch-dereg-fallback").isPresent(),
                                    "entry must be gone after deregister");

                            verify(binding, times(1)).releaseResources();

                            assertEquals(2, captured.size());
                            ChannelClosedEvent closed = (ChannelClosedEvent) captured.get(1);
                            assertEquals(
                                    "CHANNEL_CLOSED_BY_PEER",
                                    closed.reasonCode(),
                                    "fallback reason must be used when no pending reason is set");

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("deregister is idempotent — second call returns succeeded future, no double emission")
        void deregisterIsIdempotent(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-dereg-idem", secCtx, binding)
                    .compose(v2 -> manager.deregister("ch-dereg-idem", "CHANNEL_CLOSED_BY_PEER"))
                    .compose(v2 -> manager.deregister("ch-dereg-idem", "CHANNEL_CLOSED_BY_PEER"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded(), "second deregister must also succeed");
                            assertFalse(manager.current("ch-dereg-idem").isPresent());

                            // releaseResources called only once
                            verify(binding, times(1)).releaseResources();

                            // Events: opened + one closed (no double emission)
                            assertEquals(2, captured.size(), "no double emission on second deregister");
                            assertInstanceOf(ChannelOpenedEvent.class, captured.get(0));
                            assertInstanceOf(ChannelClosedEvent.class, captured.get(1));

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }

        @Test
        @DisplayName("deregister succeeds even when binding.releaseResources() fails")
        void deregisterSucceedsWhenReleaseResourcesFails(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            // Build stubs before entering when() chain to avoid nested-stubbing errors.
            ChannelBinding binding = mock(ChannelBinding.class);
            when(binding.close(any())).thenReturn(Future.succeededFuture());
            when(binding.releaseResources()).thenReturn(Future.failedFuture(new RuntimeException("scope close error")));

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(v -> manager.register("ch-dereg-fail-release", secCtx, binding)
                    .compose(v2 -> manager.deregister("ch-dereg-fail-release", "CHANNEL_CLOSED_BY_PEER"))
                    .onComplete(ar -> {
                        try {
                            assertTrue(
                                    ar.succeeded(), "deregister must succeed even if binding.releaseResources fails");

                            assertFalse(manager.current("ch-dereg-fail-release").isPresent());

                            // ChannelClosedEvent must still have been emitted
                            assertEquals(2, captured.size());
                            assertInstanceOf(ChannelClosedEvent.class, captured.get(1));

                            ctx.completeNow();
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }
    }

    // --- current() ---

    @Nested
    @DisplayName("current() — returns bound context or empty")
    class Current {

        @Test
        @DisplayName("current returns empty for an unregistered channel id")
        void currentEmptyForUnknown(Vertx vertx) {
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, new ArrayList<>());
            assertTrue(
                    manager.current("not-there").isEmpty(), "current() must return empty for an unregistered channel");
        }

        @Test
        @DisplayName("current returns bound context after register, then empty after deregister")
        void currentReturnsValueThenEmptyAfterDeregister(Vertx vertx, VertxTestContext ctx) {
            List<ChannelLifecycleEvent> captured = new ArrayList<>();
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, captured);

            SecurityContext secCtx = stubContext();
            ChannelBinding binding = happyBinding();

            ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
            dup.runOnContext(
                    v -> manager.register("ch-current", secCtx, binding).onComplete(ar -> {
                        try {
                            assertTrue(ar.succeeded());
                            assertSame(
                                    secCtx,
                                    manager.current("ch-current").orElse(null),
                                    "current() must return registered context");

                            // closeChannel leaves entry present
                            manager.closeChannel("ch-current", "TEST_CLOSE").onComplete(closeAr -> {
                                try {
                                    assertTrue(closeAr.succeeded());
                                    assertTrue(
                                            manager.current("ch-current").isPresent(),
                                            "entry must still be present after closeChannel");

                                    // deregister removes entry
                                    manager.deregister("ch-current", "TEST_FALLBACK")
                                            .onComplete(deregAr -> {
                                                try {
                                                    assertTrue(deregAr.succeeded());
                                                    assertTrue(
                                                            manager.current("ch-current")
                                                                    .isEmpty(),
                                                            "current() must be empty after deregister");
                                                    ctx.completeNow();
                                                } catch (Throwable t) {
                                                    ctx.failNow(t);
                                                }
                                            });
                                } catch (Throwable t) {
                                    ctx.failNow(t);
                                }
                            });
                        } catch (Throwable t) {
                            ctx.failNow(t);
                        }
                    }));
        }
    }

    // --- Null argument rejection ---

    @Nested
    @DisplayName("Null argument rejection — constructor and methods")
    class NullArguments {

        @Test
        @DisplayName("constructor rejects null vertx")
        void constructorRejectsNullVertx(Vertx vertx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            NullPointerException ex = assertThrows(
                    NullPointerException.class, () -> new DefaultChannelIdentityManager(null, emitter, contextHolder));
            assertEquals("vertx", ex.getMessage());
        }

        @Test
        @DisplayName("constructor rejects null emitter")
        void constructorRejectsNullEmitter(Vertx vertx) {
            NullPointerException ex = assertThrows(
                    NullPointerException.class, () -> new DefaultChannelIdentityManager(vertx, null, contextHolder));
            assertEquals("emitter", ex.getMessage());
        }

        @Test
        @DisplayName("constructor rejects null contextHolder")
        void constructorRejectsNullContextHolder(Vertx vertx) {
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());
            NullPointerException ex = assertThrows(
                    NullPointerException.class, () -> new DefaultChannelIdentityManager(vertx, emitter, null));
            assertEquals("contextHolder", ex.getMessage());
        }

        @Test
        @DisplayName("register rejects null channelId")
        void registerRejectsNullChannelId(Vertx vertx) {
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, new ArrayList<>());
            assertThrows(NullPointerException.class, () -> manager.register(null, stubContext(), happyBinding()));
        }

        @Test
        @DisplayName("register rejects null SecurityContext")
        void registerRejectsNullContext(Vertx vertx) {
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, new ArrayList<>());
            assertThrows(NullPointerException.class, () -> manager.register("ch", null, happyBinding()));
        }

        @Test
        @DisplayName("register rejects null ChannelBinding")
        void registerRejectsNullBinding(Vertx vertx) {
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, new ArrayList<>());
            assertThrows(NullPointerException.class, () -> manager.register("ch", stubContext(), null));
        }

        @Test
        @DisplayName("deregister rejects null channelId")
        void deregisterRejectsNullChannelId(Vertx vertx) {
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, new ArrayList<>());
            assertThrows(NullPointerException.class, () -> manager.deregister(null, "FALLBACK"));
        }

        @Test
        @DisplayName("deregister rejects null fallbackReasonCode")
        void deregisterRejectsNullFallbackReason(Vertx vertx) {
            DefaultChannelIdentityManager manager = managerWithCapture(vertx, new ArrayList<>());
            assertThrows(NullPointerException.class, () -> manager.deregister("ch", null));
        }
    }
}
