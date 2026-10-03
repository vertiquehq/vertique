// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * A JAX-RS interface hidden by {@code @Hidden} on the interface alone, implemented by {@link
 * HiddenContractResource}, which carries no annotation of its own.
 */
@Hidden
@Path(HiddenContract.ROUTE)
public interface HiddenContract {

    /** The resource path, relative to the mount. */
    String ROUTE = "/c";

    /** The operation id of {@link #read}. */
    String OPERATION_ID = "readHiddenContractZx";

    /** Handles {@code GET /c}; answers with an empty response. */
    @GET
    @Operation(operationId = OPERATION_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    void read();
}
