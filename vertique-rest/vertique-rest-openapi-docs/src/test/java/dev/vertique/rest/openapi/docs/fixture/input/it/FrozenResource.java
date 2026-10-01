// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link FrozenApi}: two operations, each with a body and a query parameter, so the
 * schema source returns two body schemas (one with a root {@code $defs} entry) and two parameter
 * schemas.
 */
@Path("/garden")
public class FrozenResource {

    /** The operation id of {@link #plantTree}, whose body carries root {@code $defs}. */
    public static final String PLANT_TREE = "plantTree";

    /** The operation id of {@link #recordNote}. */
    public static final String RECORD_NOTE = "recordNote";

    /** Creates the resource. */
    public FrozenResource() {}

    /**
     * Handles {@code POST /garden/trees}; answers with an empty response.
     *
     * @param mode    query {@code mode}
     * @param request the body with a self-referential member
     */
    @POST
    @Path("/trees")
    @Consumes(MediaType.APPLICATION_JSON)
    public void plantTree(@QueryParam("mode") String mode, GroveRequest request) {
        // Nothing to do: no test sends a request to this operation.
    }

    /**
     * Handles {@code POST /garden/notes}; answers with an empty response.
     *
     * @param tag     query {@code tag}, at most eight characters
     * @param request the body
     */
    @POST
    @Path("/notes")
    @Consumes(MediaType.APPLICATION_JSON)
    public void recordNote(@QueryParam("tag") @Size(max = 8) String tag, NoteRequest request) {
        // Nothing to do: no test sends a request to this operation.
    }
}
