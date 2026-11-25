// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Case (b): a class-level {@link PermitAll} resource with no security requirement, so
 * its sole operation is declared public under the policy classification (does not restrict callers, effective
 * policy {@code PermitAll}) rather than implicit — no implicit-policy warning.
 */
@Path("/permitted")
@PermitAll
public class PermitAllResource {

    /** Default constructor, injected as a lazy {@link jakarta.inject.Provider}. */
    @Inject
    public PermitAllResource() {}

    /**
     * Handles {@code GET .../permitted}.
     *
     * @return the fixed body {@code "permitted"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String permittedGet() {
        return "permitted";
    }
}
