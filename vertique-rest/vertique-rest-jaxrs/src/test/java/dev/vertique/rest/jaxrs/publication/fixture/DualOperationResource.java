// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.publication.fixture;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * T006 TP-004's fixture resource: {@link #withParams(String, String)} declares a path and a query
 * parameter (so {@link CountingSchemaSource} can attach schemas for both, plus one schema for a key
 * this resource declares no parameter for); {@link #withoutBody()} declares neither, standing in for
 * an operation whose schema source result has no body schema.
 */
@Path("/detached")
public class DualOperationResource {

    /** {@link #withoutBody()}'s operation id, the "no body schema" operation. */
    public static final String WITHOUT_BODY_OPERATION_ID = "withoutBody";

    /**
     * Declares a path parameter {@code id} and a query parameter {@code q}.
     *
     * @param id the path id
     * @param q  the query value
     * @return the id and query value, echoed
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String withParams(@PathParam("id") String id, @QueryParam("q") String q) {
        return "id=" + id + ",q=" + q;
    }

    /**
     * Declares no parameters and no body.
     *
     * @return a fixed body
     */
    @GET
    @Path("/plain")
    @Produces(MediaType.TEXT_PLAIN)
    public String withoutBody() {
        return "plain";
    }
}
