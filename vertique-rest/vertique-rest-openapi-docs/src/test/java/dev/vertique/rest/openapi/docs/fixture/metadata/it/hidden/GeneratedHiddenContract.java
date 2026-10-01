// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The twin of {@link HiddenContract} implemented by {@link GeneratedHiddenContractResource}: a JAX-RS
 * interface hidden by {@code @Hidden} on the interface alone, differing only in its operation id.
 */
@Hidden
@Path(GeneratedHiddenContract.ROUTE)
public interface GeneratedHiddenContract {

    /** The resource path, relative to the mount. */
    String ROUTE = "/c";

    /** The operation id of {@link #read}. */
    String OPERATION_ID = "readGeneratedContractZx";

    /** Handles {@code GET /c}; answers with an empty response. */
    @GET
    @Operation(operationId = OPERATION_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    void read();
}
