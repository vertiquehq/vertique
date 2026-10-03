// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Minimal single-operation JAX-RS resource reused across T006's non-empty mounts (TP-005, TP-008)
 * wherever a test needs a mount to be non-empty but the operation's own shape does not matter.
 */
@Path("/echo")
public class EchoResource {

    /**
     * Echoes a fixed body.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String echo() {
        return "echo";
    }
}
