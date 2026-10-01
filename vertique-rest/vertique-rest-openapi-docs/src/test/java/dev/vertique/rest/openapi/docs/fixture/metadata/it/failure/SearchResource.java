// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link SearchApi}: one read operation whose query parameter {@value #QUERY}
 * declares a location other than the one it binds from, with sentinel texts no message may echo.
 */
@Path(SearchResource.ROUTE)
public class SearchResource {

    /** The route, relative to the mount. */
    public static final String ROUTE = "/search";

    /** The operation id of {@link #searchItems}. */
    public static final String OPERATION_ID = "searchItems";

    /** The query parameter's name. */
    public static final String QUERY = "q";

    /** The sentinel description of the parameter. */
    public static final String DESCRIPTION = "DESCZX";

    /** The sentinel example of the parameter. */
    public static final String EXAMPLE = "EXAMPLEZX";

    /** Creates the resource. */
    public SearchResource() {}

    /**
     * Handles {@code GET /search} and echoes the query.
     *
     * @param q the query, declared as a header on the parameter annotation
     * @return the query
     */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String searchItems(
            @QueryParam(QUERY) @Parameter(in = ParameterIn.HEADER, description = DESCRIPTION, example = EXAMPLE)
                    String q) {
        return String.valueOf(q);
    }
}
