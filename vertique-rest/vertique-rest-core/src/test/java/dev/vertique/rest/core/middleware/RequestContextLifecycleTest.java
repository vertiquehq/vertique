// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.correlation.CorrelationContextMutator;
import dev.vertique.rest.core.correlation.CorrelationIngressConfig;
import dev.vertique.rest.core.correlation.CorrelationIngressMiddleware;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link RequestContextLifecycle}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Middleware contract — order, scope, and handle storage.
 *   <li>LIFO ordering of {@link RequestContextLifecycle.Handle#onClose} registrations.
 *   <li>FIFO ordering of {@link RequestContextLifecycle.Handle#afterClose} tasks.
 *   <li>afterClose tasks run after all onClose registrations complete.
 *   <li>Isolation: one throwing registration/task does not prevent others from running.
 *   <li>Idempotency: {@link RequestContextLifecycle.Handle#completeNow()} followed by the end
 *       handler is a no-op on the second invocation.
 *   <li>Fail-fast: late registration after completion throws {@link IllegalStateException}.
 *   <li>End-to-end: end handler on a real Vert.x {@link RoutingContext} drives the same ordering.
 *   <li>Request binding: one handle per request across reroutes, with one cleanup; any other slot
 *       value gets a fresh handle, and a handle closed by {@code completeNow()} gets a successor.
 * </ul>
 *
 * <p>The end-to-end case dials through a {@link WebClient} bound to the per-test {@link Vertx}
 * instance rather than a fresh inline {@code HttpClient}. Two reasons: an inline client is
 * unclosable by construction — the {@code close()} in that test's success path belongs to the
 * server, not the client — so {@code Vertx} teardown reclaims its netty pools while the request may
 * still be in flight; and a raw {@code HttpClientResponse} discards body buffers that arrive before
 * a body handler is attached, which the drain step there existed to work around (issues #167,
 * #330). A {@link WebClient} has already aggregated the body by the time its send future resolves.
 * Nothing here answers 3xx, so the follow-redirects default never engages.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RequestContextLifecycleTest {

    private final RequestContextLifecycle middleware = new RequestContextLifecycle();

    /**
     * The request client, bound by the one test that issues HTTP so it can be closed; stays
     * {@code null} for the mock-driven tests, which never create a {@link Vertx} instance at all.
     */
    private WebClient client;

    /**
     * Closes the {@link WebClient} the test that just ran created, before the extension closes the
     * {@link Vertx} instance it was created on.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to await here.
     */
    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    // --- Middleware contract ---

    @Test
    @DisplayName("Should report priority = Integer.MIN_VALUE")
    void shouldReportMinValueOrder() {
        assertEquals(Integer.MIN_VALUE, middleware.priority());
        assertEquals(RequestContextLifecycle.ORDER, middleware.priority());
    }

    @Test
    @DisplayName("Should report phase = SYSTEM_FIRST")
    void shouldReportSystemFirstPhase() {
        assertEquals(ExtensionPhase.SYSTEM_FIRST, middleware.phase());
    }

    @Test
    @DisplayName("Should report scope = ROOT")
    void shouldReportRootScope() {
        assertEquals(MiddlewareScope.ROOT, middleware.scope());
    }

    @Test
    @DisplayName("handle(ctx) should store a Handle on the RoutingContext")
    void handleShouldStoreHandleOnContext() {
        RoutingContext ctx = mockContext();

        middleware.handle(ctx);

        assertNotNull(ctx.get("dev.vertique.requestContextLifecycle"));
        assertInstanceOf(RequestContextLifecycle.Handle.class, ctx.get("dev.vertique.requestContextLifecycle"));
    }

    @Test
    @DisplayName("fromRoutingContext should return the Handle stored by handle()")
    void fromRoutingContextShouldReturnHandle() {
        RoutingContext ctx = mockContext();
        middleware.handle(ctx);

        RequestContextLifecycle.Handle handle = RequestContextLifecycle.fromRoutingContext(ctx);

        assertNotNull(handle);
    }

    @Test
    @DisplayName("fromRoutingContext should throw IllegalStateException when middleware did not run")
    void fromRoutingContextShouldThrowWhenMiddlewareDidNotRun() {
        RoutingContext ctx = mock(RoutingContext.class);
        when(ctx.get(any(String.class))).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> RequestContextLifecycle.fromRoutingContext(ctx));
    }

    // --- onClose LIFO ordering ---

    @Nested
    @DisplayName("onClose registrations")
    class OnCloseRegistrations {

        @Test
        @DisplayName("Scope registrations should be closed in LIFO order")
        void scopesShouldCloseInLifoOrder() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose(trackingScope("A", order));
            handle.onClose(trackingScope("B", order));
            handle.onClose(trackingScope("C", order));

            handle.closeAll();

            assertEquals(List.of("C", "B", "A"), order);
        }

        @Test
        @DisplayName("Runnable registrations should run in LIFO order")
        void runnablesShouldRunInLifoOrder() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose((Runnable) () -> order.add("A"));
            handle.onClose((Runnable) () -> order.add("B"));
            handle.onClose((Runnable) () -> order.add("C"));

            handle.closeAll();

            assertEquals(List.of("C", "B", "A"), order);
        }

        @Test
        @DisplayName("onCloseRun should accept a bare lambda and run it on close")
        void onCloseRunShouldAcceptBareLambda() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            // No cast, no typed local: the plain lambda form an adopter would naturally write.
            // handle.onClose(() -> ...) does not compile — onClose is overloaded on Runnable and
            // ContextHolder.Scope, two no-argument functional interfaces, so the argument is
            // ambiguous. onCloseRun is the unambiguous entry point; this test is its regression guard.
            handle.onCloseRun(() -> order.add("lambda-cleanup"));

            handle.closeAll();

            assertEquals(List.of("lambda-cleanup"), order);
        }

        @Test
        @DisplayName("onCloseRun and onClose registrations should interleave in one LIFO order")
        void onCloseRunShouldShareLifoOrderWithOnClose() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose(trackingScope("scope-A", order));
            handle.onCloseRun(() -> order.add("lambda-B"));
            handle.onClose((Runnable) () -> order.add("runnable-C"));

            handle.closeAll();

            assertEquals(List.of("runnable-C", "lambda-B", "scope-A"), order);
        }

        @Test
        @DisplayName("onCloseRun should reject null and throw IllegalStateException after completion")
        void onCloseRunShouldGuardNullAndLateRegistration() {
            RequestContextLifecycle.Handle open = new RequestContextLifecycle.Handle();
            assertThrows(NullPointerException.class, () -> open.onCloseRun(null));

            RequestContextLifecycle.Handle completed = new RequestContextLifecycle.Handle();
            completed.closeAll();
            assertThrows(IllegalStateException.class, () -> completed.onCloseRun(() -> {}));
        }

        @Test
        @DisplayName("Mixed Scope and Runnable registrations should close in LIFO order")
        void mixedRegistrationsShouldCloseInLifoOrder() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose(trackingScope("scope-A", order));
            handle.onClose((Runnable) () -> order.add("runnable-B"));
            handle.onClose(trackingScope("scope-C", order));

            handle.closeAll();

            assertEquals(List.of("scope-C", "runnable-B", "scope-A"), order);
        }

        @Test
        @DisplayName("One throwing Scope should not prevent other scopes from closing")
        void throwingScopeShouldNotBlockOthers() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose(trackingScope("A", order));
            handle.onClose(throwingScope());
            handle.onClose(trackingScope("C", order));

            // Should not throw
            assertDoesNotThrow(handle::closeAll);
            // A and C must still have run despite the middle one throwing
            assertEquals(List.of("C", "A"), order);
        }

        @Test
        @DisplayName("One throwing Runnable should not prevent other runnables from running")
        void throwingRunnableShouldNotBlockOthers() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose((Runnable) () -> order.add("A"));
            handle.onClose((Runnable) () -> {
                throw new RuntimeException("boom");
            });
            handle.onClose((Runnable) () -> order.add("C"));

            assertDoesNotThrow(handle::closeAll);
            assertEquals(List.of("C", "A"), order);
        }

        @Test
        @DisplayName("onClose(Scope) should throw IllegalStateException after lifecycle completed")
        void onCloseScopeShouldThrowAfterCompleted() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            handle.closeAll();

            assertThrows(IllegalStateException.class, () -> handle.onClose(trackingScope("X", new ArrayList<>())));
        }

        @Test
        @DisplayName("onClose(Runnable) should throw IllegalStateException after lifecycle completed")
        void onCloseRunnableShouldThrowAfterCompleted() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            handle.closeAll();

            assertThrows(IllegalStateException.class, () -> handle.onClose((Runnable) () -> {}));
        }
    }

    // --- afterClose FIFO ordering ---

    @Nested
    @DisplayName("afterClose tasks")
    class AfterCloseTasks {

        @Test
        @DisplayName("afterClose tasks should run in FIFO registration order")
        void afterCloseTasksShouldRunInFifoOrder() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.afterClose(() -> order.add("X"));
            handle.afterClose(() -> order.add("Y"));
            handle.afterClose(() -> order.add("Z"));

            handle.closeAll();

            assertEquals(List.of("X", "Y", "Z"), order);
        }

        @Test
        @DisplayName("afterClose tasks should run after all onClose registrations have completed")
        void afterCloseTasksShouldRunAfterOnClose() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.onClose(trackingScope("onClose-A", order));
            handle.onClose(trackingScope("onClose-B", order));
            handle.afterClose(() -> order.add("afterClose-X"));
            handle.afterClose(() -> order.add("afterClose-Y"));

            handle.closeAll();

            // onClose entries (LIFO: B then A), then afterClose entries (FIFO: X then Y)
            assertEquals(List.of("onClose-B", "onClose-A", "afterClose-X", "afterClose-Y"), order);
        }

        @Test
        @DisplayName("One throwing afterClose task should not prevent later tasks from running")
        void throwingAfterCloseTaskShouldNotBlockOthers() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            List<String> order = new ArrayList<>();

            handle.afterClose(() -> order.add("X"));
            handle.afterClose(() -> {
                throw new RuntimeException("after-close-boom");
            });
            handle.afterClose(() -> order.add("Z"));

            assertDoesNotThrow(handle::closeAll);
            assertEquals(List.of("X", "Z"), order);
        }

        @Test
        @DisplayName("afterClose should throw IllegalStateException after lifecycle completed")
        void afterCloseShouldThrowAfterCompleted() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            handle.closeAll();

            assertThrows(IllegalStateException.class, () -> handle.afterClose(() -> {}));
        }
    }

    // --- completeNow idempotency ---

    @Nested
    @DisplayName("completeNow idempotency")
    class CompleteNowIdempotency {

        @Test
        @DisplayName("completeNow should drive onClose and afterClose synchronously")
        void completeNowShouldDriveCleanupSynchronously() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            AtomicInteger onCloseCount = new AtomicInteger(0);
            AtomicInteger afterCloseCount = new AtomicInteger(0);

            handle.onClose((ContextHolder.Scope) onCloseCount::incrementAndGet);
            handle.afterClose(afterCloseCount::incrementAndGet);

            handle.completeNow();

            assertEquals(1, onCloseCount.get());
            assertEquals(1, afterCloseCount.get());
        }

        @Test
        @DisplayName("Calling completeNow twice should be a no-op on the second call")
        void completeNowTwiceShouldBeNoOp() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            AtomicInteger count = new AtomicInteger(0);
            handle.onClose((ContextHolder.Scope) count::incrementAndGet);

            handle.completeNow();
            handle.completeNow(); // second call — no-op

            assertEquals(1, count.get());
        }

        @Test
        @DisplayName("End handler firing after completeNow should be a no-op")
        void endHandlerAfterCompleteNowShouldBeNoOp() {
            RequestContextLifecycle.Handle handle = new RequestContextLifecycle.Handle();
            AtomicInteger count = new AtomicInteger(0);
            handle.onClose((ContextHolder.Scope) count::incrementAndGet);

            handle.completeNow();
            handle.closeAll(); // simulates the end handler firing late

            assertEquals(1, count.get());
        }
    }

    // --- End-to-end with real RoutingContext ---

    @Test
    @DisplayName("End handler on real RoutingContext should drive LIFO onClose then FIFO afterClose")
    void endHandlerOnRealRoutingContextShouldDriveCorrectOrdering(Vertx vertx, VertxTestContext ctx) {
        List<String> order = new ArrayList<>();
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        Router router = Router.router(vertx);

        // Mount the middleware
        router.route("/*").handler(middleware);

        // Route that registers two onClose scopes and one afterClose task, then responds
        router.route("/test").handler(rc -> {
            RequestContextLifecycle.Handle handle = RequestContextLifecycle.fromRoutingContext(rc);
            handle.onClose(trackingScope("onClose-1", order));
            handle.onClose(trackingScope("onClose-2", order));
            handle.afterClose(() -> order.add("afterClose-1"));
            rc.response().setStatusCode(200).end();
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(server -> {
                    int port = server.actualPort();
                    return client.get(port, "127.0.0.1", "/test")
                            .send()
                            .compose(resp -> {
                                ctx.verify(() -> assertEquals(200, resp.statusCode()));
                                // Allow end handlers to complete before asserting. The separate body
                                // drain the raw client needed is gone: a WebClient response is
                                // already aggregated when its send future resolves.
                                return Future.<Void>future(p -> vertx.setTimer(50, id -> p.complete(null)));
                            })
                            .onSuccess(v -> {
                                ctx.verify(() -> {
                                    // LIFO: onClose-2 before onClose-1; then FIFO afterClose-1
                                    assertEquals(List.of("onClose-2", "onClose-1", "afterClose-1"), order);
                                });
                                server.close().onComplete(ar -> ctx.completeNow());
                            })
                            .onFailure(ctx::failNow);
                })
                .onFailure(ctx::failNow);
    }

    // --- Request binding across reroutes ---

    /**
     * One lifecycle handle per request, across reroutes (FR-003; Codex CX-F-007 and CX-F-010).
     *
     * <p>A reroute re-runs ROOT middleware. The lifecycle must reuse the request's own open handle and register no
     * second cleanup end handler. Any other slot value (another request's handle, an unbound handle, a value of
     * another type) gets a fresh handle. A handle closed by {@code completeNow()} is never reopened: the re-entered
     * pass gets a successor that the request's one cleanup still reaches.
     *
     * <p>Both proofs use only the lifecycle's public API. TP-018 calls {@link RequestContextLifecycle#handle} on mocked
     * contexts built like {@code mockContext()}, with {@code request()} stubbed. TP-019 drives a real router through a
     * reroute, as {@code endHandlerOnRealRoutingContextShouldDriveCorrectOrdering} does.
     */
    @Nested
    @DisplayName("Request binding")
    class RequestBinding {

        /** The lifecycle's routing-context slot key, as {@code handleShouldStoreHandleOnContext} reads it. */
        private static final String SLOT_KEY = "dev.vertique.requestContextLifecycle";

        private static final String FIRST_PASS = "first-pass cleanup";
        private static final String SECOND_PASS = "second-pass cleanup";
        private static final String C1 = "c1 (on H1)";
        private static final String C2 = "c2 (on H2)";
        private static final String OTHERS_CLEANUP = "other's cleanup";
        private static final String NOT_A_HANDLE = "not-a-handle";

        /** A row with no assertion of its own. */
        private static final Executable NO_ROW_CHECK = () -> {};

        /** TP-019's first-pass flag in {@code rc.data()}, which a reroute keeps. */
        private static final String END_PROBE_FLAG = "test.requestBinding.endProbeRegistered";

        /** TP-019's bound on the wait for the end probe. */
        private static final long END_PROBE_WAIT_MS = 5_000;

        /** TP-019's settle timer, as in {@code endHandlerOnRealRoutingContextShouldDriveCorrectOrdering}. */
        private static final long SETTLE_MS = 50;

        /** TP-018's request under test. */
        private final MockedContext ctx = MockedContext.over(mockContext());

        /** TP-018's second request, with its own mocked {@link HttpServerRequest}. */
        private final MockedContext other = MockedContext.over(mockContext());

        /** Every TP-018 cleanup appends its label here when it runs. */
        private final List<String> cleanupLog = new ArrayList<>();

        // --- TP-018 ---

        /**
         * TP-018, one row per value in {@code ctx}'s slot before the call under test.
         *
         * @param slotCase the row
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("slotCases")
        @DisplayName("One handle per request: re-entry reuses it, and any other slot value gets a fresh one")
        void reentryReusesTheRequestsHandleAndAnyOtherSlotValueGetsAFreshOne(SlotCase slotCase) {
            // FR-003, Codex CX-F-007: one lifecycle handle per request, bound to it, with one cleanup end handler in
            // all. Only this request's own handle is ever reused.
            SlotState slot = slotCase.prepareSlot().apply(this);

            RequestContextLifecycle.Handle current = assertDoesNotThrow(
                    () -> {
                        pass(ctx);
                        RequestContextLifecycle.Handle handle = slotHandle();
                        handle.onCloseRun(() -> cleanupLog.add(SECOND_PASS));
                        return handle;
                    },
                    slotCase.name() + ": handle(ctx) and the second-pass registration must not throw");
            List<String> beforeFiring = List.copyOf(cleanupLog);
            List<String> afterFirstFiring = fireCtxEndHandlers();
            List<String> afterSecondFiring = fireCtxEndHandlers();

            List<String> expectedAfterFirstFiring = Stream.concat(
                            slot.ranBeforeCall().stream(), slot.firstFiring().stream())
                    .toList();
            assertAll(
                    slotCase.name(),
                    () -> assertEquals(
                            ctx.handleCalls().get(),
                            ctx.nextCalls().get(),
                            "ctx: next() must run once per handle call"),
                    () -> assertEquals(
                            other.handleCalls().get(),
                            other.nextCalls().get(),
                            "other: next() must run once per handle call"),
                    () -> assertEquals(
                            1,
                            ctx.endHandlers().size(),
                            "ctx.addEndHandler must be called exactly once in all, over "
                                    + ctx.handleCalls().get() + " handle calls"),
                    () -> {
                        if (slotCase.expectReuse()) {
                            assertSame(slot.reused(), current, "the call must reuse the request's open handle");
                        } else {
                            assertNotSame(
                                    slot.priorValue(), current, "the slot must hold a new handle, not the prior value");
                        }
                    },
                    () -> assertEquals(
                            slot.ranBeforeCall(), beforeFiring, "the cleanups that ran before any end handler fired"),
                    () -> assertEquals(
                            expectedAfterFirstFiring,
                            afterFirstFiring,
                            "the first firing runs the second-pass cleanup first (LIFO), each cleanup once"),
                    () -> assertEquals(afterFirstFiring, afterSecondFiring, "the second firing must run nothing"),
                    slot.rowCheck());
        }

        /**
         * TP-018's rows.
         *
         * @return rows (a) to (g), in contract order
         */
        static Stream<SlotCase> slotCases() {
            return Stream.of(
                    new SlotCase("(a) re-entry: the request's own open handle", RequestBinding::reentry, true),
                    new SlotCase("(b) foreign handle: another request's handle", RequestBinding::foreignHandle, false),
                    new SlotCase("(c) unbound handle: new Handle()", RequestBinding::unboundHandle, false),
                    new SlotCase("(d) another type: a String", RequestBinding::anotherType, false),
                    new SlotCase(
                            "(e) closed handle: the request's handle after completeNow()",
                            RequestBinding::closedHandle,
                            false),
                    new SlotCase(
                            "(f) a chain of successors: the open tail H2", RequestBinding::chainOfSuccessors, true),
                    new SlotCase(
                            "(g) a stale closed handle: its link leads to the open H1",
                            RequestBinding::staleClosedHandle,
                            true));
        }

        /** (a): a first pass stored its handle and registered the first-pass cleanup on it. */
        private SlotState reentry() {
            RequestContextLifecycle.Handle first = firstPass();
            return new SlotState(first, first, List.of(), List.of(SECOND_PASS, FIRST_PASS), NO_ROW_CHECK);
        }

        /** (b): the handle {@code handle(other)} stored for {@code other}, put into {@code ctx}'s slot. */
        private SlotState foreignHandle() {
            pass(other);
            RequestContextLifecycle.Handle foreign = RequestContextLifecycle.fromRoutingContext(other.routingContext());
            foreign.onCloseRun(() -> cleanupLog.add(OTHERS_CLEANUP));
            ctx.routingContext().put(SLOT_KEY, foreign);
            return new SlotState(foreign, null, List.of(), List.of(SECOND_PASS), () -> {
                assertDoesNotThrow(() -> foreign.onCloseRun(() -> {}), "other's handle must still be open");
                assertFalse(cleanupLog.contains(OTHERS_CLEANUP), "none of other's cleanups may run: " + cleanupLog);
            });
        }

        /** (c): a handle built with the public no-argument constructor, bound to no request. */
        private SlotState unboundHandle() {
            RequestContextLifecycle.Handle unbound = new RequestContextLifecycle.Handle();
            ctx.routingContext().put(SLOT_KEY, unbound);
            return new SlotState(unbound, null, List.of(), List.of(SECOND_PASS), NO_ROW_CHECK);
        }

        /** (d): a value of another type. */
        private SlotState anotherType() {
            ctx.routingContext().put(SLOT_KEY, NOT_A_HANDLE);
            return new SlotState(NOT_A_HANDLE, null, List.of(), List.of(SECOND_PASS), NO_ROW_CHECK);
        }

        /** (e): as (a), then {@code completeNow()} on that handle, which runs the first-pass cleanup. */
        private SlotState closedHandle() {
            RequestContextLifecycle.Handle first = closedFirstPass();
            return new SlotState(first, null, List.of(FIRST_PASS), List.of(SECOND_PASS), NO_ROW_CHECK);
        }

        /**
         * (f): as (e); then a pass that chains successor H1, with {@code c1} registered on H1 and {@code completeNow()}
         * on H1; then a pass that chains successor H2, with {@code c2} registered on H2.
         */
        private SlotState chainOfSuccessors() {
            closedFirstPass();
            pass(ctx);
            RequestContextLifecycle.Handle h1 = slotHandle();
            h1.onCloseRun(() -> cleanupLog.add(C1));
            h1.completeNow();
            pass(ctx);
            RequestContextLifecycle.Handle h2 = slotHandle();
            h2.onCloseRun(() -> cleanupLog.add(C2));
            return new SlotState(h2, h2, List.of(FIRST_PASS, C1), List.of(SECOND_PASS, C2), NO_ROW_CHECK);
        }

        /**
         * (g): as (e); then a pass that chains successor H1, with {@code c1} registered on H1; then the closed first
         * handle put back into the slot (trusted-code misuse).
         */
        private SlotState staleClosedHandle() {
            RequestContextLifecycle.Handle first = closedFirstPass();
            pass(ctx);
            RequestContextLifecycle.Handle h1 = slotHandle();
            h1.onCloseRun(() -> cleanupLog.add(C1));
            ctx.routingContext().put(SLOT_KEY, first);
            return new SlotState(first, h1, List.of(FIRST_PASS), List.of(SECOND_PASS, C1), NO_ROW_CHECK);
        }

        /**
         * Runs a first pass of {@code ctx} and registers the first-pass cleanup on the handle it stored.
         *
         * @return the first pass's handle
         */
        private RequestContextLifecycle.Handle firstPass() {
            pass(ctx);
            RequestContextLifecycle.Handle first = slotHandle();
            first.onCloseRun(() -> cleanupLog.add(FIRST_PASS));
            return first;
        }

        /**
         * {@link #firstPass()}, then {@code completeNow()} on its handle.
         *
         * @return the first pass's handle, now closed
         */
        private RequestContextLifecycle.Handle closedFirstPass() {
            RequestContextLifecycle.Handle first = firstPass();
            first.completeNow();
            return first;
        }

        /**
         * One routing pass of {@code mocked} through the lifecycle under test.
         *
         * @param mocked the context to pass
         */
        private void pass(MockedContext mocked) {
            mocked.handleCalls().incrementAndGet();
            middleware.handle(mocked.routingContext());
        }

        /**
         * Reads {@code ctx}'s handle through the public accessor.
         *
         * @return whatever handle {@code ctx}'s slot holds
         */
        private RequestContextLifecycle.Handle slotHandle() {
            return RequestContextLifecycle.fromRoutingContext(ctx.routingContext());
        }

        /**
         * Fires every end handler collected for {@code ctx}, in reverse registration order, as Vert.x Web does.
         *
         * @return the cleanup log after the firing
         */
        private List<String> fireCtxEndHandlers() {
            List<Handler<AsyncResult<Void>>> handlers = List.copyOf(ctx.endHandlers());
            for (int i = handlers.size() - 1; i >= 0; i--) {
                handlers.get(i).handle(Future.succeededFuture());
            }
            return List.copyOf(cleanupLog);
        }

        // --- TP-019 ---

        /**
         * TP-019: {@code GET /early} completes the lifecycle early and reroutes to {@code GET /done}.
         *
         * @param vertx       the extension's Vert.x instance
         * @param testContext the async test context
         */
        @Test
        @DisplayName("A reroute after completeNow() keeps one cleanup and the re-entered pass's bindings")
        void rerouteAfterCompleteNowChainsASuccessorUnderTheOneCleanup(Vertx vertx, VertxTestContext testContext) {
            // FR-003, Codex CX-F-010: a handle closed by completeNow() is never reopened. The re-entered pass gets a
            // successor that the request's one cleanup reaches, so a hook registered on the first pass still sees the
            // re-entered pass's bindings.
            CountingContextHolder holder = new CountingContextHolder();
            Promise<String> endProbeRequestId = Promise.promise();
            List<String> passRequestIds = new CopyOnWriteArrayList<>();
            List<String> failures = new CopyOnWriteArrayList<>();
            AtomicReference<List<Integer>> closesAtCompleteNow = new AtomicReference<>(List.of());
            AtomicInteger status = new AtomicInteger();

            // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
            client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
            Router router = Router.router(vertx);
            router.route("/*").handler(middleware);
            router.route("/*").handler(endProbe(holder, endProbeRequestId));
            router.route("/*").handler(correlationIngress(holder));
            router.route("/*").handler(passProbe(holder, passRequestIds));
            router.get("/early").handler(rc -> {
                RequestContextLifecycle.fromRoutingContext(rc).completeNow();
                closesAtCompleteNow.set(holder.closeCounts());
                rc.reroute("/done");
            });
            router.get("/done").handler(rc -> rc.response().setStatusCode(200).end());
            router.route().failureHandler(rc -> {
                failures.add(rc.statusCode() + " " + rc.failure());
                if (!rc.response().ended()) {
                    rc.response().setStatusCode(500).end();
                }
            });

            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .compose(server -> client.get(server.actualPort(), "127.0.0.1", "/early")
                            .send()
                            .compose(resp -> {
                                status.set(resp.statusCode());
                                // End handlers fire when the response ends, not necessarily before the client has it.
                                return endProbeRequestId.future().timeout(END_PROBE_WAIT_MS, TimeUnit.MILLISECONDS);
                            })
                            // The lifecycle's cleanup fires after the end probe; let it finish before asserting.
                            .compose(recorded ->
                                    Future.<String>future(p -> vertx.setTimer(SETTLE_MS, id -> p.complete(recorded))))
                            .eventually(() -> server.close()))
                    .onComplete(testContext.succeeding(recorded -> {
                        testContext.verify(() -> {
                            List<Integer> closes = holder.closeCounts();
                            List<Integer> pass1Closes = closesAtCompleteNow.get();
                            String pass2RequestId = passRequestIds.size() == 2 ? passRequestIds.get(1) : null;
                            // Pass 2's scope is attributed to the one cleanup: the end probe saw its binding, so it
                            // was still open then, and the cleanup is the only end handler that fires after the probe.
                            assertAll(
                                    "TP-019",
                                    () -> assertEquals(200, status.get(), "the response status; failures=" + failures),
                                    () -> assertEquals(List.of(), failures, "no failure may be recorded"),
                                    () -> assertEquals(
                                            2,
                                            passRequestIds.size(),
                                            "the pass probe runs once per pass: " + passRequestIds),
                                    () -> assertEquals(
                                            2,
                                            passRequestIds.stream().distinct().count(),
                                            "each pass binds its own request id: " + passRequestIds),
                                    () -> assertEquals(
                                            pass2RequestId,
                                            recorded,
                                            "the end probe, registered on pass 1, must see pass 2's binding; pass ids="
                                                    + passRequestIds),
                                    () -> assertTrue(
                                            holder.bindCalls() >= 2,
                                            "at least one bind per pass: bind calls=" + holder.bindCalls()),
                                    () -> assertFalse(
                                            pass1Closes.isEmpty(), "pass 1 must bind a scope before completeNow()"),
                                    () -> assertEquals(
                                            Collections.nCopies(pass1Closes.size(), 1),
                                            pass1Closes,
                                            "completeNow() closes each of pass 1's scopes once"),
                                    () -> assertTrue(
                                            closes.size() > pass1Closes.size(),
                                            "pass 2 must bind a scope of its own: close counts=" + closes),
                                    () -> assertEquals(
                                            Collections.nCopies(closes.size(), 1),
                                            closes,
                                            "every scope the holder returned is closed exactly once"));
                        });
                        testContext.completeNow();
                    }));
        }

        /**
         * TP-019's end probe, standing where the emitter stands. On the first pass only, it registers one end handler
         * that records the request id bound when it fires, or {@code null} when none is bound.
         *
         * @param holder   the holder to read
         * @param recorded completed with the recorded request id
         * @return the probe
         */
        private Handler<RoutingContext> endProbe(ContextHolder holder, Promise<String> recorded) {
            return rc -> {
                if (rc.get(END_PROBE_FLAG) == null) {
                    rc.put(END_PROBE_FLAG, Boolean.TRUE);
                    rc.addEndHandler(ar -> recorded.tryComplete(boundRequestId(holder)));
                }
                rc.next();
            };
        }

        /**
         * TP-019's pass probe: records the request id bound on each pass.
         *
         * @param holder         the holder to read
         * @param passRequestIds receives one request id per pass
         * @return the probe
         */
        private Handler<RoutingContext> passProbe(ContextHolder holder, List<String> passRequestIds) {
            return rc -> {
                passRequestIds.add(boundRequestId(holder));
                rc.next();
            };
        }

        /**
         * The request id of the bound {@link CorrelationContext}.
         *
         * @param holder the holder to read
         * @return the bound request id, or {@code null} when no correlation is bound
         */
        private static String boundRequestId(ContextHolder holder) {
            return holder.current(CorrelationContext.class)
                    .map(correlation -> correlation.requestId().value())
                    .orElse(null);
        }

        /**
         * The real {@link CorrelationIngressMiddleware} with the default config, as
         * {@code CorrelationIngressMiddlewareTest} builds it, over {@code holder}.
         *
         * @param holder the holder the middleware binds on
         * @return the middleware
         */
        private static CorrelationIngressMiddleware correlationIngress(ContextHolder holder) {
            return new CorrelationIngressMiddleware(
                    holder,
                    new CorrelationContextFactory(Optional.empty()),
                    new CorrelationContextMutator(holder),
                    CorrelationIngressConfig.defaults(),
                    Set.of(),
                    Set.of(),
                    Optional.empty());
        }

        // --- Fixtures ---

        /**
         * A TP-018 row.
         *
         * @param name        the row's display name
         * @param prepareSlot prepares {@code ctx}'s slot before the call under test, and returns what the row expects
         * @param expectReuse whether the call under test must reuse a handle the preparation left reachable from the
         *                    slot, rather than put a new handle into it
         */
        private record SlotCase(String name, Function<RequestBinding, SlotState> prepareSlot, boolean expectReuse) {
            @Override
            public String toString() {
                return name;
            }
        }

        /**
         * What a TP-018 row's preparation left in {@code ctx}'s slot, and what the row expects.
         *
         * @param priorValue    the slot's value just before the call under test
         * @param reused        the handle a reuse row expects in the slot after the call; {@code null} otherwise
         * @param ranBeforeCall the cleanups that ran, at {@code completeNow()}, before any end handler fired
         * @param firstFiring   the cleanups the first firing runs, in order
         * @param rowCheck      the row's own assertion, or {@code NO_ROW_CHECK}
         */
        private record SlotState(
                Object priorValue,
                RequestContextLifecycle.Handle reused,
                List<String> ranBeforeCall,
                List<String> firstFiring,
                Executable rowCheck) {}

        /**
         * A mocked {@link RoutingContext} built like {@code mockContext()}, with {@code request()} stubbed to its own
         * mocked {@link HttpServerRequest}, every {@code addEndHandler} argument collected, and {@code next()}
         * counted.
         *
         * @param routingContext the mocked context
         * @param endHandlers    every handler passed to {@code addEndHandler}, in registration order
         * @param nextCalls      how many times {@code next()} ran
         * @param handleCalls    how many times the test passed this context to the lifecycle
         */
        private record MockedContext(
                RoutingContext routingContext,
                List<Handler<AsyncResult<Void>>> endHandlers,
                AtomicInteger nextCalls,
                AtomicInteger handleCalls) {

            /**
             * Adds the stubs to a map-backed mocked context.
             *
             * @param mapBacked a context from {@code mockContext()}
             * @return the stubbed context and its recorders
             */
            static MockedContext over(RoutingContext mapBacked) {
                MockedContext mocked =
                        new MockedContext(mapBacked, new ArrayList<>(), new AtomicInteger(), new AtomicInteger());
                HttpServerRequest request = mock(HttpServerRequest.class);
                when(mapBacked.request()).thenReturn(request);
                doAnswer(inv -> {
                            mocked.endHandlers().add(inv.getArgument(0));
                            return mocked.endHandlers().size() - 1;
                        })
                        .when(mapBacked)
                        .addEndHandler(any());
                doAnswer(inv -> {
                            mocked.nextCalls().incrementAndGet();
                            return null;
                        })
                        .when(mapBacked)
                        .next();
                return mocked;
            }
        }

        /**
         * A {@link ContextHolder} over a {@link DefaultContextHolder} that counts {@code bind} calls and the closes of
         * every scope it returns.
         */
        private static final class CountingContextHolder implements ContextHolder {

            private final ContextHolder delegate = new DefaultContextHolder();
            private final AtomicInteger bindCalls = new AtomicInteger();

            /** One close counter per returned scope, in bind order. */
            private final List<AtomicInteger> scopeCloses = new CopyOnWriteArrayList<>();

            @Override
            public <T> Optional<T> current(Class<T> type) {
                return delegate.current(type);
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                bindCalls.incrementAndGet();
                Scope scope = delegate.bind(type, value);
                AtomicInteger closes = new AtomicInteger();
                scopeCloses.add(closes);
                return () -> {
                    closes.incrementAndGet();
                    scope.close();
                };
            }

            /**
             * The number of {@code bind} calls.
             *
             * @return the count
             */
            int bindCalls() {
                return bindCalls.get();
            }

            /**
             * How many times each returned scope was closed.
             *
             * @return one count per returned scope, in bind order
             */
            List<Integer> closeCounts() {
                return scopeCloses.stream().map(AtomicInteger::get).toList();
            }
        }
    }

    // --- Helpers ---

    /**
     * Creates a mock {@link RoutingContext} that supports {@code put}/{@code get} via a real map,
     * and records the end handler so tests can trigger it.
     *
     * @return the mocked routing context
     */
    private RoutingContext mockContext() {
        RoutingContext ctx = mock(RoutingContext.class);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        doAnswer(inv -> {
                    data.put(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(ctx)
                .put(any(String.class), any());
        when(ctx.get(any(String.class))).thenAnswer(inv -> data.get(inv.getArgument(0)));
        return ctx;
    }

    /**
     * Creates a {@link ContextHolder.Scope} that appends {@code label} to {@code order} when
     * {@link ContextHolder.Scope#close()} is called.
     *
     * @param label the label to append
     * @param order the list to record the close order into
     * @return the tracking scope
     */
    private static ContextHolder.Scope trackingScope(String label, List<String> order) {
        return () -> order.add(label);
    }

    /**
     * Creates a {@link ContextHolder.Scope} whose {@link ContextHolder.Scope#close()} throws a
     * {@link RuntimeException} to exercise error isolation.
     *
     * @return the throwing scope
     */
    private static ContextHolder.Scope throwingScope() {
        return () -> {
            throw new RuntimeException("scope-boom");
        };
    }

    /**
     * Captures {@link Handler} arguments registered via {@code ctx.addEndHandler(...)}.
     * Used where direct invocation is needed in pure-mock tests.
     */
    @SuppressWarnings("unchecked")
    private static Handler<Void> captureEndHandler(RoutingContext ctx) {
        final Handler<?>[] captured = new Handler<?>[1];
        doAnswer(inv -> {
                    captured[0] = inv.getArgument(0);
                    return null;
                })
                .when(ctx)
                .addEndHandler(any());
        return (Handler<Void>) captured[0];
    }
}
