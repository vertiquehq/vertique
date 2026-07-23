// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.workflow.order.command;

/**
 * Compensation command dispatched to the payment service to void a prior authorization.
 *
 * @param orderId the order identifier
 * @param authorizationId the authorization id to void; matches the value stored in workflow state
 */
public record VoidAuthorization(String orderId, String authorizationId) {}
