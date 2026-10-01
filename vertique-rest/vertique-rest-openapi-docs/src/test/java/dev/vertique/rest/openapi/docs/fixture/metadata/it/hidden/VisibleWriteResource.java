// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.AccountFixedZx;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The control resource of {@link ProtectedVisibleWriteApi}: {@code POST /a/two} with the body type
 * and the hidden query binding of {@link MixedOperationsResource#writeTwo}, but carrying no hiding
 * marker, so the operation is published.
 */
@Path(MixedOperationsResource.ROUTE)
public class VisibleWriteResource {

    /** The operation id of {@link #writeTwo}. */
    public static final String OPERATION_ID = "writeVisible";

    /** Creates the resource. */
    public VisibleWriteResource() {}

    /**
     * Handles {@code POST /a/two}; answers with an empty response.
     *
     * @param flag    query {@value MixedOperationsResource#FLAG}, hidden
     * @param account the body, whose type reserves {@code backdoorZx} in its root guard
     */
    @POST
    @Path("/two")
    @Operation(operationId = OPERATION_ID)
    @Consumes(MediaType.APPLICATION_JSON)
    public void writeTwo(
            @QueryParam(MixedOperationsResource.FLAG) @Parameter(hidden = true) String flag, AccountFixedZx account) {
        // Nothing to do: the operation only has to be rendered.
    }
}
