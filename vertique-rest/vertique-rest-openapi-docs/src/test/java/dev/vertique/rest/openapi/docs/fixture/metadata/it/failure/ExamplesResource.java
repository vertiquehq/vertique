// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link ExamplesApi}: one read operation whose query parameter {@value #QUERY}
 * carries an example that refers to a component, with a sentinel name no message may echo.
 */
@Path(ExamplesResource.ROUTE)
public class ExamplesResource {

    /** The route, relative to the mount. */
    public static final String ROUTE = "/examples";

    /** The operation id of {@link #listExamples}. */
    public static final String OPERATION_ID = "listExamples";

    /** The query parameter's name. */
    public static final String QUERY = "q";

    /** The sentinel reference of the example. */
    public static final String REFERENCE = "#/components/examples/REFZX";

    /** Creates the resource. */
    public ExamplesResource() {}

    /**
     * Handles {@code GET /examples} and echoes the query.
     *
     * @param q the query, whose example is a reference
     * @return the query
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String listExamples(
            @QueryParam(QUERY) @Parameter(examples = @ExampleObject(name = "e", ref = REFERENCE)) String q) {
        return String.valueOf(q);
    }
}
