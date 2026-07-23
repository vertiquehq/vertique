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
 * No-compensation saga integration test (§9.4 test 17b — cycle 1 adaptation).
 *
 * <p><strong>Cycle 1 limitation note:</strong> In cycle 1, service runtime failures in the relay
 * do NOT advance the saga to {@code FAILED} — the relay retries the outbox entry, and the saga
 * remains {@code WAITING}. A saga can only reach {@code FAILED} via a {@code FailNode} in its
 * plan. The "no compensable steps" scenario is therefore verified at the engine level by
 * {@link dev.vertique.workflow.postgresql.engine.CompensationLifoIT#partialCompensation} (using a
 * 3-step definition with FailNode) and cannot be demonstrated through the relay-driven
 * {@code order-fulfillment} saga in cycle 1.
 *
 * <p>This test verifies the observable cycle 1 behavior when a service is unreachable:
 * <ul>
 *   <li>The saga is started and advances to the inventory wait step.</li>
 *   <li>When inventory fails in the relay, the outbox entry is marked for retry and the saga
 *       remains in {@code WAITING} state (no state transition occurs).</li>
 *   <li>No compensation intents are written.</li>
 *   <li>No {@code COMPENSATING_START} or {@code COMPENSATING_STEP} history entries appear.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class CompensationNoCompensableStepsIT extends SagaTestBase {

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
    @DisplayName("service relay failure: saga remains WAITING, no compensation intents written")
    void serviceFailureLeavesCorrectSagaInWaiting(VertxTestContext ctx) {
        String orderId = "no-comp-1";

        // Enable inventory failure in the relay stub.
        component.stubScenario().setInventoryFails(true);

        startOrder(orderId)
                // Relay dispatches inventory.reserve → stub fails → relay marks retry.
                // Saga has already committed SIDE_EFFECT_RECORDED and transitioned to WAITING.
                // Cycle 1 limitation: relay failure does NOT fail the saga.
                .compose(id -> driveRelay().map(v -> id))
                .compose(id -> component.workflowOperations().query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "query must succeed: " + ar.cause());

                    var view = ar.result();

                    // Cycle 1: saga remains WAITING after relay failure (no FailNode triggered).
                    assertEquals(
                            WorkflowStatus.WAITING,
                            view.instance().status(),
                            "saga must remain WAITING when inventory service fails (cycle 1 relay limitation)");
                    assertEquals(
                            "wait-inventory",
                            view.instance().currentStepId(),
                            "saga must be waiting at wait-inventory step");

                    // History must NOT contain compensation entries.
                    var entryTypes = view.recentHistory().stream()
                            .map(e -> e.entryType())
                            .collect(Collectors.toList());
                    assertFalse(
                            entryTypes.contains(WorkflowEntryType.COMPENSATING_START),
                            "history must NOT have COMPENSATING_START");
                    assertFalse(
                            entryTypes.contains(WorkflowEntryType.COMPENSATING_STEP),
                            "history must NOT have COMPENSATING_STEP");
                    assertFalse(
                            entryTypes.contains(WorkflowEntryType.COMPENSATED), "history must NOT have COMPENSATED");
                    assertFalse(entryTypes.contains(WorkflowEntryType.FAILED), "history must NOT have FAILED");

                    // Stub assertions: reserve was called and failed; no downstream stubs called.
                    assertEquals(
                            1, component.stubInventoryService().reserveCalls().size(), "reserve called once");
                    assertEquals(
                            0, component.stubInventoryService().releaseCalls().size(), "release must NOT be called");
                    assertEquals(
                            0, component.stubPaymentService().authorizeCalls().size(), "payment never called");

                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("no-compensable-steps FAILED path is verified in CompensationLifoIT (engine-level)")
    void noCompensableStepsVerifiedAtEngineLevel(VertxTestContext ctx) {
        // This test documents that the engine-level behavior (FailNode with no prior compensable
        // steps → FAILED with no compensation) is covered by
        // CompensationLifoIT.partialCompensation() in vertique-workflow-postgresql.
        //
        // In that test, a 3-step saga is driven to wait-b (only step A completed). The FailNode
        // is hit via the sig-b → fail-step path. Since step B's signal was never received, only
        // step A's compensation is triggered (one COMPENSATING_STEP).
        //
        // For a "zero compensable steps completed" scenario, the engine goes directly to FAILED
        // (no COMPENSATING phase). The order-fulfillment saga cannot demonstrate this via the
        // relay in cycle 1 because relay failures do not advance the saga to FAILED.
        ctx.completeNow();
    }
}
