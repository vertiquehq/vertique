// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.service;

import dev.vertique.examples.workflow.order.command.AuthorizePayment;
import dev.vertique.examples.workflow.order.command.VoidAuthorization;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

/**
 * Service contract for payment authorization operations.
 *
 * <p>Stable target ids (type empty, value = "payment"):
 * <ul>
 *   <li>{@code payment.authorize} — authorize a charge for an order</li>
 *   <li>{@code payment.void-authorization} — void a prior authorization (compensation)</li>
 * </ul>
 *
 * <p>The workflow definition references these target ids in
 * {@link dev.vertique.examples.workflow.order.OrderFulfillmentDefinition}.
 */
@ServiceContract(value = "payment")
public interface PaymentService {

    /**
     * Authorizes a payment charge for the given order.
     *
     * <p>On success, the implementation posts a
     * {@link dev.vertique.examples.workflow.order.signal.PaymentCaptured} signal back to the
     * workflow engine.
     *
     * @param cmd the authorization command
     * @return a {@link Future} that completes when the authorization is recorded
     */
    @ServiceOperation("authorize")
    Future<Void> authorize(AuthorizePayment cmd);

    /**
     * Voids a prior payment authorization (compensation).
     *
     * @param cmd the void-authorization command
     * @return a {@link Future} that completes when the authorization is voided
     */
    @ServiceOperation("void-authorization")
    Future<Void> voidAuthorization(VoidAuthorization cmd);
}
