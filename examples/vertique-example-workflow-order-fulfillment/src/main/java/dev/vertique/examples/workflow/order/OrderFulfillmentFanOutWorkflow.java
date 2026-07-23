// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order;

import dev.vertique.examples.workflow.order.command.PlaceOrder;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowQuery;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowView;
import io.vertx.core.Future;

/**
 * Typed contract interface for the ALL_REQUIRED fan-out order-fulfillment workflow.
 *
 * <p>This contract corresponds to {@link OrderFulfillmentFanOutDefinition} (definition id
 * {@code "order-fulfillment-fanout"}, version 1). It demonstrates parallel fan-out: inventory
 * reservation, payment authorization, and fraud screening run as independent branches; the
 * workflow advances to shipment only after all three complete.
 *
 * <p>Branch signals are delivered via the branch-aware signal path and are not surfaced through
 * this typed interface — the integration test drives them directly via
 * {@link dev.vertique.workflow.ops.TransactionalWorkflowOperations}.
 */
@WorkflowContract(definitionId = "order-fulfillment-fanout", definitionVersion = 1)
public interface OrderFulfillmentFanOutWorkflow {

    /**
     * Starts a new fan-out order-fulfillment workflow instance.
     *
     * <p>The idempotency key is sourced from {@link PlaceOrder#idempotencyKey()}. Duplicate calls
     * with the same order id return the existing workflow instance id.
     *
     * @param cmd the place-order command; must not be null
     * @return a {@link Future} that resolves to the workflow instance id
     */
    @WorkflowStart
    Future<WorkflowInstanceId> start(PlaceOrder cmd);

    /**
     * Queries the current state of a fan-out order-fulfillment workflow instance.
     *
     * @param id the workflow instance id to query
     * @return a {@link Future} that resolves to a {@link WorkflowView} containing the instance
     *     snapshot and recent history
     */
    @WorkflowQuery("status")
    Future<WorkflowView> status(WorkflowInstanceId id);
}
