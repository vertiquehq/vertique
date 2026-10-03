// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A resource of the application {@code beta}: {@code GET /b} ({@value #OPERATION_ID}). */
@Path(BetaResource.ROUTE)
public class BetaResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/b";

    /** The operation id of {@link #listB}. */
    public static final String OPERATION_ID = "listB";

    /** The fixed body the resource answers with. */
    public static final String BODY = "listB";

    /** Creates the resource. */
    public BetaResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listB() {
        return BODY;
    }
}
