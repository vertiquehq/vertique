// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@code /b} of {@link HiddenOperationsApi}, hidden by {@code @Hidden} on the class
 * alone; its method carries no hiding marker. Its generated-path twin is {@link
 * GeneratedHiddenClassResource}.
 */
@Hidden
@Path(HiddenClassResource.ROUTE)
public class HiddenClassResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/b";

    /** The operation id of {@link #read}. */
    public static final String OPERATION_ID = "readHiddenClassZx";

    /** Creates the resource. */
    public HiddenClassResource() {}

    /** Handles {@code GET /b}; answers with an empty response. */
    @GET
    @Operation(operationId = OPERATION_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    public void read() {
        // Nothing to do: a request only proves the route still answers.
    }
}
