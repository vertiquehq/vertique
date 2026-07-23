// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

/**
 * Signal payload for the {@code inventory.reserved} signal in the document-defined
 * order-fulfillment integration tests.
 *
 * @param reservationId the reservation identifier assigned by the inventory service
 */
public record InventoryReservedDoc(String reservationId) {}
