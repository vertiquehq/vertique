// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;

/**
 * The application {@code mutual-tls}, whose document is protected by a mutual TLS scheme.
 */
@ApiDocs(policy = MutualTlsKindApi.AuthenticatedDocsPolicy.class, securityScheme = CachingSchemes.MUTUAL_TLS)
@RestApplication(
        name = MutualTlsKindApi.NAME,
        path = MutualTlsKindApi.PATH,
        resources = CachingResources.MutualTls.class)
public interface MutualTlsKindApi {

    /** The application's name, which also names its document. */
    String NAME = "mutual-tls";

    /** The application's path. */
    String PATH = "/kinds/mutual-tls";

    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
