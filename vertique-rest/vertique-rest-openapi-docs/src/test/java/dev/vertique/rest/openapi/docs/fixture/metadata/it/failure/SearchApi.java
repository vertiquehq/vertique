// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/** The documented application {@code search} at {@code /api/search}, listing {@link SearchResource}. */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = SearchApi.NAME, path = SearchApi.PATH, resources = SearchResource.class)
public interface SearchApi {

    /** The application's name, which also names its document. */
    String NAME = "search";

    /** The application's path. */
    String PATH = "/api/search";
}
