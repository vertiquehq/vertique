// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The unsecured resource of every {@code management} declaration of the misconfigured-protected-
 * document fixtures: one operation, {@code GET /status}, so the application's own JAX-RS mount adds
 * no security of its own beside the document under test.
 */
@Path("/status")
public class ManagementStatusResource {

    /** Creates the resource. */
    public ManagementStatusResource() {}

    /**
     * Handles {@code GET /status}.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String getManagementStatus() {
        return "management";
    }
}
