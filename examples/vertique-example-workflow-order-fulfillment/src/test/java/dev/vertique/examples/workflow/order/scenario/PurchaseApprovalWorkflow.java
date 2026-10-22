// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.scenario;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.contract.WorkflowStart;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;

/**
 * Contract of the purchase-approval workflow that the scenario tests start in several scopes. The default scope runs it
 * from code and a tenant scope runs its own variant from a YAML document; both name this contract.
 */
@WorkflowContract("purchase-approval")
public interface PurchaseApprovalWorkflow {

    /**
     * Starts a purchase approval.
     *
     * @param start the purchase to approve
     * @return the new instance
     */
    @WorkflowStart
    Future<WorkflowInstanceId> start(StartPurchase start);

    /**
     * Delivers the approval; the default scope's version waits for it.
     *
     * @param id the instance
     * @param approval the approval
     * @param dedupKey the deduplication key
     * @return a future that completes when the signal is applied
     */
    @WorkflowSignal("po.approved")
    Future<Void> approved(WorkflowInstanceId id, PoApproved approval, @SignalDedupKey String dedupKey);

    /**
     * Delivers the audit result; the tenant variant waits for it after its finance task.
     *
     * @param id the instance
     * @param audit the audit result
     * @param dedupKey the deduplication key
     * @return a future that completes when the signal is applied
     */
    @WorkflowSignal("po.audited")
    Future<Void> audited(WorkflowInstanceId id, PoAudited audit, @SignalDedupKey String dedupKey);
}
