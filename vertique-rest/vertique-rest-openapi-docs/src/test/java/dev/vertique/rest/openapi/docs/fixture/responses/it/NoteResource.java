// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Note;
import io.vertx.core.Future;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * The resource {@value #ROUTE} of {@link NotesApi}: {@code GET /notes} (operation {@value
 * #OPERATION_ID}, the method name). It declares no response; the body's only key is {@code note}.
 */
@Path(NoteResource.ROUTE)
public class NoteResource {

    /** The resource path, relative to the mount. */
    public static final String ROUTE = "/notes";

    /** The operation id of {@link #readNote}. */
    public static final String OPERATION_ID = "readNote";

    /** Creates the resource. */
    public NoteResource() {}

    /**
     * Handles {@code GET /notes}.
     *
     * @return {@code 200} with a populated body
     */
    @GET
    public Future<Note> readNote() {
        return Future.succeededFuture(new Note("hello"));
    }
}
