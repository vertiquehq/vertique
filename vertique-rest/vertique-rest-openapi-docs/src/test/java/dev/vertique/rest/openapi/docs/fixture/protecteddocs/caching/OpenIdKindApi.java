// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code openid}, whose document is protected by an OpenID Connect scheme.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = CachingSchemes.OPEN_ID_CONNECT)
@RestApplication(name = OpenIdKindApi.NAME, path = OpenIdKindApi.PATH, resources = CachingResources.OpenId.class)
public interface OpenIdKindApi {

    /** The application's name, which also names its document. */
    String NAME = "openid";

    /** The application's path. */
    String PATH = "/kinds/openid";
}
