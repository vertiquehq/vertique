// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup.collision;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;

/**
 * The documented application {@code public} at {@code /public}, holding {@link CatalogResource}.
 * Carries no {@code @OpenAPIDefinition}; the document's {@code info} comes from configuration.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = PublicRootApi.NAME, path = PublicRootApi.PATH, resources = CatalogResource.class)
public interface PublicRootApi {

    /** The application's name, which also names its document. */
    String NAME = "public";

    /** The application's path. */
    String PATH = "/public";
}
