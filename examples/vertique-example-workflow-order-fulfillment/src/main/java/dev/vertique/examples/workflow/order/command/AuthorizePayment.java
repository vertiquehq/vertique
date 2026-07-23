// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

/**
 * Command dispatched to the payment service to authorize a charge for an order.
 *
 * @param orderId the order identifier used to correlate with the {@code payment.captured} signal
 * @param customerId the customer to charge
 * @param amountCents the amount to authorize in cents
 */
public record AuthorizePayment(String orderId, String customerId, long amountCents) {}
