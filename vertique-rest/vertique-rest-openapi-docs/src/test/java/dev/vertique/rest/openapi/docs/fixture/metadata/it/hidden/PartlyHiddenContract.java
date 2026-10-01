// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS interface whose method {@link #hiddenRead} carries {@code @Hidden} and whose method {@link
 * #visibleRead} carries no hiding marker, implemented by {@link PartlyHiddenContractResource}, which
 * carries no annotation of its own.
 */
@Path(PartlyHiddenContract.ROUTE)
public interface PartlyHiddenContract {

    /** The resource path, relative to the mount. */
    String ROUTE = "/d";

    /** The operation id of {@link #hiddenRead}. */
    String HIDDEN_OPERATION_ID = "readHiddenMethodZx";

    /** The operation id of {@link #visibleRead}. */
    String VISIBLE_OPERATION_ID = "readVisibleMethod";

    /** Handles {@code GET /d/x}, hidden on the interface method; answers with an empty response. */
    @GET
    @Path("/x")
    @Hidden
    @Operation(operationId = HIDDEN_OPERATION_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    void hiddenRead();

    /** Handles {@code GET /d/y}, visible; answers with an empty response. */
    @GET
    @Path("/y")
    @Operation(operationId = VISIBLE_OPERATION_ID)
    void visibleRead();
}
