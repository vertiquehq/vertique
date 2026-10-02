// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.rest.openapi.docs.fixture.protecteddocs.shared.GuardedManagementApi;

/**
 * The application {@code partner} declared as {@link RestrictedPartnerApi} is, with the same path,
 * resource, and own contract, but with a protected document readable by any caller the scheme
 * {@value GuardedManagementApi#SECURITY_SCHEME} authenticates.
 */
@ApiDocs(access = ApiDocs.Access.PROTECTED, securityScheme = GuardedManagementApi.SECURITY_SCHEME)
@RestApplication(
        name = PartnerApi.NAME,
        path = PartnerApi.PATH,
        resources = RestrictedPartnerOrderResource.class,
        openapiPath = RestrictedPartnerApi.OPENAPI_PATH)
public interface ProtectedRestrictedPartnerApi {}
