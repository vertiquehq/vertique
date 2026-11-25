// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Case (e): class-level {@link PermitAll} together with a scoped class-level
 * {@link SecurityRequirement} (non-empty {@code scopes()}) for
 * {@link PolicySecurityModule#SCHEME_NAME}. The combination is rejected at startup, because
 * {@code @PermitAll} skips authorization and the scopes would never be checked; the test mounts this
 * resource to prove that failure.
 */
@Path("/permitted-scoped")
@PermitAll
@SecurityRequirement(
        name = PolicySecurityModule.SCHEME_NAME,
        scopes = {"read"})
public class PermitAllScopedResource {

    /** Default constructor, injected as a lazy {@link jakarta.inject.Provider}. */
    @Inject
    public PermitAllScopedResource() {}

    /**
     * Handles {@code GET .../permitted-scoped}.
     *
     * @return the fixed body {@code "permitted-scoped"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String permittedScopedGet() {
        return "permitted-scoped";
    }
}
