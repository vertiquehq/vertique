// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The same declaration as {@link MgmtApi} with a protected document: application {@code mgmt} at
 * {@code /api/mgmt}, whose declaring interface carries {@code @ApiDocs(access = PROTECTED)} guarded
 * by the {@value #SECURITY_SCHEME} scheme and the {@value #ROLE} role.
 */
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = ProtectedMgmtApi.SECURITY_SCHEME,
        rolesAllowed = {ProtectedMgmtApi.ROLE})
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface ProtectedMgmtApi {

    /** The security scheme guarding the document routes. */
    String SECURITY_SCHEME = "bearerAuth";

    /** The role allowed to read the document. */
    String ROLE = "admin";
}
