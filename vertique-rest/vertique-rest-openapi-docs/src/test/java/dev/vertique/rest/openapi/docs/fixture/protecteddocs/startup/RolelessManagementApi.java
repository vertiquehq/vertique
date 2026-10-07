// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.protecteddocs.startup;

import dev.vertique.rest.core.application.RestApplication;
import dev.vertique.rest.openapi.docs.ApiDocs;
import dev.vertique.security.authz.AccessPolicy;
import dev.vertique.security.authz.Authorized;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

/**
 * Application {@code management} at {@code /api/management}, whose protected document names the
 * scheme {@value StartupSchemeHandlers#BEARER_AUTH} and lists no roles.
 */
@ApiDocs(
        policy = RolelessManagementApi.AuthenticatedDocsPolicy.class,
        securityScheme = StartupSchemeHandlers.BEARER_AUTH)
@OpenAPIDefinition(info = @Info(title = "Management API", version = "1.0"))
@RestApplication(name = "management", path = "/api/management", resources = ManagementStatusResource.class)
public interface RolelessManagementApi {
    /** Any authenticated reader may read the document. */
    @Authorized
    public interface AuthenticatedDocsPolicy extends AccessPolicy {}
}
