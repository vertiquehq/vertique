// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The application {@code openid}, whose document is protected by an OpenID Connect scheme.
 */
@ApiDocs(policy = OpenIdKindApi.AuthenticatedDocsPolicy.class, securityScheme = CachingSchemes.OPEN_ID_CONNECT)
@RestApplication(name = OpenIdKindApi.NAME, path = OpenIdKindApi.PATH, resources = CachingResources.OpenId.class)
public interface OpenIdKindApi {

    /** The application's name, which also names its document. */
    String NAME = "openid";

    /** The application's path. */
    String PATH = "/kinds/openid";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
