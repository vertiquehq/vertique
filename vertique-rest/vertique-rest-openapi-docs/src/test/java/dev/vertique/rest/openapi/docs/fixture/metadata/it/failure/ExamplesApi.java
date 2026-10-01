// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.it.failure;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/** The documented application {@code examples} at {@code /api/examples}, listing {@link ExamplesResource}. */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = ExamplesApi.NAME, path = ExamplesApi.PATH, resources = ExamplesResource.class)
public interface ExamplesApi {

    /** The application's name, which also names its document. */
    String NAME = "examples";

    /** The application's path. */
    String PATH = "/api/examples";
}
