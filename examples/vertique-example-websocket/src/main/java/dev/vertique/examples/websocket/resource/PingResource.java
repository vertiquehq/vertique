// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.websocket.resource;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Minimal health-check resource that demonstrates HTTP and WebSocket endpoints coexisting on the
 * same Vert.x instance.
 */
@Path("/ping")
public class PingResource {

    /** Creates a new {@link PingResource}. */
    @Inject
    public PingResource() {}

    /**
     * Returns a plain-text {@code pong} response.
     *
     * @return the string {@code "pong"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(operationId = "ping", summary = "Health-check ping")
    public String ping() {
        return "pong";
    }
}
