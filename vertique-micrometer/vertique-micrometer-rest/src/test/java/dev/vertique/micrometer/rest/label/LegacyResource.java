// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * A resource of the zero-declaration composition's default mount: {@code GET /legacy}, operation
 * {@code legacy}, belonging to no application.
 */
@Path("/legacy")
public class LegacyResource {

    /** Constructed by Dagger for the {@code @JaxRsResources} set. */
    @Inject
    public LegacyResource() {}

    /**
     * Handles {@code GET /legacy}.
     *
     * @return the fixed body {@code "legacy"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String legacy() {
        return "legacy";
    }
}
