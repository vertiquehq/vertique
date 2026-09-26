// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy;

import dev.vertique.security.authz.RequiresAction;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * T005 TP-002 case (d): protected only by a method-level {@link RequiresAction} whose action
 * resolves — {@link PolicySecurityModule} binds an {@code ActionRegistry} holding
 * {@link PolicySecurityModule#REQUIRED_ACTION}, an {@code Authorizer}, and the
 * {@code AuthEnforcementCapability} marker so the action gate is enforceable. Under C-POLICY a
 * resolved required action restricts callers — no FR-013 warning.
 */
@Path("/action")
public class RequiresActionResource {

    /** Default constructor, injected as a lazy {@link jakarta.inject.Provider}. */
    @Inject
    public RequiresActionResource() {}

    /**
     * Handles {@code GET .../action}.
     *
     * @return the fixed body {@code "action"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @RequiresAction(PolicySecurityModule.REQUIRED_ACTION)
    public String requiresActionGet() {
        return "action";
    }
}
