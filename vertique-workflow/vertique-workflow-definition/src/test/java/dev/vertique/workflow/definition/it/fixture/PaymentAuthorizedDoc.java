// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.it.fixture;

/**
 * Signal payload for the {@code payment.authorized.doc} event in the document-defined fan-out
 * integration test.
 *
 * <p>This record is intentionally distinct from any Slice H payment signal to avoid coupling between
 * the linear saga IT and the fan-out IT. Only used by
 * {@link dev.vertique.workflow.definition.it.DocumentDefinitionFanOutIT}.
 *
 * @param paymentChargeId the charge identifier returned by the payment authorization service
 */
public record PaymentAuthorizedDoc(String paymentChargeId) {}
