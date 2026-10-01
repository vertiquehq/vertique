// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code notesexplicit} at {@code /api} (mount {@code /api/*}), listing
 * {@link NoteExplicitResource}. Its one operation returns {@code Response} and declares {@code
 * Note} as its {@code 200} content. Its document is public; its {@code info} comes from
 * configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = NotesExplicitApi.NAME, path = NotesExplicitApi.PATH, resources = NoteExplicitResource.class)
public interface NotesExplicitApi {

    /** The application's name, which also names its document. */
    String NAME = "notesexplicit";

    /** The application's path. */
    String PATH = "/api";
}
