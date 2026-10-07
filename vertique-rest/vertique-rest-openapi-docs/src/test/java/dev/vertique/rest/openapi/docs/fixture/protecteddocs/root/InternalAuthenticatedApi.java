// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.root;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * The same root application {@code internal} at {@code /} as {@link InternalAdminApi}, whose
 * protected document lists no roles: any caller the {@value InternalAdminApi#SECURITY_SCHEME}
 * scheme authenticates may read it.
 */
@ApiDocs(
        policy = InternalAuthenticatedApi.AuthenticatedDocsPolicy.class,
        securityScheme = InternalAdminApi.SECURITY_SCHEME)
@OpenAPIDefinition(info = @Info(title = "Internal", version = "1.0"))
@RestApplication(name = InternalAdminApi.NAME, path = InternalAdminApi.PATH, discover = true)
public interface InternalAuthenticatedApi {
    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
