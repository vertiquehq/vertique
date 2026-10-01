// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

/**
 * The twin search resource the reflective scanner describes: it has no generated companion.
 * Declaration for declaration it is {@link GeneratedSearchResource}, except for its operation id and
 * its composite classes ({@link ReflectedSearchFilters}, {@link ReflectedSearchOptions}); see {@link
 * TwinInputs} for the bindings.
 */
@Path(TwinInputs.RESOURCE_PATH)
public class ReflectedSearchResource {

    /** The operation id of {@link #search}. */
    public static final String OPERATION_ID = "searchReflected";

    /** Creates the resource. */
    public ReflectedSearchResource() {}

    /**
     * Handles {@code GET /search/{id}}; answers with an empty response.
     *
     * @param id      path {@code id}
     * @param filters the bean-param composite, not cascaded into
     * @param sku     query {@code sku}, rejected when absent
     * @param verbose query {@code verbose}, primitive without a default
     * @param options the request-params record
     * @param page    query {@code page}, defaulting to {@code 1}
     */
    @GET
    @Path(TwinInputs.METHOD_PATH)
    @Operation(operationId = OPERATION_ID)
    public void search(
            @PathParam("id") @Pattern(regexp = TwinInputs.ID_PATTERN) String id,
            @BeanParam ReflectedSearchFilters filters,
            @QueryParam("sku") @NotNull String sku,
            @QueryParam("verbose") boolean verbose,
            ReflectedSearchOptions options,
            @QueryParam("page") @DefaultValue(TwinInputs.PAGE_DEFAULT) int page) {
        // Nothing to do: no test sends a request to this operation.
    }
}
