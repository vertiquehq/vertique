// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T005 TP-002 case (c): protected only by a scopeless class-level {@link SecurityRequirement} for
 * {@link PolicySecurityModule#SCHEME_NAME} (no {@code scopes()}), so
 * {@code securityRequirementSets()} is a single non-anonymous set. Under C-POLICY this restricts
 * callers — no FR-013 warning — even though the raw {@code SecurityPolicy} stays {@code None}.
 * {@link PolicySecurityModule} installs {@link PolicySecurityModule#SCHEME_NAME}'s handler so
 * startup does not fail-closed on a declared scheme with no collected
 * {@link io.vertx.ext.web.handler.AuthenticationHandler}.
 */
@Path("/scopeless")
@SecurityRequirement(name = PolicySecurityModule.SCHEME_NAME)
public class ScopelessRequirementResource {

    /** Default constructor, injected as a lazy {@link jakarta.inject.Provider}. */
    @Inject
    public ScopelessRequirementResource() {}

    /**
     * Handles {@code GET .../scopeless}.
     *
     * @return the fixed body {@code "scopeless"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String scopelessGet() {
        return "scopeless";
    }
}
