// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code public} at {@code /api/public}, whose document is public.
 */
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = CachingPublicApi.NAME, path = CachingPublicApi.PATH, resources = CachingResources.Catalog.class)
public interface CachingPublicApi {

    /** The application's name, which also names its document. */
    String NAME = "public";

    /** The application's path. */
    String PATH = "/api/public";
}
