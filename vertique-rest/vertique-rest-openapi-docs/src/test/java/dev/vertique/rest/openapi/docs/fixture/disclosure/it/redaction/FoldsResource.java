// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.SecretFoldZx;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link FoldsApi} and {@link ProtectedFoldsApi}: one operation taking a
 * case-insensitively bound {@link SecretFoldZx} body under the default profile.
 */
@Path("/secrets")
public class FoldsResource {

    /** The operation id of {@link #submitFold}. */
    public static final String SUBMIT_FOLD = "submitFoldZx";

    /** The operation's path relative to the application's path. */
    public static final String ROUTE = "/secrets";

    /** Creates the resource. */
    public FoldsResource() {}

    /**
     * Handles {@code POST /secrets}; answers with an empty response.
     *
     * @param request the case-insensitively bound body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = SUBMIT_FOLD)
    public void submitFold(SecretFoldZx request) {
        // Nothing to do: the gate decides every answer the tests observe.
    }
}
