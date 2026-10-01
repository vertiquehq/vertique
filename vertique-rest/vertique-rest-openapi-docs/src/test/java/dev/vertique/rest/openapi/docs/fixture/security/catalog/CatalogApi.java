// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.security.catalog;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The documented discovery application {@code catalog} at {@code /api}: the sole declared
 * registration of its composition, so its mount receives every enabled resource catalog entry. Its
 * declaring interface carries {@code @ApiDocs(access = PUBLIC)}, so its document is served without
 * authentication; the document's {@code info} comes from configuration.
 */
@RestApplication(name = CatalogApi.NAME, path = CatalogApi.PATH, discover = true)
@ApiDocs(access = ApiDocs.Access.PUBLIC)
public interface CatalogApi {

    /** The application's name, which also names its document. */
    String NAME = "catalog";

    /** The application's path, as registered. */
    String PATH = "/api";
}
