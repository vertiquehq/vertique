// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Combinators}, pinning the frozen contract of each async control-flow
 * combinator: sequential fold short-circuiting and threading, ordered first-wins recovery, the
 * all-settled swallowing join, the synchronous swallowing loop, and fire-and-forget dispatch.
 */
class CombinatorsTest {

    @Nested
    @DisplayName("foldSequential()")
    class FoldSequential {

        @Test
        @DisplayName("first failing step short-circuits and later steps are never invoked")
        void foldSequential_propagatesFailFast() {
            // given: three items where the step at index 1 fails fast
            RuntimeException boom = new RuntimeException("boom at 1");
            AtomicBoolean index2Invoked = new AtomicBoolean(false);

            // when: folding with a step that fails at index 1 and records whether index 2 ran
            Future<String> result = Combinators.foldSequential(List.of(0, 1, 2), "seed", (item, value) -> {
                if (item == 1) {
                    return Future.failedFuture(boom);
                }
                if (item == 2) {
                    index2Invoked.set(true);
                }
                return Future.succeededFuture(value + "-" + item);
            });

            // then: the fold fails with that cause and index 2's step was never invoked
            assertTrue(result.failed());
            assertSame(boom, result.cause());
            assertFalse(index2Invoked.get(), "step at index 2 must not run after index 1 fails");
        }

        @Test
        @DisplayName("a step that recovers to the previous value lets the chain continue and thread it")
        void foldSequential_continuesThreadingPreviousValue() {
            // given: index 1 fails internally but recovers to the previous value, continuing the fold
            List<String> seenByNext = new ArrayList<>();

            // when: folding so index 1 recovers to the prior value and index 2 records what it sees
            Future<String> result = Combinators.foldSequential(List.of(0, 1, 2), "seed", (item, value) -> {
                if (item == 1) {
                    return Future.<String>failedFuture(new RuntimeException("transient at 1"))
                            .recover(err -> Future.succeededFuture(value));
                }
                seenByNext.add(value);
                return Future.succeededFuture(value + "-" + item);
            });

            // then: the chain continued past the recovered index 1, index 2 received the threaded
            // previous value "seed-0" and transformed it, so the final value is "seed-0-2"
            assertTrue(result.succeeded());
            assertEquals("seed-0-2", result.result());
            assertTrue(seenByNext.contains("seed-0"), "index 2 must receive the previous value threaded through");
        }

        @Test
        @DisplayName("a synchronous throw from step becomes a failed future, never a raw escape")
        void foldSequential_syncThrowEntersStepPolicy() {
            // given: a step that throws synchronously, with a fail-fast policy (no recovery)
            IllegalStateException thrown = new IllegalStateException("sync throw");

            // when/then: no raw exception escapes foldSequential; it surfaces as a failed future
            Future<String> result = Combinators.foldSequential(List.of(0, 1), "seed", (item, value) -> {
                if (item == 1) {
                    throw thrown;
                }
                return Future.succeededFuture(value + "-" + item);
            });

            assertTrue(result.failed());
            assertSame(thrown, result.cause());
        }
    }

    @Nested
    @DisplayName("recoverFirstWins()")
    class RecoverFirstWins {

        @Test
        @DisplayName("a failing recoverer threads its new failure to the next recoverer")
        void recoverFirstWins_failedRecovererThreadsNewFailure() {
            // given: recoverer A produces a NEW failure; recoverer B records what failure it receives
            RuntimeException original = new RuntimeException("original");
            RuntimeException fromA = new RuntimeException("from-A");
            List<Throwable> seenByB = new ArrayList<>();

            // when: A replaces the failure, B succeeds after recording its input failure
            Future<String> result = Combinators.recoverFirstWins(
                    List.of("A", "B"),
                    original,
                    (item, failure) -> {
                        if (item.equals("A")) {
                            return Future.failedFuture(fromA);
                        }
                        seenByB.add(failure);
                        return Future.succeededFuture("recovered-by-B");
                    },
                    t -> false);

            // then: B was invoked with A's new failure (not the original), and its recovery won
            assertTrue(result.succeeded());
            assertEquals("recovered-by-B", result.result());
            assertEquals(1, seenByB.size());
            assertSame(fromA, seenByB.get(0), "B must receive A's replacing failure, not the original error");
        }

        @Test
        @DisplayName("a mid-chain non-recoverable failure short-circuits and skips remaining recoverers")
        void recoverFirstWins_midChainNonRecoverableShortCircuits() {
            // given: A produces a failure that nonRecoverable matches; B must then be skipped
            RuntimeException original = new RuntimeException("original");
            IllegalStateException fatal = new IllegalStateException("fatal");
            AtomicBoolean bInvoked = new AtomicBoolean(false);

            // when: nonRecoverable matches A's produced failure
            Future<String> result = Combinators.recoverFirstWins(
                    List.of("A", "B"),
                    original,
                    (item, failure) -> {
                        if (item.equals("A")) {
                            return Future.failedFuture(fatal);
                        }
                        bInvoked.set(true);
                        return Future.succeededFuture("recovered-by-B");
                    },
                    t -> t instanceof IllegalStateException);

            // then: the chain short-circuits with the fatal failure and B is never invoked
            assertTrue(result.failed());
            assertSame(fatal, result.cause());
            assertFalse(bInvoked.get(), "B must be skipped once a non-recoverable failure appears mid-chain");
        }

        @Test
        @DisplayName("when no recoverer succeeds the terminal failure is the latest, not the original")
        void recoverFirstWins_terminalIsLatestFailure() {
            // given: neither recoverer succeeds; each replaces the failure with a new one
            RuntimeException original = new RuntimeException("original");
            RuntimeException fromA = new RuntimeException("from-A");
            RuntimeException fromB = new RuntimeException("from-B");

            // when: A and B both fail with replacing failures, nothing is non-recoverable
            Future<String> result = Combinators.recoverFirstWins(
                    List.of("A", "B"),
                    original,
                    (item, failure) -> Future.failedFuture(item.equals("A") ? fromA : fromB),
                    t -> false);

            // then: the terminal failure is the latest produced (from B), not the original
            assertTrue(result.failed());
            assertSame(fromB, result.cause());
        }
    }

    @Nested
    @DisplayName("joinAllSwallow()")
    class JoinAllSwallow {

        @Test
        @DisplayName("waits for a later-completing hook after an early failure, then succeeds")
        void joinAllSwallow_waitsForLaterHookAfterEarlyFailure() {
            // given: hook A is already-failed; hook B completes only when we complete its promise later
            RuntimeException fromA = new RuntimeException("from-A");
            Promise<Object> laterB = Promise.promise();
            List<Object> failedItems = new ArrayList<>();

            // when: joining A (failed) and B (pending)
            Future<Void> joined = Combinators.joinAllSwallow(
                    List.of("A", "B"),
                    item -> item.equals("A") ? Future.failedFuture(fromA) : laterB.future(),
                    (item, throwable) -> failedItems.add(item));

            // then: the join is NOT complete until B settles, even though A already failed
            assertFalse(joined.isComplete(), "join must wait for B even after A fails");
            assertTrue(failedItems.contains("A"), "A's failure must be routed to onFailure");

            // when: B finally completes
            laterB.complete("done");

            // then: the join completes SUCCESSFULLY (never fails)
            assertTrue(joined.succeeded(), "join must succeed once all hooks settle, swallowing A's failure");
        }

        @Test
        @DisplayName("a synchronous throw from a hook does not abort the fan-out and is routed to onFailure")
        void joinAllSwallow_syncThrowDoesNotAbortAndRoutesOnFailure() {
            // given: hook A throws synchronously; hook B must still be launched
            IllegalStateException fromA = new IllegalStateException("sync from-A");
            AtomicBoolean bLaunched = new AtomicBoolean(false);
            List<Throwable> routed = new ArrayList<>();

            // when: joining A (sync throw) and B (success)
            Future<Void> joined = Combinators.joinAllSwallow(
                    List.of("A", "B"),
                    item -> {
                        if (item.equals("A")) {
                            throw fromA;
                        }
                        bLaunched.set(true);
                        return Future.succeededFuture("ok");
                    },
                    (item, throwable) -> routed.add(throwable));

            // then: B was still launched, A's throwable routed, and the join succeeds
            assertTrue(bLaunched.get(), "B must be launched even though A threw synchronously");
            assertTrue(routed.contains(fromA), "A's synchronous throwable must be routed to onFailure");
            assertTrue(joined.succeeded());
        }

        @Test
        @DisplayName("a null returned future is treated as a per-item failure routed to onFailure")
        void joinAllSwallow_nullHookFutureRoutedToOnFailure() {
            // given: hook A returns null (no future); hook B returns a succeeded future
            AtomicBoolean bHookInvoked = new AtomicBoolean(false);
            List<Object> failedItems = new ArrayList<>();
            List<Throwable> failedCauses = new ArrayList<>();

            // when: joining A (null future) and B (success); the call must not throw
            Future<Void> joined = Combinators.joinAllSwallow(
                    List.of("A", "B"),
                    item -> {
                        if (item.equals("A")) {
                            return null;
                        }
                        bHookInvoked.set(true);
                        return Future.succeededFuture("ok");
                    },
                    (item, throwable) -> {
                        failedItems.add(item);
                        failedCauses.add(throwable);
                    });

            // then: A's null future was routed to onFailure with a non-null throwable, B's hook still
            // ran (the fan-out was not aborted), and the join succeeds (no raw exception escaped)
            assertTrue(bHookInvoked.get(), "B's hook must be invoked even though A returned a null future");
            assertTrue(failedItems.contains("A"), "A's null future must be routed to onFailure");
            assertEquals(1, failedCauses.size());
            assertNotNull(failedCauses.get(0), "the throwable routed for A's null future must be non-null");
            assertTrue(joined.succeeded(), "the join must succeed and not abort the fan-out");
        }
    }

    @Nested
    @DisplayName("forEachSwallowSync()")
    class ForEachSwallowSync {

        @Test
        @DisplayName("a throwing item is swallowed, routed to onFailure, and iteration continues")
        void forEachSwallowSync_failingItemContinuesAndRoutesOnFailure() {
            // given: item A's hook throws; item B's hook must still run
            RuntimeException fromA = new RuntimeException("from-A");
            AtomicBoolean bRan = new AtomicBoolean(false);
            List<String> failedItems = new ArrayList<>();
            List<Throwable> failedCauses = new ArrayList<>();

            // when: iterating A (throws) and B (runs); the call must not throw
            Combinators.forEachSwallowSync(
                    List.of("A", "B"),
                    item -> {
                        if (item.equals("A")) {
                            throw fromA;
                        }
                        bRan.set(true);
                    },
                    (item, throwable) -> {
                        failedItems.add(item);
                        failedCauses.add(throwable);
                    });

            // then: iteration continued to B and onFailure received (itemA, throwable)
            assertTrue(bRan.get(), "iteration must continue to B after A throws");
            assertEquals(List.of("A"), failedItems);
            assertEquals(1, failedCauses.size());
            assertSame(fromA, failedCauses.get(0));
        }

        @Test
        @DisplayName("a checked-exception throw is also swallowed, routed to onFailure, and iteration continues")
        void forEachSwallowSync_checkedExceptionAlsoRoutedToOnFailure() {
            // given: item A's hook throws a CHECKED exception (via sneaky-throw); item B must still run
            Exception fromA = new Exception("checked from-A");
            AtomicBoolean bRan = new AtomicBoolean(false);
            List<String> failedItems = new ArrayList<>();
            List<Throwable> failedCauses = new ArrayList<>();

            // when: iterating A (throws checked) and B (runs); the call must not throw
            Combinators.forEachSwallowSync(
                    List.of("A", "B"),
                    item -> {
                        if (item.equals("A")) {
                            sneakyThrow(fromA);
                        }
                        bRan.set(true);
                    },
                    (item, throwable) -> {
                        failedItems.add(item);
                        failedCauses.add(throwable);
                    });

            // then: the checked exception was caught (Exception breadth), iteration continued to B,
            // and onFailure received (itemA, the checked throwable)
            assertTrue(bRan.get(), "iteration must continue to B after A throws a checked exception");
            assertEquals(List.of("A"), failedItems);
            assertEquals(1, failedCauses.size());
            assertSame(fromA, failedCauses.get(0));
        }
    }

    /**
     * Throws the given checked throwable without declaring it, so a {@link java.util.function.Consumer}
     * body (which cannot declare checked exceptions) can exercise the {@code catch (Exception e)} breadth
     * of {@link Combinators#forEachSwallowSync}.
     *
     * @param t the throwable to throw
     * @param <T> the inferred {@link RuntimeException} type the compiler is tricked into expecting
     * @throws T the supplied throwable, re-thrown unchecked
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    @Nested
    @DisplayName("dispatchNoJoin()")
    class DispatchNoJoin {

        @Test
        @DisplayName("routes both async and synchronous failures without joining")
        void dispatchNoJoin_routesAsyncAndSyncFailureWithoutJoining() {
            // given: hook A returns a failed future; hook B throws synchronously
            RuntimeException fromA = new RuntimeException("async from-A");
            IllegalStateException fromB = new IllegalStateException("sync from-B");
            AtomicInteger routedCount = new AtomicInteger(0);
            List<Throwable> routed = new ArrayList<>();

            // when: dispatching A (async failure) and B (sync throw); the call returns immediately
            Combinators.dispatchNoJoin(
                    List.of("A", "B"),
                    item -> {
                        if (item.equals("A")) {
                            return Future.failedFuture(fromA);
                        }
                        throw fromB;
                    },
                    (item, throwable) -> {
                        routedCount.incrementAndGet();
                        routed.add(throwable);
                    });

            // then: both failures were routed to onFailure (no join was awaited)
            assertEquals(2, routedCount.get(), "both async and sync failures must be routed");
            assertTrue(routed.contains(fromA), "A's async failure must be routed");
            assertTrue(routed.contains(fromB), "B's synchronous throwable must be routed");
        }

        @Test
        @DisplayName("a null returned future is treated as a per-item failure routed to onFailure")
        void dispatchNoJoin_nullHookFutureRoutedToOnFailure() {
            // given: a single item whose hook returns null (no future)
            List<Object> failedItems = new ArrayList<>();
            List<Throwable> failedCauses = new ArrayList<>();

            // when: dispatching the item whose hook returns null; the call must return without throwing
            Combinators.dispatchNoJoin(List.of("A"), item -> null, (item, throwable) -> {
                failedItems.add(item);
                failedCauses.add(throwable);
            });

            // then: the null future was routed to onFailure with a non-null throwable
            assertTrue(failedItems.contains("A"), "the item with a null future must be routed to onFailure");
            assertEquals(1, failedCauses.size());
            assertNotNull(failedCauses.get(0), "the throwable routed for the null future must be non-null");
        }
    }
}
