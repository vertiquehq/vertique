// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.conformance.bound;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/** Three write operations that accept a body and answer 204 when the validation gate lets it through. */
@Path("/")
public class BoundResource {

    /** The route of {@link #reserved}. */
    public static final String RESERVED = "/reserved";

    /** The route of {@link #plain}. */
    public static final String PLAIN = "/plain";

    /** The route of {@link #nested}. */
    public static final String NESTED = "/nested";

    /**
     * Accepts a body with a reserved name.
     *
     * @param body the body
     */
    @POST
    @Path(RESERVED)
    @Consumes(MediaType.APPLICATION_JSON)
    public void reserved(ReservedBody body) {
        // Nothing to do: the request proves the gate's answer.
    }

    /**
     * Accepts a body without a reserved name.
     *
     * @param body the body
     */
    @POST
    @Path(PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public void plain(PlainBody body) {
        // Nothing to do: the request proves the gate's answer.
    }

    /**
     * Accepts a holder whose published member has a reserved name.
     *
     * @param body the body
     */
    @POST
    @Path(NESTED)
    @Consumes(MediaType.APPLICATION_JSON)
    public void nested(NestedBody body) {
        // Nothing to do: the request proves the gate's answer.
    }
}
