// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.it;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code notes} at {@code /api} (mount {@code /api/*}), listing {@link
 * NoteResource}. Its one operation returns {@code Future<Note>}, whose member is renamed away from
 * its serialized name. Its document is public; its {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = NotesApi.NAME, path = NotesApi.PATH, resources = NoteResource.class)
public interface NotesApi {

    /** The application's name, which also names its document. */
    String NAME = "notes";

    /** The application's path. */
    String PATH = "/api";
}
