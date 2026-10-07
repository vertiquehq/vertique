// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.RolesAllowed;

/**
 * The application {@code management} at {@code /api/mgmt}, whose document is protected by the JWT bearer scheme
 * {@value CachingSchemes#BEARER_AUTH} and readable only with the role {@value CachingSchemes#ADMIN_ROLE}.
 */
@ApiDocs(policy = CachingManagementApi.RolesDocsPolicy.class, securityScheme = CachingSchemes.BEARER_AUTH)
@RestApplication(
        name = CachingManagementApi.NAME,
        path = CachingManagementApi.PATH,
        resources = CachingResources.Management.class)
public interface CachingManagementApi {

    /** The application's name, which also names its document. */
    String NAME = "management";

    /** The application's path. */
    String PATH = "/api/mgmt";

    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({CachingSchemes.ADMIN_ROLE})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
