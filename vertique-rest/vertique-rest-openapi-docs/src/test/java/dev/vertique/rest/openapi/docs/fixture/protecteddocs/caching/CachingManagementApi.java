// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.caching;

import dev.vertique.rest.jaxrs.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;

/**
 * The application {@code management} at {@code /api/mgmt}, whose document is protected by the JWT bearer scheme
 * {@value CachingSchemes#BEARER_AUTH} and readable only with the role {@value CachingSchemes#ADMIN_ROLE}.
 */
@ApiDocs(
        access = ApiDocs.Access.PROTECTED,
        securityScheme = CachingSchemes.BEARER_AUTH,
        rolesAllowed = {CachingSchemes.ADMIN_ROLE})
@RestApplication(
        name = CachingManagementApi.NAME,
        path = CachingManagementApi.PATH,
        resources = CachingResources.Management.class)
public interface CachingManagementApi {

    /** The application's name, which also names its document. */
    String NAME = "management";

    /** The application's path. */
    String PATH = "/api/mgmt";
}
