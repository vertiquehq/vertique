// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry.failing.v1;

import dev.vertique.workflow.types.WorkflowType;

/**
 * The default scope's purchase state as the first deployment ships it. A document state that carries an {@code
 * orderId} of the same type extends it.
 *
 * @param orderId the order the purchase belongs to
 */
@WorkflowType("purchase-state")
public record PurchaseState(String orderId) {}
