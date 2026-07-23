// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

/**
 * Confirmation returned after a successful shipment dispatch.
 *
 * @param trackingId      unique shipment tracking identifier
 * @param orderId         the order that was shipped
 * @param destinationCity the destination city
 */
public record ShipmentReceipt(String trackingId, String orderId, String destinationCity) {}
