// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code query-key}, whose document is protected by an API key carried in a query
 * parameter.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = CachingSchemes.QUERY_KEY)
@RestApplication(name = QueryKeyKindApi.NAME, path = QueryKeyKindApi.PATH, resources = CachingResources.QueryKey.class)
public interface QueryKeyKindApi {

    /** The application's name, which also names its document. */
    String NAME = "query-key";

    /** The application's path. */
    String PATH = "/kinds/query-key";
}
