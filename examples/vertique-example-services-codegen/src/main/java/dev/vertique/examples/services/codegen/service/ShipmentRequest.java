// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

/**
 * Request payload for a shipment operation.
 *
 * @param orderId         the order to ship
 * @param destinationCity the destination city for delivery
 */
public record ShipmentRequest(String orderId, String destinationCity) {}
