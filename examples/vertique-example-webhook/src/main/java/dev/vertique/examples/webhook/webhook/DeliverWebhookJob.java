// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook.webhook;

import dev.vertique.job.delayed.DelayedJobClient;
import dev.vertique.job.delayed.DelayedJobContract;

/**
 * Typed delayed job contract for webhook delivery.
 *
 * <p>Client interface — inject this to enqueue webhook delivery jobs. The framework
 * generates a proxy via {@link dev.vertique.job.delayed.DelayedJobClientFactory}.
 */
@DelayedJobContract(name = "deliver-webhook", maxAttempts = 2)
public interface DeliverWebhookJob extends DelayedJobClient<WebhookPayload> {}
