// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory direct implementation of {@link BillingService}.
 *
 * <p>Demonstrates the <em>direct-impl</em> codegen path: this class directly
 * {@code implements BillingService}, has exactly one {@code @Inject} constructor, and is
 * annotated with {@code @Singleton}. The {@code vertique-codegen-services} processor discovers
 * it at compile time and generates a {@code BillingService_ContractContributor} that registers
 * it with the event bus dispatch infrastructure — no manual {@code ServiceModule} wiring required.
 *
 * <p>This is a stub implementation for demonstration; a real billing service would integrate
 * with a payment gateway.
 */
@Singleton
@Slf4j
public class BillingServiceImpl implements BillingService {

    /**
     * Creates a new in-memory billing service.
     */
    @Inject
    public BillingServiceImpl() {}

    /**
     * Processes a charge by generating a receipt with a random tracking identifier.
     *
     * @param req the charge request
     * @return a future containing the generated receipt
     */
    @Override
    public Future<Receipt> charge(ChargeRequest req) {
        String receiptId = UUID.randomUUID().toString();
        log.info("Processing charge: orderId={}, amount={} {}", req.orderId(), req.amountCents(), req.currency());
        return Future.succeededFuture(new Receipt(receiptId, req.orderId(), req.amountCents(), req.currency()));
    }
}
