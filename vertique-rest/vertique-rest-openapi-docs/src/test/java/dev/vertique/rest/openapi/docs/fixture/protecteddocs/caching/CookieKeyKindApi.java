// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code cookie-key}, whose document is protected by an API key carried in a cookie.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = CachingSchemes.COOKIE_KEY)
@RestApplication(
        name = CookieKeyKindApi.NAME,
        path = CookieKeyKindApi.PATH,
        resources = CachingResources.CookieKey.class)
public interface CookieKeyKindApi {

    /** The application's name, which also names its document. */
    String NAME = "cookie-key";

    /** The application's path. */
    String PATH = "/kinds/cookie-key";
}
