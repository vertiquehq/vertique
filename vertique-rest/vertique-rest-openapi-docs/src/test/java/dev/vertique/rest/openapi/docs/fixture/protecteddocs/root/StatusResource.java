// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.root;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The root application's only resource: {@code GET /status}, which requires the {@value
 * InternalAdminApi#SECURITY_SCHEME} scheme and the {@value InternalAdminApi#ROLE} role and answers
 * the fixed body {@value #BODY}.
 */
@Path(StatusResource.ROUTE)
public class StatusResource {

    /** The resource path, relative to the root mount. */
    public static final String ROUTE = "/status";

    /** The body every successful request gets. */
    public static final String BODY = "up";

    /** Creates the resource. */
    public StatusResource() {}

    /**
     * Handles {@code GET /status}.
     *
     * @return a fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @SecurityRequirement(name = InternalAdminApi.SECURITY_SCHEME)
    @RolesAllowed(InternalAdminApi.ROLE)
    public String status() {
        return BODY;
    }
}
