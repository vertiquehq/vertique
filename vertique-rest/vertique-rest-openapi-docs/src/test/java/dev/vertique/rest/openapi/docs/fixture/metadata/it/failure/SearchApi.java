// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/** The documented application {@code search} at {@code /api/search}, listing {@link SearchResource}. */
@ApiDocs(policy = SearchApi.PublicDocsPolicy.class)
@RestApplication(name = SearchApi.NAME, path = SearchApi.PATH, resources = SearchResource.class)
public interface SearchApi {

    /** The application's name, which also names its document. */
    String NAME = "search";

    /** The application's path. */
    String PATH = "/api/search";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
