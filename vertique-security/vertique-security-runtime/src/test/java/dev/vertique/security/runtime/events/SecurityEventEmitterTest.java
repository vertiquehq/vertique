// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.authz.AuthorizationDecision;
import dev.vertique.security.authz.AuthorizationRequest;
import dev.vertique.security.authz.ResourceRef;
import dev.vertique.security.events.AuthorizationDecisionEvent;
import dev.vertique.security.events.ChannelLifecycleEvent;
import dev.vertique.security.events.ChannelOpenedEvent;
import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.CredentialRejectedEvent;
import dev.vertique.security.events.IdentitySnapshotDegradationEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecurityEventEmitter}.
 *
 * <p>Verifies the fan-out contract and failure-isolation guarantees:
 * <ul>
 *   <li>Empty observer set: all emit() overloads succeed immediately</li>
 *   <li>Single happy observer: correct method invoked, future succeeds</li>
 *   <li>Two happy observers: both receive the event, future succeeds</li>
 *   <li>Sync exception from one observer: other observer still runs, future succeeds (AC-SE-6)</li>
 *   <li>Failed future from one observer: other observer still runs, future succeeds (AC-SE-6)</li>
 *   <li>Null future returned by observer: treated as success, no NPE</li>
 *   <li>All four event types fan-out correctly with isolation</li>
 * </ul>
 */
class SecurityEventEmitterTest {

    // --- Fixtures ---

    private static CorrelationContext stubCorrelation() {
        // The emitter only passes the event through to observers; it never reads correlation
        // fields. A mock keeps this test within vertique-core's dependencies (the real
        // CorrelationContextFactory lives in vertique-correlation, which depends on core).
        return mock(CorrelationContext.class);
    }

    private static CredentialAcceptedEvent stubAcceptedEvent() {
        return mock(CredentialAcceptedEvent.class);
    }

    private static CredentialRejectedEvent stubRejectedEvent() {
        return mock(CredentialRejectedEvent.class);
    }

    private static AuthorizationDecisionEvent stubAuthzEvent() {
        CorrelationContext correlation = stubCorrelation();
        SecurityContext secCtx = mock(SecurityContext.class);
        ResourceRef resource = new ResourceRef("order", "42", null);
        AuthorizationRequest request = new AuthorizationRequest(secCtx, "READ", resource, null);
        AuthorizationDecision decision = AuthorizationDecision.permit("PERMITTED");
        return new AuthorizationDecisionEvent(Instant.now(), correlation, Optional.empty(), request, decision);
    }

    private static ChannelLifecycleEvent stubChannelEvent() {
        CorrelationContext correlation = stubCorrelation();
        SecurityContext secCtx = mock(SecurityContext.class);
        return new ChannelOpenedEvent(Instant.now(), "channel-abc", secCtx, correlation);
    }

    private static IdentitySnapshotDegradationEvent stubDegradationEvent() {
        return new IdentitySnapshotDegradationEvent(Instant.now(), stubCorrelation(), Optional.empty(), "BAD_HMAC");
    }

    // --- Empty observer set ---

    @Nested
    @DisplayName("Empty observer set — all event types complete immediately")
    class EmptyObservers {

        private final SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());

        @Test
        @DisplayName("emit(CredentialAcceptedEvent) succeeds with no observers")
        void credentialAcceptedNoObservers() {
            Future<Void> result = emitter.emit(stubAcceptedEvent());
            assertTrue(result.isComplete(), "future must be complete synchronously");
            assertTrue(result.succeeded(), "future must succeed");
        }

        @Test
        @DisplayName("emit(CredentialRejectedEvent) succeeds with no observers")
        void credentialRejectedNoObservers() {
            Future<Void> result = emitter.emit(stubRejectedEvent());
            assertTrue(result.isComplete(), "future must be complete synchronously");
            assertTrue(result.succeeded(), "future must succeed");
        }

        @Test
        @DisplayName("emit(AuthorizationDecisionEvent) succeeds with no observers")
        void authorizationDecisionNoObservers() {
            Future<Void> result = emitter.emit(stubAuthzEvent());
            assertTrue(result.isComplete(), "future must be complete synchronously");
            assertTrue(result.succeeded(), "future must succeed");
        }

        @Test
        @DisplayName("emit(ChannelLifecycleEvent) succeeds with no observers")
        void channelLifecycleNoObservers() {
            Future<Void> result = emitter.emit(stubChannelEvent());
            assertTrue(result.isComplete(), "future must be complete synchronously");
            assertTrue(result.succeeded(), "future must succeed");
        }

        @Test
        @DisplayName("emit(IdentitySnapshotDegradationEvent) succeeds with no observers")
        void identitySnapshotDegradationNoObservers() {
            Future<Void> result = emitter.emit(stubDegradationEvent());
            assertTrue(result.isComplete(), "future must be complete synchronously");
            assertTrue(result.succeeded(), "future must succeed");
        }
    }

    // --- Single happy observer ---

    @Nested
    @DisplayName("Single happy observer — correct method called, future succeeds")
    class SingleHappyObserver {

        @Test
        @DisplayName("emit(CredentialAcceptedEvent) calls onCredentialAccepted exactly once")
        void credentialAcceptedCallsCorrectMethod() {
            SecurityEventObserver observer = mock(SecurityEventObserver.class);
            when(observer.onCredentialAccepted(any())).thenReturn(Future.succeededFuture());

            CredentialAcceptedEvent event = stubAcceptedEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            verify(observer, times(1)).onCredentialAccepted(event);
            verify(observer, never()).onCredentialRejected(any());
            verify(observer, never()).onAuthorizationDecided(any());
            verify(observer, never()).onChannelLifecycle(any());
        }

        @Test
        @DisplayName("emit(CredentialRejectedEvent) calls onCredentialRejected exactly once")
        void credentialRejectedCallsCorrectMethod() {
            SecurityEventObserver observer = mock(SecurityEventObserver.class);
            when(observer.onCredentialRejected(any())).thenReturn(Future.succeededFuture());

            CredentialRejectedEvent event = stubRejectedEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            verify(observer, times(1)).onCredentialRejected(event);
            verify(observer, never()).onCredentialAccepted(any());
        }

        @Test
        @DisplayName("emit(AuthorizationDecisionEvent) calls onAuthorizationDecided exactly once")
        void authzDecisionCallsCorrectMethod() {
            SecurityEventObserver observer = mock(SecurityEventObserver.class);
            when(observer.onAuthorizationDecided(any())).thenReturn(Future.succeededFuture());

            AuthorizationDecisionEvent event = stubAuthzEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            verify(observer, times(1)).onAuthorizationDecided(event);
            verify(observer, never()).onCredentialAccepted(any());
        }

        @Test
        @DisplayName("emit(ChannelLifecycleEvent) calls onChannelLifecycle exactly once")
        void channelLifecycleCallsCorrectMethod() {
            SecurityEventObserver observer = mock(SecurityEventObserver.class);
            when(observer.onChannelLifecycle(any())).thenReturn(Future.succeededFuture());

            ChannelLifecycleEvent event = stubChannelEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            verify(observer, times(1)).onChannelLifecycle(event);
            verify(observer, never()).onCredentialAccepted(any());
        }

        @Test
        @DisplayName("emit(IdentitySnapshotDegradationEvent) reaches the observer's onIdentitySnapshotDegradation")
        void emitIdentitySnapshotDegradation_reachesObserver() {
            AtomicReference<IdentitySnapshotDegradationEvent> captured = new AtomicReference<>();
            AtomicBoolean called = new AtomicBoolean(false);
            SecurityEventObserver observer = new SecurityEventObserver() {
                @Override
                public Future<Void> onIdentitySnapshotDegradation(IdentitySnapshotDegradationEvent event) {
                    called.set(true);
                    captured.set(event);
                    return Future.succeededFuture();
                }
            };

            IdentitySnapshotDegradationEvent event = stubDegradationEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(observer));
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            assertTrue(called.get(), "observer's onIdentitySnapshotDegradation must have been called");
            assertSame(event, captured.get(), "observer must receive exactly the emitted event");
        }
    }

    // --- Two happy observers ---

    @Nested
    @DisplayName("Two happy observers — both receive the event")
    class TwoHappyObservers {

        @Test
        @DisplayName("both observers receive CredentialAcceptedEvent")
        void bothObserversReceiveAcceptedEvent() {
            SecurityEventObserver first = mock(SecurityEventObserver.class);
            SecurityEventObserver second = mock(SecurityEventObserver.class);
            when(first.onCredentialAccepted(any())).thenReturn(Future.succeededFuture());
            when(second.onCredentialAccepted(any())).thenReturn(Future.succeededFuture());

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(first);
            observers.add(second);

            CredentialAcceptedEvent event = stubAcceptedEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            verify(first, times(1)).onCredentialAccepted(event);
            verify(second, times(1)).onCredentialAccepted(event);
        }
    }

    // --- Failure isolation — AC-SE-6 ---

    @Nested
    @DisplayName("Failure isolation (AC-SE-6) — one bad observer cannot break the aggregate")
    class FailureIsolation {

        @Test
        @DisplayName("sync exception from one observer: other observer still called, future succeeds")
        void syncExceptionFromOneObserverDoesNotPreventOthers() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SecurityEventObserver thrower = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    throw new RuntimeException("simulated sync failure");
                }
            };
            SecurityEventObserver healthy = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    secondCalled.set(true);
                    return Future.succeededFuture();
                }
            };

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(thrower);
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "aggregate future must succeed despite observer throwing");
            assertTrue(secondCalled.get(), "healthy observer must still have been called");
        }

        @Test
        @DisplayName("failed future from one observer: other observer still called, future succeeds")
        void failedFutureFromOneObserverDoesNotPreventOthers() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SecurityEventObserver failing = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    return Future.failedFuture(new RuntimeException("async failure"));
                }
            };
            SecurityEventObserver healthy = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    secondCalled.set(true);
                    return Future.succeededFuture();
                }
            };

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(failing);
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "aggregate future must succeed despite observer failing async");
            assertTrue(secondCalled.get(), "healthy observer must still have been called");
        }

        @Test
        @DisplayName("null future returned by observer: treated as success, no NPE, other observers called")
        void nullFutureReturnedByObserverTreatedAsSuccess() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SecurityEventObserver nullReturner = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    return null;
                }
            };
            SecurityEventObserver healthy = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    secondCalled.set(true);
                    return Future.succeededFuture();
                }
            };

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(nullReturner);
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "aggregate future must succeed when observer returns null");
            assertTrue(secondCalled.get(), "healthy observer must still have been called");
        }

        @Test
        @DisplayName("CredentialRejectedEvent: sync failure isolated, other observer called")
        void credentialRejectedSyncFailureIsolated() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SecurityEventObserver thrower = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
                    throw new IllegalStateException("observer crashed");
                }
            };
            SecurityEventObserver healthy = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialRejected(CredentialRejectedEvent event) {
                    secondCalled.set(true);
                    return Future.succeededFuture();
                }
            };

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(thrower);
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubRejectedEvent());

            assertTrue(result.succeeded(), "aggregate future must succeed");
            assertTrue(secondCalled.get(), "healthy observer must still have been called");
        }

        @Test
        @DisplayName("AuthorizationDecisionEvent: async failure isolated, other observer called")
        void authzDecisionAsyncFailureIsolated() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SecurityEventObserver failing = new SecurityEventObserver() {
                @Override
                public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                    return Future.failedFuture(new RuntimeException("authz observer failed"));
                }
            };
            SecurityEventObserver healthy = new SecurityEventObserver() {
                @Override
                public Future<Void> onAuthorizationDecided(AuthorizationDecisionEvent event) {
                    secondCalled.set(true);
                    return Future.succeededFuture();
                }
            };

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(failing);
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAuthzEvent());

            assertTrue(result.succeeded(), "aggregate future must succeed despite async failure");
            assertTrue(secondCalled.get(), "healthy observer must still have been called");
        }

        @Test
        @DisplayName("ChannelLifecycleEvent: sync failure isolated, other observer called")
        void channelLifecycleSyncFailureIsolated() {
            AtomicBoolean secondCalled = new AtomicBoolean(false);

            SecurityEventObserver thrower = new SecurityEventObserver() {
                @Override
                public Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
                    throw new RuntimeException("channel observer crashed");
                }
            };
            SecurityEventObserver healthy = new SecurityEventObserver() {
                @Override
                public Future<Void> onChannelLifecycle(ChannelLifecycleEvent event) {
                    secondCalled.set(true);
                    return Future.succeededFuture();
                }
            };

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(thrower);
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubChannelEvent());

            assertTrue(result.succeeded(), "aggregate future must succeed despite observer crash");
            assertTrue(secondCalled.get(), "healthy observer must still have been called");
        }
    }

    // --- Observer ordering not guaranteed ---

    @Nested
    @DisplayName("Observer ordering — all observers receive the event, order not asserted")
    class ObserverOrdering {

        @Test
        @DisplayName("three happy observers all receive the event regardless of Set iteration order")
        void allThreeObserversReceiveEvent() {
            SecurityEventObserver o1 = mock(SecurityEventObserver.class);
            SecurityEventObserver o2 = mock(SecurityEventObserver.class);
            SecurityEventObserver o3 = mock(SecurityEventObserver.class);
            when(o1.onCredentialAccepted(any())).thenReturn(Future.succeededFuture());
            when(o2.onCredentialAccepted(any())).thenReturn(Future.succeededFuture());
            when(o3.onCredentialAccepted(any())).thenReturn(Future.succeededFuture());

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(o1);
            observers.add(o2);
            observers.add(o3);

            CredentialAcceptedEvent event = stubAcceptedEvent();
            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(event);

            assertTrue(result.succeeded(), "future must succeed");
            // All must receive the event — ordering within the set is not asserted
            verify(o1, times(1)).onCredentialAccepted(event);
            verify(o2, times(1)).onCredentialAccepted(event);
            verify(o3, times(1)).onCredentialAccepted(event);
        }
    }

    // --- Null constructor argument ---

    @Nested
    @DisplayName("Constructor null argument rejection")
    class ConstructorNullArguments {

        @Test
        @DisplayName("SecurityEventEmitter(null) throws NullPointerException with message 'observers'")
        void nullObserversThrowsNpe() {
            NullPointerException ex = assertThrows(NullPointerException.class, () -> new SecurityEventEmitter(null));
            assertEquals("observers", ex.getMessage());
        }
    }
}
