// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Restricted by a role only: {@code @RolesAllowed("catalog-admin")}, no security requirement, and
 * no required action, so its effective policy is restrictive.
 */
@Path("/admin")
@RolesAllowed(AdminResource.ROLE)
public class AdminResource {

    /** The role the resource requires. */
    public static final String ROLE = "catalog-admin";

    /** Public {@code @Inject} constructor. */
    @Inject
    public AdminResource() {}

    /**
     * Handles {@code GET /admin}.
     *
     * @return the fixed body {@code "admin"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String adminReport() {
        return "admin";
    }
}
