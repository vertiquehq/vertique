// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.UnboundCorrelationContext;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.SnapshotDegradationReason;
import dev.vertique.security.events.IdentitySnapshotDegradationEvent;
import dev.vertique.security.events.SecurityEventObserver;
import dev.vertique.security.runtime.IdentitySnapshotDegradationPolicy;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link SnapshotDegradationGate} — the async service-dispatch counterpart to the
 * receive-side identity-snapshot reconstruction initializer.
 *
 * <p>Verifies the passthrough no-op when no {@link SnapshotDegradationMarker} is bound, the
 * {@code FAIL}/{@code CONTINUE_WITHOUT_IDENTITY} policy outcomes when a marker is bound, and that the
 * degradation event is always emitted (and awaited) before the abort/continue decision — an
 * emission-<em>ordering</em> guarantee, not an acknowledged-delivery one. Most of this class mocks
 * {@link SecurityEventEmitter} to pin the gate's own compose-propagation wiring in isolation; see
 * {@code continueWithoutIdentityProceedsWhenRealEmitterObserverFails} for a real-emitter characterization
 * test proving that an observer failure is isolated (identity-001 AC-SE-6) and never trips the
 * mocked-only fail-closed branch exercised elsewhere in this class.
 */
class SnapshotDegradationGateTest {

    private static ServiceDispatchContext dispatchContext() {
        return new ServiceDispatchContext(
                "services/svc/exec",
                "svc.exec",
                "",
                "svc",
                "exec",
                DispatchEnvelope.of("payload"),
                false,
                null,
                null,
                Map.of());
    }

    private static SnapshotDegradationGate gate(
            ContextHolder contextHolder, SecurityEventEmitter emitter, IdentitySnapshotDegradationPolicy policy) {
        return new SnapshotDegradationGate(contextHolder, emitter, Optional.ofNullable(policy));
    }

    // --- Passthrough ---

    @Nested
    @DisplayName("no marker bound")
    class NoMarkerBound {

        @Test
        @DisplayName("passes through unchanged and never emits or evaluates policy")
        void noMarkerPassesThrough() {
            ContextHolder holder = mock(ContextHolder.class);
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.empty());
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);

            SnapshotDegradationGate gate = gate(holder, emitter, IdentitySnapshotDegradationPolicy.FAIL);
            ServiceDispatchContext ctx = dispatchContext();

            Future<ServiceDispatchContext> result = gate.beforeDispatch(ctx);

            assertTrue(result.succeeded(), "no marker must pass through");
            assertSame(ctx, result.result(), "context must be returned unchanged");
            verify(emitter, never()).emit(any(IdentitySnapshotDegradationEvent.class));
        }
    }

    // --- Marker bound: policy outcomes ---

    @Nested
    @DisplayName("marker bound")
    class MarkerBound {

        @Test
        @DisplayName("FAIL policy aborts dispatch after the degradation event has been emitted")
        void failModeAbortsAfterEventEmitted() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.BAD_HMAC.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());

            SnapshotDegradationGate gate = gate(holder, emitter, IdentitySnapshotDegradationPolicy.FAIL);
            ServiceDispatchContext ctx = dispatchContext();

            Future<ServiceDispatchContext> result = gate.beforeDispatch(ctx);

            assertTrue(result.failed(), "FAIL policy must abort dispatch");
            assertInstanceOf(
                    SnapshotDegradationForbiddenException.class,
                    result.cause(),
                    "abort must carry the typed non-recoverable exception");

            ArgumentCaptor<IdentitySnapshotDegradationEvent> captor =
                    ArgumentCaptor.forClass(IdentitySnapshotDegradationEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertEquals(
                    SnapshotDegradationReason.BAD_HMAC.name(),
                    captor.getValue().reasonCode(),
                    "the emitted event must carry the marker's reasonCode");
        }

        @Test
        @DisplayName("CONTINUE_WITHOUT_IDENTITY awaits the event before returning the original context")
        void continueWithoutIdentityAwaitsEventThenReturnsOriginalDispatch() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.UNKNOWN_KEY.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());

            SnapshotDegradationGate gate =
                    gate(holder, emitter, IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);
            ServiceDispatchContext ctx = dispatchContext();

            Future<ServiceDispatchContext> result = gate.beforeDispatch(ctx);

            assertTrue(result.succeeded(), "CONTINUE_WITHOUT_IDENTITY must proceed with dispatch");
            assertSame(ctx, result.result(), "context must be returned unchanged");

            ArgumentCaptor<IdentitySnapshotDegradationEvent> captor =
                    ArgumentCaptor.forClass(IdentitySnapshotDegradationEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertEquals(
                    SnapshotDegradationReason.UNKNOWN_KEY.name(),
                    captor.getValue().reasonCode(),
                    "the emitted event must carry the marker's reasonCode even when continuing");
        }

        /**
         * White-box proof of the gate's {@code emitter.emit(event).compose(...)} wiring: an emitter
         * that returns a failed future propagates that failure as the dispatch outcome, regardless
         * of the configured policy. This state is a mocking artifact — the concrete
         * {@link SecurityEventEmitter} shipped by the framework structurally never returns a failed
         * future (its fan-out isolates every per-observer failure per identity-001 AC-SE-6), so this
         * branch is unreachable with the default emitter. See
         * {@code continueWithoutIdentityProceedsWhenRealEmitterObserverFails} for the real-emitter
         * characterization of the actually-shipped behavior.
         */
        @Test
        @DisplayName(
                "[white-box] CONTINUE_WITHOUT_IDENTITY still fails the dispatch closed if the emitter future itself"
                        + " fails (mocked-only state; the concrete emitter never produces it — see AC-SE-6)")
        void continueWithoutIdentityModeFailsWhenEmitFails() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.UNKNOWN_KEY.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            RuntimeException emitFailure = new RuntimeException("event bus unavailable");
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class)))
                    .thenReturn(Future.failedFuture(emitFailure));

            SnapshotDegradationGate gate =
                    gate(holder, emitter, IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);

            Future<ServiceDispatchContext> result = gate.beforeDispatch(dispatchContext());

            assertTrue(
                    result.failed(),
                    "a failed emission Future must fail the dispatch closed even under CONTINUE_WITHOUT_IDENTITY"
                            + " — this pins the compose wiring, not a state the concrete emitter produces");
            assertSame(emitFailure, result.cause(), "the emit failure must propagate as the dispatch failure cause");
        }

        @Test
        @DisplayName("sentinel correlation is used on the emitted event when no correlation is bound")
        void sentinelCorrelation_emitted_whenUnbound() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.DECODE_FAILED.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());

            SnapshotDegradationGate gate =
                    gate(holder, emitter, IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);

            gate.beforeDispatch(dispatchContext());

            ArgumentCaptor<IdentitySnapshotDegradationEvent> captor =
                    ArgumentCaptor.forClass(IdentitySnapshotDegradationEvent.class);
            verify(emitter, times(1)).emit(captor.capture());
            assertEquals(
                    UnboundCorrelationContext.SENTINEL_ID_VALUE,
                    captor.getValue().correlation().correlationId().value(),
                    "unbound correlation must use the sentinel id");
        }

        @Test
        @DisplayName("absent injected policy defaults to FAIL")
        void absentPolicyDefaultsToFail() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.KEY_UNAVAILABLE.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(Future.succeededFuture());

            SnapshotDegradationGate gate = new SnapshotDegradationGate(holder, emitter, Optional.empty());

            Future<ServiceDispatchContext> result = gate.beforeDispatch(dispatchContext());

            assertTrue(result.failed(), "an absent policy binding must default to fail-closed FAIL");
            assertInstanceOf(SnapshotDegradationForbiddenException.class, result.cause());
        }
    }

    // --- Real emitter: observer failure isolation (identity-001 AC-SE-6) ---

    @Nested
    @DisplayName("real SecurityEventEmitter — observer failure isolation")
    class RealEmitterCharacterization {

        /**
         * Pins the honest V1 contract with a real {@link SecurityEventEmitter} rather than a mock: a
         * single registered observer whose {@code onIdentitySnapshotDegradation} returns a failed
         * future — simulating a failing asynchronous observer — never surfaces as an emitter-level
         * failure, because {@link SecurityEventEmitter}'s per-observer {@code safe()} wrapper
         * isolates both synchronous throws and asynchronous failures (identity-001 AC-SE-6). The
         * gate's {@code CONTINUE_WITHOUT_IDENTITY} policy therefore proceeds: the guarantee this gate makes
         * is emission ordering (the emit future is awaited before the decision), not acknowledged
         * observer delivery — an absent or misbehaving observer does not block or fail the
         * dispatch. Contrast with the mocked white-box proof in
         * {@link MarkerBound#continueWithoutIdentityModeFailsWhenEmitFails}, which exercises a state the
         * concrete emitter structurally cannot produce.
         */
        @Test
        @DisplayName("CONTINUE_WITHOUT_IDENTITY proceeds when the real emitter's only observer fails asynchronously")
        void continueWithoutIdentityProceedsWhenRealEmitterObserverFails() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.BAD_HMAC.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());

            SecurityEventObserver failingObserver = new SecurityEventObserver() {
                @Override
                public Future<Void> onIdentitySnapshotDegradation(IdentitySnapshotDegradationEvent event) {
                    return Future.failedFuture(new RuntimeException("observer unavailable"));
                }
            };
            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of(failingObserver));

            SnapshotDegradationGate gate =
                    gate(holder, emitter, IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);
            ServiceDispatchContext ctx = dispatchContext();

            Future<ServiceDispatchContext> result = gate.beforeDispatch(ctx);

            assertTrue(
                    result.succeeded(),
                    "observer failure must be isolated (AC-SE-6): CONTINUE_WITHOUT_IDENTITY must still proceed");
            assertSame(ctx, result.result(), "context must be returned unchanged");
        }

        /**
         * Cheap companion assertion: a real emitter with zero registered observers also proceeds
         * under {@code CONTINUE_WITHOUT_IDENTITY} — {@link SecurityEventEmitter}'s empty-fan-out
         * short-circuit succeeds immediately, so an application that has not wired any security-event
         * observer at all still gets ordering-correct, non-blocking behavior.
         */
        @Test
        @DisplayName("CONTINUE_WITHOUT_IDENTITY proceeds when the real emitter has zero registered observers")
        void continueWithoutIdentityProceedsWhenRealEmitterHasNoObservers() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.UNKNOWN_KEY.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());

            SecurityEventEmitter emitter = new SecurityEventEmitter(Set.of());

            SnapshotDegradationGate gate =
                    gate(holder, emitter, IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);
            ServiceDispatchContext ctx = dispatchContext();

            Future<ServiceDispatchContext> result = gate.beforeDispatch(ctx);

            assertTrue(result.succeeded(), "an empty observer set must still allow the dispatch to proceed");
            assertSame(ctx, result.result(), "context must be returned unchanged");
        }
    }

    // --- Ordering: event must be awaited before the abort/continue decision ---

    @Nested
    @DisplayName("event-before-decision ordering")
    class EventBeforeDecisionOrdering {

        @Test
        @DisplayName("the gate's returned future does not settle until the emit future completes")
        void eventAwaitedBeforeAbort() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.BAD_HMAC.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());

            Promise<Void> emitPromise = Promise.promise();
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(emitPromise.future());

            SnapshotDegradationGate gate = gate(holder, emitter, IdentitySnapshotDegradationPolicy.FAIL);

            Future<ServiceDispatchContext> result = gate.beforeDispatch(dispatchContext());

            assertFalse(result.isComplete(), "the gate's future must not settle before the emit future completes");
            verify(emitter, times(1)).emit(any(IdentitySnapshotDegradationEvent.class));

            emitPromise.complete();

            assertTrue(result.isComplete(), "the gate's future must settle once the emit future completes");
            assertTrue(result.failed(), "FAIL policy must abort once the event has been emitted");
        }

        @Test
        @DisplayName("CONTINUE_WITHOUT_IDENTITY also waits for the emit future before proceeding")
        void eventAwaitedBeforeContinue() {
            ContextHolder holder = mock(ContextHolder.class);
            SnapshotDegradationMarker marker =
                    new SnapshotDegradationMarker(SnapshotDegradationReason.BAD_HMAC.name(), Optional.empty());
            when(holder.current(SnapshotDegradationMarker.class)).thenReturn(Optional.of(marker));
            when(holder.current(CorrelationContext.class)).thenReturn(Optional.empty());

            Promise<Void> emitPromise = Promise.promise();
            SecurityEventEmitter emitter = mock(SecurityEventEmitter.class);
            when(emitter.emit(any(IdentitySnapshotDegradationEvent.class))).thenReturn(emitPromise.future());

            SnapshotDegradationGate gate =
                    gate(holder, emitter, IdentitySnapshotDegradationPolicy.CONTINUE_WITHOUT_IDENTITY);
            ServiceDispatchContext ctx = dispatchContext();

            Future<ServiceDispatchContext> result = gate.beforeDispatch(ctx);

            assertFalse(result.isComplete(), "the gate's future must not settle before the emit future completes");

            emitPromise.complete();

            assertTrue(result.succeeded(), "CONTINUE_WITHOUT_IDENTITY must proceed once the event has been emitted");
            assertSame(ctx, result.result());
        }
    }
}
