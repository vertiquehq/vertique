// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.WorkflowContract;

/**
 * Contract interface for the YAML/JSON document-defined order-fulfillment workflow used in the
 * Slice H end-to-end integration tests.
 *
 * <p>This interface is the contract marker for version 1 of the {@code order-fulfillment-doc}
 * definition. It is intentionally separate from any production contract to keep the IT
 * self-contained.
 *
 * <p>Two versions exist so that the YAML IT and JSON IT can each independently register the same
 * canonical content without triggering the registry's duplicate-rejection — each IT uses its own
 * Dagger component and its own database table state.
 */
@WorkflowContract(definitionId = "order-fulfillment-doc", definitionVersion = 1)
public interface OrderFulfillmentDocumentWorkflowV1 {}
