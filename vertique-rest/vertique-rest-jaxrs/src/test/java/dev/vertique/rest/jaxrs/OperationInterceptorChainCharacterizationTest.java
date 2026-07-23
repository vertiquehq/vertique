// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.interceptor.OperationContext;
import dev.vertique.rest.core.interceptor.OperationInterceptor;
import io.vertx.core.Future;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link OperationInterceptorChain}, pinning the observable behavior of
 * each public invocation mode against the CURRENT (pre-migration) implementation.
 *
 * <p>These tests constitute a behavioral net: they must be GREEN before any refactoring of the
 * production class and must remain GREEN after. A test failure after migration indicates a
 * behavior change.
 *
 * <p>Behaviors pinned:
 * <ol>
 *   <li>{@code chainBeforeOperationInterceptors} — runs interceptors in list order threading
 *       {@link OperationContext}; a failed {@code beforeOperation} short-circuits (next not
 *       invoked) and the result fails.</li>
 *   <li>{@code chainAfterOperationInterceptors} — runs in list order threading the result
 *       {@code Object}; a failed {@code afterOperation} short-circuits and the result fails.</li>
 *   <li>{@code chainRecoverOperationInterceptors} — tries in order; the first
 *       {@code recoverOperation} that succeeds wins (later skipped); a recoverer's own failure
 *       threads to the next; if none recovers, the latest failure propagates.</li>
 *   <li>{@code fireOnOperation} / {@code fireOnSuccess} / {@code fireOnError} — each invoked for
 *       every interceptor in list order; a thrown exception is caught and swallowed (later
 *       interceptors still run).</li>
 * </ol>
 */
class OperationInterceptorChainCharacterizationTest {

    // --- Test doubles ---

    /**
     * Configurable {@link OperationInterceptor} test double that records invocations and can be
     * configured to succeed, fail, or throw on any phase.
     */
    static class RecordingInterceptor implements OperationInterceptor {

        final List<String> calls = new ArrayList<>();
        final String name;

        // Async-handler configuration
        boolean beforeFails;
        boolean afterFails;
        boolean recoverSucceeds;
        boolean recoverFails;
        Object recoveryResult;

        // Sync-observer configuration
        boolean onOperationThrows;
        boolean onSuccessThrows;
        boolean onErrorThrows;

        RecordingInterceptor(String name) {
            this.name = name;
        }

        @Override
        public Future<OperationContext> beforeOperation(OperationContext ctx) {
            calls.add("before:" + name);
            if (beforeFails) {
                return Future.failedFuture(new RuntimeException("before-fail:" + name));
            }
            return Future.succeededFuture(ctx);
        }

        @Override
        public Future<Object> afterOperation(OperationContext ctx, Object result) {
            calls.add("after:" + name);
            if (afterFails) {
                return Future.failedFuture(new RuntimeException("after-fail:" + name));
            }
            return Future.succeededFuture(result + "+" + name);
        }

        @Override
        public Future<Object> recoverOperation(OperationContext ctx, Throwable cause) {
            calls.add("recover:" + name);
            if (recoverSucceeds) {
                return Future.succeededFuture(recoveryResult);
            }
            if (recoverFails) {
                return Future.failedFuture(new RuntimeException("recover-fail:" + name));
            }
            return Future.failedFuture(cause);
        }

        @Override
        public void onOperation(OperationContext ctx) {
            calls.add("onOperation:" + name);
            if (onOperationThrows) {
                throw new RuntimeException("onOperation-throw:" + name);
            }
        }

        @Override
        public void onSuccess(OperationContext ctx, Object result) {
            calls.add("onSuccess:" + name);
            if (onSuccessThrows) {
                throw new RuntimeException("onSuccess-throw:" + name);
            }
        }

        @Override
        public void onError(OperationContext ctx, Throwable cause) {
            calls.add("onError:" + name);
            if (onErrorThrows) {
                throw new RuntimeException("onError-throw:" + name);
            }
        }
    }

    // --- Fixtures ---

    private OperationContext opCtx;

    @BeforeEach
    void setUp() {
        opCtx = new OperationContext("testOp", mock(RoutingContext.class), List.of(), List.of(), Map.of());
    }

    private OperationInterceptorChain chainOf(List<OperationInterceptor> interceptors) {
        return new OperationInterceptorChain(interceptors);
    }

    // --- chainBeforeOperationInterceptors ---

    /**
     * Tests for {@link OperationInterceptorChain#chainBeforeOperationInterceptors}.
     */
    @Nested
    @DisplayName("chainBeforeOperationInterceptors")
    class ChainBefore {

        @Test
        @DisplayName("Empty list returns succeeded future carrying the seed context")
        void emptyListReturnsSeed() {
            OperationInterceptorChain chain = chainOf(List.of());

            Future<OperationContext> result = chain.chainBeforeOperationInterceptors(opCtx, 0);

            assertTrue(result.succeeded());
            assertSame(opCtx, result.result());
        }

        @Test
        @DisplayName("Single interceptor invoked; succeeds with its returned context")
        void singleInterceptorInvoked() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            OperationInterceptorChain chain = chainOf(List.of(i1));

            Future<OperationContext> result = chain.chainBeforeOperationInterceptors(opCtx, 0);

            assertTrue(result.succeeded());
            assertEquals(List.of("before:A"), i1.calls);
        }

        @Test
        @DisplayName("Multiple interceptors run in list order threading context")
        void multipleInterceptorsRunInOrder() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            OperationInterceptorChain chain = chainOf(List.of(i1, i2, i3));

            Future<OperationContext> result = chain.chainBeforeOperationInterceptors(opCtx, 0);

            assertTrue(result.succeeded());
            assertEquals(List.of("before:A"), i1.calls);
            assertEquals(List.of("before:B"), i2.calls);
            assertEquals(List.of("before:C"), i3.calls);
        }

        @Test
        @DisplayName("Failed beforeOperation short-circuits: subsequent interceptors not invoked")
        void failedBeforeShortCircuits() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            i1.beforeFails = true;
            OperationInterceptorChain chain = chainOf(List.of(i1, i2, i3));

            Future<OperationContext> result = chain.chainBeforeOperationInterceptors(opCtx, 0);

            assertTrue(result.failed());
            assertEquals("before-fail:A", result.cause().getMessage());
            assertEquals(List.of("before:A"), i1.calls);
            assertTrue(i2.calls.isEmpty(), "B must not be invoked after A fails");
            assertTrue(i3.calls.isEmpty(), "C must not be invoked after A fails");
        }

        @Test
        @DisplayName("Non-zero start index skips earlier interceptors")
        void nonZeroIndexSkipsEarlier() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));

            // Start at index 1 — only B should run
            Future<OperationContext> result = chain.chainBeforeOperationInterceptors(opCtx, 1);

            assertTrue(result.succeeded());
            assertTrue(i1.calls.isEmpty());
            assertEquals(List.of("before:B"), i2.calls);
        }
    }

    // --- chainAfterOperationInterceptors ---

    /**
     * Tests for {@link OperationInterceptorChain#chainAfterOperationInterceptors}.
     */
    @Nested
    @DisplayName("chainAfterOperationInterceptors")
    class ChainAfter {

        @Test
        @DisplayName("Empty list returns succeeded future carrying the seed result")
        void emptyListReturnsSeed() {
            OperationInterceptorChain chain = chainOf(List.of());

            Future<Object> result = chain.chainAfterOperationInterceptors(opCtx, "original", 0);

            assertTrue(result.succeeded());
            assertEquals("original", result.result());
        }

        @Test
        @DisplayName("Single interceptor transforms the result")
        void singleInterceptorTransforms() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            OperationInterceptorChain chain = chainOf(List.of(i1));

            Future<Object> result = chain.chainAfterOperationInterceptors(opCtx, "val", 0);

            assertTrue(result.succeeded());
            assertEquals("val+A", result.result());
            assertEquals(List.of("after:A"), i1.calls);
        }

        @Test
        @DisplayName("Multiple interceptors run in list order threading the result")
        void multipleInterceptorsRunInOrderThreadingResult() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));

            Future<Object> result = chain.chainAfterOperationInterceptors(opCtx, "val", 0);

            assertTrue(result.succeeded());
            // i1 transforms "val" -> "val+A"; i2 then sees "val+A" -> "val+A+B"
            assertEquals("val+A+B", result.result());
            assertEquals(List.of("after:A"), i1.calls);
            assertEquals(List.of("after:B"), i2.calls);
        }

        @Test
        @DisplayName("Failed afterOperation short-circuits: subsequent interceptors not invoked")
        void failedAfterShortCircuits() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.afterFails = true;
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));

            Future<Object> result = chain.chainAfterOperationInterceptors(opCtx, "val", 0);

            assertTrue(result.failed());
            assertEquals("after-fail:A", result.cause().getMessage());
            assertEquals(List.of("after:A"), i1.calls);
            assertTrue(i2.calls.isEmpty(), "B must not be invoked after A fails");
        }
    }

    // --- chainRecoverOperationInterceptors ---

    /**
     * Tests for {@link OperationInterceptorChain#chainRecoverOperationInterceptors}.
     */
    @Nested
    @DisplayName("chainRecoverOperationInterceptors")
    class ChainRecover {

        @Test
        @DisplayName("Empty list returns failed future with the seed cause")
        void emptyListReturnsSeedCause() {
            OperationInterceptorChain chain = chainOf(List.of());
            RuntimeException cause = new RuntimeException("seed");

            Future<Object> result = chain.chainRecoverOperationInterceptors(opCtx, cause, 0);

            assertTrue(result.failed());
            assertSame(cause, result.cause());
        }

        @Test
        @DisplayName("First interceptor that succeeds wins; later interceptors skipped")
        void firstSuccessfulRecoveryWins() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            i1.recoverSucceeds = true;
            i1.recoveryResult = "recovered-by-A";
            // i2 and i3 also configured to succeed, but must never be called
            i2.recoverSucceeds = true;
            i2.recoveryResult = "recovered-by-B";
            OperationInterceptorChain chain = chainOf(List.of(i1, i2, i3));
            RuntimeException cause = new RuntimeException("err");

            Future<Object> result = chain.chainRecoverOperationInterceptors(opCtx, cause, 0);

            assertTrue(result.succeeded());
            assertEquals("recovered-by-A", result.result());
            assertEquals(List.of("recover:A"), i1.calls);
            assertTrue(i2.calls.isEmpty(), "B must not be invoked once A succeeds");
            assertTrue(i3.calls.isEmpty(), "C must not be invoked once A succeeds");
        }

        @Test
        @DisplayName("A recoverer that itself fails threads its new failure to the next recoverer")
        void failingRecovererThreadsFailureToNext() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.recoverFails = true; // A replaces the cause with a new one
            i2.recoverSucceeds = true;
            i2.recoveryResult = "recovered-by-B";
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));
            RuntimeException seedCause = new RuntimeException("seed");

            Future<Object> result = chain.chainRecoverOperationInterceptors(opCtx, seedCause, 0);

            assertTrue(result.succeeded());
            assertEquals("recovered-by-B", result.result());
            assertEquals(List.of("recover:A"), i1.calls);
            assertEquals(List.of("recover:B"), i2.calls);
        }

        @Test
        @DisplayName("When no recoverer succeeds, latest failure propagates (not seed cause)")
        void noRecoveryPropagatesLatestFailure() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.recoverFails = true;
            i2.recoverFails = true;
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));
            RuntimeException seedCause = new RuntimeException("seed");

            Future<Object> result = chain.chainRecoverOperationInterceptors(opCtx, seedCause, 0);

            assertTrue(result.failed());
            // Latest failure is from B, not the seed
            assertEquals("recover-fail:B", result.cause().getMessage());
        }

        @Test
        @DisplayName("All interceptors decline recovery: last cause propagates")
        void allDeclineRecovery() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            // Default impl returns failedFuture(cause) — neither succeeds nor replaces
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));
            RuntimeException seedCause = new RuntimeException("seed");

            Future<Object> result = chain.chainRecoverOperationInterceptors(opCtx, seedCause, 0);

            assertTrue(result.failed());
            assertSame(seedCause, result.cause());
            assertEquals(List.of("recover:A"), i1.calls);
            assertEquals(List.of("recover:B"), i2.calls);
        }
    }

    // --- Sync observer: fireOnOperation ---

    /**
     * Tests for {@link OperationInterceptorChain#fireOnOperation}.
     */
    @Nested
    @DisplayName("fireOnOperation")
    class FireOnOperation {

        @Test
        @DisplayName("All interceptors invoked in list order")
        void allInvokedInOrder() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            OperationInterceptorChain chain = chainOf(List.of(i1, i2, i3));

            chain.fireOnOperation(opCtx);

            assertEquals(List.of("onOperation:A"), i1.calls);
            assertEquals(List.of("onOperation:B"), i2.calls);
            assertEquals(List.of("onOperation:C"), i3.calls);
        }

        @Test
        @DisplayName("A thrown exception is swallowed; later interceptors still run")
        void exceptionSwallowedLaterRunsStill() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            RecordingInterceptor i3 = new RecordingInterceptor("C");
            i1.onOperationThrows = true;
            OperationInterceptorChain chain = chainOf(List.of(i1, i2, i3));

            // Must not throw
            assertDoesNotThrow(() -> chain.fireOnOperation(opCtx));

            assertEquals(List.of("onOperation:A"), i1.calls);
            assertEquals(List.of("onOperation:B"), i2.calls);
            assertEquals(List.of("onOperation:C"), i3.calls);
        }

        @Test
        @DisplayName("Empty list: no-op")
        void emptyListNoOp() {
            OperationInterceptorChain chain = chainOf(List.of());
            assertDoesNotThrow(() -> chain.fireOnOperation(opCtx));
        }
    }

    // --- Sync observer: fireOnSuccess ---

    /**
     * Tests for {@link OperationInterceptorChain#fireOnSuccess}.
     */
    @Nested
    @DisplayName("fireOnSuccess")
    class FireOnSuccess {

        @Test
        @DisplayName("All interceptors invoked in list order")
        void allInvokedInOrder() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));

            chain.fireOnSuccess(opCtx, "result");

            assertEquals(List.of("onSuccess:A"), i1.calls);
            assertEquals(List.of("onSuccess:B"), i2.calls);
        }

        @Test
        @DisplayName("A thrown exception is swallowed; later interceptors still run")
        void exceptionSwallowedLaterRunsStill() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.onSuccessThrows = true;
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));

            assertDoesNotThrow(() -> chain.fireOnSuccess(opCtx, "result"));

            assertEquals(List.of("onSuccess:A"), i1.calls);
            assertEquals(List.of("onSuccess:B"), i2.calls);
        }
    }

    // --- Sync observer: fireOnError ---

    /**
     * Tests for {@link OperationInterceptorChain#fireOnError}.
     */
    @Nested
    @DisplayName("fireOnError")
    class FireOnError {

        @Test
        @DisplayName("All interceptors invoked in list order")
        void allInvokedInOrder() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));
            RuntimeException cause = new RuntimeException("err");

            chain.fireOnError(opCtx, cause);

            assertEquals(List.of("onError:A"), i1.calls);
            assertEquals(List.of("onError:B"), i2.calls);
        }

        @Test
        @DisplayName("A thrown exception is swallowed; later interceptors still run")
        void exceptionSwallowedLaterRunsStill() {
            RecordingInterceptor i1 = new RecordingInterceptor("A");
            RecordingInterceptor i2 = new RecordingInterceptor("B");
            i1.onErrorThrows = true;
            OperationInterceptorChain chain = chainOf(List.of(i1, i2));
            RuntimeException cause = new RuntimeException("err");

            assertDoesNotThrow(() -> chain.fireOnError(opCtx, cause));

            assertEquals(List.of("onError:A"), i1.calls);
            assertEquals(List.of("onError:B"), i2.calls);
        }
    }
}
