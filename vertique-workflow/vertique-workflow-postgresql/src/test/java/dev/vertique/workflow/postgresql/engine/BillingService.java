// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import io.vertx.core.Future;

/** The contract of the downstream service a workflow dispatches {@code billing.charge} to. */
public interface BillingService {

    /**
     * The request of the operation.
     *
     * @param ref the label of the workflow instance that charges
     */
    record ChargeRequest(String ref) {}

    /**
     * Charges.
     *
     * @param request the request
     * @return a future that completes when the charge is done
     */
    Future<Void> charge(ChargeRequest request);
}
