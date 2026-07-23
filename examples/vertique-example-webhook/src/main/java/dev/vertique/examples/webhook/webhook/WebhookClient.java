// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.webhook.webhook;

import dev.vertique.rest.client.RestClient;
import io.vertx.core.Future;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/**
 * Declarative REST client for webhook delivery to a fixed base URL.
 *
 * <p>In a real application, this would use {@code @Url} for dynamic per-call URLs
 * once that feature is implemented (see PRD: rest-client-dynamic-url).
 */
@RestClient(value = "https://webhook-receiver.example.com", name = "webhook")
@Path("/api/webhooks")
public interface WebhookClient {

    /**
     * Notifies the webhook receiver about a payment.
     *
     * @param paymentId the payment identifier
     * @return a future that completes when the notification is delivered
     */
    @POST
    @Path("/notify/{paymentId}")
    Future<Void> notify(@PathParam("paymentId") String paymentId);
}
