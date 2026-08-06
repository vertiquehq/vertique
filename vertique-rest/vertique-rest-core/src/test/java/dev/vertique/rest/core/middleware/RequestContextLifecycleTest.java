// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.middleware;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.extension.ExtensionPhase;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

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
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class RequestContextLifecycleTest {

    private final RequestContextLifecycle middleware = new RequestContextLifecycle();

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
                .listen(0)
                .compose(server -> {
                    int port = server.actualPort();
                    return vertx.createHttpClient()
                            .request(io.vertx.core.http.HttpMethod.GET, port, "localhost", "/test")
                            .compose(req -> req.send())
                            .compose(resp -> {
                                ctx.verify(() -> assertEquals(200, resp.statusCode()));
                                return resp.body();
                            })
                            .compose(body -> {
                                // Allow end handlers to complete before asserting
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
