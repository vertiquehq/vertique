// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.it.redaction;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented application {@code folds} at {@code /api/folds}, listing {@link FoldsResource}. Its
 * document is public; its {@code info} comes from configuration. {@link ProtectedFoldsApi} declares
 * the same application with a protected document.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = FoldsApi.NAME, path = FoldsApi.PATH, resources = FoldsResource.class)
public interface FoldsApi {

    /** The application's name, which also names its document. */
    String NAME = "folds";

    /** The application's path. */
    String PATH = "/api/folds";
}
