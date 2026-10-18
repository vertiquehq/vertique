// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

/**
 * Signal payload for the {@code payment.captured} signal in the document-defined
 * order-fulfillment integration tests.
 *
 * @param authId the authorization identifier assigned by the payment service
 */
public record PaymentCapturedDoc(String authId) {}
