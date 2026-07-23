// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end runtime proof for PRD-CODEGEN-013 Phase 3 slice 3.3 (multi-module observer
 * aggregation).
 *
 * <p>This test builds a real Dagger component that includes the processor-generated
 * {@code GeneratedEventsModule}, fires an {@code Event<OrderCreated>} via {@link OrderService}, and
 * asserts that {@link OrderObserver} received the event. The proof demonstrates that:
 * <ol>
 *   <li>the processor emits {@code @Provides @IntoSet ObserverRegistration} methods that provide the
 *       {@code Set<ObserverRegistration>} multibinding consumed by {@link
 *       dev.vertique.events.ObserverRegistry},
 *   <li>the injected {@link dev.vertique.events.ObserverRegistry} dispatches a fired event to the
 *       registered observer, and
 *   <li>the lambda in the generated {@code ObserverRegistration} invokes the correct observer method
 *       on the Dagger-injected bean instance (not a separate instance, not via reflection).
 * </ol>
 *
 * <p><strong>RED (slice 3.2):</strong> the {@link EventsProcessor} only emits
 * {@code @Binds Event<OrderCreated>} — no {@code @Provides @IntoSet ObserverRegistration}. Dagger
 * therefore cannot satisfy {@code Set<ObserverRegistration>} for {@link
 * dev.vertique.events.ObserverRegistry}, so the entire module fails to compile ({@code
 * MissingBinding} error in Dagger's annotation processing pass). This compilation failure prevents
 * {@code DaggerOrderComponent} from being generated, which in turn causes a compile error in this
 * test class (because {@code DaggerOrderComponent} does not exist). The expected RED is therefore a
 * <em>compile-time failure</em> of the {@code test-compile} phase, not a runtime assertion failure.
 */
class EventAggregationTest {

    /**
     * Awaits a completed Vert.x {@link Future}, returning its value or throwing its cause.
     *
     * @param future the future to await
     * @param <T>    the future's result type
     * @return the result value
     * @throws Throwable if the future fails or does not settle within 5 seconds
     */
    private static <T> T await(Future<T> future) throws Throwable {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        future.onComplete(ar -> {
            if (ar.succeeded()) {
                value.set(ar.result());
            } else {
                error.set(ar.cause());
            }
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "future did not settle within 5 seconds");
        if (error.get() != null) {
            throw error.get();
        }
        return value.get();
    }

    @Test
    @DisplayName("firing Event<OrderCreated> invokes the @Observes observer via the multibinding")
    void firingEventInvokesObserver() throws Throwable {
        // Given: a Dagger component that includes the processor-generated GeneratedEventsModule
        //        which provides @IntoSet ObserverRegistration for OrderObserver.onOrder
        OrderComponent component = DaggerOrderComponent.create();
        OrderService service = component.orderService();
        OrderObserver observer = component.orderObserver();

        // When: an OrderCreated event is fired via the injected Event<OrderCreated> publisher
        await(service.placeOrder("ORD-001"));

        // Then: the observer received the event (the DI-injected bean instance was invoked)
        OrderCreated received = observer.lastReceived();
        assertNotNull(
                received,
                "observer must have received the event; ObserverRegistration lambda must invoke the correct bean instance");
        assertEquals("ORD-001", received.orderId(), "the received event must carry the fired order id");
    }

    @Test
    @DisplayName("the observer bean in the Dagger component is the same instance as the one in the registration")
    void observerRegistrationUsesTheSameBeanInstance() throws Throwable {
        // Given: a Dagger component (singleton scope)
        OrderComponent component = DaggerOrderComponent.create();
        OrderObserver observerFromComponent = component.orderObserver();
        OrderService service = component.orderService();

        // When: an event is fired
        await(service.placeOrder("ORD-002"));

        // Then: the observer bean that received the event is the SAME instance injected by the
        //       component — not a separate instance created by the generated lambda
        assertNotNull(
                observerFromComponent.lastReceived(),
                "the observer instance returned by the component must be the one the registration lambda invoked");
        assertEquals(
                "ORD-002",
                observerFromComponent.lastReceived().orderId(),
                "the Dagger-singleton observer must have received the event, "
                        + "proving the lambda is bound to the same injected bean instance");
    }
}
