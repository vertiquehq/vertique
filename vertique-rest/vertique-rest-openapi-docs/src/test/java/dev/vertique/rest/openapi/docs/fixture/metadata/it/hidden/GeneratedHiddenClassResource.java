// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.hidden;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The generated-path twin of {@link HiddenClassResource}, listed by {@link HiddenGeneratedApi} and
 * described by its hand-written companion {@link GeneratedHiddenClassResource_JaxRsDescriptor}. It
 * differs from the twin only in its operation id, since two resource classes may not share an
 * operation id across the mounts of one composition.
 */
@Hidden
@Path(GeneratedHiddenClassResource.ROUTE)
public class GeneratedHiddenClassResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/b";

    /** The operation id of {@link #read}. */
    public static final String OPERATION_ID = "readGeneratedClassZx";

    /** Creates the resource. */
    public GeneratedHiddenClassResource() {}

    /** Handles {@code GET /b}; answers with an empty response. */
    @GET
    @Operation(operationId = OPERATION_ID)
    @Tag(name = HiddenOperationsApi.HIDDEN_TAG)
    public void read() {
        // Nothing to do: a request only proves the route still answers.
    }
}
