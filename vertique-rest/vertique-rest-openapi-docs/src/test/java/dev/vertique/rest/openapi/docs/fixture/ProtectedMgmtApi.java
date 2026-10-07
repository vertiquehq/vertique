// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.RolesAllowed;

/**
 * The same declaration as {@link MgmtApi} with a protected document: application {@code mgmt} at
 * {@code /api/mgmt}, whose declaring interface carries {@code @ApiDocs(policy = AuthenticatedDocsPolicy.class)} guarded
 * by the {@value #SECURITY_SCHEME} scheme and the {@value #ROLE} role.
 */
@ApiDocs(policy = ProtectedMgmtApi.RolesDocsPolicy.class, securityScheme = ProtectedMgmtApi.SECURITY_SCHEME)
@RestApplication(name = MgmtApi.NAME, path = MgmtApi.PATH, resources = ManagementResource.class)
public interface ProtectedMgmtApi {

    /** The security scheme guarding the document routes. */
    String SECURITY_SCHEME = "bearerAuth";

    /** The role allowed to read the document. */
    String ROLE = "admin";

    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({ProtectedMgmtApi.ROLE})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
