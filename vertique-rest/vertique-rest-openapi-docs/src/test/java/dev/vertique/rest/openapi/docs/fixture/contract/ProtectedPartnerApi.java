// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.GuardedManagementApi;
import dev.vertique.security.authz.AccessPolicy;
import jakarta.annotation.security.RolesAllowed;

/**
 * The application {@code partner} declared as {@link PartnerApi} is, with the same path, resource,
 * and own contract, but with a protected document guarded exactly as {@link GuardedManagementApi}'s
 * generated document is: scheme {@value GuardedManagementApi#SECURITY_SCHEME}, role {@value
 * GuardedManagementApi#ROLE}.
 */
@ApiDocs(policy = ProtectedPartnerApi.RolesDocsPolicy.class, securityScheme = GuardedManagementApi.SECURITY_SCHEME)
@RestApplication(
        name = PartnerApi.NAME,
        path = PartnerApi.PATH,
        resources = PartnerOrderResource.class,
        openapiPath = PartnerApi.OPENAPI_PATH)
public interface ProtectedPartnerApi {
    /** Readers holding one of the listed roles may read the document. */
    @RolesAllowed({GuardedManagementApi.ROLE})
    public interface RolesDocsPolicy extends AccessPolicy {}
}
