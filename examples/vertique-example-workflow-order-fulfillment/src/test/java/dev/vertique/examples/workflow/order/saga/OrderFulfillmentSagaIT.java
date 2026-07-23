// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Happy-path saga integration test (§9.4 test 16).
 *
 * <p>Drives the full order-fulfillment saga end-to-end:
 * <ol>
 *   <li>Start saga → engine records {@code inventory.reserve} outbox entry.</li>
 *   <li>Drive relay → stub inventory.reserve called → {@code inventory.reserved} signal posted
 *       → engine advances to {@code authorize-payment} → records {@code payment.authorize} entry.</li>
 *   <li>Drive relay → stub payment.authorize called → {@code payment.captured} signal posted
 *       → engine advances to {@code create-shipment} → records {@code shipping.create-shipment}
 *       entry.</li>
 *   <li>Drive relay → stub shipping.create-shipment called → {@code shipment.created} signal
 *       posted → engine advances to {@code completed} → status COMPLETED.</li>
 * </ol>
 *
 * <p>Assertions: status COMPLETED, ordered history, no orphan outbox rows.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class OrderFulfillmentSagaIT extends SagaTestBase {

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
    @DisplayName("happy path: saga drives through all steps and reaches COMPLETED")
    void happyPathSagaCompletesSuccessfully(VertxTestContext ctx) {
        String orderId = "saga-happy-1";

        startOrder(orderId)
                // Step 1: relay dispatches inventory.reserve → sig-a posted → engine advances
                .compose(id -> driveRelay().map(v -> id))
                // Step 2: relay dispatches payment.authorize → sig-b posted → engine advances
                .compose(id -> driveRelay().map(v -> id))
                // Step 3: relay dispatches shipping.create-shipment → sig-c posted → engine completes
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "saga must succeed: " + ar.cause());

                    var view = ar.result();
                    assertNotNull(view, "view must not be null");
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "saga must be COMPLETED");

                    // Verify history entries exist.
                    var history = view.recentHistory();
                    var entryTypes = history.stream().map(e -> e.entryType()).collect(Collectors.toList());
                    assertTrue(entryTypes.contains(WorkflowEntryType.START), "history must have START");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.SIDE_EFFECT_RECORDED),
                            "history must have SIDE_EFFECT_RECORDED");
                    assertTrue(entryTypes.contains(WorkflowEntryType.COMPLETED), "history must have COMPLETED");

                    // Verify stub calls.
                    assertEquals(
                            1, component.stubInventoryService().reserveCalls().size(), "inventory.reserve called once");
                    assertEquals(
                            1, component.stubPaymentService().authorizeCalls().size(), "payment.authorize called once");
                    assertEquals(
                            1,
                            component.stubShippingService().createCalls().size(),
                            "shipping.create-shipment called once");

                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("saga query returns WorkflowView with instance and history")
    void sagaQueryReturnsView(VertxTestContext ctx) {
        String orderId = "saga-query-1";

        startOrder(orderId)
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query must succeed: " + ar.cause());
                    var view = ar.result();
                    assertNotNull(view.instance(), "instance must not be null");
                    assertEquals("order-fulfillment", view.instance().definitionId());
                    assertEquals(WorkflowStatus.WAITING, view.instance().status(), "must be WAITING at inventory step");
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("no orphan outbox rows after saga completes")
    void noOrphanOutboxRowsAfterCompletion(VertxTestContext ctx) {
        String orderId = "saga-outbox-1";

        startOrder(orderId)
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> component
                        .pgInboxOutboxRepository()
                        .pool()
                        .preparedQuery("SELECT COUNT(*) FROM outbox WHERE state='PENDING'")
                        .execute())
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "outbox query must succeed: " + ar.cause());
                    long pendingCount = ar.result().iterator().next().getLong(0);
                    assertEquals(0L, pendingCount, "no PENDING outbox rows after saga completes");
                    ctx.completeNow();
                }));
    }
}
