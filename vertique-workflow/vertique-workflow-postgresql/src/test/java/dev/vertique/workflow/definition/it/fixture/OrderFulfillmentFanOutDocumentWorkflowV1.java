// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;

/**
 * Contract interface for the document-defined fan-out order-fulfillment workflow used in the
 * Slice I integration test ({@link dev.vertique.workflow.definition.it.DocumentDefinitionFanOutIT}).
 *
 * <p>This interface is the contract marker for version 1 of the {@code order-fulfillment-fanout-doc}
 * definition. It is distinct from {@link OrderFulfillmentDocumentWorkflowV1} (which is for the
 * linear saga in Slice H) because it declares the signals of a different document.
 */
@WorkflowContract
public interface OrderFulfillmentFanOutDocumentWorkflowV1 {
    @WorkflowSignal("fraud.screened.doc")
    Future<Void> fraudScreenedDoc(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);

    @WorkflowSignal("inventory.reserved.doc")
    Future<Void> inventoryReservedDoc(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);

    @WorkflowSignal("payment.authorized.doc")
    Future<Void> paymentAuthorizedDoc(WorkflowInstanceId id, Object payload, @SignalDedupKey String dedupKey);
}
