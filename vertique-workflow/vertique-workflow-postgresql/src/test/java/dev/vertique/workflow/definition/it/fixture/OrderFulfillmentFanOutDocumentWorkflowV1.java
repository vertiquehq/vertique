// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.WorkflowContract;

/**
 * Contract interface for the document-defined fan-out order-fulfillment workflow used in the
 * Slice I integration test ({@link dev.vertique.workflow.definition.it.DocumentDefinitionFanOutIT}).
 *
 * <p>This interface is the contract marker for version 1 of the {@code order-fulfillment-fanout-doc}
 * definition. It is distinct from {@link OrderFulfillmentDocumentWorkflowV1} (which is for the
 * linear saga in Slice H) to prevent registry collisions between ITs.
 */
@WorkflowContract(definitionId = "order-fulfillment-fanout-doc", definitionVersion = 1)
public interface OrderFulfillmentFanOutDocumentWorkflowV1 {}
