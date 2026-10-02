// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The unsecured resource of {@link AlphaApi}: one operation, {@code GET /status}. */
@Path("/status")
public class AlphaStatusResource {

    /** Creates the resource. */
    public AlphaStatusResource() {}

    /**
     * Handles {@code GET /status}.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getAlphaStatus() {
        return "alpha";
    }
}
