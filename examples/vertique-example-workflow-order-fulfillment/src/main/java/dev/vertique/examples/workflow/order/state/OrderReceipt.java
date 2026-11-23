// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.state;

import dev.vertique.workflow.types.WorkflowType;
import jakarta.annotation.Nullable;

/**
 * What a completed order-fulfillment instance produces: the declared output of the definition.
 *
 * <p>Callers read it with {@code WorkflowOperations.result(id, OrderReceipt.class)}, and the
 * {@code WORKFLOW_COMPLETED} event carries it, without either knowing {@link OrderState}.
 *
 * @param orderId the client-assigned order identifier
 * @param customerId the customer who placed the order
 * @param totalCents total order amount in cents
 * @param trackingNumber the shipment tracking number
 */
@WorkflowType("order.Receipt")
public record OrderReceipt(String orderId, String customerId, long totalCents, @Nullable String trackingNumber) {}
