// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

import dev.vertique.core.resilience.CircuitBreaker;
import dev.vertique.services.OneWay;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Contract interface for the shipping service demonstrating the <em>handler-pattern</em> codegen path.
 *
 * <p>There is exactly one handler implementation ({@link ShippingServiceHandler}) which
 * {@code implements ServiceHandler<ShippingService>}. Handler methods accept additional
 * {@link dev.vertique.security.SecurityContext} parameters that are not part of the
 * contract — the framework injects them from the dispatch context automatically.
 *
 * <p>The class-level {@link CircuitBreaker} is resolved from this contract interface by the
 * generated contributor, applying the policy to all operations consistently.
 */
@ServiceContract(value = "shipping", namespace = "commerce")
@CircuitBreaker(maxFailures = 3, timeoutMs = 5000)
public interface ShippingService {

    /**
     * Dispatches a shipment for the given order.
     *
     * @param req the shipment request containing order and destination details
     * @return a future containing the shipment receipt with tracking information
     */
    @ServiceOperation("ship")
    Future<ShipmentReceipt> ship(ShipmentRequest req);

    /**
     * Notifies downstream systems that a shipment has been dispatched.
     *
     * <p>This is a fire-and-forget operation — the caller does not wait for
     * processing to complete and receives no acknowledgement.
     *
     * @param orderId the order identifier for the dispatched shipment
     * @return a future that completes immediately after the message is sent
     */
    @ServiceOperation("notifyDispatch")
    @OneWay
    Future<Void> notifyDispatch(String orderId);
}
