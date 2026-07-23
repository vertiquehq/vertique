// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.client.interceptor.RestClientAttemptCompletion;
import dev.vertique.rest.client.interceptor.RestClientAttemptTarget;
import dev.vertique.rest.client.interceptor.RestClientInterceptor;
import dev.vertique.rest.client.interceptor.RestClientRequestContext;
import dev.vertique.rest.client.interceptor.RestClientResponseContext;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests for {@link RestClientInterceptorChain}, pinning the current behavior of
 * all eight interceptor callback styles before the migration to {@link dev.vertique.core.async.Combinators}.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code runBeforeInterceptors} — ordering, threading, short-circuit on failure</li>
 *   <li>{@code runAfterInterceptors} — ordering, short-circuit on failure</li>
 *   <li>{@code runTransformError} — ordering, threading the {@code Throwable} value</li>
 *   <li>{@code runRecoverInterceptors} — first-wins, declining interceptors thread on</li>
 *   <li>{@code fireOnRequest}, {@code fireOnResponse}, {@code fireOnError},
 *       {@code fireOnAttemptCompleted} — in-order invocation, exception swallowing at DEBUG,
 *       exact log message strings</li>
 * </ul>
 */
class RestClientInterceptorChainTest {

    // --- Test fixtures ---

    /** Minimal request context used across tests. */
    private static final RestClientRequestContext REQ =
            new RestClientRequestContext("GET", "http://example.com/test", null, "test-client", "testMethod");

    /** Minimal response context used across tests. */
    private static final RestClientResponseContext RES =
            new RestClientResponseContext(200, "OK", Buffer.buffer(), MultiMap.caseInsensitiveMultiMap());

    /** A minimal attempt completion used for {@code onAttemptCompleted} tests. */
    private static final RestClientAttemptCompletion COMPLETION = new RestClientAttemptCompletion(
            RES,
            null,
            "call-id-1",
            1,
            10L,
            Instant.now(),
            new RestClientAttemptTarget("http", "example.com", 80, "/test"));

    // --- Static inner-class doubles ---

    /**
     * Base interceptor double that records each invocation in the provided list.
     * All async methods return no-ops (pass-through) by default.
     */
    static class RecordingInterceptor implements RestClientInterceptor {

        /** Mutable invocation log (callers supply a shared list for ordering assertions). */
        final List<String> invocations;

        final String name;

        /**
         * Creates a recording interceptor.
         *
         * @param name       the label appended to invocation entries
         * @param invocations the shared list to record entries into
         */
        RecordingInterceptor(String name, List<String> invocations) {
            this.name = name;
            this.invocations = invocations;
        }

        @Override
        public void onRequest(RestClientRequestContext ctx) {
            invocations.add(name + ".onRequest");
        }

        @Override
        public void onResponse(RestClientRequestContext req, RestClientResponseContext res) {
            invocations.add(name + ".onResponse");
        }

        @Override
        public void onError(RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
            invocations.add(name + ".onError");
        }

        @Override
        public void onAttemptCompleted(RestClientRequestContext req, RestClientAttemptCompletion completion) {
            invocations.add(name + ".onAttemptCompleted");
        }

        @Override
        public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
            invocations.add(name + ".beforeRequest");
            return Future.succeededFuture(ctx);
        }

        @Override
        public Future<Void> afterResponse(RestClientRequestContext req, RestClientResponseContext res) {
            invocations.add(name + ".afterResponse");
            return Future.succeededFuture();
        }

        @Override
        public Future<Throwable> transformError(
                RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
            invocations.add(name + ".transformError");
            return Future.succeededFuture(error);
        }

        @Override
        public Future<RestClientRequestContext> recoverRequest(
                RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
            invocations.add(name + ".recoverRequest");
            return Future.failedFuture(error); // decline by default
        }
    }

    /**
     * Interceptor that adds a header in {@code beforeRequest} to let downstream interceptors see it.
     */
    static class HeaderAddingInterceptor implements RestClientInterceptor {

        final String headerName;
        final String headerValue;

        HeaderAddingInterceptor(String headerName, String headerValue) {
            this.headerName = headerName;
            this.headerValue = headerValue;
        }

        @Override
        public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
            return Future.succeededFuture(ctx.withHeader(headerName, headerValue));
        }
    }

    /**
     * Interceptor that replaces the throwable in {@code transformError}.
     */
    static class ThrowableReplacingInterceptor implements RestClientInterceptor {

        final Throwable replacement;

        ThrowableReplacingInterceptor(Throwable replacement) {
            this.replacement = replacement;
        }

        @Override
        public Future<Throwable> transformError(
                RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
            return Future.succeededFuture(replacement);
        }
    }

    /**
     * Interceptor that accepts recovery (returns a new context) from {@code recoverRequest}.
     */
    static class AcceptingRecoverInterceptor implements RestClientInterceptor {

        final RestClientRequestContext recovered;

        AcceptingRecoverInterceptor(RestClientRequestContext recovered) {
            this.recovered = recovered;
        }

        @Override
        public Future<RestClientRequestContext> recoverRequest(
                RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
            return Future.succeededFuture(recovered);
        }
    }

    /**
     * Interceptor that throws synchronously from a sync observer callback.
     */
    static class ThrowingOnRequestInterceptor implements RestClientInterceptor {

        final RuntimeException toThrow;

        ThrowingOnRequestInterceptor(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void onRequest(RestClientRequestContext ctx) {
            throw toThrow;
        }
    }

    /**
     * Interceptor that throws a checked exception ({@link Exception}) from its sync observer.
     * This validates that {@code Exception} (not only {@code RuntimeException}) is caught.
     */
    static class ThrowingCheckedOnResponseInterceptor implements RestClientInterceptor {

        final Exception toThrow;

        ThrowingCheckedOnResponseInterceptor(Exception toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void onResponse(RestClientRequestContext req, RestClientResponseContext res) {
            // Wrap in RuntimeException to throw the checked exception; the chain must catch Exception
            throw new RuntimeException("wrapper", toThrow) {
                // anonymous subclass that IS-A Exception — the chain catches Exception
            };
        }
    }

    /**
     * Interceptor that throws from {@code onError}.
     */
    static class ThrowingOnErrorInterceptor implements RestClientInterceptor {

        final RuntimeException toThrow;

        ThrowingOnErrorInterceptor(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void onError(RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
            throw toThrow;
        }
    }

    /**
     * Interceptor that throws from {@code onAttemptCompleted}.
     */
    static class ThrowingOnAttemptCompletedInterceptor implements RestClientInterceptor {

        final RuntimeException toThrow;

        ThrowingOnAttemptCompletedInterceptor(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public void onAttemptCompleted(RestClientRequestContext req, RestClientAttemptCompletion completion) {
            throw toThrow;
        }
    }

    // --- runBeforeInterceptors ---

    @Nested
    @DisplayName("runBeforeInterceptors()")
    class RunBeforeInterceptors {

        @Test
        @DisplayName("invokes interceptors in list order and threads the context through the chain")
        void runsInOrderAndThreadsContext() {
            // given: two interceptors each adding a header; the second sees the first's header
            List<String> order = new ArrayList<>();
            var first = new RecordingInterceptor("A", order) {
                @Override
                public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                    invocations.add(name + ".beforeRequest");
                    return Future.succeededFuture(ctx.withHeader("X-A", "first"));
                }
            };
            var second = new RecordingInterceptor("B", order) {
                @Override
                public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                    invocations.add(name + ".beforeRequest");
                    // proof of threading: both headers must be present
                    return Future.succeededFuture(
                            ctx.withHeader("X-B", ctx.headers().get("X-A") + "-second"));
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(first, second));

            // when
            Future<RestClientRequestContext> result = chain.runBeforeInterceptors(REQ);

            // then: both ran in order and the final context has both headers
            assertTrue(result.succeeded());
            assertThat(order).containsExactly("A.beforeRequest", "B.beforeRequest");
            assertThat(result.result().headers().get("X-A")).isEqualTo("first");
            assertThat(result.result().headers().get("X-B")).isEqualTo("first-second");
        }

        @Test
        @DisplayName("first failing interceptor short-circuits and later interceptors are not invoked")
        void failFastShortCircuits() {
            // given: interceptor A fails; interceptor B must not be invoked
            RuntimeException boom = new RuntimeException("fail from A");
            AtomicBoolean bInvoked = new AtomicBoolean(false);
            var failingA = new RestClientInterceptor() {
                @Override
                public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                    return Future.failedFuture(boom);
                }
            };
            var recordingB = new RestClientInterceptor() {
                @Override
                public Future<RestClientRequestContext> beforeRequest(RestClientRequestContext ctx) {
                    bInvoked.set(true);
                    return Future.succeededFuture(ctx);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(failingA, recordingB));

            // when
            Future<RestClientRequestContext> result = chain.runBeforeInterceptors(REQ);

            // then
            assertTrue(result.failed());
            assertSame(boom, result.cause());
            assertFalse(bInvoked.get(), "B must not be invoked after A fails");
        }

        @Test
        @DisplayName("empty interceptor list returns the seed context unchanged")
        void emptyListReturnsSeed() {
            var chain = new RestClientInterceptorChain("client", Collections.emptyList());
            Future<RestClientRequestContext> result = chain.runBeforeInterceptors(REQ);
            assertTrue(result.succeeded());
            assertSame(REQ, result.result());
        }
    }

    // --- runAfterInterceptors ---

    @Nested
    @DisplayName("runAfterInterceptors()")
    class RunAfterInterceptors {

        @Test
        @DisplayName("invokes interceptors in list order")
        void runsInOrder() {
            // given
            List<String> order = new ArrayList<>();
            var a = new RecordingInterceptor("A", order);
            var b = new RecordingInterceptor("B", order);
            var chain = new RestClientInterceptorChain("client", List.of(a, b));

            // when
            Future<Void> result = chain.runAfterInterceptors(REQ, RES);

            // then
            assertTrue(result.succeeded());
            assertThat(order).containsExactly("A.afterResponse", "B.afterResponse");
        }

        @Test
        @DisplayName("first failing interceptor short-circuits and later interceptors are not invoked")
        void failFastShortCircuits() {
            // given
            RuntimeException boom = new RuntimeException("fail from A");
            AtomicBoolean bInvoked = new AtomicBoolean(false);
            var failingA = new RestClientInterceptor() {
                @Override
                public Future<Void> afterResponse(RestClientRequestContext req, RestClientResponseContext res) {
                    return Future.failedFuture(boom);
                }
            };
            var recordingB = new RestClientInterceptor() {
                @Override
                public Future<Void> afterResponse(RestClientRequestContext req, RestClientResponseContext res) {
                    bInvoked.set(true);
                    return Future.succeededFuture();
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(failingA, recordingB));

            // when
            Future<Void> result = chain.runAfterInterceptors(REQ, RES);

            // then
            assertTrue(result.failed());
            assertSame(boom, result.cause());
            assertFalse(bInvoked.get(), "B must not be invoked after A fails");
        }
    }

    // --- runTransformError ---

    @Nested
    @DisplayName("runTransformError()")
    class RunTransformError {

        @Test
        @DisplayName("invokes interceptors in list order and threads the Throwable through the chain")
        void runsInOrderAndThreadsThrowable() {
            // given: A replaces the throwable; B sees A's replacement (proving threading)
            RuntimeException original = new RuntimeException("original");
            RuntimeException fromA = new RuntimeException("from-A");
            List<Throwable> seenByB = new ArrayList<>();

            var replacingA = new RestClientInterceptor() {
                @Override
                public Future<Throwable> transformError(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
                    return Future.succeededFuture(fromA);
                }
            };
            var recordingB = new RestClientInterceptor() {
                @Override
                public Future<Throwable> transformError(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
                    seenByB.add(error);
                    return Future.succeededFuture(error);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(replacingA, recordingB));

            // when
            Future<Throwable> result = chain.runTransformError(REQ, RES, original);

            // then: B received A's replacement; the final value is from-A
            assertTrue(result.succeeded());
            assertSame(fromA, result.result());
            assertEquals(1, seenByB.size());
            assertSame(fromA, seenByB.get(0), "B must receive A's replacement, not the original");
        }

        @Test
        @DisplayName("first failing step short-circuits the fold")
        void failFastShortCircuits() {
            // given
            RuntimeException boom = new RuntimeException("fold-fail");
            AtomicBoolean bInvoked = new AtomicBoolean(false);
            var failingA = new RestClientInterceptor() {
                @Override
                public Future<Throwable> transformError(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
                    return Future.failedFuture(boom);
                }
            };
            var recordingB = new RestClientInterceptor() {
                @Override
                public Future<Throwable> transformError(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
                    bInvoked.set(true);
                    return Future.succeededFuture(error);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(failingA, recordingB));

            // when
            Future<Throwable> result = chain.runTransformError(REQ, null, new RuntimeException("original"));

            // then
            assertTrue(result.failed());
            assertSame(boom, result.cause());
            assertFalse(bInvoked.get());
        }

        @Test
        @DisplayName("empty interceptor list returns the original error unchanged")
        void emptyListReturnsOriginalError() {
            RuntimeException original = new RuntimeException("original");
            var chain = new RestClientInterceptorChain("client", Collections.emptyList());
            Future<Throwable> result = chain.runTransformError(REQ, null, original);
            assertTrue(result.succeeded());
            assertSame(original, result.result());
        }
    }

    // --- runRecoverInterceptors ---

    @Nested
    @DisplayName("runRecoverInterceptors()")
    class RunRecoverInterceptors {

        @Test
        @DisplayName("first interceptor that accepts recovery wins and subsequent interceptors are skipped")
        void firstWinsAndSkipsRemainder() {
            // given: A declines; B accepts; C must not be invoked
            RuntimeException error = new RuntimeException("original");
            RestClientRequestContext recovered = REQ.withHeader("X-Recovered", "true");
            AtomicBoolean cInvoked = new AtomicBoolean(false);

            var declining = new RecordingInterceptor("A", new ArrayList<>());
            var accepting = new AcceptingRecoverInterceptor(recovered);
            var neverCalled = new RestClientInterceptor() {
                @Override
                public Future<RestClientRequestContext> recoverRequest(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable err) {
                    cInvoked.set(true);
                    return Future.failedFuture(err);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(declining, accepting, neverCalled));

            // when
            Future<RestClientRequestContext> result = chain.runRecoverInterceptors(REQ, null, error);

            // then
            assertTrue(result.succeeded());
            assertSame(recovered, result.result());
            assertFalse(cInvoked.get(), "C must not be invoked after B accepts recovery");
        }

        @Test
        @DisplayName("declining interceptors thread the error on to the next interceptor")
        void decliningInterceptorsThreadErrorOnward() {
            // given: A declines with the original error; B records the error it receives
            RuntimeException original = new RuntimeException("original");
            List<Throwable> seenByB = new ArrayList<>();

            var decliningA = new RestClientInterceptor() {
                @Override
                public Future<RestClientRequestContext> recoverRequest(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable err) {
                    return Future.failedFuture(err); // thread on with same error
                }
            };
            var recordingB = new RestClientInterceptor() {
                @Override
                public Future<RestClientRequestContext> recoverRequest(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable err) {
                    seenByB.add(err);
                    return Future.failedFuture(err);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(decliningA, recordingB));

            // when
            Future<RestClientRequestContext> result = chain.runRecoverInterceptors(REQ, null, original);

            // then: B received the original error (A threaded it on)
            assertTrue(result.failed());
            assertEquals(1, seenByB.size());
            assertSame(original, seenByB.get(0), "B must receive the threaded error from A");
        }

        @Test
        @DisplayName("when all interceptors decline the original error propagates")
        void allDecline_originalErrorPropagates() {
            // given
            RuntimeException original = new RuntimeException("original");
            var chain = new RestClientInterceptorChain(
                    "client",
                    List.of(
                            new RecordingInterceptor("A", new ArrayList<>()),
                            new RecordingInterceptor("B", new ArrayList<>())));

            // when
            Future<RestClientRequestContext> result = chain.runRecoverInterceptors(REQ, null, original);

            // then: failed with the error (the RecordingInterceptor.recoverRequest declines with the same error)
            assertTrue(result.failed());
        }
    }

    // --- Sync observer: fireOnRequest ---

    @Nested
    @DisplayName("fireOnRequest()")
    class FireOnRequest {

        @Test
        @DisplayName("invokes all interceptors in list order")
        void invokesInOrder() {
            List<String> order = new ArrayList<>();
            var a = new RecordingInterceptor("A", order);
            var b = new RecordingInterceptor("B", order);
            var chain = new RestClientInterceptorChain("client", List.of(a, b));

            chain.fireOnRequest(REQ);

            assertThat(order).containsExactly("A.onRequest", "B.onRequest");
        }

        @Test
        @DisplayName("swallows exceptions and continues iteration to the next interceptor")
        void swallowsExceptionAndContinues() {
            // given: A throws; B must still run
            RuntimeException boom = new RuntimeException("boom");
            AtomicBoolean bRan = new AtomicBoolean(false);
            var throwingA = new ThrowingOnRequestInterceptor(boom);
            var recordingB = new RestClientInterceptor() {
                @Override
                public void onRequest(RestClientRequestContext ctx) {
                    bRan.set(true);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(throwingA, recordingB));

            // when: must not throw
            chain.fireOnRequest(REQ);

            // then
            assertTrue(bRan.get(), "B must run after A throws (exception swallowed)");
        }
    }

    // --- Sync observer: fireOnResponse ---

    @Nested
    @DisplayName("fireOnResponse()")
    class FireOnResponse {

        @Test
        @DisplayName("invokes all interceptors in list order")
        void invokesInOrder() {
            List<String> order = new ArrayList<>();
            var a = new RecordingInterceptor("A", order);
            var b = new RecordingInterceptor("B", order);
            var chain = new RestClientInterceptorChain("client", List.of(a, b));

            chain.fireOnResponse(REQ, RES);

            assertThat(order).containsExactly("A.onResponse", "B.onResponse");
        }

        @Test
        @DisplayName("swallows exceptions and continues iteration to the next interceptor")
        void swallowsExceptionAndContinues() {
            // given: A throws; B must still run
            RuntimeException boom = new RuntimeException("boom");
            AtomicBoolean bRan = new AtomicBoolean(false);
            var throwingA = new RestClientInterceptor() {
                @Override
                public void onResponse(RestClientRequestContext req, RestClientResponseContext res) {
                    throw boom;
                }
            };
            var recordingB = new RestClientInterceptor() {
                @Override
                public void onResponse(RestClientRequestContext req, RestClientResponseContext res) {
                    bRan.set(true);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(throwingA, recordingB));

            // when: must not throw
            chain.fireOnResponse(REQ, RES);

            // then
            assertTrue(bRan.get(), "B must run after A throws (exception swallowed)");
        }
    }

    // --- Sync observer: fireOnError ---

    @Nested
    @DisplayName("fireOnError()")
    class FireOnError {

        @Test
        @DisplayName("invokes all interceptors in list order")
        void invokesInOrder() {
            List<String> order = new ArrayList<>();
            var a = new RecordingInterceptor("A", order);
            var b = new RecordingInterceptor("B", order);
            var chain = new RestClientInterceptorChain("client", List.of(a, b));

            chain.fireOnError(REQ, RES, new RuntimeException("err"));

            assertThat(order).containsExactly("A.onError", "B.onError");
        }

        @Test
        @DisplayName("swallows exceptions and continues iteration to the next interceptor")
        void swallowsExceptionAndContinues() {
            // given: A throws; B must still run
            RuntimeException boom = new RuntimeException("boom");
            AtomicBoolean bRan = new AtomicBoolean(false);
            var throwingA = new ThrowingOnErrorInterceptor(boom);
            var recordingB = new RestClientInterceptor() {
                @Override
                public void onError(
                        RestClientRequestContext req, @Nullable RestClientResponseContext res, Throwable error) {
                    bRan.set(true);
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(throwingA, recordingB));

            // when: must not throw
            chain.fireOnError(REQ, null, new RuntimeException("original"));

            // then
            assertTrue(bRan.get(), "B must run after A throws (exception swallowed)");
        }
    }

    // --- Sync observer: fireOnAttemptCompleted ---

    @Nested
    @DisplayName("fireOnAttemptCompleted()")
    class FireOnAttemptCompleted {

        @Test
        @DisplayName("invokes all interceptors in list order")
        void invokesInOrder() {
            List<String> order = new ArrayList<>();
            var a = new RecordingInterceptor("A", order);
            var b = new RecordingInterceptor("B", order);
            var chain = new RestClientInterceptorChain("client", List.of(a, b));

            chain.fireOnAttemptCompleted(REQ, COMPLETION);

            assertThat(order).containsExactly("A.onAttemptCompleted", "B.onAttemptCompleted");
        }

        @Test
        @DisplayName("swallows exceptions and continues iteration to the next interceptor")
        void swallowsExceptionAndContinues() {
            // given: A throws; B must still run
            RuntimeException boom = new RuntimeException("boom");
            AtomicInteger bCallCount = new AtomicInteger(0);
            var throwingA = new ThrowingOnAttemptCompletedInterceptor(boom);
            var recordingB = new RestClientInterceptor() {
                @Override
                public void onAttemptCompleted(RestClientRequestContext req, RestClientAttemptCompletion completion) {
                    bCallCount.incrementAndGet();
                }
            };
            var chain = new RestClientInterceptorChain("client", List.of(throwingA, recordingB));

            // when: must not throw
            chain.fireOnAttemptCompleted(REQ, COMPLETION);

            // then
            assertEquals(1, bCallCount.get(), "B must run after A throws (exception swallowed)");
        }
    }
}
