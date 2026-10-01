// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.patterns;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link PatternsApi}: a case-insensitively bound body, a body with an authored
 * flagged pattern, and a path and a query parameter with authored patterns.
 */
@Path("/patterns")
public class PatternsResource {

    /** The operation id of {@link #submitFolded}. */
    public static final String SUBMIT_FOLDED = "submitFolded";

    /** The operation id of {@link #submitFlagged}. */
    public static final String SUBMIT_FLAGGED = "submitFlagged";

    /** The operation id of {@link #findCode}. */
    public static final String FIND_CODE = "findCode";

    /** The name of the path parameter of {@link #findCode}. */
    public static final String ID = "id";

    /** The authored regular expression of the {@code id} path parameter. */
    public static final String ID_PATTERN = "^[0-9]+$";

    /** The name of the query parameter of {@link #findCode}. */
    public static final String NAME = "name";

    /** The authored regular expression of the {@code name} query parameter. */
    public static final String NAME_PATTERN = "^[a-z]+$";

    /** Creates the resource. */
    public PatternsResource() {}

    /**
     * Handles {@code POST /patterns/folded}; answers with an empty response.
     *
     * @param request the case-insensitively bound body
     */
    @POST
    @Path("/folded")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = SUBMIT_FOLDED)
    public void submitFolded(FoldedRequest request) {
        // Nothing to do: no test sends a request to this operation.
    }

    /**
     * Handles {@code POST /patterns/flagged}; answers with an empty response.
     *
     * @param request the body whose member carries an authored pattern with flags
     */
    @POST
    @Path("/flagged")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = SUBMIT_FLAGGED)
    public void submitFlagged(FlaggedRequest request) {
        // Nothing to do: no test sends a request to this operation.
    }

    /**
     * Handles {@code GET /patterns/codes/{id}}; answers with an empty response.
     *
     * @param id   path {@code id}, digits only
     * @param name query {@code name}, lowercase letters only
     */
    @GET
    @Path("/codes/{" + ID + "}")
    @Operation(operationId = FIND_CODE)
    public void findCode(
            @PathParam(ID) @Pattern(regexp = ID_PATTERN) String id,
            @QueryParam(NAME) @Pattern(regexp = NAME_PATTERN) String name) {
        // Nothing to do: no test sends a request to this operation.
    }
}
