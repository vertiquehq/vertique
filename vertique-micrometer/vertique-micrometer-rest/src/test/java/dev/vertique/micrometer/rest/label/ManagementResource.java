// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest.label;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The {@code mgmt} application's sole resource: {@code GET /status}, operation {@code status},
 * authenticated by the {@value StubScheme#NAME} scheme and restricted to role {@code admin}. A request
 * without a credential is rejected with 401 by the scheme's authentication handler; an authenticated
 * caller without the role is rejected with 403 by the fixture's role check. Both rejections happen
 * after the route recorded its operation, so their completion events carry this operation and its
 * application.
 */
@Path("/status")
public class ManagementResource {

    /** Constructed by Dagger through its catalog entry. */
    @Inject
    public ManagementResource() {}

    /**
     * Handles {@code GET /status} for an {@code admin}.
     *
     * @return the fixed body {@code "ok"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @RolesAllowed("admin")
    @SecurityRequirement(name = StubScheme.NAME)
    public String status() {
        return "ok";
    }
}
