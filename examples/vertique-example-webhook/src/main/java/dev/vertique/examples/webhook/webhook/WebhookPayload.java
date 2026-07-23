// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook.webhook;

/**
 * Payload for a webhook delivery job.
 *
 * @param paymentId the payment identifier to notify about
 */
public record WebhookPayload(String paymentId) {}
