// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Note;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * The resource {@value #ROUTE} of {@link NotesExplicitApi}: {@code GET /notes} (operation {@value
 * #OPERATION_ID}, the method name). It returns {@code Response} and declares {@code 200} with
 * {@code Note} content.
 */
@Path(NoteExplicitResource.ROUTE)
public class NoteExplicitResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/notes";

    /** The operation id of {@link #readNoteExplicit}. */
    public static final String OPERATION_ID = "readNoteExplicit";

    /** Creates the resource. */
    public NoteExplicitResource() {}

    /**
     * Handles {@code GET /notes}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = Note.class)))
    public Response readNoteExplicit() {
        return Response.ok(new Note("hello")).build();
    }
}
