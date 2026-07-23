// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.WorkflowContract;

/**
 * Contract interface for the JSON-encoding variant of the document-defined order-fulfillment
 * workflow used in the JSON parity IT ({@code DocumentDefinitionEndToEndJsonIT}).
 *
 * <p>The JSON IT uses version 2 of the same {@code order-fulfillment-doc} definition so that its
 * independent Dagger component can register the definition without colliding with the YAML IT's
 * version 1 registration if they ever run against the same registry (they use separate Dagger
 * components, but a distinct version avoids ambiguity).
 */
@WorkflowContract(definitionId = "order-fulfillment-doc", definitionVersion = 2)
public interface OrderFulfillmentDocumentWorkflowV2 {}
