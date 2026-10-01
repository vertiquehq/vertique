// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.startup;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.ManagementResource;
import dev.vertique.rest.openapi.docs.fixture.MgmtApi;

/**
 * The same declaration as {@link MgmtApi} with a protected document readable by any reader the
 * {@value #SECURITY_SCHEME} scheme authenticates: application {@code mgmt} at {@code /api/mgmt}
 * listing {@link ManagementResource}, with no allowed roles.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = AuthenticatedMgmtApi.SECURITY_SCHEME)
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface AuthenticatedMgmtApi {

    /** The security scheme guarding the document routes. */
    String SECURITY_SCHEME = "bearerAuth";
}
