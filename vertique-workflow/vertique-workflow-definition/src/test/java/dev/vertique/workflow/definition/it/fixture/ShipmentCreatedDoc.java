// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

/**
 * Signal payload for the {@code shipment.created} signal in the document-defined
 * order-fulfillment integration tests.
 *
 * @param trackingNumber the tracking number assigned by the shipping service
 */
public record ShipmentCreatedDoc(String trackingNumber) {}
