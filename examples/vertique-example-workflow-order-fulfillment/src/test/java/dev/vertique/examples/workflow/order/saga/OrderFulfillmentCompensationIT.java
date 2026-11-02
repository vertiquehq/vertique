// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Cancellation and service failure compensation of the order saga. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class OrderFulfillmentCompensationIT extends SagaTestBase {

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        setUpComponent(vertx).onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }

    @BeforeEach
    void reset(VertxTestContext ctx) {
        component.stubScenario().setPaymentFails(false);
        component.stubScenario().setInventoryFails(false);
        component.stubScenario().setShippingFails(false);
        component.stubInventoryService().reset();
        component.stubPaymentService().reset();
        component.stubShippingService().reset();
        truncateTables().onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        closeComponent();
    }

    @Test
    @DisplayName("cancel after partial completion: saga reaches CANCELLED with no compensation phase")
    void cancelAfterInventorySucceeds(VertxTestContext ctx) {
        String orderId = "comp-cancel-1";

        startOrder(orderId)
                // Drive relay: inventory.reserve called → inventory.reserved signal posted → saga
                // advances to authorize-payment → outbox entry for payment.authorize written.
                .compose(id -> driveRelay().map(v -> id))
                .compose(id ->
                        // Cancel the saga before payment.captured arrives.
                        component
                                .workflowOperations()
                                .cancel(id, "Payment cancelled by test")
                                .map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "cancel + query must succeed: " + ar.cause());

                    var view = ar.result();
                    assertEquals(WorkflowStatus.CANCELLED, view.instance().status(), "saga must be CANCELLED");

                    // History must NOT contain compensation entries (cycle 1: cancel ≠ compensate).
                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertFalse(
                            entryTypes.contains(WorkflowEntryType.COMPENSATING_START),
                            "CANCELLED saga must NOT have COMPENSATING_START");
                    assertTrue(entryTypes.contains(WorkflowEntryType.CANCELLED), "history must have CANCELLED entry");

                    // Inventory reserve was called; payment was dispatched (outbox) but not confirmed.
                    assertEquals(
                            1, component.stubInventoryService().reserveCalls().size(), "inventory.reserve called once");
                    // Payment stub may or may not have been called (depends on relay timing after cancel).
                    assertEquals(
                            0, component.stubShippingService().createCalls().size(), "shipping never called");

                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("saga stuck in WAITING when payment service is unreachable (relay marks retry)")
    void sagaWaitsWhenPaymentUnreachable(VertxTestContext ctx) {
        String orderId = "comp-unreachable-1";

        // Payment failure in the stub causes the relay to mark outbox as retry,
        // but the saga remains WAITING (cycle 1: service runtime failures do not advance saga).
        component.stubScenario().setPaymentFails(true);

        startOrder(orderId)
                .compose(id -> driveRelay().map(v -> id)) // inventory succeeds
                .compose(id -> driveRelay().map(v -> id)) // payment fails (relay retry) → saga stays WAITING
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query must succeed: " + ar.cause());

                    var view = ar.result();
                    // Saga must still be WAITING — cycle 1 cannot advance via service runtime failure.
                    assertEquals(
                            WorkflowStatus.WAITING,
                            view.instance().status(),
                            "saga must remain WAITING when payment service fails (cycle 1 limitation)");
                    assertEquals(
                            "wait-payment",
                            view.instance().currentStepId(),
                            "saga must be waiting at wait-payment step");

                    ctx.completeNow();
                }));
    }

    @Test
    void failedShipmentTakesTheFailureRouteAndCompensatesWhatCompleted(VertxTestContext ctx) {
        component.stubScenario().setShippingFails(true);
        startOrder("shipment-failure")
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> historySteps(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query: " + ar.cause());
                    assertTrue(ar.result().contains("fail-order"));
                    assertEquals(
                            1, component.stubInventoryService().releaseCalls().size());
                    assertEquals(1, component.stubPaymentService().voidCalls().size());
                    assertEquals(
                            1, component.stubShippingService().createCalls().size());
                    ctx.completeNow();
                }));
    }
}
