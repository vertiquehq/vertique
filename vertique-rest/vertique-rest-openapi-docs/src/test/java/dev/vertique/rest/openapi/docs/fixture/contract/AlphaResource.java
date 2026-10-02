// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** A resource of the application {@code alpha}: {@code GET /a} ({@value #OPERATION_ID}). */
@Path(AlphaResource.ROUTE)
public class AlphaResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/a";

    /** The operation id of {@link #listA}. */
    public static final String OPERATION_ID = "listA";

    /** The fixed body the resource answers with. */
    public static final String BODY = "listA";

    /** Creates the resource. */
    public AlphaResource() {}

    /**
     * Answers with {@value #BODY}.
     *
     * @return the fixed body
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listA() {
        return BODY;
    }
}
