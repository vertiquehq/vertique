// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp.resilientresource;

import dev.vertique.resilience.annotation.Resilient;
import dev.vertique.resilience.annotation.Timeout;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS server resource interface that carries the resilience anchor and a short timeout on the
 * interface method; the implementor declares neither.
 */
@Path("/slow")
@Produces(MediaType.TEXT_PLAIN)
public interface SlowEndpoint {

    /**
     * Answers only after far longer than the declared timeout.
     *
     * @return the late answer
     */
    @GET
    @Resilient
    @Timeout(value = 100)
    Future<String> slow();
}
