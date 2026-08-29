// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.dispatch.NonRecoverableDispatchFailure;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Characterization tests that pin the <em>current</em> interceptor-plumbing behavior of
 * {@link ServiceMethodInvoker} ahead of its migration to the combinator kernel.
 *
 * <p>These tests are behavior-preserving guardrails: they capture the exact observable contract of
 * {@code chainBeforeDispatch}, {@code fireAfterDispatch}, {@code chainRecoverError}, and the sync
 * observers ({@code fireOnDispatch} / {@code fireOnComplete} / {@code fireOnTerminalComplete}) so a
 * later refactor can prove zero behavior change. The invariants pinned here are:
 *
 * <ol>
 *   <li><b>before-chain ordering + short-circuit</b> — {@code beforeDispatch} runs interceptors in
 *       list order, threading the context; a failed future from interceptor {@code i} skips
 *       {@code i+1} and routes to the failure path
 *       ({@code ServiceMethodInvoker.chainBeforeDispatch}, lines 424–429).</li>
 *   <li><b>afterDispatch parallel-join / waits-for-all / swallow</b> — every interceptor's
 *       {@code afterDispatch} is invoked; a per-interceptor failure or a slow-completing future does
 *       not prevent the others, and the after-phase does not proceed until every future has settled;
 *       per-interceptor failures are swallowed and never fail the dispatch
 *       ({@code ServiceMethodInvoker.fireAfterDispatch}, lines 551–580).</li>
 *   <li><b>recoverError first-wins + NonRecoverable short-circuit</b> — {@code recoverError} is
 *       tried in order, the first interceptor that succeeds wins (later ones skipped), a recoverer's
 *       own failure threads to the next, and a {@link NonRecoverableDispatchFailure} bypasses the
 *       chain entirely ({@code ServiceMethodInvoker.chainRecoverError}, lines 446–454).</li>
 *   <li><b>sync observers in-order + swallow</b> — {@code onDispatch} / {@code onComplete} /
 *       {@code onTerminalComplete} fire for every interceptor in list order; an exception thrown by
 *       one is swallowed so later interceptors still run and the dispatch is unaffected
 *       ({@code fireOnDispatch} 463–475, {@code fireOnComplete} 485–497,
 *       {@code fireOnTerminalComplete} 508–521).</li>
 * </ol>
 *
 * <p>The harness mirrors {@link ServiceMethodInvokerTest}: register the invoker as an event bus
 * consumer, dispatch a {@link DispatchEnvelope}, and observe the reply plus interceptor-side
 * recording. Interceptor side effects are recorded into thread-safe lists so the invocation order is
 * asserted deterministically.
 */
@ExtendWith(VertxExtension.class)
class ServiceMethodInvokerCharacterizationTest {

    // --- Contract Fixture ---

    @ServiceContract(namespace = "test", value = "svc")
    interface TestService {
        @ServiceOperation("greet")
        Future<String> greet(String name);

        @ServiceOperation("failAlways")
        Future<String> failAlways(String input);
    }

    /** Default implementation: {@code greet} succeeds, {@code failAlways} fails with a recoverable error. */
    static class TestServiceImpl implements TestService {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }

        @Override
        public Future<String> failAlways(String input) {
            return Future.failedFuture(new RuntimeException("boom"));
        }
    }

    // --- Address counter for unique per-test addresses ---

    private static final AtomicInteger ADDRESS_COUNTER = new AtomicInteger(0);

    private static String uniqueAddress(String base) {
        return "char-invoker/" + base + "/" + ADDRESS_COUNTER.incrementAndGet();
    }

    // --- Setup ---

    private static final TestServiceImpl SERVICE_IMPL = new TestServiceImpl();

    @BeforeAll
    static void setup(Vertx vertx) {
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException e) {
            // Already registered — safe to ignore
        }
    }

    private static final DeliveryOptions BODY_OPTIONS = new DeliveryOptions().setCodecName("dispatch.envelope");

    // --- Meta Builders ---

    private ServiceMethodMeta greetMeta(Object impl, String address) throws Exception {
        Method method = TestService.class.getMethod("greet", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "svc",
                "greet",
                String.class,
                String.class,
                List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    private ServiceMethodMeta failAlwaysMeta(Object impl, String address) throws Exception {
        Method method = TestService.class.getMethod("failAlways", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                null,
                "test",
                "svc",
                "failAlways",
                String.class,
                String.class,
                List.of(new ParamMeta("input", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    private ServiceExceptionMapper emptyExceptionMapper() {
        return new ServiceExceptionMapper();
    }

    // --- Test doubles ---

    /**
     * Phase a {@link RecordingInterceptor} can be configured to fail or throw at.
     */
    enum Phase {
        /** {@code beforeDispatch} returns a failed future. */
        BEFORE,
        /** {@code afterDispatch} returns a failed future. */
        AFTER,
        /** {@code recoverError} returns a succeeded future (i.e. recovers). */
        RECOVER,
        /** {@code onDispatch} throws synchronously. */
        ON_DISPATCH,
        /** {@code onComplete} throws synchronously. */
        ON_COMPLETE,
        /** {@code onTerminalComplete} throws synchronously. */
        ON_TERMINAL
    }

    /**
     * A {@link ServiceInterceptor} that records the order in which each callback fires (by appending
     * its {@code id} to a shared event log) and can be configured to fail or throw at a chosen phase.
     *
     * <p>By default all callbacks are benign no-ops: {@code beforeDispatch} threads the context
     * through unchanged, {@code afterDispatch} succeeds, {@code recoverError} declines (default
     * SPI behaviour), and the sync observers record but do nothing else.
     */
    static class RecordingInterceptor implements ServiceInterceptor {
        private final String id;
        private final List<String> log;

        /** When set, {@code beforeDispatch} fails with this throwable instead of threading the context. */
        Throwable failBeforeWith;
        /** When set, {@code afterDispatch} fails with this throwable instead of succeeding. */
        Throwable failAfterWith;
        /** When {@code true}, {@code recoverError} returns a succeeded future (recovers the failure). */
        boolean recover;
        /** When set, {@code recoverError} fails with this throwable instead of declining with the inbound error. */
        Throwable recoverFailWith;
        /** Phase at which a sync observer throws synchronously, or {@code null} for none. */
        Phase throwSyncAt;

        RecordingInterceptor(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext ctx) {
            log.add("before:" + id);
            if (failBeforeWith != null) {
                return Future.failedFuture(failBeforeWith);
            }
            return Future.succeededFuture(ctx);
        }

        @Override
        public Future<Void> afterDispatch(ServiceDispatchContext ctx, Result<?> result) {
            log.add("after:" + id);
            if (failAfterWith != null) {
                return Future.failedFuture(failAfterWith);
            }
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> recoverError(ServiceDispatchContext ctx, Throwable error) {
            log.add("recover:" + id);
            if (recover) {
                return Future.succeededFuture();
            }
            if (recoverFailWith != null) {
                return Future.failedFuture(recoverFailWith);
            }
            return Future.failedFuture(error); // decline — thread the inbound error onward
        }

        @Override
        public void onDispatch(ServiceDispatchContext ctx) {
            log.add("onDispatch:" + id);
            if (throwSyncAt == Phase.ON_DISPATCH) {
                throw new RuntimeException("onDispatch boom:" + id);
            }
        }

        @Override
        public void onComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
            log.add("onComplete:" + id);
            if (throwSyncAt == Phase.ON_COMPLETE) {
                throw new RuntimeException("onComplete boom:" + id);
            }
        }

        @Override
        public void onTerminalComplete(
                ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
            log.add("onTerminalComplete:" + id);
            if (throwSyncAt == Phase.ON_TERMINAL) {
                throw new RuntimeException("onTerminalComplete boom:" + id);
            }
        }
    }

    /** A recoverable failure used to exercise the recover chain. */
    static final class RecoverableBoom extends RuntimeException {
        RecoverableBoom(String message) {
            super(message);
        }
    }

    /** A failure marked {@link NonRecoverableDispatchFailure} that must bypass the recover chain entirely. */
    static final class NonRecoverableBoom extends RuntimeException implements NonRecoverableDispatchFailure {
        NonRecoverableBoom(String message) {
            super(message);
        }
    }

    // --- 1. before-chain ordering + short-circuit ---

    @Nested
    @DisplayName("chainBeforeDispatch: list-order threading + first-failure short-circuit")
    class BeforeChain {

        @Test
        @DisplayName("beforeDispatch runs interceptors in list order before the method is invoked")
        void shouldRunBeforeDispatchInListOrder(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: three interceptors in a fixed list order, all benign
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            RecordingInterceptor c = new RecordingInterceptor("C", log);

            String address = uniqueAddress("before-order");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: beforeDispatch fired A, B, C in list order, all before the result
                        assertTrue(reply.body().isSuccess());
                        assertEquals(
                                List.of("before:A", "before:B", "before:C"),
                                onlyBefore(log),
                                "beforeDispatch must run in list order");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("a context enriched by an earlier beforeDispatch is threaded to the next interceptor")
        void shouldThreadEnrichedContextToNextInterceptor(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: interceptor A enriches the context; interceptor B reads the enriched attribute
            List<String> log = new CopyOnWriteArrayList<>();
            ServiceInterceptor enricher = new ServiceInterceptor() {
                @Override
                public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext c) {
                    return Future.succeededFuture(c.withAttribute("tag", "from-A"));
                }
            };
            ServiceInterceptor reader = new ServiceInterceptor() {
                @Override
                public Future<ServiceDispatchContext> beforeDispatch(ServiceDispatchContext c) {
                    log.add("seen:" + c.attributes().get("tag"));
                    return Future.succeededFuture(c);
                }
            };

            String address = uniqueAddress("before-thread");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(enricher, reader), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: B observed the attribute A added — the context was threaded
                        assertTrue(reply.body().isSuccess());
                        assertEquals(
                                List.of("seen:from-A"), log, "the enriched context must reach the next interceptor");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("a beforeDispatch failure skips the next interceptor and the method, routing to the failure path")
        void shouldShortCircuitOnFirstBeforeFailure(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: A fails in beforeDispatch; B must never run; the method must never run
            List<String> log = new CopyOnWriteArrayList<>();
            AtomicBoolean methodCalled = new AtomicBoolean(false);
            TestService impl = new TestServiceImpl() {
                @Override
                public Future<String> greet(String name) {
                    methodCalled.set(true);
                    return Future.succeededFuture("Hello " + name);
                }
            };
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            a.failBeforeWith = new RuntimeException("A rejected");
            RecordingInterceptor b = new RecordingInterceptor("B", log);

            String address = uniqueAddress("before-shortcircuit");
            ServiceMethodMeta meta = greetMeta(impl, address);
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: B's beforeDispatch never ran, the method never ran, and the result is a failure
                        assertEquals(List.of("before:A"), onlyBefore(log), "B.beforeDispatch must be skipped");
                        assertFalse(methodCalled.get(), "the method must not be invoked after a before-chain failure");
                        assertTrue(reply.body().isFailure(), "the dispatch must take the failure path");
                        ctx.completeNow();
                    })));
        }

        private List<String> onlyBefore(List<String> log) {
            return log.stream().filter(e -> e.startsWith("before:")).toList();
        }
    }

    // --- 2. afterDispatch parallel-join + waits-for-all + swallow ---

    @Nested
    @DisplayName("fireAfterDispatch: all invoked, waits for all to settle, failures swallowed")
    class AfterChain {

        @Test
        @DisplayName("every afterDispatch is invoked even when one fails, and the reply stays a success")
        void shouldInvokeAllAfterDispatchAndSwallowFailure(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: three interceptors; B's afterDispatch fails — A and C must still run
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            b.failAfterWith = new RuntimeException("after B failed — must be swallowed");
            RecordingInterceptor c = new RecordingInterceptor("C", log);

            String address = uniqueAddress("after-swallow");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: all three afterDispatch callbacks fired and the reply is still success
                        List<String> afters = log.stream()
                                .filter(e -> e.startsWith("after:"))
                                .sorted()
                                .toList();
                        assertEquals(
                                List.of("after:A", "after:B", "after:C"),
                                afters,
                                "every afterDispatch must be invoked despite one failing");
                        assertTrue(reply.body().isSuccess(), "an afterDispatch failure must not fail the dispatch");
                        assertEquals("Hello World", reply.body().get());
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("the after-phase does not proceed until a slow afterDispatch future settles (waits-for-all)")
        void shouldWaitForAllAfterDispatchFuturesToSettle(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: interceptor SLOW's afterDispatch returns a manually-completed Promise; the
            //        onTerminalComplete observer marks the after-phase boundary (the invoker only
            //        fires onTerminalComplete once afterDispatch + reply/recovery have both settled).
            List<String> log = new CopyOnWriteArrayList<>();
            Promise<Void> gate = Promise.promise();
            AtomicBoolean terminalFired = new AtomicBoolean(false);
            Promise<Void> terminalSeen = Promise.promise();

            ServiceInterceptor slow = new ServiceInterceptor() {
                @Override
                public Future<Void> afterDispatch(ServiceDispatchContext c, Result<?> result) {
                    log.add("after:SLOW");
                    return gate.future(); // does not settle until the test completes the gate
                }

                @Override
                public void onTerminalComplete(
                        ServiceDispatchContext c, Result<?> result, Instant startTime, Instant endTime) {
                    terminalFired.set(true);
                    terminalSeen.tryComplete();
                }
            };

            String address = uniqueAddress("after-waits");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(slow), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched and we wait for the reply (which arrives before afterDispatch settles)
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> {
                        // The reply has arrived but afterDispatch has not settled — onTerminalComplete
                        // must NOT have fired yet (the after-phase is still waiting for the slow future).
                        ctx.verify(() -> {
                            assertTrue(reply.body().isSuccess());
                            assertTrue(log.contains("after:SLOW"), "the slow afterDispatch must have started");
                            assertFalse(
                                    terminalFired.get(),
                                    "onTerminalComplete must NOT fire until the slow afterDispatch settles");
                        });
                        // Now settle the gate on the Vert.x context after a tick, then assert the
                        // after-phase proceeded (onTerminalComplete fired).
                        vertx.runOnContext(ignored -> {
                            gate.complete();
                            terminalSeen
                                    .future()
                                    .onComplete(ctx.succeeding(done -> ctx.verify(() -> {
                                        assertTrue(
                                                terminalFired.get(),
                                                "onTerminalComplete must fire once the slow afterDispatch settles");
                                        ctx.completeNow();
                                    })));
                        });
                    }));
        }
    }

    // --- 3. recoverError first-wins + NonRecoverable short-circuit ---

    @Nested
    @DisplayName("chainRecoverError: first-success-wins, decline-threads-onward, NonRecoverable bypasses")
    class RecoverChain {

        @Test
        @DisplayName("recoverError is tried in order; the first succeeding interceptor wins and skips the rest")
        void shouldStopAtFirstRecoveringInterceptor(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: A declines, B recovers, C must never be tried
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log); // declines (default)
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            b.recover = true; // recovers
            RecordingInterceptor c = new RecordingInterceptor("C", log); // must not be reached

            String address = uniqueAddress("recover-firstwins");
            ServiceMethodMeta meta = failAlwaysMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a recoverable handler failure is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: A then B were tried, C was skipped, and the dispatch reports a recovered success
                        assertEquals(
                                List.of("recover:A", "recover:B"),
                                onlyRecover(log),
                                "recoverError must stop at the first succeeding interceptor");
                        assertTrue(reply.body().isSuccess(), "a recovered failure replies as success(null)");
                        assertNull(reply.body().get(), "the recovered reply carries a null value");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("a recoverError that fails threads its own failure to the next interceptor")
        void shouldThreadRecovererFailureToNext(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: A fails its recoverError with a new throwable; B then recovers
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            a.recoverFailWith = new RuntimeException("A's recover failed");
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            b.recover = true;

            String address = uniqueAddress("recover-threads");
            ServiceMethodMeta meta = failAlwaysMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a recoverable handler failure is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: A then B were tried (A's failure threaded onward) and B recovered
                        assertEquals(
                                List.of("recover:A", "recover:B"),
                                onlyRecover(log),
                                "a recoverer's own failure must thread to the next interceptor");
                        assertTrue(reply.body().isSuccess(), "B's recovery makes the dispatch a success");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("when no interceptor recovers, the original failure propagates")
        void shouldPropagateWhenNoInterceptorRecovers(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: both A and B decline recovery
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            RecordingInterceptor b = new RecordingInterceptor("B", log);

            String address = uniqueAddress("recover-none");
            ServiceMethodMeta meta = failAlwaysMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a recoverable handler failure is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: both were tried and the original failure propagated unrecovered
                        assertEquals(
                                List.of("recover:A", "recover:B"),
                                onlyRecover(log),
                                "every declining interceptor is tried in order");
                        assertTrue(reply.body().isFailure(), "an unrecovered failure propagates");
                        assertInstanceOf(RuntimeException.class, reply.body().cause());
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("a NonRecoverableDispatchFailure bypasses the recover chain and propagates unchanged")
        void shouldBypassRecoverChainForNonRecoverableFailure(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: the handler fails with a NonRecoverableDispatchFailure; a recovering interceptor
            //        is present but must NEVER be invoked.
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            a.recover = true; // would recover anything it is asked to recover

            NonRecoverableBoom nonRecoverable = new NonRecoverableBoom("deny — must not be recovered");
            TestService denyingImpl = new TestServiceImpl() {
                @Override
                public Future<String> failAlways(String input) {
                    return Future.failedFuture(nonRecoverable);
                }
            };

            String address = uniqueAddress("recover-nonrecoverable");
            ServiceMethodMeta meta = failAlwaysMeta(denyingImpl, address);
            ServiceMethodInvoker invoker = new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a), null);
            vertx.eventBus().consumer(address, invoker);

            // When: the non-recoverable failure is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("x"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: no recoverError was invoked and the marked failure propagated unchanged
                        assertTrue(onlyRecover(log).isEmpty(), "recoverError must not be invoked for a marked failure");
                        assertTrue(reply.body().isFailure(), "a NonRecoverableDispatchFailure must propagate");
                        assertSame(
                                nonRecoverable,
                                reply.body().cause(),
                                "the marked failure must propagate unchanged (no recovery, no mapping)");
                        ctx.completeNow();
                    })));
        }

        private List<String> onlyRecover(List<String> log) {
            return log.stream().filter(e -> e.startsWith("recover:")).toList();
        }
    }

    // --- 4. sync observers in-order + swallow ---

    @Nested
    @DisplayName("sync observers: onDispatch / onComplete / onTerminalComplete fire in order, throws swallowed")
    class SyncObservers {

        @Test
        @DisplayName("onDispatch fires for every interceptor in list order")
        void shouldFireOnDispatchInListOrder(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: three benign interceptors
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            RecordingInterceptor c = new RecordingInterceptor("C", log);

            String address = uniqueAddress("ondispatch-order");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: onDispatch fired A, B, C in list order
                        assertEquals(
                                List.of("onDispatch:A", "onDispatch:B", "onDispatch:C"),
                                only(log, "onDispatch:"),
                                "onDispatch must fire in list order");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("a throwing onDispatch is swallowed; later observers still fire and the dispatch succeeds")
        void shouldSwallowThrowingOnDispatch(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: A throws in onDispatch; B and C must still fire onDispatch
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            a.throwSyncAt = Phase.ON_DISPATCH;
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            RecordingInterceptor c = new RecordingInterceptor("C", log);

            String address = uniqueAddress("ondispatch-throw");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: all three onDispatch ran (A's throw swallowed) and the dispatch succeeded
                        assertEquals(
                                List.of("onDispatch:A", "onDispatch:B", "onDispatch:C"),
                                only(log, "onDispatch:"),
                                "a throwing onDispatch must be swallowed so later observers still run");
                        assertTrue(reply.body().isSuccess(), "a throwing onDispatch must not affect the dispatch");
                        assertEquals("Hello World", reply.body().get());
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("onComplete fires for every interceptor in list order, and a throw is swallowed")
        void shouldFireOnCompleteInOrderAndSwallowThrow(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: A throws in onComplete; B and C must still fire onComplete
            List<String> log = new CopyOnWriteArrayList<>();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            a.throwSyncAt = Phase.ON_COMPLETE;
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            RecordingInterceptor c = new RecordingInterceptor("C", log);

            String address = uniqueAddress("oncomplete-throw");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: all three onComplete ran in order (A's throw swallowed) and the dispatch succeeded
                        assertEquals(
                                List.of("onComplete:A", "onComplete:B", "onComplete:C"),
                                only(log, "onComplete:"),
                                "onComplete must fire in list order and a throw must be swallowed");
                        assertTrue(reply.body().isSuccess(), "a throwing onComplete must not affect the dispatch");
                        ctx.completeNow();
                    })));
        }

        @Test
        @DisplayName("onTerminalComplete fires for every interceptor in list order, and a throw is swallowed")
        void shouldFireOnTerminalCompleteInOrderAndSwallowThrow(Vertx vertx, VertxTestContext ctx) throws Exception {
            // Given: A throws in onTerminalComplete; B and C must still fire onTerminalComplete. A
            //        Promise on C captures the boundary so the assertion runs after all three fired.
            List<String> log = new CopyOnWriteArrayList<>();
            Promise<Void> terminalSeen = Promise.promise();
            RecordingInterceptor a = new RecordingInterceptor("A", log);
            a.throwSyncAt = Phase.ON_TERMINAL;
            RecordingInterceptor b = new RecordingInterceptor("B", log);
            ServiceInterceptor c = new ServiceInterceptor() {
                @Override
                public void onTerminalComplete(
                        ServiceDispatchContext dispatchCtx, Result<?> result, Instant startTime, Instant endTime) {
                    log.add("onTerminalComplete:C");
                    terminalSeen.tryComplete();
                }
            };

            String address = uniqueAddress("onterminal-throw");
            ServiceMethodMeta meta = greetMeta(SERVICE_IMPL, address);
            ServiceMethodInvoker invoker =
                    new ServiceMethodInvoker(meta, emptyExceptionMapper(), List.of(a, b, c), null);
            vertx.eventBus().consumer(address, invoker);

            // When: a request is dispatched and we await the terminal boundary
            vertx.eventBus()
                    .<Result<?>>request(address, DispatchEnvelope.of("World"), BODY_OPTIONS)
                    .compose(reply -> terminalSeen.future().map(reply))
                    .onComplete(ctx.succeeding(reply -> ctx.verify(() -> {
                        // Then: all three onTerminalComplete ran in order (A's throw swallowed) and the reply succeeded
                        assertEquals(
                                List.of("onTerminalComplete:A", "onTerminalComplete:B", "onTerminalComplete:C"),
                                only(log, "onTerminalComplete:"),
                                "onTerminalComplete must fire in list order and a throw must be swallowed");
                        assertTrue(reply.body().isSuccess(), "a throwing onTerminalComplete must not affect the reply");
                        ctx.completeNow();
                    })));
        }

        private List<String> only(List<String> log, String prefix) {
            return log.stream().filter(e -> e.startsWith(prefix)).toList();
        }
    }
}
