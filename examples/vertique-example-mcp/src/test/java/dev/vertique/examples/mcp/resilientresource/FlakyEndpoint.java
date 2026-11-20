// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Retry;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS server resource interface that carries the resilience anchor and its retry declaration on
 * the interface method; the implementor declares neither.
 */
@Path("/flaky")
@Produces(MediaType.TEXT_PLAIN)
public interface FlakyEndpoint {

    /**
     * Succeeds only on the third attempt.
     *
     * @return the result of the third attempt
     */
    @GET
    @Resilient
    @Retry(maxRetries = 3, delayMs = 1, maxDelayMs = 1)
    Future<String> flaky();
}
