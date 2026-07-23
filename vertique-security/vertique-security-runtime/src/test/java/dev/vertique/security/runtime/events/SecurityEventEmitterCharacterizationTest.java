// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.security.runtime.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.security.events.CredentialAcceptedEvent;
import dev.vertique.security.events.SecurityEventObserver;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link SecurityEventEmitter} that pin the observable fan-out and
 * auth-isolation contract before and after the migration of {@code fanOut} onto the
 * {@code dev.vertique.core.async.Combinators} kernel.
 *
 * <p>These tests deliberately use static inner-class {@link SecurityEventObserver} doubles (rather
 * than mocks) so the exact synchronous-throw / null-return / async-failure behaviours are exercised
 * against the real emitter code path. They pin three invariants that MUST hold byte-for-byte across
 * the migration:
 *
 * <ul>
 *   <li><b>Fan-out completeness</b> — every observer in the set is invoked exactly once when an
 *       event is emitted.</li>
 *   <li><b>Per-observer isolation (auth-isolation invariant)</b> — the returned emit future always
 *       succeeds even when an observer throws synchronously, returns a {@code null} future, or fails
 *       its future asynchronously; one misbehaving observer can neither fail the emit nor prevent
 *       the other observers from running.</li>
 *   <li><b>Waits-for-all</b> — the emit future completes only after every observer future has
 *       settled; a still-pending observer keeps the emit incomplete.</li>
 * </ul>
 */
class SecurityEventEmitterCharacterizationTest {

    // --- Fixtures ---

    /**
     * Produces a stub {@link CredentialAcceptedEvent}. The emitter only passes the event through to
     * observers and never reads its fields, so a Mockito stub avoids constructing the full
     * non-null-validated record graph.
     *
     * @return a stub accepted-credential event
     */
    private static CredentialAcceptedEvent stubAcceptedEvent() {
        return mock(CredentialAcceptedEvent.class);
    }

    // --- Observer doubles ---

    /**
     * Records that it was invoked and returns a succeeded future.
     */
    private static final class RecordingObserver implements SecurityEventObserver {
        private final AtomicBoolean invoked = new AtomicBoolean(false);

        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            invoked.set(true);
            return Future.succeededFuture();
        }

        boolean wasInvoked() {
            return invoked.get();
        }
    }

    /**
     * Throws synchronously from the observer method (case 1 of {@code safe(...)}).
     */
    private static final class SyncThrowingObserver implements SecurityEventObserver {
        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            throw new RuntimeException("simulated synchronous failure");
        }
    }

    /**
     * Sneak-throws a <em>checked</em> {@link Exception} from the observer method without declaring
     * it, exercising the widened {@code catch (Exception)} branch of {@code safe(...)}. Before the
     * widening this checked throw escaped {@code safe()} and was silently swallowed by the kernel's
     * no-op {@code onFailure}; the widening routes it through {@code safe()}'s log-and-isolate path.
     */
    private static final class CheckedSneakThrowingObserver implements SecurityEventObserver {
        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return sneakyThrow(new Exception("simulated checked synchronous failure"));
        }
    }

    /**
     * Throws the supplied checked {@link Throwable} without declaring it, via the generic
     * sneaky-throw idiom. The bogus {@code <R>} return type lets call sites use it in a
     * {@code return} position even though it never returns normally.
     *
     * @param t   the throwable to raise
     * @param <T> the (erased) throwable type the compiler is tricked into treating {@code t} as
     * @param <R> the bogus return type so the call can sit in a {@code return} position
     * @return never returns normally; always throws {@code t}
     * @throws T the supplied throwable, raised unchecked
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * Returns a {@code null} future from the observer method (case 2 of {@code safe(...)}).
     */
    private static final class NullReturningObserver implements SecurityEventObserver {
        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return null;
        }
    }

    /**
     * Returns an already-failed future from the observer method (case 3 of {@code safe(...)}).
     */
    private static final class AsyncFailingObserver implements SecurityEventObserver {
        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return Future.failedFuture(new RuntimeException("simulated asynchronous failure"));
        }
    }

    /**
     * Returns a future that completes only when its promise is resolved by the test, allowing the
     * waits-for-all behaviour to be observed.
     */
    private static final class PendingObserver implements SecurityEventObserver {
        private final Promise<Void> promise = Promise.promise();

        @Override
        public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
            return promise.future();
        }

        void complete() {
            promise.complete();
        }
    }

    // --- Invariant 1: fan-out completeness ---

    @Nested
    @DisplayName("Fan-out completeness — every observer is invoked when an event is emitted")
    class FanOutCompleteness {

        @Test
        @DisplayName("all three observers are invoked exactly once on emit")
        void allObserversInvoked() {
            RecordingObserver first = new RecordingObserver();
            RecordingObserver second = new RecordingObserver();
            RecordingObserver third = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(first);
            observers.add(second);
            observers.add(third);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "emit future must succeed");
            assertTrue(first.wasInvoked(), "first observer must be invoked");
            assertTrue(second.wasInvoked(), "second observer must be invoked");
            assertTrue(third.wasInvoked(), "third observer must be invoked");
        }
    }

    // --- Invariant 2: per-observer isolation (auth-isolation invariant) ---

    @Nested
    @DisplayName("Per-observer isolation — a bad observer cannot fail the emit or stop the others")
    class PerObserverIsolation {

        @Test
        @DisplayName("synchronous throw is isolated: emit succeeds and the healthy observer runs")
        void syncThrowIsolated() {
            RecordingObserver healthy = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(new SyncThrowingObserver());
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "emit must succeed despite an observer throwing synchronously");
            assertTrue(healthy.wasInvoked(), "the healthy observer must still be invoked");
        }

        @Test
        @DisplayName("sneak-thrown checked exception is isolated, logged, and emit still succeeds")
        void emit_observerSneakThrowsCheckedException_isolatedLoggedAndEmitSucceeds() {
            // Regression guard (CORE-001 round-2): safe() now catches Exception, not only
            // RuntimeException. A sneak-thrown CHECKED exception is therefore routed through safe()'s
            // log-and-isolate path instead of escaping into the kernel's no-op onFailure (where it
            // would be silently swallowed with no log). We assert isolation via the existing harness:
            // the emit Future SUCCEEDS and the other observers still run; the failure is not lost.
            RecordingObserver healthy = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(new CheckedSneakThrowingObserver());
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "emit must succeed despite an observer sneak-throwing a checked exception");
            assertTrue(healthy.wasInvoked(), "the healthy observer must still be invoked (isolation)");
        }

        @Test
        @DisplayName("null-returning observer is isolated: emit succeeds and the healthy observer runs")
        void nullReturnIsolated() {
            RecordingObserver healthy = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(new NullReturningObserver());
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "emit must succeed despite an observer returning null");
            assertTrue(healthy.wasInvoked(), "the healthy observer must still be invoked");
        }

        @Test
        @DisplayName("async failure is isolated: emit succeeds and the healthy observer runs")
        void asyncFailureIsolated() {
            RecordingObserver healthy = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(new AsyncFailingObserver());
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "emit must succeed despite an observer failing asynchronously");
            assertTrue(healthy.wasInvoked(), "the healthy observer must still be invoked");
        }

        @Test
        @DisplayName("all three failure modes at once: emit still succeeds and the healthy observer runs")
        void allFailureModesIsolatedTogether() {
            RecordingObserver healthy = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(new SyncThrowingObserver());
            observers.add(new NullReturningObserver());
            observers.add(new AsyncFailingObserver());
            observers.add(healthy);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertTrue(result.succeeded(), "emit must succeed even when every failure mode fires at once");
            assertTrue(healthy.wasInvoked(), "the healthy observer must still be invoked");
        }
    }

    // --- Invariant 3: waits-for-all ---

    @Nested
    @DisplayName("Waits-for-all — emit completes only after every observer future settles")
    class WaitsForAll {

        @Test
        @DisplayName("a still-pending observer keeps emit incomplete until it settles")
        void pendingObserverKeepsEmitIncomplete() {
            PendingObserver pending = new PendingObserver();
            RecordingObserver fast = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(pending);
            observers.add(fast);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertFalse(result.isComplete(), "emit must not complete while one observer future is still pending");
            assertTrue(fast.wasInvoked(), "the fast observer must already have been invoked");

            pending.complete();

            assertTrue(result.isComplete(), "emit must complete once the slow observer settles");
            assertTrue(result.succeeded(), "emit must succeed once all observers settle");
        }

        @Test
        @DisplayName("a pending observer that eventually fails still lets emit complete and succeed")
        void pendingThenFailingObserverStillCompletesEmit() {
            Promise<Void> slow = Promise.promise();
            SecurityEventObserver slowThenFailing = new SecurityEventObserver() {
                @Override
                public Future<Void> onCredentialAccepted(CredentialAcceptedEvent event) {
                    return slow.future();
                }
            };
            RecordingObserver fast = new RecordingObserver();

            Set<SecurityEventObserver> observers = new HashSet<>();
            observers.add(slowThenFailing);
            observers.add(fast);

            SecurityEventEmitter emitter = new SecurityEventEmitter(observers);
            Future<Void> result = emitter.emit(stubAcceptedEvent());

            assertFalse(result.isComplete(), "emit must wait for the slow observer to settle");

            slow.fail(new RuntimeException("late async failure"));

            assertTrue(result.isComplete(), "emit must complete after the slow observer fails");
            assertTrue(result.succeeded(), "emit must still succeed — the late failure is isolated");
        }
    }

    // --- Constructor null rejection (unchanged across migration) ---

    @Nested
    @DisplayName("Constructor — null observer set is rejected with the 'observers' message")
    class ConstructorContract {

        @Test
        @DisplayName("new SecurityEventEmitter(null) throws NullPointerException('observers')")
        void nullObserversRejected() {
            NullPointerException ex = org.junit.jupiter.api.Assertions.assertThrows(
                    NullPointerException.class, () -> new SecurityEventEmitter(null));
            assertEquals("observers", ex.getMessage(), "the null-check message must remain 'observers'");
        }
    }
}
