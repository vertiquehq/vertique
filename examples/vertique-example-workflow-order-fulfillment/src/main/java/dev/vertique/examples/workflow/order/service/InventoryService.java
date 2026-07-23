// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.ReleaseInventory;
import dev.vertique.examples.workflow.order.command.ReserveInventory;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for inventory management operations.
 *
 * <p>Stable target ids (type empty, value = "inventory"):
 * <ul>
 *   <li>{@code inventory.reserve} — reserve stock for an order</li>
 *   <li>{@code inventory.release} — release a prior reservation (compensation)</li>
 * </ul>
 *
 * <p>The workflow definition references these target ids in
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentDefinition}.
 */
@ServiceContract(value = "inventory")
public interface InventoryService {

    /**
     * Reserves stock for the given order.
     *
     * <p>On success, the implementation posts an
     * {@link dev.vertique.examples.workflow.order.signal.InventoryReserved} signal back to the
     * workflow engine.
     *
     * @param cmd the reservation command
     * @return a {@link Future} that completes when the reservation is recorded
     */
    @ServiceOperation("reserve")
    Future<Void> reserve(ReserveInventory cmd);

    /**
     * Releases a prior inventory reservation (compensation).
     *
     * @param cmd the release command
     * @return a {@link Future} that completes when the reservation is released
     */
    @ServiceOperation("release")
    Future<Void> release(ReleaseInventory cmd);
}
