// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.PermitAll;

/** The documented application {@code examples} at {@code /api/examples}, listing {@link ExamplesResource}. */
@ApiDocs(policy = ExamplesApi.PublicDocsPolicy.class)
@RestApplication(name = ExamplesApi.NAME, path = ExamplesApi.PATH, resources = ExamplesResource.class)
public interface ExamplesApi {

    /** The application's name, which also names its document. */
    String NAME = "examples";

    /** The application's path. */
    String PATH = "/api/examples";

    /** Anyone may read the document. */
    @PermitAll
    public interface PublicDocsPolicy extends AccessPolicy {}
}
