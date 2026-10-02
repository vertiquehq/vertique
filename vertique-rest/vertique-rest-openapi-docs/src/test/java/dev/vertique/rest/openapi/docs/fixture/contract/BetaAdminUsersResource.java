// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A resource of the application {@code beta}: {@code GET /admin/users} ({@value #OPERATION_ID}). */
@Path(BetaAdminUsersResource.ROUTE)
public class BetaAdminUsersResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/admin/users";

    /** The operation id of {@link #listUsers}. */
    public static final String OPERATION_ID = "listUsers";

    /** The fixed body the resource answers with. */
    public static final String BODY = "listUsers";

    /** Creates the resource. */
    public BetaAdminUsersResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listUsers() {
        return BODY;
    }
}
