// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

/**
 * Request payload for a billing charge operation.
 *
 * @param orderId    the unique order identifier to charge
 * @param amountCents the amount to charge in cents (smallest currency unit)
 * @param currency    ISO 4217 currency code, e.g. {@code "USD"}
 */
public record ChargeRequest(String orderId, long amountCents, String currency) {}
