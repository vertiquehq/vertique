// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import dev.vertique.security.authz.RequiresAction;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Restricted by a required action only: {@code @RequiresAction} naming
 * {@value CatalogSecurityModule#REQUIRED_ACTION}, no role, and no security requirement. Its
 * effective policy stays non-restrictive, yet it restricts callers through the action gate.
 */
@Path("/action")
public class ActionResource {

    /** Public {@code @Inject} constructor. */
    @Inject
    public ActionResource() {}

    /**
     * Handles {@code GET /action}.
     *
     * @return the fixed body {@code "action"}
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    @RequiresAction(CatalogSecurityModule.REQUIRED_ACTION)
    public String actionGet() {
        return "action";
    }
}
