// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.CreateShipment;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for shipment creation operations.
 *
 * <p>Stable target id (type empty, value = "shipping"):
 * <ul>
 *   <li>{@code shipping.create-shipment} — create a shipment for a fulfilled order</li>
 * </ul>
 *
 * <p>The workflow definition references this target id in
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentDefinition}.
 */
@ServiceContract(value = "shipping")
public interface ShippingService {

    /**
     * Creates a shipment for the given fulfilled order.
     *
     * <p>On success, the implementation posts a
     * {@link dev.vertique.examples.workflow.order.signal.ShipmentCreated} signal back to the
     * workflow engine.
     *
     * @param cmd the create-shipment command
     * @return a {@link Future} that completes when the shipment is created
     */
    @ServiceOperation("create-shipment")
    Future<Void> createShipment(CreateShipment cmd);
}
