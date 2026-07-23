// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

/**
 * Compensation command dispatched to the inventory service to release a prior reservation.
 *
 * @param orderId the order identifier
 * @param reservationId the reservation id to release; matches the value stored in workflow state
 */
public record ReleaseInventory(String orderId, String reservationId) {}
