// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.state;

/**
 * Application-level status of an order-fulfillment saga instance.
 *
 * <p>Distinct from {@link dev.vertique.workflow.state.WorkflowStatus} — this captures business
 * state rather than engine state.
 */
public enum OrderStatus {
    /** Order accepted; waiting for inventory reservation. */
    PENDING,
    /** Inventory reserved; waiting for payment authorization. */
    INVENTORY_RESERVED,
    /** Payment authorized; waiting for shipment creation. */
    PAYMENT_AUTHORIZED,
    /** Shipment created; order is complete. */
    SHIPPED,
    /** Saga failed and compensation is complete or not required. */
    FAILED
}
