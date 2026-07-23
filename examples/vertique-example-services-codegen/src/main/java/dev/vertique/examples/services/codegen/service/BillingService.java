// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

import dev.vertique.core.resilience.Timeout;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Contract interface for the billing service demonstrating the <em>direct-impl</em> codegen path.
 *
 * <p>There is exactly one implementation ({@link BillingServiceImpl}) — the prerequisite for
 * {@code vertique-codegen-services} to generate a {@code ServiceContractContributor} at compile
 * time. When multiple implementations are needed (e.g. sandbox vs. production selected at
 * runtime), use the legacy {@code @Services Set<Object>} path as shown in
 * {@code vertique-example-services}.
 *
 * <p>The {@link Timeout} on {@link #charge} is resolved from the <em>contract interface</em>
 * by the generated contributor, ensuring consistent policy enforcement regardless of which
 * implementation is used.
 */
@ServiceContract(value = "billing", namespace = "commerce")
public interface BillingService {

    /**
     * Charges a customer for the given order.
     *
     * @param req the charge request containing order and amount details
     * @return a future containing the receipt for the completed charge
     */
    @ServiceOperation("charge")
    @Timeout(3000)
    Future<Receipt> charge(ChargeRequest req);
}
