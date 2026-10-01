// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input.it;

import jakarta.validation.constraints.Pattern;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link VerbatimApi}: one operation whose body member and query parameter each
 * carry an authored {@code @Pattern}.
 */
@Path("/expressions")
public class VerbatimResource {

    /** The operation id of {@link #evaluateExpression}. */
    public static final String OPERATION_ID = "evaluateExpression";

    /** The name of the query parameter with an authored pattern. */
    public static final String CODE = "code";

    /** The authored regular expression of the {@code code} query parameter. */
    public static final String CODE_PATTERN = "^[a-z]+$";

    /** Creates the resource. */
    public VerbatimResource() {}

    /**
     * Handles {@code POST /expressions}; answers with an empty response.
     *
     * @param code    query {@code code}, with an authored pattern
     * @param request the body, whose member carries an authored pattern with flags
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void evaluateExpression(
            @QueryParam(CODE) @Pattern(regexp = CODE_PATTERN) String code, ExpressionRequest request) {
        // Nothing to do: no test sends a request to this operation.
    }
}
