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
 * T005 TP-002 case (e): class-level {@link PermitAll} together with a scoped class-level
 * {@link SecurityRequirement} (non-empty {@code scopes()}) for
 * {@link PolicySecurityModule#SCHEME_NAME}. Per RL-8 this case is a guard only: both "declared
 * public" ({@code PermitAll}, no restricting declaration) and "restricts callers" (a non-empty,
 * non-anonymous requirement set) suppress the FR-013 warning, so this fixture cannot itself
 * distinguish which rule wins — that precedence is proven by
 * {@code ExplicitPolicyClassificationTest} (TP-005) with constructed inputs.
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
