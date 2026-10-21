// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

import dev.vertique.workflow.contract.WorkflowContract;

/**
 * Contract interface for the YAML/JSON document-defined order-fulfillment workflow used in the
 * Slice H end-to-end integration tests.
 *
 * <p>This interface is the contract of the {@code order-fulfillment-doc} definition. It is
 * intentionally separate from any production contract to keep the IT self-contained, and it
 * declares the signals the document waits for through {@link OrderDocumentSignals}.
 *
 * <p>The YAML IT registers version 1 and the JSON IT version 2 of the definition; both name this
 * one contract, since every version of a definition names the same contract. Each IT uses its own
 * Dagger component and its own database table state.
 */
@WorkflowContract
public interface OrderFulfillmentDocumentWorkflowV1 extends OrderDocumentSignals {}
