// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.saga;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.List;
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
 * Integration test for the ALL_REQUIRED fan-out order-fulfillment workflow (PRD-WF-002 §A.5.1).
 *
 * <p>Exercises {@link dev.vertique.examples.workflow.order.OrderFulfillmentFanOutDefinition}
 * end-to-end:
 * <ol>
 *   <li>Start workflow → engine creates the fork ({@code reserve-order}) and three branch tokens
 *       ({@code inventory}, {@code payment}, {@code fraud}), each with a {@code RUNNING} status
 *       and a pending outbox entry for its service dispatch.</li>
 *   <li>Drive relay (cycle 1) → all three service stubs called → three branch signals posted with
 *       branch identity → engine receives each signal, advances the matching branch token to
 *       {@code COMPLETED}, evaluates the join after each completion, and advances the workflow to
 *       {@code ship-order} once all three are done.</li>
 *   <li>Drive relay (cycle 2) → shipping.create-shipment dispatched → shipment.created signal
 *       posted (instance-level) → workflow reaches {@code COMPLETED}.</li>
 * </ol>
 *
 * <p>Assertions:
 * <ul>
 *   <li>Workflow instance status is {@link WorkflowStatus#COMPLETED}.</li>
 *   <li>History contains {@code FORK_DISPATCHED}, three {@code BRANCH_COMPLETED},
 *       {@code FAN_IN_EVALUATED}, {@code FAN_IN_COMPLETED}, and {@code COMPLETED} entries.</li>
 *   <li>All three stub services were called exactly once.</li>
 *   <li>No PENDING outbox entries remain after completion.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class FanOutOrderFulfillmentIT extends SagaTestBase {

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
        component.stubFraudService().reset();
        truncateTables().onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (component != null && component.pgInboxOutboxRepository() != null) {
            component.pgInboxOutboxRepository().pool().close();
        }
    }

    @Test
    @DisplayName("happy path: ALL_REQUIRED fan-out completes after all three branch signals arrive")
    void happyPathFanOutCompletesSuccessfully(VertxTestContext ctx) {
        String orderId = "fanout-happy-1";

        // Fan-out branch drives hit DurableContextPropagator.bindFrom which is strict on
        // non-duplicated contexts (FR-CTX-157b). Run the entire async chain on a freshly-
        // duplicated context so the substrate's write guard accepts the bind, matching how
        // production dispatch (event-bus / Kafka / cron) always reaches the engine.
        runOnDuplicatedContext(() -> startFanOutOrder(orderId)
                        // Cycle 1: relay dispatches inventory.reserve → inventory.reserved signal → inventory
                        //           branch COMPLETED.
                        .compose(id -> driveRelay().map(v -> id))
                        // Cycle 2: relay dispatches payment.authorize → payment.captured signal → payment
                        //           branch COMPLETED.
                        .compose(id -> driveRelay().map(v -> id))
                        // Cycle 3: relay dispatches fraud.screen → fraud.screened signal → fraud branch
                        //           COMPLETED → join fires → ship-order dispatch recorded.
                        .compose(id -> driveRelay().map(v -> id))
                        // Cycle 2: relay dispatches shipping.create-shipment → shipment.created signal →
                        // workflow reaches COMPLETED.
                        .compose(id -> driveRelay().map(v -> id))
                        .compose(id -> component.workflowOperations().query(id)))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "fan-out saga must succeed: " + ar.cause());

                    var view = ar.result();
                    assertNotNull(view, "view must not be null");
                    assertEquals(WorkflowStatus.COMPLETED, view.instance().status(), "saga must be COMPLETED");

                    // Verify history contains fan-out markers.
                    var history = view.recentHistory();
                    var entryTypes = history.stream().map(e -> e.entryType()).collect(Collectors.toList());

                    assertTrue(entryTypes.contains(WorkflowEntryType.START), "history must have START");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.FORK_DISPATCHED),
                            "history must have FORK_DISPATCHED");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.BRANCH_COMPLETED),
                            "history must have BRANCH_COMPLETED");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.FAN_IN_EVALUATED),
                            "history must have FAN_IN_EVALUATED");
                    assertTrue(
                            entryTypes.contains(WorkflowEntryType.FAN_IN_COMPLETED),
                            "history must have FAN_IN_COMPLETED");
                    assertTrue(entryTypes.contains(WorkflowEntryType.COMPLETED), "history must have COMPLETED");

                    // All three branch completions recorded.
                    long branchCompletedCount = history.stream()
                            .filter(e -> e.entryType() == WorkflowEntryType.BRANCH_COMPLETED)
                            .count();
                    assertEquals(3L, branchCompletedCount, "all three branches must have BRANCH_COMPLETED entries");

                    // Verify all three stub services were called exactly once.
                    assertEquals(
                            1, component.stubInventoryService().reserveCalls().size(), "inventory.reserve called once");
                    assertEquals(
                            1, component.stubPaymentService().authorizeCalls().size(), "payment.authorize called once");
                    assertEquals(1, component.stubFraudService().screenCalls().size(), "fraud.screen called once");
                    assertEquals(
                            1,
                            component.stubShippingService().createCalls().size(),
                            "shipping.create-shipment called once (post-join)");

                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("branch tokens all reach COMPLETED and join state is COMPLETED")
    void branchTokensAndJoinStateComplete(VertxTestContext ctx) {
        String orderId = "fanout-tokens-1";

        runOnDuplicatedContext(() -> startFanOutOrder(orderId)
                // Three relay cycles to process the three parallel branch dispatches.
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .compose(view -> {
                    var pool = component.pgInboxOutboxRepository().pool();
                    var instanceId = view.instance().id().value();

                    // Assert all three branch tokens are COMPLETED.
                    return pool.preparedQuery("SELECT branch_id, status FROM workflow_branch_tokens"
                                    + " WHERE workflow_id = $1 ORDER BY branch_id")
                            .execute(io.vertx.sqlclient.Tuple.of(instanceId))
                            .compose(rows -> {
                                List<String> branchIds = new java.util.ArrayList<>();
                                List<String> statuses = new java.util.ArrayList<>();
                                for (var row : rows) {
                                    branchIds.add(row.getString("branch_id"));
                                    statuses.add(row.getString("status"));
                                }
                                return Future.succeededFuture(new BranchSnapshot(instanceId, branchIds, statuses));
                            })
                            .compose(snapshot -> pool.preparedQuery(
                                            "SELECT status FROM workflow_join_states WHERE workflow_id = $1")
                                    .execute(io.vertx.sqlclient.Tuple.of(snapshot.instanceId()))
                                    .map(joinRows -> {
                                        String joinStatus =
                                                joinRows.iterator().next().getString("status");
                                        return new FullSnapshot(snapshot, joinStatus);
                                    }));
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query must succeed: " + ar.cause());

                    var snap = ar.result();

                    // Three branch tokens: fraud, inventory, payment (sorted by branch_id).
                    assertEquals(3, snap.branches().branchIds().size(), "three branch tokens must exist");
                    for (String status : snap.branches().statuses()) {
                        assertEquals("COMPLETED", status, "every branch token must be COMPLETED");
                    }
                    assertTrue(
                            snap.branches().branchIds().containsAll(List.of("fraud", "inventory", "payment")),
                            "branch ids must include inventory, payment, fraud");

                    // Join state COMPLETED.
                    assertEquals("COMPLETED", snap.joinStatus(), "join state must be COMPLETED");

                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("no orphan outbox rows after fan-out saga completes")
    void noOrphanOutboxRowsAfterCompletion(VertxTestContext ctx) {
        String orderId = "fanout-outbox-1";

        runOnDuplicatedContext(() -> startFanOutOrder(orderId)
                        // 3 cycles for branch dispatches + 1 for ship-order = 4 total.
                        .compose(id -> driveRelay().map(v -> id))
                        .compose(id -> driveRelay().map(v -> id))
                        .compose(id -> driveRelay().map(v -> id))
                        .compose(id -> driveRelay().map(v -> id))
                        .compose(id -> component
                                .pgInboxOutboxRepository()
                                .pool()
                                .preparedQuery("SELECT COUNT(*) FROM outbox WHERE state='PENDING'")
                                .execute()))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "outbox query must succeed: " + ar.cause());
                    long pendingCount = ar.result().iterator().next().getLong(0);
                    assertEquals(0L, pendingCount, "no PENDING outbox rows after fan-out saga completes");
                    ctx.completeNow();
                }));
    }

    // --- Internal records for multi-step compose chains ---

    /**
     * Snapshot of branch token ids and statuses for a workflow instance.
     *
     * @param instanceId  workflow instance UUID
     * @param branchIds   branch id values in query order
     * @param statuses    branch status strings in query order (parallel with branchIds)
     */
    private record BranchSnapshot(java.util.UUID instanceId, List<String> branchIds, List<String> statuses) {}

    /**
     * Full snapshot combining branch token data with the join state status.
     *
     * @param branches   branch token snapshot
     * @param joinStatus join state status string
     */
    private record FullSnapshot(BranchSnapshot branches, String joinStatus) {}
}
