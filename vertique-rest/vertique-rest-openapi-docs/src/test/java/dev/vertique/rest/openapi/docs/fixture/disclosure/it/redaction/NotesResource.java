// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.rest.openapi.docs.fixture.disclosure.dto.NotesZx;
import dev.vertique.rest.openapi.docs.fixture.disclosure.profile.TagsProfileModule;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link NotesApi} and {@link ProtectedNotesApi}: one operation taking a
 * case-sensitively bound {@link NotesZx} body under the {@value TagsProfileModule#TAGS_PROFILE}
 * profile, which describes the body's {@code tags} member with a developer-declared fragment.
 */
@Path("/notes")
public class NotesResource {

    /** The operation id of {@link #addNote}. */
    public static final String ADD_NOTE = "addNoteZx";

    /** The operation's path relative to the application's path. */
    public static final String ROUTE = "/notes";

    /** Creates the resource. */
    public NotesResource() {}

    /**
     * Handles {@code POST /notes}; answers with an empty response.
     *
     * @param note the body
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = ADD_NOTE)
    @JsonProfile(TagsProfileModule.TAGS_PROFILE)
    public void addNote(NotesZx note) {
        // Nothing to do: the gate decides every answer the tests observe.
    }
}
