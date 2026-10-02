// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code header-key}, whose document is protected by an API key carried in a request
 * header.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = CachingSchemes.HEADER_KEY)
@RestApplication(
        name = HeaderKeyKindApi.NAME,
        path = HeaderKeyKindApi.PATH,
        resources = CachingResources.HeaderKey.class)
public interface HeaderKeyKindApi {

    /** The application's name, which also names its document. */
    String NAME = "header-key";

    /** The application's path. */
    String PATH = "/kinds/header-key";
}
