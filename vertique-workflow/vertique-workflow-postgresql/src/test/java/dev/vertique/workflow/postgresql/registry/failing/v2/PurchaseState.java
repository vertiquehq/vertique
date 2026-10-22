// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry.failing.v2;

import dev.vertique.workflow.types.WorkflowType;

/**
 * The default scope's purchase state as a later deployment ships it: the same logical name as the first deployment's
 * class, with one more property that a document state of the first deployment does not have.
 *
 * @param orderId the order the purchase belongs to
 * @param costCenter the cost center the purchase is booked to
 */
@WorkflowType("purchase-state")
public record PurchaseState(String orderId, String costCenter) {}
