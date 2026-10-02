// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code oauth}, whose document is protected by an OAuth 2 scheme.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = CachingSchemes.OAUTH2)
@RestApplication(name = OAuthKindApi.NAME, path = OAuthKindApi.PATH, resources = CachingResources.OAuth.class)
public interface OAuthKindApi {

    /** The application's name, which also names its document. */
    String NAME = "oauth";

    /** The application's path. */
    String PATH = "/kinds/oauth";
}
