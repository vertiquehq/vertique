// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The same declaration as {@link NotesApi} with a protected document guarded by the {@value
 * #SECURITY_SCHEME} scheme. Only a component that renders documents without serving them lists it.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = ProtectedNotesApi.SECURITY_SCHEME)
@RestApplication(name = NotesApi.NAME, path = NotesApi.PATH, resources = NotesResource.class)
public interface ProtectedNotesApi {

    /** The security scheme guarding the document. */
    String SECURITY_SCHEME = "bearerAuth";
}
