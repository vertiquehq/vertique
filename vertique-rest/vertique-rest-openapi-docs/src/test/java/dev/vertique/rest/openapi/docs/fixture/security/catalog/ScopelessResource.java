// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Restricted by a security requirement only: a scopeless {@code @SecurityRequirement} for
 * {@value CatalogSecurityModule#SCHEME_NAME}, no role, and no required action. Its effective policy
 * stays non-restrictive, yet it restricts callers through its requirement set.
 */
@Path("/scopeless")
@SecurityRequirement(name = CatalogSecurityModule.SCHEME_NAME)
public class ScopelessResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ScopelessResource() {}

    /**
     * Handles {@code GET /scopeless}.
     *
     * @return the fixed body {@code "scopeless"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String scopelessGet() {
        return "scopeless";
    }
}
