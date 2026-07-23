// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

import dev.vertique.security.SecurityContext;
import dev.vertique.services.ServiceHandler;
import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Handler-pattern implementation of {@link ShippingService}.
 *
 * <p>Demonstrates the <em>handler-pattern</em> codegen path: this class
 * {@code implements ServiceHandler<ShippingService>}, has exactly one {@code @Inject} constructor,
 * and is annotated with {@code @Singleton}. Handler methods accept an additional
 * {@link SecurityContext} parameter beyond what the contract declares — the framework injects
 * it automatically from the dispatch context at runtime.
 *
 * <p>The {@code vertique-codegen-services} processor discovers this handler at compile time,
 * resolves the {@code ShippingService} type argument, and generates a
 * {@code ShippingService_ContractContributor} that wires it into the event bus dispatch
 * infrastructure without any manual module code.
 *
 * <p>This is a stub implementation for demonstration; a real shipping service would integrate
 * with a fulfilment provider.
 */
@Singleton
@Slf4j
public class ShippingServiceHandler implements ServiceHandler<ShippingService> {

    /**
     * Creates a new shipping service handler.
     */
    @Inject
    public ShippingServiceHandler() {}

    /**
     * Dispatches a shipment, optionally logging the requesting principal from the security context.
     *
     * @param req the shipment request
     * @param sc  the security context auto-injected by the framework; may be {@code null}
     * @return a future containing the shipment receipt with a generated tracking identifier
     */
    public Future<ShipmentReceipt> ship(ShipmentRequest req, SecurityContext sc) {
        if (sc != null) {
            log.info(
                    "Shipping order {} to {} (requested by {})",
                    req.orderId(),
                    req.destinationCity(),
                    sc.identity().actor().id());
        } else {
            log.info("Shipping order {} to {}", req.orderId(), req.destinationCity());
        }
        String trackingId = UUID.randomUUID().toString();
        return Future.succeededFuture(new ShipmentReceipt(trackingId, req.orderId(), req.destinationCity()));
    }

    /**
     * Handles the fire-and-forget dispatch notification.
     *
     * @param orderId the order identifier for the dispatched shipment
     * @param sc      the security context auto-injected by the framework; may be {@code null}
     * @return a future that completes when notification processing finishes
     */
    public Future<Void> notifyDispatch(String orderId, SecurityContext sc) {
        if (sc != null) {
            log.info(
                    "Dispatch notification for order {} (sent by {})",
                    orderId,
                    sc.identity().actor().id());
        } else {
            log.info("Dispatch notification for order {}", orderId);
        }
        return Future.succeededFuture();
    }
}
