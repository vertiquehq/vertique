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

/**
 * Saga cancellation integration test (§9.4 test 17 — cycle 1 adaptation).
 *
 * <p><strong>Cycle 1 limitation note:</strong> The cycle 1 engine triggers compensation ONLY via
 * a {@code FailNode} in the plan or a service-target-validation failure at recording time — NOT
 * via service runtime failures that happen after the saga commits and waits. When
 * {@code payment.authorize} fails in the relay, the saga is already in WAITING state for
 * {@code payment.captured}; the relay failure causes an outbox retry, not a saga failure.
 *
 * <p>This test therefore verifies the cycle 1 saga cancellation behavior:
 * <ol>
 *   <li>Saga starts → inventory dispatched → relay → {@code inventory.reserved} signal → saga
 *       advances to {@code authorize-payment}.</li>
 *   <li>Payment is dispatched but before the captured signal arrives, the saga is
 *       <strong>cancelled</strong> via {@link dev.vertique.workflow.ops.WorkflowOperations#cancel}.
 *       </li>
 *   <li>Status becomes CANCELLED. No COMPENSATING phase is triggered (cycle 1: cancel does NOT
 *       run compensation; compensation is triggered only by FailNode or recording failure).</li>
 * </ol>
 *
 * <p>LIFO compensation ordering across ≥2 completed compensable steps is separately covered by
 * {@link dev.vertique.workflow.postgresql.engine.CompensationLifoIT} in the workflow-postgresql
 * module, which uses a 3-step saga with an explicit {@code FailNode}. Full end-to-end LIFO
 * compensation via the order-fulfillment saga requires a cycle 2+ signal-based failure path.
 */
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
        component.stubInventoryService().reset();
        component.stubPaymentService().reset();
        component.stubShippingService().reset();
        truncateTables().onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (component != null && component.pgInboxOutboxRepository() != null) {
            component.pgInboxOutboxRepository().pool().close();
        }
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
}
