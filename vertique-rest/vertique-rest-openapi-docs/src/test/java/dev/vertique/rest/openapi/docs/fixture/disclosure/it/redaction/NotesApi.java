// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/**
 * The documented application {@code notes} at {@code /api/notes}, listing {@link NotesResource}. Its
 * document is public; its {@code info} comes from configuration. {@link ProtectedNotesApi} declares
 * the same application with a protected document.
 */
@ApiDocs(policy = NotesApi.PublicDocsPolicy.class)
@RestApplication(name = NotesApi.NAME, path = NotesApi.PATH, resources = NotesResource.class)
public interface NotesApi {

    /** The application's name, which also names its document. */
    String NAME = "notes";

    /** The application's path. */
    String PATH = "/api/notes";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
