// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.codegen.service;

/**
 * Receipt returned after a successful billing charge.
 *
 * @param receiptId   unique identifier for this receipt
 * @param orderId     the order that was charged
 * @param amountCents the amount charged in cents
 * @param currency    ISO 4217 currency code
 */
public record Receipt(String receiptId, String orderId, long amountCents, String currency) {}
